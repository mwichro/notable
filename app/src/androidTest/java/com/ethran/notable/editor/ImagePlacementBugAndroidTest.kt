package com.ethran.notable.editor

import android.content.Context
import android.graphics.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.test.core.app.ApplicationProvider
import com.ethran.notable.data.db.AppDatabase
import com.ethran.notable.data.db.Image
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.data.db.Page
import com.ethran.notable.editor.utils.imageBoundsInt
import com.ethran.notable.editor.utils.offsetImage
import com.ethran.notable.testing.TestDatabaseFactory
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * Instrumented tests that reproduce image import / move / resize bugs that need
 * the real Android framework (android.graphics.Rect) and/or the real Room DB.
 *
 * These do NOT fix anything; each test asserts the buggy behaviour so it is
 * pinned and visible. See ImagePlacementMathTest (host JVM) for the pure
 * arithmetic bugs, and the report in REPORT_image_bugs.md.
 */
class ImagePlacementBugAndroidTest {

    private lateinit var db: AppDatabase
    private lateinit var context: Context
    private lateinit var pageId: String

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = TestDatabaseFactory.createInMemory(context)
        runBlocking {
            val notebookId = UUID.randomUUID().toString()
            db.notebookDao().create(Notebook(id = notebookId, title = "img-bugs"))
            pageId = UUID.randomUUID().toString()
            db.pageDao().create(
                Page(
                    id = pageId,
                    notebookId = notebookId,
                    background = "blank",
                    backgroundType = "native",
                )
            )
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ---------------------------------------------------------------------
    // BUG 1 (real Rect): the page-height growth in PageView.addImage uses x.
    // Here we reproduce the exact formula against the image's real bounding box
    // (imageBoundsInt, which uses android.graphics.Rect) to show the height the
    // page SHOULD grow to vs what addImage computes.
    //   PageView.kt:407  bottomPlusPadding = it.x + it.height + 50
    // ---------------------------------------------------------------------
    @Test(timeout = 10000)
    fun addImageHeightGrowth_usesXInsteadOfBottom() {
        val image = Image(
            x = 30, y = 5000, width = 400, height = 600,
            uri = "content://fake", pageId = pageId
        )

        val bounds: Rect = imageBoundsInt(image)
        // The real bottom edge of the image on the page:
        val realBottom = bounds.bottom               // 5600
        val correctGrowTo = realBottom + 50          // 5650

        // Verbatim production formula from PageView.addImage (PageView.kt:407):
        val buggyGrowTo = image.x + image.height + 50 // 30 + 600 + 50 = 680

        assertEquals(5600, realBottom)
        assertNotEquals(
            "Page should grow to the image bottom; addImage uses x and stops far short",
            correctGrowTo,
            buggyGrowTo
        )
        assertTrue(
            "BUG: image lives at y=5000..5600 but page only grows to ~680px -> unreachable/clipped",
            buggyGrowTo < image.y
        )
    }

    // ---------------------------------------------------------------------
    // Round-trip control: an imported image's geometry must survive persistence.
    // This exercises the real ImageDao create/getById path used after a move
    // (SelectionState.applySelectionDisplace -> page.addImage -> saveImagesToDb).
    // ---------------------------------------------------------------------
    @Test(timeout = 10000)
    fun imageGeometry_survivesDbRoundTrip_control() {
        runBlocking {
            withTimeout(8000) {
                val image = Image(
                    x = 123, y = 456, width = 78, height = 90,
                    uri = "content://round-trip", pageId = pageId
                )
                db.ImageDao().create(image)
                val loaded = db.ImageDao().getById(image.id)
                assertEquals(123, loaded.x)
                assertEquals(456, loaded.y)
                assertEquals(78, loaded.width)
                assertEquals(90, loaded.height)
                assertEquals("content://round-trip", loaded.uri)
            }
        }
    }

    // ---------------------------------------------------------------------
    // BUG: a moved image is committed with offsetImage, which truncates the
    // fractional drag offset. After persisting, the stored position differs from
    // where the user released. Reproduced end-to-end through the DB.
    //   operations.kt:185-186  x = image.x + offset.x.toInt()
    // ---------------------------------------------------------------------
    @Test(timeout = 10000)
    fun movedImage_persistsTruncatedPosition() {
        runBlocking {
            withTimeout(8000) {
                val original = Image(
                    x = 100, y = 100, width = 50, height = 50,
                    uri = "content://move", pageId = pageId
                )
                // User drops the selection 10.8px to the right, 10.8px down.
                val dropOffset = Offset(10.8f, 10.8f)
                val moved = offsetImage(original, dropOffset)

                db.ImageDao().create(moved)
                val loaded = db.ImageDao().getById(moved.id)

                // Truncated to 110, not 110.8 / not rounded to 111.
                assertEquals(110, loaded.x)
                assertEquals(110, loaded.y)
                assertNotEquals(
                    "BUG: fractional drag is truncated, not rounded -> sub-pixel drop drifts",
                    111, loaded.x
                )
            }
        }
    }

    // ---------------------------------------------------------------------
    // BUG 3 reproduction (resize displacement center drift) expressed against
    // the same IntOffset/Int math the production resizeImages uses
    // (SelectionState.kt:100-104, :109). A +10% resize of a 33px-wide image
    // should shift the left edge left by half the real growth (1.5px) to keep
    // the center fixed, but integer /200 yields 1px -> 0.5px drift per step.
    // ---------------------------------------------------------------------
    @Test(timeout = 10000)
    fun resizeImages_centerDriftAccumulates() {
        val startWidth = 33
        val startHeight = 33
        val scale = 10

        var width = startWidth
        var displaceX = 0

        // Apply the production resize math 4 times in a row.
        repeat(4) {
            val grownWidth = width + (width * scale / 100)
            val sizeChange = IntOffset(x = grownWidth * scale / 200, y = grownWidth * scale / 200)
            displaceX -= sizeChange.x
            width = grownWidth
        }

        // True center-preserving left shift after the 4 growth steps:
        val trueLeftShift = -(width - startWidth) / 2 // negative

        assertNotEquals(
            "BUG: integer /200 compensation drifts the image center on repeated resize. " +
                "displaceX=$displaceX but center-preserving shift=$trueLeftShift",
            trueLeftShift,
            displaceX
        )
    }
}
