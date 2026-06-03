package com.ethran.notable.sync

import com.ethran.notable.data.db.Image
import com.ethran.notable.data.db.Page
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.db.StrokePoint
import com.ethran.notable.editor.utils.Pen
import com.ethran.notable.sync.serializers.NotebookSerializer
import com.ethran.notable.utils.AppResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Instrumented tests for sync (de)serialization bugs in NotebookSerializer (which
 * pulls in the SB1 encoder + ShipBook logging, so it runs as an instrumented test).
 *
 * No production behaviour is changed; each test pins the buggy round-trip.
 * See REPORT_sync_bugs.md.
 */
class NotebookSerializerBugAndroidTest {

    private val pageId = UUID.randomUUID().toString()

    private fun page() = Page(
        id = pageId,
        notebookId = UUID.randomUUID().toString(),
        background = "blank",
        backgroundType = "native",
    )

    private fun stroke(pen: Pen) = Stroke(
        id = UUID.randomUUID().toString(),
        size = 3f,
        pen = pen,
        color = 0xFF000000.toInt(),
        maxPressure = 4096,
        top = 0f, bottom = 10f, left = 0f, right = 10f,
        points = listOf(
            StrokePoint(x = 0f, y = 0f, pressure = 1000f),
            StrokePoint(x = 10f, y = 10f, pressure = 1000f),
        ),
        pageId = pageId,
    )

    // ---------------------------------------------------------------------
    // BUG 7: a stroke whose pen name the current enum does not recognise is
    // SILENTLY DROPPED on download.
    //   deserializePage uses Pen.valueOf(strokeDto.pen) (NotebookSerializer.kt:184),
    //   which THROWS on an unknown name; the throw is swallowed by the per-stroke
    //   try/catch (:201) and the stroke is skipped (mapNotNull -> null).
    // The lenient Pen.fromString (pen.kt:20, defaults to BALLPEN) exists but is not
    // used here. The doc (§4.3) even documents the pen as "BALLPOINT", which is NOT
    // a valid enum name (the enum is BALLPEN) — so a manifest written per the doc,
    // or by any other/older/newer client, loses every stroke.
    // ---------------------------------------------------------------------
    @Test(timeout = 10000)
    fun deserialize_unknownPenName_silentlyDropsStroke() {
        // Serialize a normal stroke, then corrupt only the pen name in the JSON to a
        // value the enum does not contain (e.g. the doc's "BALLPOINT").
        val json = NotebookSerializer.serializePage(page(), listOf(stroke(Pen.BALLPEN)), emptyList())
        val corrupted = json.replace("\"BALLPEN\"", "\"BALLPOINT\"")
        assertTrue("precondition: pen name was rewritten", corrupted.contains("BALLPOINT"))

        val result = NotebookSerializer.deserializePage(corrupted)
        assertTrue(result is AppResult.Success)
        val (_, strokes, _) = (result as AppResult.Success).data

        // BUG: the whole stroke vanished instead of falling back to BALLPEN.
        assertEquals(
            "BUG: unknown pen name 'BALLPOINT' silently drops the stroke on download " +
                "(Pen.valueOf throws; Pen.fromString fallback is not used)",
            0, strokes.size
        )
    }

    // ---------------------------------------------------------------------
    // Control: a stroke with a known pen round-trips and is preserved.
    // ---------------------------------------------------------------------
    @Test(timeout = 10000)
    fun deserialize_knownPen_roundTrips_control() {
        val json = NotebookSerializer.serializePage(page(), listOf(stroke(Pen.FOUNTAIN)), emptyList())
        val result = NotebookSerializer.deserializePage(json)
        assertTrue(result is AppResult.Success)
        val (_, strokes, _) = (result as AppResult.Success).data
        assertEquals(1, strokes.size)
        assertEquals(Pen.FOUNTAIN, strokes[0].pen)
        assertEquals(2, strokes[0].points.size)
    }

    // ---------------------------------------------------------------------
    // Related observation (data dependent): the SB1 dt clamp also bites here,
    // because pointsData is the same SB1 binary. A dt of 65535 round-trips as
    // 65534 through serialize/deserialize. (Same root cause as REPORT_db_bugs BUG A.)
    // ---------------------------------------------------------------------
    @Test(timeout = 10000)
    fun serialize_dtMaxValue_doesNotRoundTrip() {
        val s = Stroke(
            id = UUID.randomUUID().toString(),
            size = 3f, pen = Pen.BALLPEN, color = 0xFF000000.toInt(), maxPressure = 4096,
            top = 0f, bottom = 1f, left = 0f, right = 1f,
            points = listOf(
                StrokePoint(x = 0f, y = 0f, dt = 65535u),
                StrokePoint(x = 1f, y = 1f, dt = 65535u),
            ),
            pageId = pageId,
        )
        val json = NotebookSerializer.serializePage(page(), listOf(s), emptyList())
        val result = NotebookSerializer.deserializePage(json) as AppResult.Success
        val decodedDt = result.data.second[0].points[0].dt!!.toInt()
        assertEquals(
            "BUG (shared with SB1): dt=65535 round-trips through sync as 65534",
            65534, decodedDt
        )
    }
}
