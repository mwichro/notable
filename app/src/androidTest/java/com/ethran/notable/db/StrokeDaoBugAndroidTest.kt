package com.ethran.notable.db

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
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * Instrumented tests for Room DAO / TypeConverter bugs around saving, reading and
 * deleting strokes. Uses a real in-memory Room database.
 *
 * No production behaviour is changed; each test pins the buggy behaviour.
 */
class StrokeDaoBugAndroidTest {

    private lateinit var db: AppDatabase
    private lateinit var pageId: String

    private fun stroke(
        id: String = UUID.randomUUID().toString(),
        points: List<StrokePoint>,
    ) = Stroke(
        id = id,
        size = 5f,
        pen = Pen.BALLPEN,
        color = 0xFF000000.toInt(),
        maxPressure = 4096,
        top = points.minOfOrNull { it.y } ?: 0f,
        bottom = points.maxOfOrNull { it.y } ?: 0f,
        left = points.minOfOrNull { it.x } ?: 0f,
        right = points.maxOfOrNull { it.x } ?: 0f,
        points = points,
        pageId = pageId,
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = TestDatabaseFactory.createInMemory(context)
        runBlocking {
            val notebookId = UUID.randomUUID().toString()
            db.notebookDao().create(Notebook(id = notebookId, title = "stroke-bugs"))
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
    fun tearDown() = db.close()

    // ---------------------------------------------------------------------
    // BUG: saving a stroke with an EMPTY point list crashes.
    // The TypeConverter Converters.fromStrokePoints (Db.kt:41) calls
    // computeStrokeMask, which `require(points.isNotEmpty())` (StrokePointConverter.kt:34)
    // and encodeStrokePoints also rejects empty lists. So a zero-point stroke
    // cannot be persisted at all -> insert throws, even though Stroke allows
    // points = emptyList(). Meanwhile toStrokePoints happily returns emptyList()
    // for empty bytes, so read and write are asymmetric.
    // ---------------------------------------------------------------------
    @Test(timeout = 10000)
    fun insertingStrokeWithEmptyPoints_crashes() {
        runBlocking {
            withTimeout(8000) {
                try {
                    db.strokeDao().create(stroke(points = emptyList()))
                    fail("Expected insert of an empty-point stroke to throw (it cannot be encoded)")
                } catch (e: Exception) {
                    // BUG reproduced: the converter rejects the empty list at insert time.
                    assertNotNull(e.message)
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // BUG: StrokeDao.getById has a NON-NULL return type but the query can match
    // nothing. Reading a missing id does not return null cleanly — Room's
    // generated code for a non-null type throws when the cursor is empty.
    //   StrokeDao.getById (Stroke.kt:75-76): suspend fun getById(id): Stroke
    // (ImageDao.getById has the same shape.) Callers that expect a graceful miss
    // get a crash instead.
    // ---------------------------------------------------------------------
    @Test(timeout = 10000)
    fun getById_missingStroke_throwsInsteadOfReturningNull() {
        runBlocking {
            withTimeout(8000) {
                try {
                    val result = db.strokeDao().getById("does-not-exist")
                    // If Room ever returns a non-null default here, that is also a
                    // bug (a fabricated row); flag it.
                    fail("Expected getById on a missing id to throw, but returned: $result")
                } catch (e: Exception) {
                    assertNotNull(e)
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // Control: a normal stroke saves and reads back, with points preserved
    // through the SB1 TypeConverter.
    // ---------------------------------------------------------------------
    @Test(timeout = 10000)
    fun normalStroke_savesAndReadsBack_control() {
        runBlocking {
            withTimeout(8000) {
                val s = stroke(
                    points = listOf(
                        StrokePoint(x = 1f, y = 2f, pressure = 1000f),
                        StrokePoint(x = 3f, y = 4f, pressure = 1100f),
                    )
                )
                db.strokeDao().create(s)
                val loaded = db.strokeDao().getById(s.id)
                assertEquals(s.id, loaded.id)
                assertEquals(2, loaded.points.size)
                assertEquals(1f, loaded.points[0].x, 0.01f)
                assertEquals(4f, loaded.points[1].y, 0.01f)
            }
        }
    }
}
