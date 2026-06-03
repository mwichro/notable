package com.ethran.notable.db

import com.ethran.notable.data.db.decode
import com.ethran.notable.data.db.encode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM unit tests for database serialization bugs that live in code with NO
 * Android dependency: the polyline coordinate codec ([encode]/[decode] in
 * EncodePolyline.kt) and the documented numeric contracts of the SB1 stroke
 * format (database-structure.md §3).
 *
 * Bugs needing the Room DB or the ShipBook-logging encoder/decoder wrapper are in
 * the instrumented test StrokeEncodingBugAndroidTest. No production behaviour is
 * changed; each test pins the buggy result.
 */
class StrokeEncodingMathTest {

    // ---------------------------------------------------------------------
    // BUG E: coordinate encoding overflows Int for large coordinates.
    //   EncodePolyline.encode (line 22):
    //     val iValue = (value.toDouble() * 10.0.pow(precision)).toInt()
    // With precision = 2 (ENCODING_PRECISION_XY) a coordinate is multiplied by
    // 100 and cast to Int. Int max is ~2.147e9, so any coordinate above ~21.47
    // million overflows. encodeStrokePoints only guards points.FIRST().y against
    // MAX_PAGE_HEIGHT (10_000_000) — a later point, or any large x, is not guarded
    // and silently corrupts.
    // ---------------------------------------------------------------------
    @Test
    fun polyline_overflowsForLargeCoordinate() {
        val precision = 2 // ENCODING_PRECISION_XY

        // A coordinate beyond ~21.47M overflows the Int cast.
        val big = 30_000_000f

        // The round-trip is corrupted: either it throws while decoding the broken
        // polyline, or it returns a value nowhere near the original. Both prove the
        // coordinate was not stored faithfully.
        val roundTripped: Double? = try {
            decode(encode(listOf(big), precision), precision) { it.toFloat() }
                .firstOrNull()?.toDouble()
        } catch (e: Exception) {
            null // decoding the corrupted stream threw -> data was destroyed
        }

        val faithful = roundTripped != null && kotlin.math.abs(roundTripped - big) < 1.0
        assertTrue(
            "BUG: coordinate 30,000,000 overflows the Int*100 cast and is not stored " +
                "faithfully (got $roundTripped)",
            !faithful
        )
    }

    @Test
    fun polyline_overflowExactMechanism() {
        // The exact mechanism: encode() does (value * 10^precision).toInt().
        // With precision=2, 25_000_000 * 100 = 2.5e9, which exceeds Int.MAX
        // (2_147_483_647). Double.toInt() saturates to Int.MAX instead of keeping
        // the real value, so distinct large coordinates collapse to the SAME int
        // and are indistinguishable after encoding.
        val a = (25_000_000.0 * 100.0).toInt()
        val b = (40_000_000.0 * 100.0).toInt()
        assertEquals("Double.toInt saturates at Int.MAX for 2.5e9", Int.MAX_VALUE, a)
        assertEquals(
            "BUG: two different huge coordinates both saturate to Int.MAX and become " +
                "indistinguishable when encoded",
            a, b
        )
    }

    @Test
    fun polyline_roundTripsForNormalCoordinates_control() {
        val precision = 2
        val coords = listOf(0f, 12.34f, 1500.5f, 9999.99f, 1_000_000f)
        val decoded = decode(encode(coords, precision), precision) { it.toFloat() }
        for (i in coords.indices) {
            assertEquals(coords[i].toDouble(), decoded[i].toDouble(), 0.01)
        }
    }

    // ---------------------------------------------------------------------
    // BUG A: dt sentinel contract is violated.
    // database-structure.md §3.4 and the decoder docstring both state that a
    // decoded dt of 0xFFFF (65535) means "null". But:
    //   - the ENCODER coerces dt into [0, 65534] (DT_MAX_VALUE_INT), so a real
    //     dt of 65535 is silently changed to 65534 on save;
    //   - the DECODER does NOT map 0xFFFF back to null (it returns 65535.toUShort).
    // Net effect: dt does not round-trip at the top of its range, and the
    // documented null-sentinel semantics are not implemented.
    //
    // This test reproduces the numeric contract violation without needing the
    // ShipBook-logging encoder. The exact production expressions are replicated
    // (StrokePointConverter.kt:231 encode, :370 decode) with citations.
    // ---------------------------------------------------------------------
    @Test
    fun dt_maxValueIsSilentlyClampedOnEncode() {
        val DT_NULL_SENTINEL_INT = 0xFFFF      // 65535 (StrokePointConverter.kt:106)
        val DT_MAX_VALUE_INT = DT_NULL_SENTINEL_INT - 1 // 65534 (:107)

        val originalDt = 65535               // UShort.MAX_VALUE, a legal dt value
        // Encoder: v = dt.toInt().coerceIn(0, DT_MAX_VALUE_INT) (:231)
        val encodedV = originalDt.coerceIn(0, DT_MAX_VALUE_INT)

        assertNotEquals(
            "BUG: a real dt of 65535 is silently clamped to 65534 on encode",
            originalDt,
            encodedV
        )
        assertEquals(65534, encodedV)
    }

    @Test
    fun dt_decoderDoesNotRemapSentinelToNull() {
        // Decoder: dt = dts.getOrNull(i)?.toUShort() (StrokePointConverter.kt:370)
        // Per spec §3.4 and the decode docstring, 0xFFFF should become null.
        val storedShort: Short = 0xFFFF.toShort() // -1 as signed short
        val decodedDt = storedShort.toUShort()    // production conversion

        // Documented contract: this should be null. Production keeps it as 65535.
        assertEquals(
            "BUG: decoder does not honour the documented 0xFFFF -> null sentinel",
            65535,
            decodedDt.toInt()
        )
    }

    // ---------------------------------------------------------------------
    // BUG B: pressure is stored as Float -> Int -> Short, which truncates the
    // fraction and WRAPS for values above Short.MAX_VALUE (32767).
    //   encode: bodyBuffer.putShort(p.pressure!!.toInt().toShort())  (:221)
    //   decode: pressures.getOrNull(i)?.toFloat()                    (:367)
    // StrokePoint documents pressure as "1 to 4096, usually whole number", but the
    // field is a Float and nothing clamps it. A pressure of 40000 (possible on some
    // digitizers / future maxPressure scaling) wraps to a negative value.
    // ---------------------------------------------------------------------
    @Test
    fun pressure_wrapsAboveShortRange() {
        val pressure = 40000f
        // Production round-trip math (encode :221 then decode :367):
        val stored: Short = pressure.toInt().toShort()
        val restored: Float = stored.toFloat()

        assertNotEquals(
            "BUG: pressure 40000 wraps through Short and comes back negative/wrong",
            pressure.toDouble(),
            restored.toDouble(),
            1.0
        )
        assertTrue("40000 wraps to a negative short on round-trip", restored < 0f)
    }

    @Test
    fun pressure_truncatesFraction() {
        val pressure = 1234.9f
        val stored: Short = pressure.toInt().toShort() // 1234
        val restored: Float = stored.toFloat()
        assertEquals(
            "fractional pressure is truncated (documented as whole-ish, but field is Float)",
            1234f, restored, 0.001f
        )
        assertNotEquals(1234.9, restored.toDouble(), 0.001)
    }
}
