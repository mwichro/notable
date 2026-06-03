package com.ethran.notable.editor

import androidx.compose.ui.geometry.Offset
import com.ethran.notable.data.db.Image
import com.ethran.notable.editor.utils.offsetImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM unit tests that reproduce arithmetic bugs in the image
 * import / move / resize pipeline.
 *
 * These tests only touch code that does NOT depend on the Android framework
 * (android.graphics.* is stubbed in local unit tests and throws), so they run
 * on the host JVM with no emulator.
 *
 * Bugs that need android.graphics.Rect (imageBoundsInt, drawImage, toScreenCoordinates)
 * or the Room database are reproduced in the instrumented test
 * ImagePlacementBugAndroidTest instead.
 *
 * NONE of these tests change production behaviour. Where the buggy expression is
 * inlined inside a method that needs a PageView/Android (handleImage, addImage,
 * resizeImages) the exact production formula is replicated verbatim and the source
 * line is cited, so the assertion fails the same way the real code misbehaves.
 */
class ImagePlacementMathTest {

    // ---------------------------------------------------------------------
    // BUG 1: PageView.addImage grows page height using x instead of y.
    //   PageView.kt:395  ->  val bottomPlusPadding = imageToAdd.x + imageToAdd.height + 50
    //   PageView.kt:407  ->  val bottomPlusPadding = it.x + it.height + 50
    // The intent is the image's BOTTOM edge in page coordinates, which is
    // (y + height). Using x means:
    //   - a tall image placed far down the page (large y) never grows the page,
    //     so you cannot scroll to see it / it gets clipped;
    //   - an image pushed far to the right (large x) grows the page height for
    //     no reason.
    // ---------------------------------------------------------------------
    @Test
    fun addImage_pageHeightUsesXInsteadOfY() {
        // Image placed low on the page (y is large), near the left edge (x small).
        val image = Image(
            x = 10,
            y = 4000,
            width = 200,
            height = 300,
            uri = "content://fake",
            pageId = "page-1"
        )

        // The CORRECT bottom edge that should drive page growth:
        val correctBottom = image.y + image.height + 50   // 4350

        // What PageView.addImage actually computes (verbatim from PageView.kt:395):
        val buggyBottom = image.x + image.height + 50      // 360

        // The bug: the page would only grow to ~360px tall, even though the image
        // sits at y=4000..4300. The image is effectively unreachable.
        assertNotEquals(
            "addImage should grow height by the image bottom (y+height), not (x+height)",
            correctBottom,
            buggyBottom
        )
        assertTrue("Demonstration of the bug: buggy bottom is far above the real image",
            buggyBottom < image.y)
    }

    // ---------------------------------------------------------------------
    // BUG 2: ImageHandler.handleImage centers the image using the PHYSICAL
    // (zoomed) bitmap pixel size against the page-coordinate viewport, and adds
    // scroll without dividing by zoom.
    //   ImageHandler.kt:61  centerX = (page.viewWidth - imageWidth)/2 + scroll.x
    //   ImageHandler.kt:62  centerY = (page.viewHeight- imageHeight)/2 + scroll.y
    // viewWidth/viewHeight and scroll are in PAGE coordinates, but imageWidth/
    // imageHeight are RAW BITMAP pixels. When zoomLevel != 1, or when the image
    // is larger than the viewport, the image is not centered (and can land at a
    // negative coordinate, i.e. off-page).
    // ---------------------------------------------------------------------
    @Test
    fun handleImage_centeringIgnoresZoomAndCanGoOffPage() {
        val viewWidth = 1000
        val viewHeight = 1400
        val scrollX = 0
        val scrollY = 0

        // A photo larger than the viewport (very common: a phone camera image).
        val imageWidth = 3000
        val imageHeight = 4000

        // Verbatim production formula (ImageHandler.kt:61-62):
        val centerX = (viewWidth - imageWidth) / 2 + scrollX
        val centerY = (viewHeight - imageHeight) / 2 + scrollY

        // Bug: top-left lands at strongly negative coordinates, so most of the
        // image (and its top-left handle) is off the top-left of the page and
        // cannot be grabbed/moved.
        assertTrue("Expected x to land off-page (negative) for a large image", centerX < 0)
        assertTrue("Expected y to land off-page (negative) for a large image", centerY < 0)

        // For "centering" to be correct the image center should equal the
        // viewport center; show that it does not.
        val imageCenterX = centerX + imageWidth / 2
        val expectedCenterX = viewWidth / 2
        // They happen to match only because zoom is ignored AND scroll is 0;
        // with zoom this breaks. Demonstrate the zoom case:
        val zoom = 2.0f
        // Correct page-space size would be imageWidth / zoom.
        val correctCenterXTopLeft = ((viewWidth - imageWidth / zoom) / 2 + scrollX).toInt()
        assertNotEquals(
            "With zoom!=1 the placement ignores zoom and is wrong",
            correctCenterXTopLeft,
            centerX
        )
    }

    // ---------------------------------------------------------------------
    // BUG 3: SelectionState.resizeImages drifts the image position because the
    // re-centering offset is recomputed from the NEW (already grown) size, and
    // uses integer truncation, so resize is not anchored about the center.
    //   SelectionState.kt:87-92  builds resized copy (height + height*scale/100)
    //   SelectionState.kt:100-104 sizeChange = newWidth*scale/200, newHeight*scale/200
    //   SelectionState.kt:109     displaceOffset -= sizeChange
    // The compensation should keep the image CENTER fixed. Growing then shrinking
    // back by the same percentage should return to the original size AND position;
    // it does neither because:
    //   (a) shrink uses width*(-scale)/100 of the ALREADY-GROWN width, so the
    //       size does not round-trip;
    //   (b) the offset uses the post-resize size and /200 truncation, so the
    //       center is not preserved.
    // ---------------------------------------------------------------------
    @Test
    fun resizeImages_sizeDoesNotRoundTrip() {
        var width = 100
        var height = 100
        val scale = 10 // +10%

        // grow (SelectionState.kt:88-91)
        val grownWidth = width + (width * scale / 100)     // 110
        val grownHeight = height + (height * scale / 100)  // 110

        // shrink back by -10% of the GROWN size (SelectionState.kt:88-91 again)
        val shrunkWidth = grownWidth + (grownWidth * (-scale) / 100)   // 110 - 11 = 99
        val shrunkHeight = grownHeight + (grownHeight * (-scale) / 100) // 99

        // Bug: +10% then -10% does not return to 100.
        assertNotEquals(
            "Resize up then down should round-trip width back to original",
            width,
            shrunkWidth
        )
        assertEquals(99, shrunkWidth)
        assertEquals(99, shrunkHeight)
    }

    @Test
    fun resizeImages_centerDriftsOnResize() {
        // Original image occupies page x in [200, 300], center at 250.
        val x = 200
        val width = 100
        val scale = 10

        val grownWidth = width + (width * scale / 100) // 110

        // displaceOffset compensation (SelectionState.kt:100-103):
        // sizeChange.x = grownWidth * scale / 200
        val compensationX = grownWidth * scale / 200 // 110*10/200 = 5

        // The bitmap is redrawn at the SAME page x (image.x is unchanged in the
        // resized copy), and the compose layer is shifted left by compensationX.
        // For the center to be preserved, the left edge must move left by exactly
        // half the width growth: (grownWidth - width)/2 = 5. Here it happens to be
        // 5, but the value is derived from the post-resize width and /200 integer
        // truncation, so it diverges for other sizes. Demonstrate divergence:
        val width2 = 33
        val grownWidth2 = width2 + (width2 * scale / 100) // 33 + 3 = 36
        val compensation2 = grownWidth2 * scale / 200       // 36*10/200 = 1 (truncated)
        val correctHalfGrowth = (grownWidth2 - width2) / 2  // (36-33)/2 = 1 ... but real growth is 3 -> half=1.5
        // The true half-growth is 1.5px; integer compensation is 1px => 0.5px drift
        // accumulates every resize step.
        val trueHalfGrowthTimes2 = grownWidth2 - width2 // 3 (so half = 1.5)
        assertNotEquals(
            "Compensation (=$compensation2) should equal half the real width growth (1.5px); " +
                "integer math drops the half pixel and the image center drifts",
            (trueHalfGrowthTimes2 / 2.0),
            compensation2.toDouble()
        )
    }

    // ---------------------------------------------------------------------
    // offsetImage is android-free and used to commit a move. Confirm it is
    // correct (control test) so the bugs above are clearly localized.
    // ---------------------------------------------------------------------
    @Test
    fun offsetImage_movesByOffset_control() {
        val image = Image(x = 100, y = 200, width = 50, height = 60, uri = "u", pageId = "p")
        val moved = offsetImage(image, Offset(15f, -25f))
        assertEquals(115, moved.x)
        assertEquals(175, moved.y)
        // size and identity preserved
        assertEquals(50, moved.width)
        assertEquals(60, moved.height)
        assertEquals(image.id, moved.id)
    }

    // ---------------------------------------------------------------------
    // BUG 4: offsetImage truncates fractional offsets toward zero. When a move
    // is applied as several small drags whose fractional parts each truncate,
    // the committed position drifts from where the user dropped the selection.
    //   operations.kt:185-186  x = image.x + offset.x.toInt()
    // ---------------------------------------------------------------------
    @Test
    fun offsetImage_truncatesFractionalDrag() {
        val image = Image(x = 0, y = 0, width = 10, height = 10, uri = "u", pageId = "p")
        // Three drags of 0.9px each = 2.7px of real movement.
        var moved = image
        repeat(3) { moved = offsetImage(moved, Offset(0.9f, 0.9f)) }
        // Each step truncates 0.9 -> 0, so the image never moves at all.
        assertEquals("0.9px drags each truncate to 0 -> image never moves", 0, moved.x)
        assertNotEquals("User dragged 2.7px but commit kept it at 0", 2, moved.x)
    }
}
