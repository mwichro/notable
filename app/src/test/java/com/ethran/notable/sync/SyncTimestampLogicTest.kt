package com.ethran.notable.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

/**
 * Pure-JVM unit tests for sync conflict-resolution timestamp logic. These exercise
 * only java.util.Date / long arithmetic (no Android, no network), replicating the
 * exact production comparisons with source citations.
 *
 * No production behaviour is changed; each test pins the buggy logic.
 * See REPORT_sync_bugs.md.
 */
class SyncTimestampLogicTest {

    private val TIMESTAMP_TOLERANCE_MS = 1000L // NotebookReconciliationService.kt:110

    // ---------------------------------------------------------------------
    // BUG: tombstone resurrection uses a STRICT `after` with NO tolerance, unlike
    // the ±1s tolerance used everywhere else for timestamps.
    //   NotebookSyncService.applyRemoteDeletions (NotebookSyncService.kt:47):
    //     if (deletedAt != null && localNotebook.updatedAt.after(deletedAt)) resurrect
    //     else delete locally
    //
    // The doc (webdav-sync-technical.md §5.3) promises: "edits made after a deletion
    // are never silently discarded." But timestamps are serialized to 1-second
    // resolution (ISO 8601), so an edit made in the SAME second as the deletion has
    // updatedAt == deletedAt -> `after` is false -> the notebook is DELETED and the
    // edit is silently lost. With the ±1s tolerance applied to every other
    // comparison, this edit would have been preserved.
    // ---------------------------------------------------------------------
    @Test
    fun resurrection_sameSecondEditIsDeletedNotResurrected() {
        // Deletion and edit happen in the same wall-clock second; after ISO 8601
        // round-trip both truncate to the same millisecond.
        val deletedAt = Date(1_700_000_000_000L)
        val localEditedAt = Date(1_700_000_000_000L) // identical after truncation

        // Production decision (NotebookSyncService.kt:47):
        val resurrected = localEditedAt.after(deletedAt)

        assertFalse(
            "BUG: a same-second local edit is NOT resurrected (after== false) and the " +
                "notebook + its edit are silently deleted",
            resurrected
        )
    }

    @Test
    fun resurrection_editJustBeforeDeletionWithinToleranceIsAlsoDeleted() {
        // Edit 400ms BEFORE the tombstone — well within the 1s tolerance the rest of
        // the system treats as "equal". Strict `after` still deletes it.
        val deletedAt = Date(1_700_000_000_400L)
        val localEditedAt = Date(1_700_000_000_000L)

        val resurrected = localEditedAt.after(deletedAt)
        assertFalse(
            "BUG: an edit 400ms before the deletion (within the 1s tolerance used " +
                "elsewhere) is deleted with no resurrection",
            resurrected
        )

        // Show that, had the same tolerance been applied, this would count as a tie
        // and the safe choice (resurrect) could be made.
        val diffMs = localEditedAt.time - deletedAt.time
        assertTrue(
            "the edit is within the ±1s tolerance band used by notebook comparison",
            kotlin.math.abs(diffMs) <= TIMESTAMP_TOLERANCE_MS
        )
    }

    // ---------------------------------------------------------------------
    // BUG: folder merge has the SAME strict-`after` asymmetry, with no tolerance.
    //   FolderSyncService.syncFolders (FolderSyncService.kt:40):
    //     if (remote == null || local.updatedAt.after(remote.updatedAt)) keep local
    // When local and remote folder timestamps are equal (a common case right after
    // a sync, or after ISO truncation), the REMOTE folder wins — a just-made local
    // rename that lands in the same second as the remote timestamp is dropped.
    // ---------------------------------------------------------------------
    @Test
    fun folderMerge_equalTimestamps_remoteWinsOverLocal() {
        val ts = Date(1_700_000_000_000L)
        val localUpdatedAt = ts
        val remoteUpdatedAt = ts

        // Production keeps local only if local.after(remote) (FolderSyncService.kt:40):
        val localWins = localUpdatedAt.after(remoteUpdatedAt)
        assertFalse(
            "BUG: on equal timestamps the local folder edit is discarded in favour of remote",
            localWins
        )
    }

    // ---------------------------------------------------------------------
    // Control: the documented notebook timestamp comparison (§5.2) behaves as
    // specified for clearly-different timestamps.
    // ---------------------------------------------------------------------
    @Test
    fun notebookComparison_followsDocumentedBands_control() {
        fun decide(localMs: Long, remoteMs: Long): String {
            val diff = localMs - remoteMs
            return when {
                diff > TIMESTAMP_TOLERANCE_MS -> "upload"
                diff < -TIMESTAMP_TOLERANCE_MS -> "download"
                else -> "skip"
            }
        }
        assertTrue(decide(5000, 1000) == "upload")
        assertTrue(decide(1000, 5000) == "download")
        assertTrue(decide(1000, 1500) == "skip") // within tolerance
    }
}
