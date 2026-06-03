package com.ethran.notable.editor

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ethran.notable.data.db.AppDatabase
import com.ethran.notable.data.db.Image
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.data.db.Page
import com.ethran.notable.editor.utils.offsetImage
import com.ethran.notable.testing.TestDatabaseFactory
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * Reproduces the "image disappears while being dropped" bug.
 *
 * Root cause (see REPORT_image_bugs.md, BUG 5):
 * When a selected image is MOVED and dropped, SelectionState.applySelectionDisplace()
 * does, in order:
 *
 *   page.removeImages(originalIds)   // -> PageDataManager.removeImagesFromDb -> dataScope.launch { deleteAll }
 *   page.addImage(displacedImages)   // -> PageDataManager.saveImagesToDb     -> dataScope.launch { create   }
 *
 * Crucially `offsetImage` (operations.kt:183) keeps the SAME primary-key id, so the
 * displaced image carries the id that was just scheduled for deletion.
 *
 * Both DB operations are dispatched as independent coroutines on
 *   CoroutineScope(SupervisorJob() + Dispatchers.IO)        (PageDataManager.kt:81)
 * with NO ordering and NO transaction. On Dispatchers.IO (up to 64 threads) the
 * create and the delete of the same id run concurrently. If the delete coroutine
 * runs last, the freshly-created row is deleted: the image is still in the in-memory
 * list (so it looks fine), but it is GONE from the database -> it disappears on the
 * next reload / page switch / sync.
 *
 * This test does NOT fix the bug. It pins the buggy behaviour by reproducing the
 * exact scheduling and forcing the losing interleaving deterministically.
 */
class ImageDropDisappearRaceTest {

    private lateinit var db: AppDatabase
    private lateinit var context: Context
    private lateinit var pageId: String

    /** Same scope shape as PageDataManager.dataScope. */
    private val dataScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = TestDatabaseFactory.createInMemory(context)
        runBlocking {
            val notebookId = UUID.randomUUID().toString()
            db.notebookDao().create(Notebook(id = notebookId, title = "img-drop"))
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

    /**
     * Deterministic reproduction: force the delete coroutine to land AFTER the
     * create coroutine (the "losing" interleaving). This is exactly what happens
     * in the field when the IO thread running deleteAll is scheduled after the one
     * running create.
     *
     * To make it deterministic without touching production code, we add the same
     * `dataScope.launch` calls in the same order applySelectionDisplace uses, but
     * insert a small delay inside the delete coroutine so it commits last. In the
     * real app this delay is supplied by the OS scheduler at random -> "sometimes".
     */
    @Test(timeout = 15000)
    fun movedImage_disappears_whenDeleteCoroutineWinsRace() {
        runBlocking {
            withTimeout(12000) {
                val imageDao = db.ImageDao()

                // 1. Original imported image already persisted.
                val original = Image(
                    x = 100, y = 100, width = 80, height = 80,
                    uri = "content://drop", pageId = pageId
                )
                imageDao.create(original)
                assertNotNull("precondition: image is in DB", imageDao.getByIdOrNull(original.id))

                // 2. User drags and drops. offsetImage keeps the SAME id.
                val displaced = offsetImage(original, Offset(40f, 40f))
                assertEquals("offsetImage keeps the same primary key", original.id, displaced.id)

                // 3. Reproduce applySelectionDisplace ordering on the unordered IO scope:
                //    remove (original id) first, then add (displaced, same id).
                val removeJob = dataScope.launch {
                    // Force this to commit LAST -> the losing interleaving.
                    delay(300)
                    imageDao.deleteAll(listOf(original.id))
                }
                val addJob = dataScope.launch {
                    imageDao.create(displaced)
                }

                addJob.join()
                removeJob.join()

                // 4. In-memory the app still believes the image exists, but the DB
                //    row was deleted by the late-running remove coroutine.
                val inDb = imageDao.getByIdOrNull(original.id)
                assertNull(
                    "BUG: dropped image was deleted from DB by the racing remove coroutine " +
                        "(same id, no ordering, no transaction) -> image disappears on reload",
                    inDb
                )
            }
        }
    }

    /**
     * Control: when the operations are correctly ordered (remove fully completes
     * before add), the image survives. This shows the disappearance is purely an
     * ordering/race problem, not a logic error in the move itself.
     */
    @Test(timeout = 15000)
    fun movedImage_survives_whenOrderedCorrectly_control() {
        runBlocking {
            withTimeout(12000) {
                val imageDao = db.ImageDao()
                val original = Image(
                    x = 100, y = 100, width = 80, height = 80,
                    uri = "content://drop2", pageId = pageId
                )
                imageDao.create(original)

                val displaced = offsetImage(original, Offset(40f, 40f))

                // Correct ordering: remove THEN add, sequentially.
                imageDao.deleteAll(listOf(original.id))
                imageDao.create(displaced)

                val inDb = imageDao.getByIdOrNull(displaced.id)
                assertNotNull("ordered move keeps the image in the DB", inDb)
                assertEquals(140, inDb!!.x)
                assertEquals(140, inDb.y)
            }
        }
    }
}
