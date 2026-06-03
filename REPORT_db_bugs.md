# Database operations — bug report

Scope: saving, reading, modifying and deleting persistent data (Room entities,
DAOs, repositories, TypeConverters, the SB1 stroke binary format, and migrations).
Reference docs: [docs/database-structure.md](docs/database-structure.md).

No production behaviour was changed. Each bug below has a reproducing test:
- host-JVM: [StrokeEncodingMathTest.kt](app/src/test/java/com/ethran/notable/db/StrokeEncodingMathTest.kt)
- instrumented: [StrokeEncodingBugAndroidTest.kt](app/src/androidTest/java/com/ethran/notable/db/StrokeEncodingBugAndroidTest.kt),
  [StrokeDaoBugAndroidTest.kt](app/src/androidTest/java/com/ethran/notable/db/StrokeDaoBugAndroidTest.kt)

One test-support change was made (allowed by the task): added a null-safe
`getByIdOrNull` to `ImageDao` in an earlier task; no DB logic changed here.

---

## BUG A — `dt` does not round-trip; documented null sentinel not implemented

Doc [§3.4](docs/database-structure.md) and the `decodeStrokePoints` docstring both
state a decoded `dt` of `0xFFFF` (65535) means **null**. Neither side honours this:

- **Encoder** clamps `dt` to `[0, 65534]`
  ([StrokePointConverter.kt:231](app/src/main/java/com/ethran/notable/data/db/StrokePointConverter.kt#L231)):
  `val v = p.dt!!.toInt().coerceIn(0, DT_MAX_VALUE_INT)`. A real `dt = 65535u`
  (`UShort.MAX_VALUE`, a legal value of the field) is **silently changed to 65534**
  on save.
- **Decoder** never maps `0xFFFF` back to null
  ([StrokePointConverter.kt:370](app/src/main/java/com/ethran/notable/data/db/StrokePointConverter.kt#L370)):
  `dt = dts?.getOrNull(i)?.toUShort()` — unconditional.

Net: `dt` is lossy at the top of its range, and the reserved sentinel can neither
be produced nor honoured as documented.

Tests: `StrokeEncodingMathTest.dt_maxValueIsSilentlyClampedOnEncode`,
`dt_decoderDoesNotRemapSentinelToNull`;
`StrokeEncodingBugAndroidTest.dt_maxValueDoesNotRoundTrip`,
`dt_sentinelIsNotMappedToNull_contradictingDoc`.

---

## BUG B — pressure wraps/loses precision (Float → Int → Short)

`StrokePoint.pressure` is a `Float` with no clamping. The encoder stores it as a
signed 16-bit short
([StrokePointConverter.kt:221](app/src/main/java/com/ethran/notable/data/db/StrokePointConverter.kt#L221)):
`bodyBuffer.putShort(p.pressure!!.toInt().toShort())`, and the decoder does
`short.toFloat()` ([:367](app/src/main/java/com/ethran/notable/data/db/StrokePointConverter.kt#L367)).

- Any pressure above `Short.MAX_VALUE` (32767) **wraps to a negative value** on
  round-trip. Some digitizers report raw pressure far above 4096, and
  `maxPressure` is stored per-stroke precisely because the scale is device
  dependent — so this is reachable.
- Fractional pressure is truncated (`toInt`).

Tests: `StrokeEncodingMathTest.pressure_wrapsAboveShortRange`,
`pressure_truncatesFraction`;
`StrokeEncodingBugAndroidTest.pressure_aboveShortRange_wrapsNegativeOnRoundTrip`.

---

## BUG C — coordinate overflow; page-size guard only checks the first point

The polyline coordinate codec multiplies by `10^precision` and casts to `Int`
([EncodePolyline.kt:22](app/src/main/java/com/ethran/notable/data/db/EncodePolyline.kt#L22)):
`val iValue = (value.toDouble() * 10.0.pow(precision)).toInt()`.

With `precision = 2`, any coordinate above ~21.47M overflows `Int` and wraps,
silently corrupting `x`/`y`. The only guard in `encodeStrokePoints`
([StrokePointConverter.kt:188](app/src/main/java/com/ethran/notable/data/db/StrokePointConverter.kt#L188))
checks **`points.first().y`** against `MAX_PAGE_HEIGHT` (10_000_000). It does not
check:

- any point other than the first,
- the `x` coordinate at all,
- and `MAX_PAGE_HEIGHT` itself (10M) is already only ~half of the overflow
  threshold, but a later point can be far larger.

So a stroke whose first point is small but a later point (or any large x) is huge
passes the guard and is written corrupted, with no error surfaced to the user.

Tests: `StrokeEncodingMathTest.polyline_overflowsForLargeCoordinate`,
`polyline_overflowExactWraparound`;
`StrokeEncodingBugAndroidTest.encode_corruptsLaterLargeCoordinate_passingFirstPointGuard`.

---

## BUG D — empty-point stroke cannot be saved (write/read asymmetry)

`Converters.toStrokePoints` returns `emptyList()` for empty/blank bytes
([Db.kt:48-51](app/src/main/java/com/ethran/notable/data/db/Db.kt#L48)), but the
**write** path rejects empty lists:
`Converters.fromStrokePoints` → `computeStrokeMask` → `require(points.isNotEmpty())`
([StrokePointConverter.kt:34](app/src/main/java/com/ethran/notable/data/db/StrokePointConverter.kt#L34))
and `encodeStrokePoints` → `require(count > 0)`.

`Stroke.points` is a normal `List` and `Stroke(points = emptyList())` is
constructible, but **inserting it throws** at converter time. Read and write are
asymmetric: the DB can yield a zero-point stroke that it can never store back.

Test: `StrokeDaoBugAndroidTest.insertingStrokeWithEmptyPoints_crashes`.

---

## BUG E — `getById` has a non-null return but the row may not exist

`StrokeDao.getById` ([Stroke.kt:75-76](app/src/main/java/com/ethran/notable/data/db/Stroke.kt#L75))
and `ImageDao.getById` ([Image.kt](app/src/main/java/com/ethran/notable/data/db/Image.kt))
declare a **non-null** return (`suspend fun getById(id): Stroke` / `: Image`) over a
`WHERE id = :id` query that can match nothing. On a missing id, Room's generated
code for a non-null type throws rather than returning null, so callers that expect
a graceful miss get a crash. (`PageDao.getById`/`NotebookDao.getById` correctly
return nullable types — the stroke/image DAOs are inconsistent with them.)

Test: `StrokeDaoBugAndroidTest.getById_missingStroke_throwsInsteadOfReturningNull`.

---

## Other observations (analysed, not separately tested)

- **Migration 32→33 declared twice.** `Db.kt` lists both an `AutoMigration(32, 33)`
  ([Db.kt:71](app/src/main/java/com/ethran/notable/data/db/Db.kt#L71)) and a manual
  `MIGRATION_32_33` ([Db.kt:135](app/src/main/java/com/ethran/notable/data/db/Db.kt#L135),
  defined in [Migrations.kt:62](app/src/main/java/com/ethran/notable/data/db/Migrations.kt#L62)).
  The manual one renames `stroke`→`stroke_old` and rebuilds the table; an
  auto-migration for the same version pair is at best redundant and at worst a
  conflicting path. Worth confirming Room picks the manual migration. (Not unit
  testable without a full on-disk schema fixture.)
- **Inconsistent `updatedAt` bumping.** `BookRepository.update` bumps `updatedAt`
  ([Notebook.kt:107](app/src/main/java/com/ethran/notable/data/db/Notebook.kt#L107))
  but `removePage` calls `notebookDao.update` directly without bumping
  ([Notebook.kt:151](app/src/main/java/com/ethran/notable/data/db/Notebook.kt#L151)),
  and `setPageIds`/`setOpenPageId` are raw `UPDATE`s that never touch `updatedAt`.
  WebDAV sync uses timestamps to decide winners, so page add/remove/reorder can be
  lost on sync because the notebook's `updatedAt` did not change.
- **`StrokeRepository.create(List)` is not chunked** while `deleteAll` is
  ([Stroke.kt:100-104](app/src/main/java/com/ethran/notable/data/db/Stroke.kt#L100)).
  Room emits per-row inserts for `@Insert(List)` so the 999-variable limit does not
  bite here, but the asymmetry is a latent foot-gun if the insert is ever rewritten
  as a multi-row statement.

---

## How to run

- Host JVM: `./gradlew :app:testDebugUnitTest --tests "com.ethran.notable.db.StrokeEncodingMathTest"`
- Instrumented (needs a device/emulator; compile-only in this environment):
  `./gradlew :app:connectedDebugAndroidTest` (or compile with
  `:app:compileDebugAndroidTestKotlin`).
