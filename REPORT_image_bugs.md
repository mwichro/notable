# Image import / move / resize / placement — bug report

Scope: importing an image to a page via the toolbar, then moving, resizing and
placing it. Analysis of the code path:

```
Toolbar import
  -> EditorView.kt:175   CanvasEventBus.addImageByUri.value = uri
  -> ImageHandler.observeImageUri() collects it
  -> ImageHandler.handleImage()            (decode + center + select)
  -> selectImage()/selectImagesAndStrokes() (Select.kt)  build selection bitmap
  -> SelectorBitmap.kt                      drag to move, +/- to resize
  -> SelectionState.applySelectionDisplace()  commit move/paste -> PageView.addImage
  -> SelectionState.resizeImages()             resize
  -> PageView.addImage()                       grow page height + persist (ImageDao)
```

No production behaviour was changed. Each bug below has a reproducing test
(`app/src/test/.../ImagePlacementMathTest.kt` for host-JVM arithmetic,
`app/src/androidTest/.../ImagePlacementBugAndroidTest.kt` for framework/DB).

---

## BUG 1 — page height grows by `x + height` instead of `y + height`

`PageView.addImage()` (both overloads):

- [PageView.kt:395](app/src/main/java/com/ethran/notable/editor/PageView.kt#L395)
  `val bottomPlusPadding = imageToAdd.x + imageToAdd.height + 50`
- [PageView.kt:407](app/src/main/java/com/ethran/notable/editor/PageView.kt#L407)
  `val bottomPlusPadding = it.x + it.height + 50`

The page should grow to contain the image's **bottom edge**, i.e. `y + height`.
Using `x` means:

- An image placed/moved far **down** the page (large `y`, small `x`) never grows
  the page, so the page stays too short and the image is clipped / unreachable by
  scrolling.
- An image placed far to the **right** (large `x`) grows the page height for no
  reason, adding spurious empty vertical space.

Tests:
- `ImagePlacementMathTest.addImage_pageHeightUsesXInsteadOfY`
- `ImagePlacementBugAndroidTest.addImageHeightGrowth_usesXInsteadOfBottom`

Likely fix (not applied): use `imageToAdd.y + imageToAdd.height + 50`.

---

## BUG 2 — import centering ignores zoom and image-vs-viewport size

`ImageHandler.handleImage()`:

- [ImageHandler.kt:61](app/src/main/java/com/ethran/notable/editor/utils/ImageHandler.kt#L61)
  `val centerX = (page.viewWidth - imageWidth) / 2 + page.scroll.x.toInt()`
- [ImageHandler.kt:62](app/src/main/java/com/ethran/notable/editor/utils/ImageHandler.kt#L62)
  `val centerY = (page.viewHeight - imageHeight) / 2 + page.scroll.y.toInt()`

`viewWidth`, `viewHeight`, `scroll` are in **page coordinates**, but `imageWidth`
/ `imageHeight` are **raw bitmap pixels**. Consequences:

- When `zoomLevel != 1`, the placement does not account for zoom, so the image is
  not centered in the visible viewport.
- A photo larger than the viewport (typical phone camera image) gets a **negative**
  top-left, so it lands off the top-left of the page; its top-left resize/move
  handle is off-screen and cannot be grabbed.

Test: `ImagePlacementMathTest.handleImage_centeringIgnoresZoomAndCanGoOffPage`.

Note: the same method also calls `drawImage(... -page.scroll)` to paint a preview,
but the *stored* `Image` uses page coordinates that already include `+scroll`; the
preview and the selection bitmap (built in page coords by `selectImagesAndStrokes`)
use different conventions, which compounds the offset under zoom/scroll. Best
verified manually (see "Manual repro" below) because it needs a live `PageView`
surface.

---

## BUG 3 — resize is not anchored about the center and does not round-trip

`SelectionState.resizeImages()`:

- grow: [SelectionState.kt:88-91](app/src/main/java/com/ethran/notable/editor/state/SelectionState.kt#L88)
  `height + (height * scale / 100)`, `width + (width * scale / 100)`
- recenter offset: [SelectionState.kt:100-104](app/src/main/java/com/ethran/notable/editor/state/SelectionState.kt#L100)
  `IntOffset(width * scale / 200, height * scale / 200)` — derived from the
  **already-grown** size, with integer `/200` truncation.
- [SelectionState.kt:109](app/src/main/java/com/ethran/notable/editor/state/SelectionState.kt#L109)
  `selectionDisplaceOffset -= sizeChange`

Problems:

1. **No size round-trip:** +10% then −10% does not return to the original size,
   because the −10% is taken of the grown size (`110 → 99`, not `100`).
2. **Center drift:** the compensation that should keep the image center fixed is
   `grownSize * scale / 200`, computed with integer truncation, instead of
   `(grownSize − originalSize)/2`. For sizes whose growth is odd, the half-pixel is
   dropped and the image center walks across the page on repeated resizes.
3. `image.x` / `image.y` in the resized copy are **unchanged**; only the compose
   overlay offset is shifted. So the persisted page-coordinate origin and the
   on-screen preview can disagree after resize (the resized bitmap is drawn at the
   old origin in `resizeImages`, but the bitmap from `selectImagesAndStrokes` was
   built with zoom scaling — these two code paths scale differently).

Tests:
- `ImagePlacementMathTest.resizeImages_sizeDoesNotRoundTrip`
- `ImagePlacementMathTest.resizeImages_centerDriftsOnResize`
- `ImagePlacementBugAndroidTest.resizeImages_centerDriftAccumulates`

---

## BUG 4 — move commit truncates fractional offset (drift)

`offsetImage()`:

- [operations.kt:185-186](app/src/main/java/com/ethran/notable/editor/utils/operations.kt#L185)
  `x = image.x + offset.x.toInt()`, `y = image.y + offset.y.toInt()`

`Float.toInt()` truncates toward zero. Strokes are offset in `Float` and keep
sub-pixel precision; images do not. A drop at a fractional offset is truncated, so
the committed image position differs from where the user released. Worse, if a move
is ever applied as several small sub-pixel deltas, each truncates to 0 and the image
does not move at all.

Tests:
- `ImagePlacementMathTest.offsetImage_truncatesFractionalDrag`
- `ImagePlacementBugAndroidTest.movedImage_persistsTruncatedPosition`

Likely fix (not applied): `offset.x.roundToInt()` (matches the `Rect +/- Offset`
operators in `GeometryExtensions.kt`, which already round).

---

## BUG 5 — images disappear when dropped (DB delete/create race on the same id)

**This is the "images sometimes disappear while being dropped" bug.**

When a selected image is **moved** and dropped, `SelectionState.applySelectionDisplace()`
runs, in order:

- [SelectionState.kt:246](app/src/main/java/com/ethran/notable/editor/state/SelectionState.kt#L246)
  `page.removeImages(selectedImagesCopy.map { it.id })`
- [SelectionState.kt:248](app/src/main/java/com/ethran/notable/editor/state/SelectionState.kt#L248)
  `page.addImage(displacedImages)`

`offsetImage` keeps the **same primary-key id** for the displaced image
([operations.kt:183-192](app/src/main/java/com/ethran/notable/editor/utils/operations.kt#L183)),
so the image being *added* carries the id that was just scheduled for *deletion*.

Both DB calls are fire-and-forget coroutines on the **same unordered IO scope**:

- `removeImages` → `removeImagesFromDb` → [PageDataManager.kt:655-660](app/src/main/java/com/ethran/notable/data/PageDataManager.kt#L655)
  `dataScope.launch { imageRepository.deleteAll(...) }`
- `addImage` → `saveImagesToDb` → [PageDataManager.kt:641-646](app/src/main/java/com/ethran/notable/data/PageDataManager.kt#L641)
  `dataScope.launch { imageRepository.create(...) }`
- `dataScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)` —
  [PageDataManager.kt:81](app/src/main/java/com/ethran/notable/data/PageDataManager.kt#L81)

`Dispatchers.IO` runs these on up to 64 threads with **no ordering and no
transaction**. The two coroutines (delete id X, create id X) race:

- **create wins, delete loses (delete commits last):** the freshly written row is
  deleted. The image is still in the in-memory `images` list, so it looks fine on
  screen — but it is **gone from the DB**. On the next reload / page switch / sync,
  it disappears. ← the reported symptom.
- delete-then-create order: fine.

Because the winner depends on OS thread scheduling, it happens **"sometimes."**
The `// TODO: find why sometimes we add two times same operation.` comment at
[SelectionState.kt:251](app/src/main/java/com/ethran/notable/editor/state/SelectionState.kt#L251)
is a symptom of the same same-id remove+add design.

Test: `ImageDropDisappearRaceTest.movedImage_disappears_whenDeleteCoroutineWinsRace`
(forces the losing interleaving with a 300ms delay inside the remove coroutine —
in the field this delay comes from the scheduler at random). Control:
`movedImage_survives_whenOrderedCorrectly_control`.

Likely fixes (not applied): persist the move as a single ordered/transactional
operation (delete then create, awaited), or use Room `@Update` instead of
delete+create since the id is unchanged, or give the displaced image a new id.

### Forcing it without a test (manual)

Add `kotlinx.coroutines.delay(300)` inside `removeImagesFromDb`'s `dataScope.launch`
([PageDataManager.kt:656](app/src/main/java/com/ethran/notable/data/PageDataManager.kt#L656))
before `deleteAll`. Then move an image, drop it, switch page and back (forces a DB
reload): the image is gone every time.

---

## Other observations (not separately tested)

- `selectImagesAndStrokes` mutates the *shared* `selectionRect` Rect in place in
  `applySelectionDisplace` ([SelectionState.kt:214-215](app/src/main/java/com/ethran/notable/editor/state/SelectionState.kt#L214)):
  `finalZone = selectionRect!!; finalZone.offset(...)` aliases the state's Rect, so
  the stored `selectionRect` is moved as a side effect of committing. Re-committing
  (e.g. double click / duplicate then move) accumulates the offset.
- `resizeImages` recomputes `selectionRect` via `toScreenCoordinates` (screen space)
  while `selectImagesAndStrokes` stores it in **page** space, then `SelectorBitmap`
  calls `toScreenCoordinates` on it again — a double conversion after a resize.

---

## Manual reproduction (where a test needs the live canvas surface)

The zoom-dependent mis-placement (BUG 2) and the resize preview/origin mismatch
(BUG 3.3) are easiest to see by hand because they require a rendered `PageView`
SurfaceView and a real zoom level:

1. Open a notebook page. Pinch-zoom to ~2x.
2. Toolbar → import image. **Observed:** the image is not centered in the visible
   area; with a large image its top-left is off-screen and cannot be dragged.
3. Reset zoom to 1x, import a wide-but-short image and drag it to the very bottom
   of a long page. **Observed (BUG 1):** you cannot scroll far enough to see it —
   the page did not grow to `y + height`.
4. Select a single image, press **+** several times then **−** the same number of
   times. **Observed (BUG 3):** the image is not back to its original size, and its
   center has crept from the start position.

### Forcing the race / ordering deterministically

`ImageHandler.observeImageUri()` collects on a background coroutine while
`applySelectionDisplace` / `addImage` run on commit. To make any ordering issue
reproduce consistently, insert a delay in
[ImageHandler.handleImage](app/src/main/java/com/ethran/notable/editor/utils/ImageHandler.kt#L43)
right before `selectImage(...)` (e.g. `kotlinx.coroutines.delay(300)` — requires
making `handleImage` a `suspend` fun / launching in the existing scope), then trigger
a second import or a move while the first is mid-flight.
