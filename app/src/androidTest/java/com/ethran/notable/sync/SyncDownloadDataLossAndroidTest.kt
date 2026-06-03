package com.ethran.notable.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ethran.notable.data.db.AppDatabase
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.data.db.Page
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.db.StrokePoint
import com.ethran.notable.editor.utils.Pen
import com.ethran.notable.testing.TestDatabaseFactory
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * Instrumented test reproducing the non-atomic page-overwrite data-loss bug in
 * NotebookSyncService.downloadPage.
 *
 * When a downloaded page already exists locally, downloadPage does
 * (NotebookSyncService.kt:358-373):
 *
 *   strokeRepository.deleteAll(existing strokes)   // old data destroyed FIRST
 *   imageRepository.deleteAll(existing images)
 *   pageRepository.update(page)
 *   strokeRepository.create(strokes)               // new data inserted AFTER
 *   imageRepository.create(updatedImages)
 *
 * There is NO surrounding transaction. If create(...) throws (e.g. a stroke the SB1
 * converter rejects, or any Room/constraint error), the catch only AGGREGATES the
 * error — the old strokes were already deleted and the new ones never landed, so
 * the page loses ALL its strokes. A transient/corrupt remote page silently wipes
 * good local content.
 *
 * This test does NOT fix the bug; it reproduces the destructive ordering on a real
 * in-memory Room DB. No production code is changed.
 */
class SyncDownloadDataLossAndroidTest {

    private lateinit var db: AppDatabase
    private lateinit var pageId: String

    private fun stroke(points: List<StrokePoint>) = Stroke(
        id = UUID.randomUUID().toString(),
        size = 5f, pen = Pen.BALLPEN, color = 0xFF000000.toInt(), maxPressure = 4096,
        top = 0f, bottom = 10f, left = 0f, right = 10f,
        points = points,
        pageId = pageId,
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = TestDatabaseFactory.createInMemory(context)
        runBlocking {
            val notebookId = UUID.randomUUID().toString()
            db.notebookDao().create(Notebook(id = notebookId, title = "sync-dataloss"))
            pageId = UUID.randomUUID().toString()
            db.pageDao().create(
                Page(
                    id = pageId,
                    notebookId = notebookId,
                    background = "blank",
                    backgroundType = "native",
                )
            )
            // Seed good local content: two valid strokes already on the page.
            db.strokeDao().create(stroke(listOf(StrokePoint(x = 0f, y = 0f, pressure = 1000f))))
            db.strokeDao().create(stroke(listOf(StrokePoint(x = 5f, y = 5f, pressure = 1100f))))
        }
    }

    @After
    fun tearDown() = db.close()

    @Test(timeout = 10000)
    fun downloadPage_failedStrokeInsert_wipesExistingStrokes() {
        runBlocking {
            withTimeout(8000) {
                // Precondition: page has 2 strokes.
                val before = db.pageDao().getPageWithDataById(pageId)!!
                assertEquals(2, before.strokes.size)

                // Replicate downloadPage's exact ordering for an existing page.
                // The "downloaded" replacement contains one VALID stroke and one
                // INVALID stroke (empty points -> SB1 converter rejects on insert),
                // exactly the kind of partially-corrupt remote payload the per-stroke
                // skip logic upstream may still pass through as a batch insert.
                val incoming = listOf(
                    stroke(listOf(StrokePoint(x = 1f, y = 1f, pressure = 1000f))),
                    stroke(emptyList()), // cannot be encoded -> create() throws
                )

                val existing = before.strokes.map { it.id }
                var insertFailed = false
                try {
                    // 1. destroy old data first (NotebookSyncService.kt:365)
                    db.strokeDao().deleteAll(existing)
                    // 2. then try to insert the replacement as a batch (:372)
                    db.strokeDao().create(incoming)
                } catch (e: Exception) {
                    insertFailed = true
                }

                assertTrue("precondition: the batch insert fails on the invalid stroke", insertFailed)

                // BUG: old strokes are gone AND the new ones did not land -> the page
                // is left with zero strokes. Good local content was silently wiped by
                // a failed download.
                val after = db.pageDao().getPageWithDataById(pageId)!!
                assertEquals(
                    "BUG: failed page download deleted existing strokes without restoring them",
                    0, after.strokes.size
                )
            }
        }
    }

    @Test(timeout = 10000)
    fun downloadPage_validReplacement_replacesCleanly_control() {
        runBlocking {
            withTimeout(8000) {
                val before = db.pageDao().getPageWithDataById(pageId)!!
                val existing = before.strokes.map { it.id }

                val incoming = listOf(
                    stroke(listOf(StrokePoint(x = 1f, y = 1f, pressure = 1000f))),
                    stroke(listOf(StrokePoint(x = 2f, y = 2f, pressure = 1000f))),
                    stroke(listOf(StrokePoint(x = 3f, y = 3f, pressure = 1000f))),
                )

                db.strokeDao().deleteAll(existing)
                db.strokeDao().create(incoming)

                val after = db.pageDao().getPageWithDataById(pageId)!!
                assertEquals("a fully-valid replacement updates cleanly", 3, after.strokes.size)
            }
        }
    }
}
