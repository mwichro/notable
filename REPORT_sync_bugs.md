# WebDAV sync — bug report

Scope: the sync subsystem in `com.ethran.notable.sync` (orchestration, conflict
resolution, deletion/tombstone handling, folder merge, serialization) checked
against [docs/webdav-sync-technical.md](docs/webdav-sync-technical.md).

No production behaviour was changed. Tests:
- host-JVM: [SyncTimestampLogicTest.kt](app/src/test/java/com/ethran/notable/sync/SyncTimestampLogicTest.kt)
- instrumented: [NotebookSerializerBugAndroidTest.kt](app/src/androidTest/java/com/ethran/notable/sync/NotebookSerializerBugAndroidTest.kt),
  [SyncDownloadDataLossAndroidTest.kt](app/src/androidTest/java/com/ethran/notable/sync/SyncDownloadDataLossAndroidTest.kt)

---

## BUG 1 — page download is non-atomic: a failed insert wipes existing strokes/images

`NotebookSyncService.downloadPage` for an already-existing page
([NotebookSyncService.kt:358-373](app/src/main/java/com/ethran/notable/sync/NotebookSyncService.kt#L358)):

```kotlin
strokeRepository.deleteAll(pageWithData.strokes.map { it.id })   // destroy old FIRST
imageRepository.deleteAll(pageWithData.images.map { it.id })
pageRepository.update(page)
...
strokeRepository.create(strokes)        // insert new AFTER  <-- can throw
imageRepository.create(updatedImages)
```

There is **no surrounding transaction**. If `create(...)` throws — e.g. a stroke
the SB1 converter rejects (empty points, see REPORT_db_bugs BUG D), an oversized
stroke, or any Room/constraint error — the `catch` only **aggregates** the error.
The old strokes/images were already deleted and the replacements never landed, so
the page is left **empty**. A transient or partly-corrupt remote page silently
destroys good local content. The doc (§7.3 "Failure Isolation") promises page-level
isolation, but the failure here is *destructive*, not isolated.

Test: `SyncDownloadDataLossAndroidTest.downloadPage_failedStrokeInsert_wipesExistingStrokes`.

Likely fix (not applied): wrap delete+update+create in a single Room
`@Transaction`, or insert-then-delete, or validate the incoming payload before
deleting.

---

## BUG 2 — tombstone resurrection uses strict `after` with NO tolerance → same-second edits silently deleted

`NotebookSyncService.applyRemoteDeletions`
([NotebookSyncService.kt:47](app/src/main/java/com/ethran/notable/sync/NotebookSyncService.kt#L47)):

```kotlin
if (deletedAt != null && localNotebook.updatedAt.after(deletedAt)) { /* resurrect */ }
else { /* delete locally */ }
```

Every *other* timestamp comparison in sync uses a ±1000ms tolerance
([NotebookReconciliationService.kt:110](app/src/main/java/com/ethran/notable/sync/NotebookReconciliationService.kt#L110))
precisely because ISO 8601 serialization truncates to whole seconds (§5.2). The
resurrection check does **not**. So:

- A local edit made in the **same second** as the deletion has
  `updatedAt == deletedAt` after truncation → `after` is false → the notebook is
  **deleted and the edit is lost**.
- An edit made up to ~1s *before* the tombstone (within the tolerance band the rest
  of the system treats as "equal") is also deleted.

This directly contradicts the doc's headline guarantee (§5.3): *"edits made after a
deletion are never silently discarded."*

Tests: `SyncTimestampLogicTest.resurrection_sameSecondEditIsDeletedNotResurrected`,
`resurrection_editJustBeforeDeletionWithinToleranceIsAlsoDeleted`.

---

## BUG 3 — folder merge: equal timestamps drop the local edit; no tolerance

`FolderSyncService.syncFolders`
([FolderSyncService.kt:40](app/src/main/java/com/ethran/notable/sync/FolderSyncService.kt#L40)):

```kotlin
if (remote == null || local.updatedAt.after(remote.updatedAt)) folderMap[local.id] = local
```

Same strict-`after`, no-tolerance asymmetry as BUG 2, but for folders. On equal
timestamps (common right after a sync, or after ISO truncation) the **remote**
folder wins and a just-made local rename in the same second is discarded.

Additionally the merge is a **union** of remote + local folders with no deletion
tracking, so a folder deleted locally is silently **re-created** from the remote
copy on the next sync (§5.7 documents that folder deletions don't propagate, but the
local-resurrection side effect is surprising).

Test: `SyncTimestampLogicTest.folderMerge_equalTimestamps_remoteWinsOverLocal`.

---

## BUG 4 — unknown pen name silently drops the whole stroke on download (doc/code mismatch)

`NotebookSerializer.deserializePage`
([NotebookSerializer.kt:184](app/src/main/java/com/ethran/notable/sync/serializers/NotebookSerializer.kt#L184))
uses `Pen.valueOf(strokeDto.pen)`, which **throws** on an unknown name. The throw is
swallowed by the per-stroke `try/catch` ([:201](app/src/main/java/com/ethran/notable/sync/serializers/NotebookSerializer.kt#L201))
and the stroke is skipped (`mapNotNull` → null). A lenient
`Pen.fromString` (defaults to `BALLPEN`,
[pen.kt:20](app/src/main/java/com/ethran/notable/editor/utils/pen.kt#L20)) exists but
is **not** used.

Consequences:
- The doc (§4.3) documents the pen value as `"BALLPOINT"`, which is **not** a valid
  enum name (the enum is `BALLPEN`,
  [pen.kt:9](app/src/main/java/com/ethran/notable/editor/utils/pen.kt#L9)). A page
  written per the doc, or by any client using a different/newer pen name, loses
  **every** stroke on download.
- This defeats the `ignoreUnknownKeys` forward-compatibility intent (§4.6) for the
  pen field specifically.

Test: `NotebookSerializerBugAndroidTest.deserialize_unknownPenName_silentlyDropsStroke`.

---

## BUG 5 — SB1 `dt` corruption also travels over sync

Because `pointsData` is the same SB1 binary, the `dt=65535 → 65534` clamp
(REPORT_db_bugs BUG A) corrupts delta-time on every upload/download too. Pinned here
as a sync round-trip.

Test: `NotebookSerializerBugAndroidTest.serialize_dtMaxValue_doesNotRoundTrip`.

---

## Other observations (analysed, not separately tested)

- **Image URI round-trip is folder-name dependent.** `convertToRelativeUri`
  ([NotebookSerializer.kt:245](app/src/main/java/com/ethran/notable/sync/serializers/NotebookSerializer.kt#L245))
  keeps only the immediate parent directory name + filename, while the upload path
  uses `localFile.name` against a fixed `images/` remote dir
  ([NotebookSyncService.kt:220](app/src/main/java/com/ethran/notable/sync/NotebookSyncService.kt#L220)).
  If a local image is stored under a directory not literally named `images`, the
  page JSON records a relative path (`<parent>/<file>`) that does not match the
  server layout (`images/<file>`), so a later download cannot find the file.
- **`uploadDeletion` race with concurrent edit.** `uploadDeletion`
  ([SyncOrchestrator.kt:229](app/src/main/java/com/ethran/notable/sync/SyncOrchestrator.kt#L229))
  is **not** guarded by `syncMutex` (only full sync, force up/down are). It deletes
  the remote dir and writes a tombstone even if a full sync is running, and even if
  the local notebook was just edited — there is no updatedAt-vs-now check before
  tombstoning, so a delete that races an edit can win.
- **`syncNotebook` (sync-on-close) bypasses the mutex.** It early-returns
  `Success` if `syncMutex.isLocked` ([SyncOrchestrator.kt:196](app/src/main/java/com/ethran/notable/sync/SyncOrchestrator.kt#L196))
  but otherwise does **not** acquire it, so a sync-on-close can run concurrently
  with `uploadDeletion` (also unguarded) on the same notebook.
- **Clock-skew error rounds toward zero.** `skewMs / 1000`
  ([SyncPreflightService.kt:35](app/src/main/java/com/ethran/notable/sync/SyncPreflightService.kt#L35))
  truncates; a 30.9s skew is reported as "30s". Cosmetic.

### Forcing the races deterministically (manual)

The `uploadDeletion`/`syncNotebook` mutex gaps are timing-dependent. To force them:

- In `SyncOrchestrator.uploadDeletion`, add `kotlinx.coroutines.delay(500)` right
  after `client.delete(path)` ([SyncOrchestrator.kt:247](app/src/main/java/com/ethran/notable/sync/SyncOrchestrator.kt#L247))
  and, on another coroutine, call `syncNotebook(sameId)` during that window — the
  delete and the re-upload interleave because neither holds `syncMutex`.
- For BUG 1, add `delay(...)` between `deleteAll(...)`
  ([NotebookSyncService.kt:365](app/src/main/java/com/ethran/notable/sync/NotebookSyncService.kt#L365))
  and `create(strokes)` ([:372](app/src/main/java/com/ethran/notable/sync/NotebookSyncService.kt#L372))
  to widen the window where the page is empty; reading the page during that window
  shows the loss even without an insert failure.

---

## How to run

- Host JVM: `./gradlew :app:testDebugUnitTest --tests "com.ethran.notable.sync.SyncTimestampLogicTest"`
- Instrumented (needs device/emulator; compile-only here):
  `./gradlew :app:compileDebugAndroidTestKotlin`
