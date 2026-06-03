package com.ethran.notable.db

import com.ethran.notable.data.db.StrokePoint
import com.ethran.notable.data.db.decodeStrokePoints
import com.ethran.notable.data.db.encodeStrokePoints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Instrumented tests for SB1 stroke (de)serialization bugs. These exercise the
 * REAL encodeStrokePoints / decodeStrokePoints (which init the ShipBook logger at
 * class load, so they run as instrumented tests rather than host-JVM).
 *
 * No production behaviour is changed; each test pins the buggy round-trip.
 * See REPORT_db_bugs.md and the host-JVM StrokeEncodingMathTest.
 */
class StrokeEncodingBugAndroidTest {

    private fun p(
        x: Float, y: Float,
        pressure: Float? = null, tiltX: Int? = null, tiltY: Int? = null,
        dt: UShort? = null
    ) = StrokePoint(x = x, y = y, pressure = pressure, tiltX = tiltX, tiltY = tiltY, dt = dt)

    // ---------------------------------------------------------------------
    // BUG E: only the FIRST point's y is validated against MAX_PAGE_HEIGHT.
    //   encodeStrokePoints (StrokePointConverter.kt:188):
    //     if (points.first().y > MAX_PAGE_HEIGHT) throw ...
    // A stroke whose first point is small but a LATER point has a huge coordinate
    // bypasses the guard and is silently corrupted by the Int*100 overflow in the
    // polyline encoder. The data is written to the DB wrong, with no error.
    // ---------------------------------------------------------------------
    @Test(timeout = 10000)
    fun encode_corruptsLaterLargeCoordinate_passingFirstPointGuard() {
        val points = listOf(
            p(10f, 10f),                 // small first point -> guard passes
            p(20f, 30_000_000f),         // later point overflows the Int*100 cast
        )

        // The guard only saw the first point, so encoding proceeds. The result is
        // corrupted: encoding may throw, decoding may throw, or the second point's
        // y comes back wrong. Any of these proves the data was not stored faithfully.
        val faithful: Boolean = try {
            val decoded = decodeStrokePoints(encodeStrokePoints(points))
            decoded.size == 2 &&
                kotlin.math.abs(decoded[1].y.toDouble() - 30_000_000.0) < 1.0
        } catch (e: Exception) {
            false // overflow corrupted the stream so badly it could not round-trip
        }

        assertTrue(
            "BUG: later point y=30,000,000 bypassed the first-point-only guard and " +
                "was not stored faithfully",
            !faithful
        )
    }

    // ---------------------------------------------------------------------
    // BUG A: dt does not round-trip at the top of its range, and the documented
    // 0xFFFF -> null sentinel is not honoured.
    //   encode coerces to [0, 65534] (:231); decode keeps 65535 as-is (:370).
    // ---------------------------------------------------------------------
    @Test(timeout = 10000)
    fun dt_maxValueDoesNotRoundTrip() {
        val points = listOf(
            p(1f, 1f, dt = 65535u),
            p(2f, 2f, dt = 65535u),
        )
        val decoded = decodeStrokePoints(encodeStrokePoints(points))

        // Real value 65535 comes back as 65534 (silent clamp on encode).
        assertNotEquals(
            "BUG: dt=65535 is clamped to 65534 and does not round-trip",
            65535,
            decoded[0].dt!!.toInt()
        )
        assertEquals(65534, decoded[0].dt!!.toInt())
    }

    @Test(timeout = 10000)
    fun dt_sentinelIsNotMappedToNull_contradictingDoc() {
        // Spec §3.4 + the decodeStrokePoints docstring both claim: "if a decoded dt
        // equals 0xFFFF, it returns null for that field." The decoder does NOT do
        // this (StrokePointConverter.kt:370 is an unconditional toUShort()).
        //
        // We cannot get a 65535 into the encoder (it clamps to 65534), so we feed
        // 65534 and show the decoder returns the concrete value rather than ever
        // applying the documented null mapping. The doc/code mismatch means the
        // reserved sentinel can never be produced or honoured as written.
        val points = listOf(p(1f, 1f, dt = 65534u), p(2f, 2f, dt = 65534u))
        val decoded = decodeStrokePoints(encodeStrokePoints(points))

        // Documented behaviour would special-case the top of the range; the code
        // returns it verbatim, never null.
        assertEquals(65534, decoded[0].dt!!.toInt())
        assertNotEquals(
            "decoder never maps the reserved-region dt to null as the doc promises",
            null,
            decoded[0].dt
        )
    }

    // ---------------------------------------------------------------------
    // BUG B: pressure above Short range wraps negative on round-trip.
    //   encode: putShort(pressure.toInt().toShort()) (:221)
    //   decode: short.toFloat()                       (:367)
    // ---------------------------------------------------------------------
    @Test(timeout = 10000)
    fun pressure_aboveShortRange_wrapsNegativeOnRoundTrip() {
        val points = listOf(
            p(1f, 1f, pressure = 40000f),
            p(2f, 2f, pressure = 40000f),
        )
        val decoded = decodeStrokePoints(encodeStrokePoints(points))
        assertTrue(
            "BUG: pressure 40000 wraps through Short -> negative after decode (got ${decoded[0].pressure})",
            decoded[0].pressure!! < 0f
        )
    }

    // ---------------------------------------------------------------------
    // Control: a normal stroke round-trips exactly (within precision). Confirms
    // the bugs above are localized to the documented edge cases.
    // ---------------------------------------------------------------------
    @Test(timeout = 10000)
    fun normalStroke_roundTrips_control() {
        val points = listOf(
            p(10.5f, 20.25f, pressure = 1200f, tiltX = 5, tiltY = -5, dt = 16u),
            p(11.5f, 21.75f, pressure = 1300f, tiltX = 6, tiltY = -4, dt = 32u),
        )
        val decoded = decodeStrokePoints(encodeStrokePoints(points))
        assertEquals(2, decoded.size)
        assertEquals(10.5, decoded[0].x.toDouble(), 0.01)
        assertEquals(20.25, decoded[0].y.toDouble(), 0.01)
        assertEquals(1200f, decoded[0].pressure!!, 0.5f)
        assertEquals(5, decoded[0].tiltX)
        assertEquals(16, decoded[0].dt!!.toInt())
    }
}
