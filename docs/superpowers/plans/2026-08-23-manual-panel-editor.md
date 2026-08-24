# Manual Panel Editor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A dedicated screen for hand-correcting a page's panel-by-panel boundaries (resize, move, add, remove, merge, split), persisted separately from the detection cache so corrections survive `DETECTOR_VERSION` bumps and are structured for later use as model fine-tuning data.

**Architecture:** A new `panel_manual_override` DB table + repository (mirrors the existing `panel_full_page_override` pattern, plus an image-snapshot file copy). `PanelDetector` checks it as the highest-priority override, ahead of the full-page override, ahead of normal detection. A standalone `PanelEditorActivity` (own Compose UI, own from-scratch touch handling — deliberately isolated from the live panel-by-panel viewer's gesture stack, per CLAUDE.md's documented history of gesture-layering failures there) shows the page with editable boxes; saving returns an activity result that triggers the same `RefreshPanelDetection` mechanism built for the full-page-override toggle.

**Tech Stack:** Kotlin, Jetpack Compose (Canvas + `pointerInput`/`detectDragGestures`), SQLDelight, Coroutines, JUnit 5.

**Spec:** `docs/superpowers/specs/2026-08-23-manual-panel-editor-design.md`

## Global Constraints

- The `panel_manual_override` table has NO `detector_version` or `image_hash` column — a version bump or re-detection must never invalidate it (spec: Data model).
- The editor's own touch handling must have zero code-level relationship to `Pager`, `SubsamplingScaleImageView`, `PagerPageHolder`, or any live-viewer gesture code (spec: Architecture — why an isolated screen).
- Override priority in `PanelDetector.detect()`: manual override > full-page override > normal detection (spec: Detector integration).
- Saving a manual override clears any full-page override on the same page (spec: Goals).
- No bubble/text-box editing, no manual reading-order editing, no training-export script — out of scope for this plan (spec: Non-goals).

---

## Task 1: Database schema for manual overrides

**Files:**
- Create: `data/src/main/sqldelight/tachiyomi/data/panel_manual_override.sq`
- Create: `data/src/main/sqldelight/tachiyomi/migrations/17.sqm`

**Interfaces:**
- Produces: SQLDelight-generated `Database.panel_manual_overrideQueries` with `get(chapterId, pageIndex)`, `upsert(chapterId, pageIndex, panelsJson, imageSnapshotPath, correctedAt)`, `delete(chapterId, pageIndex)`.

- [ ] **Step 1: Write the table + queries**

`data/src/main/sqldelight/tachiyomi/data/panel_manual_override.sq`:

```sql
CREATE TABLE panel_manual_override(
    chapter_id INTEGER NOT NULL,
    page_index INTEGER NOT NULL,
    panels_json TEXT NOT NULL,
    image_snapshot_path TEXT NOT NULL,
    corrected_at INTEGER NOT NULL,
    PRIMARY KEY (chapter_id, page_index),
    FOREIGN KEY(chapter_id) REFERENCES chapters (_id)
    ON DELETE CASCADE
);

get:
SELECT panels_json, image_snapshot_path
FROM panel_manual_override
WHERE chapter_id = :chapterId
AND page_index = :pageIndex;

upsert:
INSERT INTO panel_manual_override(chapter_id, page_index, panels_json, image_snapshot_path, corrected_at)
VALUES (:chapterId, :pageIndex, :panelsJson, :imageSnapshotPath, :correctedAt)
ON CONFLICT(chapter_id, page_index)
DO UPDATE
SET
    panels_json = :panelsJson,
    image_snapshot_path = :imageSnapshotPath,
    corrected_at = :correctedAt;

delete:
DELETE FROM panel_manual_override
WHERE chapter_id = :chapterId
AND page_index = :pageIndex;
```

- [ ] **Step 2: Mirror it as a migration**

`data/src/main/sqldelight/tachiyomi/migrations/17.sqm` (same `CREATE TABLE` as above — this is how an *existing* installed database picks up the new table; the `.sq` file above is what a fresh install uses):

```sql
CREATE TABLE panel_manual_override(
    chapter_id INTEGER NOT NULL,
    page_index INTEGER NOT NULL,
    panels_json TEXT NOT NULL,
    image_snapshot_path TEXT NOT NULL,
    corrected_at INTEGER NOT NULL,
    PRIMARY KEY (chapter_id, page_index),
    FOREIGN KEY(chapter_id) REFERENCES chapters (_id)
    ON DELETE CASCADE
);
```

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :data:compileDebugKotlin` (this triggers SQLDelight codegen from the `.sq` file; a syntax error in the SQL fails this step, not a separate SQLDelight-specific task).
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add data/src/main/sqldelight/tachiyomi/data/panel_manual_override.sq data/src/main/sqldelight/tachiyomi/migrations/17.sqm
git commit -m "feat(reader): add panel_manual_override table for hand-corrected panels"
```

---

## Task 2: PanelManualOverrideRepository

**Files:**
- Create: `app/src/main/java/eu/kanade/tachiyomi/data/reader/PanelManualOverrideRepository.kt`
- Modify: `app/src/main/java/mihon/app/di/AppGraph.kt`

**Interfaces:**
- Consumes: `tachiyomi.data.Database` (existing, same as `PanelCacheRepository`/`PanelFullPageOverrideRepository`), `android.content.Context` (for the snapshot directory), `kotlinx.serialization.json.Json` (existing, same as `PanelCacheRepository`), `eu.kanade.tachiyomi.ui.reader.viewer.panel.PanelPageData` (existing serializable type already used by `PanelCacheRepository`).
- Produces:
  - `suspend fun get(chapterId: Long, pageIndex: Int): List<PanelRect>?`
  - `suspend fun save(chapterId: Long, pageIndex: Int, panels: List<PanelRect>, sourceImage: File): Unit`
  - `suspend fun remove(chapterId: Long, pageIndex: Int): Unit`

- [ ] **Step 1: Write the repository**

`app/src/main/java/eu/kanade/tachiyomi/data/reader/PanelManualOverrideRepository.kt`:

```kotlin
package eu.kanade.tachiyomi.data.reader

import android.content.Context
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.ui.reader.viewer.panel.PanelPageData
import eu.kanade.tachiyomi.ui.reader.viewer.panel.PanelRect
import kotlinx.serialization.json.Json
import tachiyomi.data.Database
import java.io.File

/**
 * User hand-corrected panel boundaries for a specific page — a stronger, more specific override
 * than [PanelFullPageOverrideRepository]'s "just show the whole page" toggle. Backed by its own
 * table with no detector_version/image_hash column, so a DETECTOR_VERSION bump or re-detection
 * never invalidates it (see PanelDetector's override-priority check, and CLAUDE.md's
 * DETECTOR_VERSION caching notes for why that distinction matters). Also copies a snapshot of the
 * source page image alongside the correction, so a later training-data export isn't dependent on
 * the source chapter still being downloaded.
 */
@Inject
@SingleIn(AppScope::class)
class PanelManualOverrideRepository(
    private val context: Context,
    private val database: Database,
    private val json: Json,
) {

    suspend fun get(chapterId: Long, pageIndex: Int): List<PanelRect>? {
        val row = database.panel_manual_overrideQueries
            .get(chapterId, pageIndex.toLong())
            .awaitAsOneOrNull()
            ?: return null
        return runCatching { json.decodeFromString<PanelPageData>(row.panels_json) }
            .getOrNull()
            ?.panels
            ?.map { it.bounds }
    }

    suspend fun save(chapterId: Long, pageIndex: Int, panels: List<PanelRect>, sourceImage: File) {
        val snapshotFile = snapshotFile(chapterId, pageIndex)
        snapshotFile.parentFile?.mkdirs()
        sourceImage.copyTo(snapshotFile, overwrite = true)

        val data = PanelPageData(panels.map { eu.kanade.tachiyomi.ui.reader.viewer.panel.Panel(it) })
        database.panel_manual_overrideQueries.upsert(
            chapterId = chapterId,
            pageIndex = pageIndex.toLong(),
            panelsJson = json.encodeToString(PanelPageData.serializer(), data),
            imageSnapshotPath = snapshotFile.absolutePath,
            correctedAt = System.currentTimeMillis(),
        )
    }

    suspend fun remove(chapterId: Long, pageIndex: Int) {
        snapshotFile(chapterId, pageIndex).delete()
        database.panel_manual_overrideQueries.delete(chapterId, pageIndex.toLong())
    }

    private fun snapshotFile(chapterId: Long, pageIndex: Int): File =
        File(context.filesDir, "panel_training_snapshots/${chapterId}_$pageIndex.jpg")
}
```

- [ ] **Step 2: Confirmed shape reference**

`Panel.kt` (`eu.kanade.tachiyomi.ui.reader.viewer.panel`) defines `data class Panel(val bounds: PanelRect, val subStops: List<PanelRect> = emptyList())` and `data class PanelPageData(val panels: List<Panel>)` — the panel's rect is the `bounds` property, not `rect` (Step 1's code above already uses `it.bounds`/positional-`bounds` correctly; this step is just the confirmation record, no code change needed).

- [ ] **Step 3: Register in the DI graph**

In `app/src/main/java/mihon/app/di/AppGraph.kt`, add the import and the interface property next to the existing `panelFullPageOverrideRepository` line:

```kotlin
import eu.kanade.tachiyomi.data.reader.PanelManualOverrideRepository
```

```kotlin
    val panelFullPageOverrideRepository: PanelFullPageOverrideRepository
    val panelManualOverrideRepository: PanelManualOverrideRepository
```

- [ ] **Step 4: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/eu/kanade/tachiyomi/data/reader/PanelManualOverrideRepository.kt app/src/main/java/mihon/app/di/AppGraph.kt
git commit -m "feat(reader): add PanelManualOverrideRepository with image snapshotting"
```

---

## Task 3: PanelDetector override-priority integration

**Files:**
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/panel/PanelDetector.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/PanelByPanelViewer.kt`
- Test: `app/src/test/java/eu/kanade/tachiyomi/ui/reader/viewer/panel/PanelDetectorTest.kt` (create if it doesn't exist)

**Interfaces:**
- Consumes: `PanelManualOverrideRepository.get(chapterId, pageIndex): List<PanelRect>?` (Task 2), `PanelOrdering.order(panels, rightToLeft, isSpread): List<PanelRect>` (existing).
- Produces: `PanelDetector`'s constructor now also takes `panelManualOverrideRepository: PanelManualOverrideRepository`.

- [ ] **Step 1: Check whether `PanelDetectorTest.kt` already exists and how `PanelDetector` is currently unit-tested**

Run: `find app/src/test -iname "PanelDetectorTest.kt"` (or check via Glob). If it exists, read it fully before writing new tests — match its existing fake/mock style for `PanelCacheRepository` and reuse the same style for `PanelFullPageOverrideRepository`/`PanelManualOverrideRepository` fakes. If it doesn't exist, `PanelDetector.detect()` talks directly to Android APIs (`BitmapFactory`) for the non-override path, so only the override short-circuit paths (manual/full-page, both of which return before touching bitmaps) are practically unit-testable without an instrumented test — write the test to cover exactly those two short-circuits and the priority order between them, not the full detection path.

- [ ] **Step 2: Write the failing test**

`app/src/test/java/eu/kanade/tachiyomi/ui/reader/viewer/panel/PanelDetectorTest.kt`:

```kotlin
package eu.kanade.tachiyomi.ui.reader.viewer.panel

import eu.kanade.tachiyomi.data.reader.PanelCacheRepository
import eu.kanade.tachiyomi.data.reader.PanelFullPageOverrideRepository
import eu.kanade.tachiyomi.data.reader.PanelManualOverrideRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PanelDetectorTest {

    @Test
    fun manualOverrideWinsOverFullPageOverride() = runTest {
        val chapterId = 1L
        val pageIndex = 0
        val manualPanels = listOf(PanelRect(0.1f, 0.1f, 0.4f, 0.4f), PanelRect(0.6f, 0.1f, 0.9f, 0.4f))

        val panelCacheRepository = mockk<PanelCacheRepository>()
        val fullPageOverrideRepository = mockk<PanelFullPageOverrideRepository> {
            coEvery { isOverridden(chapterId, pageIndex) } returns true
        }
        val manualOverrideRepository = mockk<PanelManualOverrideRepository> {
            coEvery { get(chapterId, pageIndex) } returns manualPanels
        }

        val detector = PanelDetector(
            context = mockk(relaxed = true),
            panelCacheRepository = panelCacheRepository,
            panelFullPageOverrideRepository = fullPageOverrideRepository,
            panelManualOverrideRepository = manualOverrideRepository,
        )

        val page = fakeReaderPage(chapterId, pageIndex)
        val result = detector.detect(page, Buffer(), PanelDirection.LTR)

        assertEquals(manualPanels, result.map { it.bounds })
    }

    @Test
    fun fullPageOverrideAppliesWhenNoManualOverride() = runTest {
        val chapterId = 1L
        val pageIndex = 0

        val panelCacheRepository = mockk<PanelCacheRepository>()
        val fullPageOverrideRepository = mockk<PanelFullPageOverrideRepository> {
            coEvery { isOverridden(chapterId, pageIndex) } returns true
        }
        val manualOverrideRepository = mockk<PanelManualOverrideRepository> {
            coEvery { get(chapterId, pageIndex) } returns null
        }

        val detector = PanelDetector(
            context = mockk(relaxed = true),
            panelCacheRepository = panelCacheRepository,
            panelFullPageOverrideRepository = fullPageOverrideRepository,
            panelManualOverrideRepository = manualOverrideRepository,
        )

        val page = fakeReaderPage(chapterId, pageIndex)
        val result = detector.detect(page, Buffer(), PanelDirection.LTR)

        assertEquals(listOf(PanelRect.FULL_PAGE), result.map { it.bounds })
    }

    private fun fakeReaderPage(chapterId: Long, pageIndex: Int): eu.kanade.tachiyomi.ui.reader.model.ReaderPage {
        // Chapter.create() (tachiyomi.domain.chapter.model.Chapter's companion factory) fills every
        // field with a valid default — only chapterId needs overriding for what PanelDetector.detect()
        // actually reads (page.chapter.chapter.id, page.index).
        val chapter = tachiyomi.domain.chapter.model.Chapter.create().copy(id = chapterId)
        val readerChapter = eu.kanade.tachiyomi.ui.reader.model.ReaderChapter(chapter)
        return eu.kanade.tachiyomi.ui.reader.model.ReaderPage(index = pageIndex).apply {
            chapter = readerChapter
        }
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "eu.kanade.tachiyomi.ui.reader.viewer.panel.PanelDetectorTest"`
Expected: FAIL — `PanelDetector`'s constructor doesn't yet accept `panelManualOverrideRepository`, or the fake page construction needs fixing first (fix that first if it's a compile error, then confirm a real behavioral failure).

- [ ] **Step 4: Add the manual-override repository parameter and priority check**

In `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/panel/PanelDetector.kt`, add the import and constructor parameter:

```kotlin
import eu.kanade.tachiyomi.data.reader.PanelManualOverrideRepository
```

```kotlin
class PanelDetector(
    context: Context,
    private val panelCacheRepository: PanelCacheRepository,
    private val panelFullPageOverrideRepository: PanelFullPageOverrideRepository,
    private val panelManualOverrideRepository: PanelManualOverrideRepository,
) {
```

Then, in `detect()`, add the manual-override check **before** the existing full-page-override check (highest priority — see Global Constraints):

```kotlin
    suspend fun detect(page: ReaderPage, imageBytes: Buffer, direction: PanelDirection): List<Panel> {
        val chapterId = page.chapter.chapter.id ?: return listOf(Panel(PanelRect.FULL_PAGE))

        // A hand-corrected panel list always wins — it's a stronger, more specific override than
        // the full-page toggle below, and (like that toggle) must never be invalidated by a
        // DETECTOR_VERSION bump since it's a user annotation, not a cached detection outcome. Only
        // reading order still runs (PanelOrdering.order), so edited panels don't need to be added
        // in reading-order sequence by hand.
        withContext(Dispatchers.IO) { panelManualOverrideRepository.get(chapterId, page.index) }
            ?.let { manualPanels ->
                // isSpread deliberately left at its default (false): manually-placed panels are
                // never divided/merged, only ordered, so PanelPipeline's own spread-detection
                // (which only matters for the divide step) doesn't apply here.
                return PanelOrdering.order(manualPanels, direction == PanelDirection.RTL)
                    .map { Panel(it) }
            }

        // User-marked "always show whole page" pages (e.g. title/cast-intro art the detector
        // can't meaningfully decompose) skip detection entirely — this must win over even a
        // cached real detection, and isn't invalidated by a DETECTOR_VERSION bump since it's an
        // annotation, not a detection outcome.
        if (withContext(Dispatchers.IO) { panelFullPageOverrideRepository.isOverridden(chapterId, page.index) }) {
            return listOf(Panel(PanelRect.FULL_PAGE))
        }

        val hash = imageBytes.contentHash()
        // ... rest of the existing method body is unchanged from here
```

Check `PanelOrdering.order`'s actual parameter names/order in `PanelOrdering.kt` before pasting the call above — match its real signature exactly (this plan's snippet is based on `PanelPipeline.kt`'s existing call to it, but confirm before compiling).

- [ ] **Step 5: Wire the new repository into `PanelByPanelViewer`'s `PanelDetector` construction**

In `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/PanelByPanelViewer.kt`:

```kotlin
    val panelDetector = PanelDetector(
        context = activity.applicationContext,
        panelCacheRepository = graph.panelCacheRepository,
        panelFullPageOverrideRepository = graph.panelFullPageOverrideRepository,
        panelManualOverrideRepository = graph.panelManualOverrideRepository,
    )
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "eu.kanade.tachiyomi.ui.reader.viewer.panel.PanelDetectorTest"`
Expected: PASS.

- [ ] **Step 7: Run the full panel test suite to check for regressions**

Run: `./gradlew :app:testDebugUnitTest --tests "eu.kanade.tachiyomi.ui.reader.viewer.panel.*"`
Expected: All pass (no regressions in `PanelPipelineTest`/`PanelPlannerTest`/`PanelOrderingTest`/etc. — this task didn't touch their code, but confirms the build overall is sound).

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/panel/PanelDetector.kt app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/PanelByPanelViewer.kt app/src/test/java/eu/kanade/tachiyomi/ui/reader/viewer/panel/PanelDetectorTest.kt
git commit -m "feat(reader): manual panel override takes priority over detection"
```

---

## Task 4: PanelEditOps — pure box-manipulation logic

**Files:**
- Create: `app/src/main/java/eu/kanade/tachiyomi/ui/reader/panel/edit/PanelEditOps.kt`
- Test: `app/src/test/java/eu/kanade/tachiyomi/ui/reader/panel/edit/PanelEditOpsTest.kt`

**Interfaces:**
- Consumes: `PanelRect` (existing, `eu.kanade.tachiyomi.ui.reader.viewer.panel.PanelRect`).
- Produces:
  - `enum class Handle { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, LEFT, RIGHT, TOP, BOTTOM }`
  - `fun PanelEditOps.resize(rect: PanelRect, handle: Handle, dx: Float, dy: Float): PanelRect`
  - `fun PanelEditOps.move(rect: PanelRect, dx: Float, dy: Float): PanelRect`
  - `fun PanelEditOps.merge(a: PanelRect, b: PanelRect): PanelRect`
  - `enum class SplitOrientation { HORIZONTAL, VERTICAL }`
  - `fun PanelEditOps.split(rect: PanelRect, orientation: SplitOrientation, at: Float): Pair<PanelRect, PanelRect>`
  - `fun PanelEditOps.defaultSplitOrientation(rect: PanelRect): SplitOrientation`

This is the task other UI tasks (5-7) call into — all functions are pure (no Android/Compose types), all coordinates are normalized `[0,1]` same as everywhere else in the panel pipeline.

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/eu/kanade/tachiyomi/ui/reader/panel/edit/PanelEditOpsTest.kt`:

```kotlin
package eu.kanade.tachiyomi.ui.reader.panel.edit

import eu.kanade.tachiyomi.ui.reader.viewer.panel.PanelRect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PanelEditOpsTest {

    @Test
    fun resizeFromBottomRightGrowsRightAndBottomOnly() {
        val rect = PanelRect(0.1f, 0.1f, 0.5f, 0.5f)
        val result = PanelEditOps.resize(rect, Handle.BOTTOM_RIGHT, dx = 0.1f, dy = 0.05f)
        assertEquals(PanelRect(0.1f, 0.1f, 0.6f, 0.55f), result)
    }

    @Test
    fun resizeFromTopLeftShrinksFromTheOppositeCornerAnchor() {
        val rect = PanelRect(0.1f, 0.1f, 0.5f, 0.5f)
        val result = PanelEditOps.resize(rect, Handle.TOP_LEFT, dx = 0.05f, dy = 0.05f)
        assertEquals(PanelRect(0.15f, 0.15f, 0.5f, 0.5f), result)
    }

    @Test
    fun resizeFromLeftEdgeOnlyChangesLeft() {
        val rect = PanelRect(0.1f, 0.1f, 0.5f, 0.5f)
        val result = PanelEditOps.resize(rect, Handle.LEFT, dx = -0.05f, dy = 0.2f)
        assertEquals(PanelRect(0.05f, 0.1f, 0.5f, 0.5f), result)
    }

    @Test
    fun resizeClampsToZeroToOneBounds() {
        val rect = PanelRect(0.02f, 0.02f, 0.5f, 0.5f)
        val result = PanelEditOps.resize(rect, Handle.TOP_LEFT, dx = -0.5f, dy = -0.5f)
        assertEquals(0f, result.left)
        assertEquals(0f, result.top)
    }

    @Test
    fun moveShiftsAllFourEdgesByTheSameAmount() {
        val rect = PanelRect(0.1f, 0.1f, 0.5f, 0.5f)
        val result = PanelEditOps.move(rect, dx = 0.1f, dy = -0.05f)
        assertEquals(PanelRect(0.2f, 0.05f, 0.6f, 0.45f), result)
    }

    @Test
    fun moveClampsSoThePanelNeverLeavesThePage() {
        val rect = PanelRect(0.0f, 0.0f, 0.2f, 0.2f)
        val result = PanelEditOps.move(rect, dx = -0.5f, dy = -0.5f)
        assertEquals(PanelRect(0.0f, 0.0f, 0.2f, 0.2f), result, "clamped back to the left/top edge, width/height preserved")
    }

    @Test
    fun mergeProducesTheUnionOfBothRects() {
        val a = PanelRect(0.1f, 0.1f, 0.3f, 0.3f)
        val b = PanelRect(0.25f, 0.2f, 0.5f, 0.4f)
        val result = PanelEditOps.merge(a, b)
        assertEquals(PanelRect(0.1f, 0.1f, 0.5f, 0.4f), result)
    }

    @Test
    fun splitVerticalDividesLeftAndRight() {
        val rect = PanelRect(0.0f, 0.0f, 1.0f, 0.5f)
        val (left, right) = PanelEditOps.split(rect, SplitOrientation.VERTICAL, at = 0.4f)
        assertEquals(PanelRect(0.0f, 0.0f, 0.4f, 0.5f), left)
        assertEquals(PanelRect(0.4f, 0.0f, 1.0f, 0.5f), right)
    }

    @Test
    fun splitHorizontalDividesTopAndBottom() {
        val rect = PanelRect(0.0f, 0.0f, 1.0f, 0.5f)
        val (top, bottom) = PanelEditOps.split(rect, SplitOrientation.HORIZONTAL, at = 0.2f)
        assertEquals(PanelRect(0.0f, 0.0f, 1.0f, 0.2f), top)
        assertEquals(PanelRect(0.0f, 0.2f, 1.0f, 0.5f), bottom)
    }

    @Test
    fun defaultSplitOrientationIsVerticalForAWiderThanTallPanel() {
        val wide = PanelRect(0.0f, 0.0f, 1.0f, 0.3f)
        assertEquals(SplitOrientation.VERTICAL, PanelEditOps.defaultSplitOrientation(wide))
    }

    @Test
    fun defaultSplitOrientationIsHorizontalForATallerThanWidePanel() {
        val tall = PanelRect(0.0f, 0.0f, 0.3f, 1.0f)
        assertEquals(SplitOrientation.HORIZONTAL, PanelEditOps.defaultSplitOrientation(tall))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "eu.kanade.tachiyomi.ui.reader.panel.edit.PanelEditOpsTest"`
Expected: FAIL with "unresolved reference: PanelEditOps" (the file doesn't exist yet).

- [ ] **Step 3: Write the implementation**

`app/src/main/java/eu/kanade/tachiyomi/ui/reader/panel/edit/PanelEditOps.kt`:

```kotlin
package eu.kanade.tachiyomi.ui.reader.panel.edit

import eu.kanade.tachiyomi.ui.reader.viewer.panel.PanelRect
import kotlin.math.max
import kotlin.math.min

enum class Handle { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, LEFT, RIGHT, TOP, BOTTOM }

enum class SplitOrientation { HORIZONTAL, VERTICAL }

/**
 * Pure box-manipulation logic for the manual panel editor — deliberately free of any
 * Android/Compose dependency so it's testable without an instrumented test, and so the editor
 * screen's touch-handling code (which does need those) stays a thin layer translating drag deltas
 * into calls here, rather than mixing gesture math with panel-geometry math.
 */
object PanelEditOps {

    /**
     * Drags [handle] by ([dx], [dy]) in normalized page coordinates. A corner handle moves both
     * of its edges; a side handle (LEFT/RIGHT/TOP/BOTTOM) moves only that one edge. Every edge is
     * clamped to [0,1] and to not cross its opposite edge (min panel size of a tiny epsilon,
     * rather than letting left cross right and produce an inverted/negative-size rect).
     */
    fun resize(rect: PanelRect, handle: Handle, dx: Float, dy: Float): PanelRect {
        var left = rect.left
        var top = rect.top
        var right = rect.right
        var bottom = rect.bottom
        val minSize = 0.01f

        when (handle) {
            Handle.TOP_LEFT -> {
                left = (left + dx).coerceIn(0f, right - minSize)
                top = (top + dy).coerceIn(0f, bottom - minSize)
            }
            Handle.TOP_RIGHT -> {
                right = (right + dx).coerceIn(left + minSize, 1f)
                top = (top + dy).coerceIn(0f, bottom - minSize)
            }
            Handle.BOTTOM_LEFT -> {
                left = (left + dx).coerceIn(0f, right - minSize)
                bottom = (bottom + dy).coerceIn(top + minSize, 1f)
            }
            Handle.BOTTOM_RIGHT -> {
                right = (right + dx).coerceIn(left + minSize, 1f)
                bottom = (bottom + dy).coerceIn(top + minSize, 1f)
            }
            Handle.LEFT -> left = (left + dx).coerceIn(0f, right - minSize)
            Handle.RIGHT -> right = (right + dx).coerceIn(left + minSize, 1f)
            Handle.TOP -> top = (top + dy).coerceIn(0f, bottom - minSize)
            Handle.BOTTOM -> bottom = (bottom + dy).coerceIn(top + minSize, 1f)
        }
        return PanelRect(left, top, right, bottom)
    }

    /** Shifts all four edges by the same amount, clamped so the panel's own size is preserved even at a page edge. */
    fun move(rect: PanelRect, dx: Float, dy: Float): PanelRect {
        val width = rect.width
        val height = rect.height
        val left = (rect.left + dx).coerceIn(0f, 1f - width)
        val top = (rect.top + dy).coerceIn(0f, 1f - height)
        return PanelRect(left, top, left + width, top + height)
    }

    /** The bounding box containing both panels — used for the editor's "Merge" action. */
    fun merge(a: PanelRect, b: PanelRect): PanelRect =
        PanelRect(min(a.left, b.left), min(a.top, b.top), max(a.right, b.right), max(a.bottom, b.bottom))

    /**
     * Divides [rect] at normalized position [at] (an absolute page coordinate, not a fraction of
     * the panel) along [orientation]. [at] is clamped to stay strictly inside the panel so neither
     * resulting piece has zero size.
     */
    fun split(rect: PanelRect, orientation: SplitOrientation, at: Float): Pair<PanelRect, PanelRect> {
        val minSize = 0.01f
        return when (orientation) {
            SplitOrientation.VERTICAL -> {
                val cut = at.coerceIn(rect.left + minSize, rect.right - minSize)
                PanelRect(rect.left, rect.top, cut, rect.bottom) to PanelRect(cut, rect.top, rect.right, rect.bottom)
            }
            SplitOrientation.HORIZONTAL -> {
                val cut = at.coerceIn(rect.top + minSize, rect.bottom - minSize)
                PanelRect(rect.left, rect.top, rect.right, cut) to PanelRect(rect.left, cut, rect.right, rect.bottom)
            }
        }
    }

    /** Vertical divider (left/right pieces) for a wider-than-tall panel, horizontal for taller-than-wide. */
    fun defaultSplitOrientation(rect: PanelRect): SplitOrientation =
        if (rect.width >= rect.height) SplitOrientation.VERTICAL else SplitOrientation.HORIZONTAL
}
```

- [ ] **Step 4: Check `PanelRect`'s actual constructor/property names before running**

Read `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/panel/Panel.kt` (already read once earlier this session: `data class PanelRect(val left: Float, val top: Float, val right: Float, val bottom: Float)` with computed `width`/`height`/`centerX`/`centerY`) — confirm this hasn't changed and the code above compiles against it as-is.

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "eu.kanade.tachiyomi.ui.reader.panel.edit.PanelEditOpsTest"`
Expected: PASS (all 11 tests).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/eu/kanade/tachiyomi/ui/reader/panel/edit/PanelEditOps.kt app/src/test/java/eu/kanade/tachiyomi/ui/reader/panel/edit/PanelEditOpsTest.kt
git commit -m "feat(reader): add PanelEditOps pure resize/move/merge/split logic"
```

---

## Task 5: PanelEditorActivity scaffold — load page, render boxes, select

**Files:**
- Create: `app/src/main/java/eu/kanade/tachiyomi/ui/reader/panel/edit/PanelEditorActivity.kt`
- Create: `app/src/main/java/eu/kanade/tachiyomi/ui/reader/panel/edit/PanelEditorScreen.kt`
- Create: `app/src/main/java/eu/kanade/tachiyomi/ui/reader/panel/edit/PanelEditorState.kt`

**Interfaces:**
- Consumes: `PanelManualOverrideRepository` (Task 2), `PanelEditOps`/`Handle`/`SplitOrientation` (Task 4), `PanelRect` (existing).
- Produces:
  - `PanelEditorActivity.newIntent(context: Context, imagePath: String, chapterId: Long, pageIndex: Int, rightToLeft: Boolean): Intent`
  - `PanelEditorActivity.EXTRA_WAS_EDITED: String` (result extra key, `Boolean`) — read by the caller (Task 8) to decide whether to refresh.
  - `data class PanelEditorState(val panels: List<PanelRect>, val selectedIndex: Int?, val mode: EditMode)`
  - `enum class EditMode { SELECT, ADD, MERGE_PICK_SECOND }`

- [ ] **Step 1: Write the state holder**

`app/src/main/java/eu/kanade/tachiyomi/ui/reader/panel/edit/PanelEditorState.kt`:

```kotlin
package eu.kanade.tachiyomi.ui.reader.panel.edit

import eu.kanade.tachiyomi.ui.reader.viewer.panel.PanelRect

enum class EditMode { SELECT, ADD, MERGE_PICK_SECOND }

/**
 * The editor's entire in-progress state. [panels] starts as whatever's currently effective for
 * the page (a previous manual override, or the pipeline's own detected/full-page result — decided
 * by the caller before launching, see PanelEditorActivity.newIntent's callers in Task 8) and is
 * mutated locally as the user edits; nothing is persisted until Save (Task 7).
 */
data class PanelEditorState(
    val panels: List<PanelRect> = emptyList(),
    val selectedIndex: Int? = null,
    val mode: EditMode = EditMode.SELECT,
    /** Set only while [mode] is MERGE_PICK_SECOND — the first panel tapped for a merge. */
    val mergeFirstIndex: Int? = null,
)
```

- [ ] **Step 2: Write the Activity shell**

`app/src/main/java/eu/kanade/tachiyomi/ui/reader/panel/edit/PanelEditorActivity.kt`:

```kotlin
package eu.kanade.tachiyomi.ui.reader.panel.edit

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import kotlinx.coroutines.launch
import mihon.app.di.appGraph

/**
 * A standalone screen for hand-correcting a page's panel boundaries. Deliberately has no
 * relationship to Pager/SubsamplingScaleImageView/PagerPageHolder or any of the live
 * panel-by-panel viewer's gesture code — see the design spec's Architecture section for why
 * (CLAUDE.md documents a full session where layering new gestures onto that live viewer caused
 * cascading, never-fully-resolved breakage). This screen's touch handling (Task 6) is
 * self-contained Compose `pointerInput` code operating on a static bitmap.
 */
class PanelEditorActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val imagePath = intent.getStringExtra(EXTRA_IMAGE_PATH) ?: run { finish(); return }
        val chapterId = intent.getLongExtra(EXTRA_CHAPTER_ID, -1L).takeIf { it != -1L } ?: run { finish(); return }
        val pageIndex = intent.getIntExtra(EXTRA_PAGE_INDEX, -1).takeIf { it != -1 } ?: run { finish(); return }
        val rightToLeft = intent.getBooleanExtra(EXTRA_RIGHT_TO_LEFT, false)
        val initialPanels = intent.getStringArrayListExtra(EXTRA_INITIAL_PANELS_JSON)
            ?.map { kotlinx.serialization.json.Json.decodeFromString<PanelRectSerializable>(it).toPanelRect() }
            ?: emptyList()

        setContent {
            MaterialTheme {
                Surface {
                    PanelEditorScreen(
                        imagePath = imagePath,
                        initialPanels = initialPanels,
                        onSave = { finalPanels ->
                            lifecycleScope.launch {
                                appGraph.panelManualOverrideRepository.save(
                                    chapterId, pageIndex, finalPanels, java.io.File(imagePath),
                                )
                                appGraph.panelFullPageOverrideRepository.removeOverride(chapterId, pageIndex)
                                setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_WAS_EDITED, true))
                                finish()
                            }
                        },
                        onResetToDetected = {
                            lifecycleScope.launch {
                                appGraph.panelManualOverrideRepository.remove(chapterId, pageIndex)
                                setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_WAS_EDITED, true))
                                finish()
                            }
                        },
                        onCancel = {
                            setResult(Activity.RESULT_CANCELED)
                            finish()
                        },
                    )
                }
            }
        }
    }

    companion object {
        private const val EXTRA_IMAGE_PATH = "image_path"
        private const val EXTRA_CHAPTER_ID = "chapter_id"
        private const val EXTRA_PAGE_INDEX = "page_index"
        private const val EXTRA_RIGHT_TO_LEFT = "right_to_left"
        private const val EXTRA_INITIAL_PANELS_JSON = "initial_panels_json"
        const val EXTRA_WAS_EDITED = "was_edited"

        fun newIntent(
            context: Context,
            imagePath: String,
            chapterId: Long,
            pageIndex: Int,
            rightToLeft: Boolean,
            initialPanels: List<eu.kanade.tachiyomi.ui.reader.viewer.panel.PanelRect>,
        ): Intent = Intent(context, PanelEditorActivity::class.java).apply {
            putExtra(EXTRA_IMAGE_PATH, imagePath)
            putExtra(EXTRA_CHAPTER_ID, chapterId)
            putExtra(EXTRA_PAGE_INDEX, pageIndex)
            putExtra(EXTRA_RIGHT_TO_LEFT, rightToLeft)
            putStringArrayListExtra(
                EXTRA_INITIAL_PANELS_JSON,
                ArrayList(
                    initialPanels.map {
                        kotlinx.serialization.json.Json.encodeToString(
                            PanelRectSerializable.serializer(),
                            PanelRectSerializable(it.left, it.top, it.right, it.bottom),
                        )
                    },
                ),
            )
        }
    }
}

@kotlinx.serialization.Serializable
private data class PanelRectSerializable(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun toPanelRect() = eu.kanade.tachiyomi.ui.reader.viewer.panel.PanelRect(left, top, right, bottom)
}
```

Confirmed: `BaseActivity`'s real import is `eu.kanade.tachiyomi.ui.base.activity.BaseActivity` (matches `ReaderActivity.kt`'s own import), and `appGraph` (`mihon.app.di.AppGraphUtils.kt`: `val Context.appGraph get() = metroGraph<AppGraph>()`) is a `Context` extension property, not something specific to `ReaderActivity`/`PagerViewer` — so plain `appGraph` resolves inside `PanelEditorActivity` exactly as written above (an `Activity` is a `Context`), no adjustment needed. Add `import mihon.app.di.appGraph` alongside the other imports.

- [ ] **Step 3: Write the screen composable (selection + rendering only — resize/move/add/merge/split come in Task 6)**

`app/src/main/java/eu/kanade/tachiyomi/ui/reader/panel/edit/PanelEditorScreen.kt`:

```kotlin
package eu.kanade.tachiyomi.ui.reader.panel.edit

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import eu.kanade.tachiyomi.ui.reader.viewer.panel.PanelRect

@Composable
fun PanelEditorScreen(
    imagePath: String,
    initialPanels: List<PanelRect>,
    onSave: (List<PanelRect>) -> Unit,
    onResetToDetected: () -> Unit,
    onCancel: () -> Unit,
) {
    val bitmap = remember(imagePath) { BitmapFactory.decodeFile(imagePath) }
    var panels by remember { mutableStateOf(initialPanels) }
    var selectedIndex by remember { mutableStateOf<Int?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize().weight(1f)) {
            Canvas(
                modifier = Modifier.fillMaxSize()
                    .pointerInput(panels) {
                        detectTapGestures { tapOffset ->
                            val normalized = Offset(tapOffset.x / size.width, tapOffset.y / size.height)
                            selectedIndex = panels.indexOfFirst { rect ->
                                normalized.x in rect.left..rect.right && normalized.y in rect.top..rect.bottom
                            }.takeIf { it >= 0 }
                        }
                    },
            ) {
                bitmap?.let { drawImage(it.asImageBitmap()) }
                panels.forEachIndexed { index, rect ->
                    val color = if (index == selectedIndex) Color.Yellow else Color.Cyan
                    drawRect(
                        color = color,
                        topLeft = Offset(rect.left * size.width, rect.top * size.height),
                        size = androidx.compose.ui.geometry.Size(rect.width * size.width, rect.height * size.height),
                        style = Stroke(width = 4f),
                    )
                }
            }
        }
        Column {
            Button(onClick = { onSave(panels) }) { Text("Save") }
            Button(onClick = onResetToDetected) { Text("Reset to detected") }
            Button(onClick = onCancel) { Text("Cancel") }
        }
    }
}
```

Note: `drawImage(ImageBitmap)` inside a `DrawScope` draws at the bitmap's own pixel size starting at the canvas origin, not scaled to fill the canvas — before this looks right on-device, check Compose's `androidx.compose.ui.graphics.drawscope.DrawScope.drawImage` overloads for the one that takes a destination size (likely `drawImage(image, dstSize = ...)` or wrapping the bitmap draw in a `scale()`/`Modifier.aspectRatio` so the full page is visible regardless of screen size) — Task 5's Step 4 on-device check is exactly where this gets caught if wrong.

- [ ] **Step 4: On-device check — does the page image render and can you tap to select a box?**

This step has no automated test — there's no existing UI test infrastructure for this reader (consistent with everything else in this feature area this session; see CLAUDE.md). Build and install (`./gradlew assembleDebug`, `adb install -r ...`), launch `PanelEditorActivity` directly via `adb shell am start -n <applicationId>/eu.kanade.tachiyomi.ui.reader.panel.edit.PanelEditorActivity --es image_path <a real decoded page jpg path on-device> --el chapter_id 1 --ei page_index 0` (find the applicationId in `app/build.gradle.kts`), and confirm: the page image is visible full-screen, and tapping inside a box's area highlights it yellow. Task 8 wires the real launch path from the reader; this step is just confirming the screen itself works before more logic is layered on.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/eu/kanade/tachiyomi/ui/reader/panel/edit/PanelEditorActivity.kt app/src/main/java/eu/kanade/tachiyomi/ui/reader/panel/edit/PanelEditorScreen.kt app/src/main/java/eu/kanade/tachiyomi/ui/reader/panel/edit/PanelEditorState.kt
git commit -m "feat(reader): PanelEditorActivity scaffold with image render + box selection"
```

---

## Task 6: Wire resize/move/add/delete/merge/split into the screen

**Files:**
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/reader/panel/edit/PanelEditorScreen.kt`

**Interfaces:**
- Consumes: `PanelEditOps.resize/move/merge/split/defaultSplitOrientation` (Task 4), `Handle`, `SplitOrientation` (Task 4).

- [ ] **Step 1: Add drag handling for resize/move on the selected panel**

Extend the `Canvas`'s `pointerInput` block in `PanelEditorScreen.kt` (added alongside, not replacing, the existing tap-to-select block — Compose's `pointerInput` supports multiple keyed blocks in the same modifier chain) with a `detectDragGestures` block: on drag start, hit-test whether the touch is near one of the selected panel's 8 handle zones (corner/edge, within some pixel tolerance converted to normalized coordinates) or inside its body (move) or outside any panel (no-op); on each drag delta, call `PanelEditOps.resize(...)` or `PanelEditOps.move(...)` with the delta converted from screen pixels to normalized `[0,1]` (divide by `size.width`/`size.height`, matching the same conversion already used for tap hit-testing in Task 5) and update `panels` by replacing the selected index's entry.

```kotlin
                    .pointerInput(panels, selectedIndex) {
                        detectDragGestures(
                            onDragStart = { startOffset ->
                                val normalized = Offset(startOffset.x / size.width, startOffset.y / size.height)
                                val index = selectedIndex ?: return@detectDragGestures
                                val rect = panels.getOrNull(index) ?: return@detectDragGestures
                                dragHandle = hitTestHandle(rect, normalized, handleTolerance = 0.02f)
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val index = selectedIndex ?: return@detectDragGestures
                                val rect = panels.getOrNull(index) ?: return@detectDragGestures
                                val dx = dragAmount.x / size.width
                                val dy = dragAmount.y / size.height
                                val updated = dragHandle?.let { PanelEditOps.resize(rect, it, dx, dy) }
                                    ?: PanelEditOps.move(rect, dx, dy)
                                panels = panels.toMutableList().also { it[index] = updated }
                            },
                        )
                    }
```

Add `var dragHandle by remember { mutableStateOf<Handle?>(null) }` alongside the other `remember`s, and a `hitTestHandle` helper:

```kotlin
private fun hitTestHandle(rect: PanelRect, point: Offset, handleTolerance: Float): Handle? {
    val nearLeft = kotlin.math.abs(point.x - rect.left) < handleTolerance
    val nearRight = kotlin.math.abs(point.x - rect.right) < handleTolerance
    val nearTop = kotlin.math.abs(point.y - rect.top) < handleTolerance
    val nearBottom = kotlin.math.abs(point.y - rect.bottom) < handleTolerance
    return when {
        nearLeft && nearTop -> Handle.TOP_LEFT
        nearRight && nearTop -> Handle.TOP_RIGHT
        nearLeft && nearBottom -> Handle.BOTTOM_LEFT
        nearRight && nearBottom -> Handle.BOTTOM_RIGHT
        nearLeft -> Handle.LEFT
        nearRight -> Handle.RIGHT
        nearTop -> Handle.TOP
        nearBottom -> Handle.BOTTOM
        else -> null // inside the body, not near any edge -> move, not resize
    }
}
```

- [ ] **Step 2: Add Delete, Add, Merge, Split buttons to the bottom bar**

Extend the bottom `Column`'s buttons in `PanelEditorScreen.kt`:

```kotlin
            Button(
                onClick = {
                    val index = selectedIndex ?: return@Button
                    panels = panels.filterIndexed { i, _ -> i != index }
                    selectedIndex = null
                },
                enabled = selectedIndex != null,
            ) { Text("Delete") }

            Button(
                onClick = {
                    // A new panel starts as a fixed-size box near the page centre — the user drags
                    // its handles (Step 1's resize logic, reused as-is) to the real size/position
                    // they want, rather than needing a separate "draw a rectangle from scratch"
                    // gesture.
                    panels = panels + PanelRect(0.35f, 0.35f, 0.65f, 0.65f)
                    selectedIndex = panels.lastIndex
                },
            ) { Text("Add") }

            var mergeFirstIndex by remember { mutableStateOf<Int?>(null) }
            Button(
                onClick = {
                    val first = mergeFirstIndex
                    val second = selectedIndex
                    if (first == null) {
                        mergeFirstIndex = selectedIndex
                    } else if (second != null && second != first) {
                        val merged = PanelEditOps.merge(panels[first], panels[second])
                        panels = panels.filterIndexed { i, _ -> i != first && i != second } + merged
                        selectedIndex = panels.lastIndex
                        mergeFirstIndex = null
                    }
                },
                enabled = selectedIndex != null,
            ) { Text(if (mergeFirstIndex == null) "Merge: pick first" else "Merge: pick second, then tap again") }

            Button(
                onClick = {
                    val index = selectedIndex ?: return@Button
                    val rect = panels[index]
                    val orientation = PanelEditOps.defaultSplitOrientation(rect)
                    val at = if (orientation == SplitOrientation.VERTICAL) {
                        (rect.left + rect.right) / 2f
                    } else {
                        (rect.top + rect.bottom) / 2f
                    }
                    val (first, second) = PanelEditOps.split(rect, orientation, at)
                    panels = panels.filterIndexed { i, _ -> i != index } + first + second
                    selectedIndex = null
                },
                enabled = selectedIndex != null,
            ) { Text("Split") }
```

This "Split" button uses the panel's centre immediately rather than the spec's draggable-divider preview — call this out explicitly to the user after implementing: a draggable split-preview divider is a real UI affordance the spec describes, but needs its own drag-gesture state (a third `pointerInput` mode) layered on top of what Step 1 already added, and risks the same handle-hit-testing/drag-mode conflicts Step 1 just resolved for resize vs. move. Ship the centre-split first, confirm the rest of the editor works end-to-end on-device (Task 7/8), then treat the draggable divider as a follow-up refinement — don't block the whole feature on getting that one interaction perfect first.

- [ ] **Step 3: On-device check**

Build, install, launch the same way as Task 5 Step 4. Confirm: dragging a selected panel's corner resizes it (only that corner moves), dragging its edge resizes one side only, dragging its body moves the whole panel, Delete removes the selected panel, Add creates a new centred box, selecting two panels then tapping Merge twice combines them, selecting one panel then tapping Split divides it in two along its longer axis.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/eu/kanade/tachiyomi/ui/reader/panel/edit/PanelEditorScreen.kt
git commit -m "feat(reader): wire resize/move/add/delete/merge/split into the panel editor"
```

---

## Task 7: Save/Reset/Cancel already wired — confirm persistence round-trip

Task 5 already wired `onSave`/`onResetToDetected`/`onCancel` to the repository calls and `finish()` with a result. This task is purely verification — no new code — but is its own task because it's the first point the *whole* save path (editor screen -> repository -> DB row + snapshot file -> detector priority check) can be checked together, and a reviewer should be able to gate on "does a save actually round-trip" independently of "does the UI look right" (Task 6).

**Files:** None (verification only).

- [ ] **Step 1: On-device check — save persists and takes effect**

With the app installed from Task 6: launch the editor via the same `adb shell am start` command as before, make an edit (e.g., delete one panel and resize another), tap Save. Confirm the activity closes. Then pull the DB and inspect the row:

Run: `adb shell run-as <applicationId> sqlite3 databases/tachiyomi.db "SELECT chapter_id, page_index, image_snapshot_path, corrected_at FROM panel_manual_override;"` (replace `<applicationId>` with the real one from `app/build.gradle.kts`; if `run-as` fails because the installed build isn't debuggable, use `adb shell run-as` only if it previously worked in this session — CLAUDE.md/this session's own transcript shows `run-as` failing earlier for a *different*, non-debug package; confirm which package id the actual debug build under test uses before assuming this command works verbatim).
Expected: one row, with a `page_index`/`chapter_id` matching what was passed to the editor, and `image_snapshot_path` pointing at a file that exists (check with `adb shell run-as <applicationId> ls files/panel_training_snapshots/`).

- [ ] **Step 2: On-device check — Reset to detected removes the override**

Launch the editor again for the same page, tap "Reset to detected". Re-run the same `sqlite3` query from Step 1.
Expected: zero rows, and the snapshot file from Step 1 no longer listed.

- [ ] **Step 3: No commit** — this task made no code changes.

---

## Task 8: Wire the launch from the reader

**Files:**
- Modify: `app/src/main/java/eu/kanade/presentation/reader/ReaderPageActionsDialog.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderActivity.kt`
- Modify: `i18n/src/commonMain/moko-resources/base/strings.xml`

**Interfaces:**
- Consumes: `PanelEditorActivity.newIntent(...)` (Task 5), `PanelEditorActivity.EXTRA_WAS_EDITED` (Task 5), `PagerViewer.refreshPanelDetection(page, forceFirstStop)` (already exists, built for the full-page-override feature earlier this session).

- [ ] **Step 1: Add the new string**

In `i18n/src/commonMain/moko-resources/base/strings.xml`, alongside the other panel-by-panel action strings added earlier this session (`action_show_full_page`, `action_show_panel_order`):

```xml
    <string name="action_edit_panels">Edit panels</string>
```

- [ ] **Step 2: Add the new action to the page-actions dialog**

In `app/src/main/java/eu/kanade/presentation/reader/ReaderPageActionsDialog.kt`, add a new parameter and button in the same Guided-view-only `Row` added earlier this session (alongside "Show full page"/"Show panel order"):

```kotlin
    isGuidedView: Boolean = false,
    isFullPageOverridden: Boolean = false,
    onToggleFullPageOverride: () -> Unit = {},
    showDebugOrder: Boolean = false,
    onToggleDebugOrder: () -> Unit = {},
    onEditPanels: () -> Unit = {},
```

```kotlin
                ActionButton(
                    modifier = Modifier.weight(1f),
                    title = stringResource(MR.strings.action_edit_panels),
                    icon = Icons.Outlined.Edit,
                    onClick = {
                        onEditPanels()
                        onDismissRequest()
                    },
                )
```

Add the import: `import androidx.compose.material.icons.outlined.Edit`. This makes the Guided-view row 3 buttons instead of 2 — check whether `Row`'s existing `horizontalArrangement = Arrangement.spacedBy(...)` still lays out three `Modifier.weight(1f)` buttons acceptably on a phone-width screen once this is on-device (Step 5); if it looks cramped, wrapping to two rows of buttons is a reasonable follow-up, not a blocker for this task.

- [ ] **Step 3: Wire the click handler and activity result in `ReaderActivity.kt`**

Add the imports:

```kotlin
import androidx.activity.result.contract.ActivityResultContracts
import eu.kanade.tachiyomi.ui.reader.panel.edit.PanelEditorActivity
import eu.kanade.tachiyomi.ui.reader.viewer.panel.PanelDirection
```

Add a registered launcher as a class property (alongside `ReaderActivity`'s other class-level state, near the top of the class body):

```kotlin
    private val panelEditorLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data?.getBooleanExtra(PanelEditorActivity.EXTRA_WAS_EDITED, false) == true) {
            val page = (viewModel.state.value.dialog as? ReaderViewModel.Dialog.PageActions)?.page
                ?: viewModel.getCurrentPage()
            (viewModel.state.value.viewer as? PanelByPanelViewer)?.let { viewer ->
                page?.let { viewer.refreshPanelDetection(it, forceFirstStop = true) }
            }
        }
    }
```

Check `ReaderViewModel.kt` for a method that returns the currently-displayed `ReaderPage` (used as a fallback since the page-actions dialog will already be dismissed by the time this callback runs, so `state.dialog` is likely `null`) — search for how `PagerViewer`/`ReaderActivity` currently track "the page on screen" (e.g. `state.currentPage` is an `Int` index in the `State` data class seen earlier this session, not a `ReaderPage` — resolving an index back to a `ReaderPage` needs `state.viewerChapters?.currChapter?.pages?.getOrNull(index)`; use whatever the codebase's own existing pattern for this resolution is, don't invent a new one).

Add the launch call, wired to the dialog's new `onEditPanels`:

```kotlin
            is ReaderViewModel.Dialog.PageActions -> {
                val showDebugOrder by readerPreferences.panelByPanelShowDebugOrder.collectAsState()
                ReaderPageActionsDialog(
                    onDismissRequest = onDismissRequest,
                    onSetAsCover = viewModel::setAsCover,
                    onShare = viewModel::shareImage,
                    onSave = viewModel::saveImage,
                    isGuidedView = state.viewer is PanelByPanelViewer,
                    isFullPageOverridden = state.isPanelFullPageOverridden,
                    onToggleFullPageOverride = viewModel::toggleFullPageOverride,
                    showDebugOrder = showDebugOrder,
                    onToggleDebugOrder = { readerPreferences.panelByPanelShowDebugOrder.set(!showDebugOrder) },
                    onEditPanels = { launchPanelEditor(dialog.page) },
                )
            }
```

First, expose the page holder's already-detected panels — `PagerPageHolder.detectedPanels` (`app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/PagerPageHolder.kt:96`) is `private var detectedPanels: List<Panel>? = null` today, with no public read accessor. Add one right next to the existing `isDetectingPanels()` (`PagerPageHolder.kt:107`), same file:

```kotlin
    /** The page's currently-detected panels, if detection has finished — used to seed the manual editor's starting state. */
    fun currentPanels(): List<Panel>? = detectedPanels
```

Then add the matching lookup to `PagerViewer.kt`, right next to `refreshPanelDetection` (added earlier this session for the full-page-override feature) — same `getPageHolder(page)` pattern:

```kotlin
    /** [PagerPageHolder.currentPanels] for [page], if its holder is currently alive; null otherwise. */
    fun currentPanels(page: ReaderPage): List<Panel>? = getPageHolder(page)?.currentPanels()
```

Now the launch helper itself — writes the page's already-loaded bytes to a cache file (mirrors `shareImage`/`saveImage`'s existing use of `context.cacheImageDir`, seen in `ReaderViewModel.kt` earlier this session) before starting the editor, since `PanelEditorActivity` reads a plain file path rather than re-resolving the page through the manga/chapter loading pipeline itself, and uses the two new accessors above plus `PanelByPanelViewer.panelDirection` (existing, added in the original panel-to-panel reader design) for direction:

```kotlin
    private fun launchPanelEditor(page: ReaderPage) {
        val chapterId = page.chapter.chapter.id ?: return
        val stream = page.stream ?: return
        val viewer = viewModel.state.value.viewer as? PanelByPanelViewer ?: return
        lifecycleScope.launch {
            val imageFile = withIOContext {
                File(cacheImageDir, "panel_edit_source.jpg").apply {
                    parentFile?.mkdirs()
                    stream().use { input -> outputStream().use { input.copyTo(it) } }
                }
            }
            val currentPanels = viewer.currentPanels(page)?.map { it.bounds } ?: emptyList()
            panelEditorLauncher.launch(
                PanelEditorActivity.newIntent(
                    context = this@ReaderActivity,
                    imagePath = imageFile.absolutePath,
                    chapterId = chapterId,
                    pageIndex = page.index,
                    rightToLeft = viewer.panelDirection == PanelDirection.RTL,
                    initialPanels = currentPanels,
                ),
            )
        }
    }
```

If detection hasn't finished yet when the user opens the editor (`currentPanels()` returns `null`, e.g. they long-pressed immediately after the page appeared), `currentPanels` falls back to an empty list — the editor opens with no boxes, and the "Add" button (Task 6) lets them build a list from scratch. This is an acceptable, narrow edge case, not a blocker: re-entering the editor after detection finishes (or after the page has been viewed once) shows the real detected boxes.

- [ ] **Step 4: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: On-device check — full flow from the reader**

Build, install, open a manga in Guided view, long-press a page, tap "Edit panels". Confirm the editor opens showing that exact page with its current panel boxes. Make an edit, Save. Confirm you're back in the reader, on the same page, and it now reflects the edited panels (compare against Task 7's on-device DB check for the same page).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/eu/kanade/presentation/reader/ReaderPageActionsDialog.kt app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderActivity.kt i18n/src/commonMain/moko-resources/base/strings.xml
git commit -m "feat(reader): wire manual panel editor launch from the page-actions menu"
```

---

## Self-Review Notes (for whoever executes this plan)

- **Spec coverage:** Data model (Task 1-2), detector priority (Task 3), edit operations (Task 4), editor UI (Task 5-6), save/persistence round-trip (Task 2, verified in Task 7), launch wiring + return-to-reader refresh (Task 8). Training-data format conversion (`PanelRect` → YOLO) and the actual export tool are explicitly out of scope per the spec's Non-goals — not covered here, correctly.
- **Resolved during self-review** (checked against the real source, not left as guesses): `Panel`'s rect property is `bounds`, not `rect` — fixed in Task 2's repository and Task 3's test (this was a real bug in the first draft, not a hedge). `fakeReaderPage`'s `Chapter`/`ReaderChapter`/`ReaderPage` construction (Task 3) — uses the real `Chapter.create()` factory. `BaseActivity`'s import path and `appGraph`'s reachability from `PanelEditorActivity` (Task 5) — confirmed `appGraph` is a `Context` extension property, not `ReaderActivity`-specific. The "get this page's current panel list to seed the editor" gap (Task 8) — resolved as a small additive `currentPanels()` accessor on `PagerPageHolder`/`PagerViewer`, mirroring the existing `refreshPanelDetection`/`getPageHolder` pattern exactly, with real code included in Task 8.
- **Genuinely needs on-device verification, not resolvable by reading source alone:** `drawImage`'s exact scaling behavior inside a Compose `DrawScope` (Task 5, flagged inline with what to check for); the three-button Guided-view row's layout on a real phone width (Task 8, two buttons already shipped there earlier this session, this adds a third); the `run-as`/package-id command in Task 7 (this session's own transcript shows `run-as` failing for a differently-packaged build earlier — confirm the actual debug package id first, don't assume). The draggable split-divider was deliberately descoped to an instant centre-split for this pass (Task 6) — a named, deliberate scope decision, not an unresolved unknown.
