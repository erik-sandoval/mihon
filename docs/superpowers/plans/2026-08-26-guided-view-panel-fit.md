# Guided View: Bubble-Aware Panel Fit Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When Guided View's panel-by-panel reader encounters a panel that doesn't fit the current device orientation well, step through its detected speech bubbles individually (each comfortably readable) before revealing the full panel — gated behind an off-by-default preference — instead of forcing the reader to rotate the device or squint at a badly-scaled panel.

**Architecture:** The existing ML panel detector already emits speech-bubble bounding boxes in the same inference pass that detects panels (`result.bubbles`), currently discarded after being used only to influence panel padding/merge decisions. This plan retains those boxes, associates each with its final owning panel (`Panel.bubbles`, a new cached field), and adds a new `SpeechBubblePanelSubStopGenerator` (implementing an existing-but-unused `PanelSubStopGenerator` interface, widened to fit) that decides — fresh, every time, from the *current* view dimensions, never cached — whether a panel needs bubble-stop expansion. The existing `flattenToStops()`/`subStops` mechanism needs no changes; it already does the right thing once `subStops` is populated. A hard guard ensures this never runs against the `PanelRect.FULL_PAGE` "no panels detected" fallback. Mid-read preference toggles and device rotation both need to resume sensibly across a stop list that may have reshaped, using one shared "grow → first stop of panel; shrink/same → nearest stop within that panel" rule.

**Tech Stack:** Kotlin, JUnit 5 (`kotlinx-coroutines-test` for suspend functions), Android `SubsamplingScaleImageView`-based reader, kotlinx.serialization for cached panel data, SQLDelight-backed `PanelCacheRepository`.

**Spec:** `docs/superpowers/specs/2026-08-26-guided-view-panel-fit-design.md`

## Global Constraints

- Off by default — a fresh install/existing user sees zero behavior change until they explicitly enable the new preference.
- Detection caching (`PanelCacheRepository`, keyed by `chapterId/pageIndex/imageHash/detectorVersion`) stays orientation-agnostic. Only bubble *locations* are new cached data; whether they're used to expand a panel into stops is always decided live, never cached.
- Any change to what `PanelDetector`/`PanelPipeline` produce for an already-cached page requires bumping `PanelDetector.DETECTOR_VERSION` in the same commit as the change (established project rule).
- A sub-stop generator must never run against the `PanelRect.FULL_PAGE` "no real panels detected" fallback sentinel — checked before generation is ever invoked, not as a side effect of any other heuristic.
- `GeometricPanelSubStopGenerator` (dead code implementing a rejected geometric-split approach) is deleted, not revived or left in place alongside the new generator.

---

### Task 1: `Panel.bubbles` field, widen `PanelSubStopGenerator`, delete the dead geometric generator

**Files:**
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/panel/Panel.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/panel/PanelSubStopGenerator.kt`
- Delete: `app/src/test/java/eu/kanade/tachiyomi/ui/reader/viewer/panel/GeometricPanelSubStopGeneratorTest.kt`
- Test: `app/src/test/java/eu/kanade/tachiyomi/ui/reader/viewer/panel/PanelTest.kt` (existing tests must still pass unchanged)

**Interfaces:**
- Produces: `Panel(bounds: PanelRect, bubbles: List<PanelRect> = emptyList(), subStops: List<PanelRect> = emptyList())` — `bubbles` is new.
- Produces: `interface PanelSubStopGenerator { suspend fun generate(panel: Panel, direction: PanelDirection, viewWidth: Int, viewHeight: Int, cropPanel: suspend () -> Bitmap?): List<PanelRect> }` — signature widened from `panel: PanelRect` to `panel: Panel` (so a generator can read `bubbles`), and `viewWidth`/`viewHeight` added (so a generator can judge fit against the current orientation).

- [ ] **Step 1: Run the existing `PanelTest` to confirm current behavior before touching anything**

Run: `./gradlew :app:testDebugUnitTest --tests "eu.kanade.tachiyomi.ui.reader.viewer.panel.PanelTest"`
Expected: PASS (6 tests, all green) — this is the baseline `flattenToStops()`/`Panel` behavior this task must not change.

- [ ] **Step 2: Add the `bubbles` field to `Panel`**

In `Panel.kt`, change:

```kotlin
@Serializable
data class Panel(
    val bounds: PanelRect,
    val subStops: List<PanelRect> = emptyList(),
)
```

to:

```kotlin
@Serializable
data class Panel(
    val bounds: PanelRect,
    /**
     * This panel's detected speech-bubble boxes, in reading order, normalized to page
     * coordinates (same convention as [bounds]). Populated once at detection time from the
     * same ML inference pass that detects panels — orientation-agnostic, cached alongside
     * [bounds]. Whether these get used to expand this panel into multiple stops is a
     * separate, live decision (see [PanelSubStopGenerator]), never baked in here.
     */
    val bubbles: List<PanelRect> = emptyList(),
    val subStops: List<PanelRect> = emptyList(),
)
```

- [ ] **Step 3: Run `PanelTest` again to confirm the new field doesn't break existing tests**

Run: `./gradlew :app:testDebugUnitTest --tests "eu.kanade.tachiyomi.ui.reader.viewer.panel.PanelTest"`
Expected: PASS — the default `emptyList()` means every existing `Panel(bounds = ...)` construction in the test file still compiles and behaves identically.

- [ ] **Step 4: Widen the `PanelSubStopGenerator` interface and delete the dead geometric implementation**

Replace the entire contents of `PanelSubStopGenerator.kt` with just the widened interface:

```kotlin
package eu.kanade.tachiyomi.ui.reader.viewer.panel

import android.graphics.Bitmap

interface PanelSubStopGenerator {
    /**
     * Returns ordered sub-stops for [panel] given the current [viewWidth]/[viewHeight], or an
     * empty list if it doesn't need any (the panel itself is the only stop). When non-empty,
     * the last stop is always the full [panel] bounds. [cropPanel] lazily crops the panel out
     * of the full-resolution page bitmap, for a generator that needs to inspect panel content
     * (e.g. OCR) — unused by a generator that only needs already-known geometry.
     */
    suspend fun generate(
        panel: Panel,
        direction: PanelDirection,
        viewWidth: Int,
        viewHeight: Int,
        cropPanel: suspend () -> Bitmap?,
    ): List<PanelRect>
}
```

- [ ] **Step 5: Delete the dead test file**

```bash
git rm app/src/test/java/eu/kanade/tachiyomi/ui/reader/viewer/panel/GeometricPanelSubStopGeneratorTest.kt
```

- [ ] **Step 6: Compile-check the module**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL — confirms nothing else in the app referenced `GeometricPanelSubStopGenerator` or the old interface signature (the spec's earlier investigation already confirmed no call sites exist, but this re-verifies after the actual edit).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/panel/Panel.kt \
  app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/panel/PanelSubStopGenerator.kt \
  app/src/test/java/eu/kanade/tachiyomi/ui/reader/viewer/panel/GeometricPanelSubStopGeneratorTest.kt
git commit -m "feat(panel): add Panel.bubbles field, widen PanelSubStopGenerator, remove dead geometric generator"
```

---

### Task 2: Associate detected bubbles with their final owning panel

**Files:**
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/panel/PanelPipeline.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/panel/MlPanelBoundaryDetector.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/panel/PanelDetector.kt`
- Test: `app/src/test/java/eu/kanade/tachiyomi/ui/reader/viewer/panel/PanelPipelineTest.kt`

**Interfaces:**
- Consumes: `PanelOrdering.order(panels: List<PanelRect>, rightToLeft: Boolean = false, isSpread: Boolean = false): List<PanelRect>` (existing, unchanged).
- Produces: `PanelPipeline.associateBubbles(panels: List<PanelRect>, bubbles: List<PanelRect>, rightToLeft: Boolean): List<Panel>`
- Produces: `MlPanelBoundaryDetector.detect(page: Bitmap, rightToLeft: Boolean, label: String = ""): List<Panel>` — return type changed from `List<PanelRect>`.

- [ ] **Step 1: Write the failing test for `associateBubbles`**

Add to `PanelPipelineTest.kt` (following the file's existing style — check its current imports/package before adding):

```kotlin
@Test
fun `associateBubbles attaches each bubble to the panel whose bounds contain its center, in reading order`() {
    // A real gutter gap between the panels (0.45 to 0.55) so a bubble can genuinely fall in
    // neither's bounds, rather than the panels' edges exactly meeting at a shared boundary.
    val leftPanel = PanelRect(0f, 0f, 0.45f, 1f)
    val rightPanel = PanelRect(0.55f, 0f, 1f, 1f)
    // Two bubbles inside rightPanel, given out of reading order on purpose.
    val bubbleBottom = PanelRect(0.6f, 0.6f, 0.9f, 0.7f)
    val bubbleTop = PanelRect(0.6f, 0.1f, 0.9f, 0.2f)
    val bubbleInGutter = PanelRect(0.48f, 0.4f, 0.52f, 0.45f) // center x=0.5, inside neither panel

    val result = PanelPipeline.associateBubbles(
        panels = listOf(leftPanel, rightPanel),
        bubbles = listOf(bubbleBottom, bubbleTop, bubbleInGutter),
        rightToLeft = false,
    )

    assertEquals(2, result.size)
    assertEquals(leftPanel, result[0].bounds)
    assertEquals(emptyList<PanelRect>(), result[0].bubbles)
    assertEquals(rightPanel, result[1].bounds)
    assertEquals(listOf(bubbleTop, bubbleBottom), result[1].bubbles)
}

@Test
fun `associateBubbles orders a panel's bubbles right-to-left when the panel list is RTL`() {
    val panel = PanelRect(0f, 0f, 1f, 1f)
    val bubbleLeft = PanelRect(0.1f, 0.1f, 0.3f, 0.2f)
    val bubbleRight = PanelRect(0.7f, 0.1f, 0.9f, 0.2f)

    val result = PanelPipeline.associateBubbles(
        panels = listOf(panel),
        bubbles = listOf(bubbleLeft, bubbleRight),
        rightToLeft = true,
    )

    assertEquals(listOf(bubbleRight, bubbleLeft), result.single().bubbles)
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "eu.kanade.tachiyomi.ui.reader.viewer.panel.PanelPipelineTest"`
Expected: FAIL with "unresolved reference: associateBubbles" (function doesn't exist yet).

- [ ] **Step 3: Implement `associateBubbles` in `PanelPipeline.kt`**

Add this function to the `PanelPipeline` object (near `zoomRegions`, since it operates on the same final panel list `zoomRegions` produces):

```kotlin
/**
 * Attaches each bubble to whichever final panel's bounds contains its center (same
 * containment test [pad] already uses for bubble-to-panel ownership), ordered per panel in
 * natural reading order. A bubble that falls in the gutter between panels (no panel's bounds
 * contains its center) is simply not attached to any panel — it's still shown when that
 * panel's own bounds render (nothing is hidden), it just isn't its own stepping stop.
 */
fun associateBubbles(panels: List<PanelRect>, bubbles: List<PanelRect>, rightToLeft: Boolean): List<Panel> {
    return panels.map { panel ->
        val owned = bubbles.filter { b -> b.centerX in panel.left..panel.right && b.centerY in panel.top..panel.bottom }
        val ordered = if (owned.size > 1) PanelOrdering.order(owned, rightToLeft, isSpread = false) else owned
        Panel(bounds = panel, bubbles = ordered)
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "eu.kanade.tachiyomi.ui.reader.viewer.panel.PanelPipelineTest"`
Expected: PASS, including all pre-existing `PanelPipelineTest` cases (unchanged).

- [ ] **Step 5: Wire `associateBubbles` into `MlPanelBoundaryDetector.detect()`**

In `MlPanelBoundaryDetector.kt`, change:

```kotlin
fun detect(page: Bitmap, rightToLeft: Boolean, label: String = ""): List<PanelRect> = synchronized(lock) {
    val result = try {
        run(page)
    } catch (t: Throwable) {
        logcat(LogPriority.ERROR, t) { "ML panel inference failed" }
        DetectResult(emptyList(), emptyList(), page.width, page.height)
    }
    logcat { "panelDebug [$label] ${result.pageW}x${result.pageH} raw panels=${result.panels} bubbles=${result.bubbles}" }
    val planned = PanelPipeline.zoomRegions(
        result.panels, result.bubbles, result.pageW, result.pageH, rightToLeft,
    )
    logcat { "panelDebug [$label] ${result.pageW}x${result.pageH} planned=$planned" }
    if (planned.size < 2) listOf(PanelRect.FULL_PAGE) else planned
}
```

to:

```kotlin
fun detect(page: Bitmap, rightToLeft: Boolean, label: String = ""): List<Panel> = synchronized(lock) {
    val result = try {
        run(page)
    } catch (t: Throwable) {
        logcat(LogPriority.ERROR, t) { "ML panel inference failed" }
        DetectResult(emptyList(), emptyList(), page.width, page.height)
    }
    logcat { "panelDebug [$label] ${result.pageW}x${result.pageH} raw panels=${result.panels} bubbles=${result.bubbles}" }
    val planned = PanelPipeline.zoomRegions(
        result.panels, result.bubbles, result.pageW, result.pageH, rightToLeft,
    )
    logcat { "panelDebug [$label] ${result.pageW}x${result.pageH} planned=$planned" }
    val finalPanels = if (planned.size < 2) listOf(PanelRect.FULL_PAGE) else planned
    PanelPipeline.associateBubbles(finalPanels, result.bubbles, rightToLeft)
}
```

- [ ] **Step 6: Simplify `PanelDetector.runDetection()` now that the detector returns `List<Panel>` directly**

In `PanelDetector.kt`, change:

```kotlin
private suspend fun runDetection(imageBytes: Buffer, direction: PanelDirection, label: String): List<Panel> {
    val detector = mlDetector ?: return listOf(Panel(PanelRect.FULL_PAGE))

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeStream(imageBytes.copy().inputStream(), null, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return listOf(Panel(PanelRect.FULL_PAGE))

    val sample = sampleSizeFor(bounds.outWidth, bounds.outHeight, MAX_DETECTION_DIMENSION)
    val smallBitmap = BitmapFactory.decodeStream(
        imageBytes.copy().inputStream(),
        null,
        BitmapFactory.Options().apply { inSampleSize = sample },
    ) ?: return listOf(Panel(PanelRect.FULL_PAGE))

    val rects = detector.detect(smallBitmap, rightToLeft = direction == PanelDirection.RTL, label = label)
    smallBitmap.recycle()

    // Sub-stops (multiple zoom stops within one wide panel) aren't used here: the ML pipeline's
    // own merge/divide planner already produces exactly the final stop list, so every entry —
    // whether it's a raw detected panel or a planned merge/split piece — is exactly one stop.
    return rects.map { rect -> Panel(rect) }
}
```

to:

```kotlin
private suspend fun runDetection(imageBytes: Buffer, direction: PanelDirection, label: String): List<Panel> {
    val detector = mlDetector ?: return listOf(Panel(PanelRect.FULL_PAGE))

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeStream(imageBytes.copy().inputStream(), null, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return listOf(Panel(PanelRect.FULL_PAGE))

    val sample = sampleSizeFor(bounds.outWidth, bounds.outHeight, MAX_DETECTION_DIMENSION)
    val smallBitmap = BitmapFactory.decodeStream(
        imageBytes.copy().inputStream(),
        null,
        BitmapFactory.Options().apply { inSampleSize = sample },
    ) ?: return listOf(Panel(PanelRect.FULL_PAGE))

    val panels = detector.detect(smallBitmap, rightToLeft = direction == PanelDirection.RTL, label = label)
    smallBitmap.recycle()
    return panels
}
```

(The removed comment about sub-stops no longer applies now that bubble association happens inside the detector itself.)

- [ ] **Step 7: Bump `DETECTOR_VERSION`**

In `PanelDetector.kt`'s companion object, `DETECTOR_VERSION` is currently `44`. Bump it to `45` — this is a real pipeline output change (every cached `Panel` now carries `bubbles`), so old cached rows must be invalidated per the project's established rule, not silently deserialize with `bubbles = emptyList()` forever.

- [ ] **Step 8: Compile-check and run the full panel test suite**

Run: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --tests "eu.kanade.tachiyomi.ui.reader.viewer.panel.*"`
Expected: BUILD SUCCESSFUL, all tests PASS.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/panel/PanelPipeline.kt \
  app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/panel/MlPanelBoundaryDetector.kt \
  app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/panel/PanelDetector.kt \
  app/src/test/java/eu/kanade/tachiyomi/ui/reader/viewer/panel/PanelPipelineTest.kt
git commit -m "feat(panel): associate detected bubbles with their owning panel, bump DETECTOR_VERSION"
```

---

### Task 3: `SpeechBubblePanelSubStopGenerator`

**Files:**
- Create: `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/panel/SpeechBubblePanelSubStopGenerator.kt`
- Test: `app/src/test/java/eu/kanade/tachiyomi/ui/reader/viewer/panel/SpeechBubblePanelSubStopGeneratorTest.kt`

**Interfaces:**
- Consumes: `Panel(bounds: PanelRect, bubbles: List<PanelRect>, subStops: List<PanelRect>)`, `PanelSubStopGenerator` (Task 1).
- Produces: `object SpeechBubblePanelSubStopGenerator : PanelSubStopGenerator`

- [ ] **Step 1: Write the failing tests**

Create `SpeechBubblePanelSubStopGeneratorTest.kt`:

```kotlin
package eu.kanade.tachiyomi.ui.reader.viewer.panel

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpeechBubblePanelSubStopGeneratorTest {

    @Test
    fun `never generates sub-stops for the full-page fallback, regardless of bubbles or view size`() = runTest {
        val bubble = PanelRect(0.1f, 0.1f, 0.2f, 0.2f)
        val fallback = Panel(bounds = PanelRect.FULL_PAGE, bubbles = listOf(bubble))

        // A view shape that would otherwise clearly trigger expansion (very tall, narrow panel
        // vs. a normal portrait viewport) — must still return empty for FULL_PAGE.
        val stops = SpeechBubblePanelSubStopGenerator.generate(
            fallback, PanelDirection.LTR, viewWidth = 1080, viewHeight = 2400,
        ) { null }

        assertTrue(stops.isEmpty())
    }

    @Test
    fun `a panel with no bubbles is never expanded even if it doesn't fit well`() = runTest {
        // Extremely tall, narrow panel relative to the viewport, but no dialogue to preserve context for.
        val panel = Panel(bounds = PanelRect(0.4f, 0f, 0.6f, 1f), bubbles = emptyList())

        val stops = SpeechBubblePanelSubStopGenerator.generate(
            panel, PanelDirection.LTR, viewWidth = 1080, viewHeight = 2400,
        ) { null }

        assertTrue(stops.isEmpty())
    }

    @Test
    fun `a panel that fits the current view well is never expanded even with bubbles`() = runTest {
        // A roughly square panel comfortably fits either orientation.
        val panel = Panel(
            bounds = PanelRect(0f, 0f, 1f, 1f),
            bubbles = listOf(PanelRect(0.1f, 0.1f, 0.3f, 0.2f)),
        )

        val stops = SpeechBubblePanelSubStopGenerator.generate(
            panel, PanelDirection.LTR, viewWidth = 1080, viewHeight = 1200,
        ) { null }

        assertTrue(stops.isEmpty())
    }

    @Test
    fun `an oversized panel with bubbles expands to its bubbles in order, then the full panel`() = runTest {
        val bubble1 = PanelRect(0.05f, 0.05f, 0.25f, 0.15f)
        val bubble2 = PanelRect(0.7f, 0.8f, 0.9f, 0.9f)
        // Very wide, short panel — poor fit for a tall portrait viewport.
        val panel = Panel(
            bounds = PanelRect(0f, 0f, 1f, 0.15f),
            bubbles = listOf(bubble1, bubble2),
        )

        val stops = SpeechBubblePanelSubStopGenerator.generate(
            panel, PanelDirection.LTR, viewWidth = 1080, viewHeight = 2400,
        ) { null }

        assertEquals(listOf(bubble1, bubble2, panel.bounds), stops)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "eu.kanade.tachiyomi.ui.reader.viewer.panel.SpeechBubblePanelSubStopGeneratorTest"`
Expected: FAIL with "unresolved reference: SpeechBubblePanelSubStopGenerator" (doesn't exist yet).

- [ ] **Step 3: Implement `SpeechBubblePanelSubStopGenerator`**

Create `SpeechBubblePanelSubStopGenerator.kt`:

```kotlin
package eu.kanade.tachiyomi.ui.reader.viewer.panel

import android.graphics.Bitmap

/**
 * Expands an oversized panel into its detected speech bubbles (in reading order), then the
 * full panel, instead of leaving it scaled down to fit both axes at once. Mirrors the existing
 * page-level "show each panel, then the full page" pattern one level down.
 *
 * A panel with no bubbles is never expanded regardless of fit — there's no dialogue continuity
 * to preserve, so it's shown as a single stop at whatever scale is achievable (same as today's
 * behavior). The [PanelRect.FULL_PAGE] "no real panels detected" fallback is never expanded
 * either, checked explicitly and first — see [Panel.bounds] doc and the design spec
 * (docs/superpowers/specs/2026-08-26-guided-view-panel-fit-design.md) for why this must be a
 * hard, explicit guard rather than an incidental consequence of the fit-quality check below.
 *
 * The fit-quality check is a deliberate simplification versus [ReaderPageImageView]'s own
 * `panelStopTarget()` scale math, which also factors in the page's real pixel aspect ratio.
 * Using only the panel's own normalized aspect ratio against the viewport's avoids needing the
 * page's real decoded pixel dimensions plumbed through the (cached) detection pipeline for what
 * is fundamentally a "does this shape mismatch this orientation" question. It's also a
 * deliberately *different* question from `panelStopTarget()`'s own tall/narrow-panel cap: that
 * one exists because such a panel renders too large/dominant at fit-both-axes scale; this one
 * exists because a panel whose shape badly mismatches the viewport's own shape renders too
 * *small* on whichever axis binds the fit-scale, prompting rotation to get more room on that
 * axis. They're solving different problems and reuse none of each other's constants. Per the
 * design spec's testing section, the exact threshold constants here are a starting point to be
 * tuned against real captured pages, not a final, precise formula.
 */
object SpeechBubblePanelSubStopGenerator : PanelSubStopGenerator {

    /**
     * How far the panel's own aspect ratio (width:height) is allowed to diverge from the
     * viewport's own aspect ratio, as a ratio of the two, before the panel counts as a poor fit.
     * A panel whose aspect is between [MIN_FIT_RATIO] and [MAX_FIT_RATIO] times the viewport's
     * own aspect is considered to use the screen reasonably well; further outside that range
     * means one axis will end up significantly under-using the available space once the other
     * axis binds the fit-scale (e.g. a wide/short panel in a tall/narrow portrait viewport ends
     * up constrained by the scarce width, rendering small with a lot of unused vertical margin).
     */
    private const val MIN_FIT_RATIO = 0.4f
    private const val MAX_FIT_RATIO = 2.5f

    override suspend fun generate(
        panel: Panel,
        direction: PanelDirection,
        viewWidth: Int,
        viewHeight: Int,
        cropPanel: suspend () -> Bitmap?,
    ): List<PanelRect> {
        if (panel.bounds == PanelRect.FULL_PAGE) return emptyList()
        if (panel.bubbles.isEmpty()) return emptyList()
        if (fitsCurrentOrientation(panel.bounds, viewWidth, viewHeight)) return emptyList()
        return panel.bubbles + panel.bounds
    }

    private fun fitsCurrentOrientation(bounds: PanelRect, viewWidth: Int, viewHeight: Int): Boolean {
        if (bounds.height <= 0f || viewHeight <= 0) return true
        val panelAspect = bounds.width / bounds.height
        val viewportAspect = viewWidth.toFloat() / viewHeight.toFloat()
        val mismatch = panelAspect / viewportAspect
        return mismatch in MIN_FIT_RATIO..MAX_FIT_RATIO
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "eu.kanade.tachiyomi.ui.reader.viewer.panel.SpeechBubblePanelSubStopGeneratorTest"`
Expected: PASS, all 4 tests green.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/panel/SpeechBubblePanelSubStopGenerator.kt \
  app/src/test/java/eu/kanade/tachiyomi/ui/reader/viewer/panel/SpeechBubblePanelSubStopGeneratorTest.kt
git commit -m "feat(panel): add SpeechBubblePanelSubStopGenerator"
```

---

### Task 4: `panelIndexForFlatStop()` and the grow/shrink resume rule

**Files:**
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/panel/Panel.kt`
- Test: `app/src/test/java/eu/kanade/tachiyomi/ui/reader/viewer/panel/PanelTest.kt`

**Interfaces:**
- Produces: `List<Panel>.panelIndexForFlatStop(flatIndex: Int, showIntro: Boolean, showOutro: Boolean): Int?` — `null` for an intro/outro full-page bracket stop, which belongs to no panel.
- Produces: `List<Panel>.resumeIndexAfterReshape(oldFlatIndex: Int, oldPanels: List<Panel>, oldShowIntro: Boolean, oldShowOutro: Boolean, newShowIntro: Boolean, newShowOutro: Boolean): Int` — this list is the *new* flattened panels; returns the flat stop index to resume at, per the grow/shrink rule.

- [ ] **Step 1: Write the failing tests**

Add to `PanelTest.kt`:

```kotlin
@Test
fun `panelIndexForFlatStop maps a flat index back to its owning panel, or null for an intro-outro bracket`() {
    val panels = listOf(
        Panel(bounds = PanelRect(0f, 0f, 0.5f, 1f)), // 1 stop
        Panel(
            bounds = PanelRect(0.5f, 0f, 1f, 1f),
            subStops = listOf(PanelRect(0.5f, 0f, 0.7f, 1f), PanelRect(0.5f, 0f, 1f, 1f)),
        ), // 2 stops
    )
    // Flattened with intro+outro: [FULL_PAGE, panel0, panel1-sub0, panel1-sub1, FULL_PAGE]

    assertEquals(null, panels.panelIndexForFlatStop(0, showIntro = true, showOutro = true)) // intro bracket
    assertEquals(0, panels.panelIndexForFlatStop(1, showIntro = true, showOutro = true))
    assertEquals(1, panels.panelIndexForFlatStop(2, showIntro = true, showOutro = true))
    assertEquals(1, panels.panelIndexForFlatStop(3, showIntro = true, showOutro = true))
    assertEquals(null, panels.panelIndexForFlatStop(4, showIntro = true, showOutro = true)) // outro bracket
}

@Test
fun `resumeIndexAfterReshape resumes at the panel's first new stop when its stop count grew`() {
    val oldPanels = listOf(Panel(bounds = PanelRect(0f, 0f, 1f, 0.15f))) // 1 stop, plain view
    val newPanels = listOf(
        Panel(
            bounds = PanelRect(0f, 0f, 1f, 0.15f),
            subStops = listOf(
                PanelRect(0.05f, 0.05f, 0.25f, 0.15f),
                PanelRect(0.7f, 0.05f, 0.9f, 0.15f),
                PanelRect(0f, 0f, 1f, 0.15f),
            ),
        ), // grew to 3 stops
    )

    val resumeIndex = newPanels.resumeIndexAfterReshape(
        oldFlatIndex = 0,
        oldPanels = oldPanels,
        oldShowIntro = false,
        oldShowOutro = false,
        newShowIntro = false,
        newShowOutro = false,
    )

    assertEquals(0, resumeIndex) // first of the panel's new stops (bubble1), not the trailing full-panel reveal
}

@Test
fun `resumeIndexAfterReshape resumes at the panel's single stop when its stop count shrank`() {
    val oldPanels = listOf(
        Panel(
            bounds = PanelRect(0f, 0f, 1f, 0.15f),
            subStops = listOf(
                PanelRect(0.05f, 0.05f, 0.25f, 0.15f),
                PanelRect(0.7f, 0.05f, 0.9f, 0.15f),
                PanelRect(0f, 0f, 1f, 0.15f),
            ),
        ),
    )
    val newPanels = listOf(Panel(bounds = PanelRect(0f, 0f, 1f, 0.15f))) // shrank to 1 stop

    val resumeIndex = newPanels.resumeIndexAfterReshape(
        oldFlatIndex = 1, // was on the second bubble
        oldPanels = oldPanels,
        oldShowIntro = false,
        oldShowOutro = false,
        newShowIntro = false,
        newShowOutro = false,
    )

    assertEquals(0, resumeIndex) // the panel's only remaining stop
}

@Test
fun `resumeIndexAfterReshape resumes at the same bubble when the panel's stop count is unchanged`() {
    val bubble1 = PanelRect(0.05f, 0.05f, 0.25f, 0.15f)
    val bubble2 = PanelRect(0.7f, 0.05f, 0.9f, 0.15f)
    val bounds = PanelRect(0f, 0f, 1f, 0.15f)
    val oldPanels = listOf(Panel(bounds = bounds, subStops = listOf(bubble1, bubble2, bounds)))
    // Same bubbles, same order — as they would be across a rotation, since bubble rects are
    // orientation-agnostic cached data.
    val newPanels = listOf(Panel(bounds = bounds, subStops = listOf(bubble1, bubble2, bounds)))

    val resumeIndex = newPanels.resumeIndexAfterReshape(
        oldFlatIndex = 1, // was on bubble2
        oldPanels = oldPanels,
        oldShowIntro = false,
        oldShowOutro = false,
        newShowIntro = false,
        newShowOutro = false,
    )

    assertEquals(1, resumeIndex) // still bubble2, not reset to bubble1
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "eu.kanade.tachiyomi.ui.reader.viewer.panel.PanelTest"`
Expected: FAIL with "unresolved reference" for both new functions.

- [ ] **Step 3: Implement both functions in `Panel.kt`**

Add below `flattenToStops`:

```kotlin
/**
 * Maps a flat stop index (as produced by [flattenToStops] with the same [showIntro]/[showOutro])
 * back to the index of the panel in this list that owns it, or `null` if [flatIndex] is an
 * intro/outro full-page bracket stop, which belongs to no panel.
 */
fun List<Panel>.panelIndexForFlatStop(flatIndex: Int, showIntro: Boolean, showOutro: Boolean): Int? {
    val stopsPerPanel = map { it.subStops.ifEmpty { listOf(it.bounds) }.size }
    if (stopsPerPanel.size == 1 && this.single().bounds == PanelRect.FULL_PAGE && stopsPerPanel.single() == 1) {
        // flattenToStops' own no-duplicate-fallback rule: exactly one stop, no brackets at all.
        return if (flatIndex == 0) 0 else null
    }
    var cursor = if (showIntro) 1 else 0
    for ((panelIndex, count) in stopsPerPanel.withIndex()) {
        if (flatIndex in cursor until cursor + count) return panelIndex
        cursor += count
    }
    return null // falls in the outro bracket (or out of range)
}

/**
 * Resumes across a stop list that may have reshaped (a preference toggle changed, or the
 * device rotated) — this receiver is the *new* flattened panel list. Finds which panel owned
 * [oldFlatIndex] under [oldPanels], then: if that panel's stop count grew, resumes at its first
 * new stop; otherwise (shrank or unchanged), resumes at the nearest-by-distance stop among only
 * that panel's new stops. See the design spec's "Resuming across a stop-list reshape" section
 * for why one rule serves both triggers.
 */
fun List<Panel>.resumeIndexAfterReshape(
    oldFlatIndex: Int,
    oldPanels: List<Panel>,
    oldShowIntro: Boolean,
    oldShowOutro: Boolean,
    newShowIntro: Boolean,
    newShowOutro: Boolean,
): Int {
    val ownerPanelIndex = oldPanels.panelIndexForFlatStop(oldFlatIndex, oldShowIntro, oldShowOutro)
        ?: return 0
    val oldStopsForOwner = oldPanels[ownerPanelIndex].subStops.ifEmpty { listOf(oldPanels[ownerPanelIndex].bounds) }
    val oldAnchorRect = run {
        val cursorStart = (if (oldShowIntro) 1 else 0) +
            oldPanels.take(ownerPanelIndex).sumOf { it.subStops.ifEmpty { l -> listOf(it.bounds) }.size }
        oldStopsForOwner[oldFlatIndex - cursorStart]
    }

    val newStopsPerPanel = map { it.subStops.ifEmpty { listOf(it.bounds) } }
    val newStopsForOwner = newStopsPerPanel.getOrNull(ownerPanelIndex) ?: return 0
    val newCursorStart = (if (newShowIntro) 1 else 0) +
        newStopsPerPanel.take(ownerPanelIndex).sumOf { it.size }

    return if (newStopsForOwner.size > oldStopsForOwner.size) {
        newCursorStart
    } else {
        val nearestLocalIndex = newStopsForOwner.indices.minByOrNull { i ->
            val s = newStopsForOwner[i]
            val dx = s.centerX - oldAnchorRect.centerX
            val dy = s.centerY - oldAnchorRect.centerY
            dx * dx + dy * dy
        } ?: 0
        newCursorStart + nearestLocalIndex
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "eu.kanade.tachiyomi.ui.reader.viewer.panel.PanelTest"`
Expected: PASS, all tests (existing + 5 new) green.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/panel/Panel.kt \
  app/src/test/java/eu/kanade/tachiyomi/ui/reader/viewer/panel/PanelTest.kt
git commit -m "feat(panel): add panelIndexForFlatStop and the grow/shrink resume rule"
```

---

### Task 5: New preference + wire the generator into `PagerPageHolder`

**Files:**
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/reader/setting/ReaderPreferences.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/PagerPageHolder.kt`

**Interfaces:**
- Consumes: `SpeechBubblePanelSubStopGenerator.generate(...)` (Task 3), `ReaderPageImageView.setPanelStops(stops, anchorRect, forceFirstStop)` and `.currentPanelStopRect()` (existing, unchanged).
- Produces: `ReaderPreferences.panelByPanelBubbleStopsEnabled(): Preference<Boolean>`

- [ ] **Step 1: Add the preference**

In `ReaderPreferences.kt`, add near the other `panelByPanel*` preferences (e.g. next to `panelByPanelShowFullPageOutro`):

```kotlin
/** Off by default: expands an oversized panel into its detected speech bubbles before the full panel, instead of leaving it scaled to fit both axes. See SpeechBubblePanelSubStopGenerator. */
fun panelByPanelBubbleStopsEnabled() = preferenceStore.getBoolean("pref_panel_by_panel_bubble_stops_enabled", false)
```

- [ ] **Step 2: Wire the generator into `loadPanels()`**

In `PagerPageHolder.kt`, this holder **is** a `ReaderPageImageView` (its own `width`/`height` are the current view dimensions), so no new plumbing is needed to reach them. Change:

```kotlin
private suspend fun loadPanels(
    viewer: PanelByPanelViewer,
    imageBytes: Buffer,
    anchorRect: PanelRect? = null,
    forceFirstStop: Boolean = false,
) {
    val panels = viewer.panelDetector.detect(page, imageBytes, viewer.panelDirection)
    detectedPanels = panels
    val stops = panels.flattenToStops(
        // Only the chapter's first page gets the reveal — showIntro isn't a "every page"
        // toggle, it's specifically for orienting the reader when a new chapter begins.
        showIntro = viewer.readerPreferences.panelByPanelShowFullPageIntro.get() && page.index == 0,
        showOutro = viewer.readerPreferences.panelByPanelShowFullPageOutro.get(),
    )
    withUIContext {
        setPanelStops(stops, anchorRect = anchorRect, forceFirstStop = forceFirstStop)
```

to:

```kotlin
private suspend fun loadPanels(
    viewer: PanelByPanelViewer,
    imageBytes: Buffer,
    anchorRect: PanelRect? = null,
    forceFirstStop: Boolean = false,
) {
    val panels = viewer.panelDetector.detect(page, imageBytes, viewer.panelDirection)
    detectedPanels = expandForCurrentView(panels, viewer)
    val stops = detectedPanels!!.flattenToStops(
        // Only the chapter's first page gets the reveal — showIntro isn't a "every page"
        // toggle, it's specifically for orienting the reader when a new chapter begins.
        showIntro = viewer.readerPreferences.panelByPanelShowFullPageIntro.get() && page.index == 0,
        showOutro = viewer.readerPreferences.panelByPanelShowFullPageOutro.get(),
    )
    withUIContext {
        setPanelStops(stops, anchorRect = anchorRect, forceFirstStop = forceFirstStop)
```

(leave the rest of the function, and the closing brace, unchanged).

Add a new private helper right below `loadPanels`:

```kotlin
/**
 * Runs [SpeechBubblePanelSubStopGenerator] per panel against this holder's *current* view
 * dimensions when the feature is enabled, producing a fresh [Panel] list with [Panel.subStops]
 * populated accordingly. Always orientation-agnostic detection stays cached; this step never
 * is — it re-runs every time this is called (initial load, direction change, rotation-fresh
 * holder, or the reactive toggle in [bubbleStopsJob]).
 */
private suspend fun expandForCurrentView(panels: List<Panel>, viewer: PanelByPanelViewer): List<Panel> {
    if (!viewer.readerPreferences.panelByPanelBubbleStopsEnabled().get()) return panels
    return panels.map { panel ->
        val subStops = SpeechBubblePanelSubStopGenerator.generate(
            panel, viewer.panelDirection, width, height,
        ) { null }
        panel.copy(subStops = subStops)
    }
}
```

- [ ] **Step 3: Update `refreshPanels()` to also go through the expansion step**

`refreshPanels()` calls `loadPanels(...)` internally, which now always calls `expandForCurrentView` — no separate change needed there. Verify this by reading the current `refreshPanels()` body: it should already route through the updated `loadPanels`.

- [ ] **Step 4: Compile-check**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/eu/kanade/tachiyomi/ui/reader/setting/ReaderPreferences.kt \
  app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/PagerPageHolder.kt
git commit -m "feat(reader): add panelByPanelBubbleStopsEnabled preference, wire generator into panel loading"
```

---

### Task 6: Reactive `bubbleStopsJob` for mid-read toggling

**Files:**
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/PagerPageHolder.kt`

**Interfaces:**
- Consumes: `List<Panel>.resumeIndexAfterReshape(...)` (Task 4), `ReaderPageImageView.setPanelStops(stops, anchorRect, forceFirstStop)`, `panelStopIndex` (existing private field — this task adds a way to read it, see Step 1).

- [ ] **Step 1: Expose the current flat stop index for the resume calculation**

`ReaderPageImageView.panelStopIndex` is currently `private`. Change its visibility in `ReaderPageImageView.kt`:

```kotlin
private var panelStopIndex: Int = -1
```

to:

```kotlin
internal var panelStopIndex: Int = -1
    private set
```

(readable from the same module, e.g. `PagerPageHolder`, but still not externally settable — matches how `currentPanelStopRect()` already exposes read-only derived state).

- [ ] **Step 2: Add the `bubbleStopsJob` field and cancel it in `onDetachedFromWindow`**

In `PagerPageHolder.kt`, add alongside `introOutroJob`/`directionJob`:

```kotlin
private var bubbleStopsJob: Job? = null
```

In `onDetachedFromWindow()`, add alongside the existing `introOutroJob?.cancel()` / `directionJob?.cancel()` lines:

```kotlin
bubbleStopsJob?.cancel()
bubbleStopsJob = null
```

- [ ] **Step 3: Start the reactive collector in `init`**

Add right after the existing `introOutroJob = scope.launch { ... }` block in `init`:

```kotlin
bubbleStopsJob = scope.launch {
    viewer.readerPreferences.panelByPanelBubbleStopsEnabled().changes().drop(1).collectLatest {
        val oldPanels = detectedPanels ?: return@collectLatest
        val oldFlatIndex = panelStopIndex
        val oldShowIntro = viewer.readerPreferences.panelByPanelShowFullPageIntro.get() && page.index == 0
        val oldShowOutro = viewer.readerPreferences.panelByPanelShowFullPageOutro.get()

        val newPanels = expandForCurrentView(oldPanels.map { it.copy(subStops = emptyList()) }, viewer)
        detectedPanels = newPanels
        val newStops = newPanels.flattenToStops(showIntro = oldShowIntro, showOutro = oldShowOutro)
        val resumeIndex = newPanels.resumeIndexAfterReshape(
            oldFlatIndex = oldFlatIndex,
            oldPanels = oldPanels,
            oldShowIntro = oldShowIntro,
            oldShowOutro = oldShowOutro,
            newShowIntro = oldShowIntro,
            newShowOutro = oldShowOutro,
        )
        setPanelStops(newStops, anchorRect = newStops.getOrNull(resumeIndex))
    }
}
```

(`.drop(1)` mirrors `directionJob`'s existing pattern of skipping the initial replay — this only needs to react to an actual later toggle, not the value already in effect when the holder was created, which `expandForCurrentView` already accounted for during the initial `loadPanels()` call. `oldPanels.map { it.copy(subStops = emptyList()) }` resets to un-expanded panels first, since `expandForCurrentView` always computes `subStops` fresh from scratch rather than incrementally.)

`setPanelStops`'s `anchorRect` parameter expects a `PanelRect`, not a raw index — passing `newStops.getOrNull(resumeIndex)` as the anchor makes `setPanelStops` itself call `nearestPanelStopIndex` against it, which will trivially resolve back to `resumeIndex` since it's an exact rect match already present in `newStops`. This reuses `setPanelStops`'s existing code path rather than needing a new "set by raw index" entry point.

- [ ] **Step 4: Compile-check**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Manual on-device verification**

This step has no automated test — the grow/shrink *logic* is already unit-tested (Task 4), but wiring it to the live preference and view requires a real device:
1. Find or construct a page with a wide/short (or tall/narrow) panel containing 2+ speech bubbles.
2. With the preference off, confirm the panel renders as a single stop (today's behavior).
3. While viewing that panel, turn the preference on from Settings. Confirm the reader immediately jumps to that panel's first bubble, not the full panel.
4. Advance through the bubbles, then turn the preference off mid-sequence. Confirm it immediately collapses to the panel's single plain view.
5. Per the design spec's testing section, `SpeechBubblePanelSubStopGenerator`'s `MIN_FIT_RATIO`/`MAX_FIT_RATIO` constants (Task 3) are a theoretical starting point, not tuned against real pages yet — pull real panel coordinates for a genuinely borderline panel (one that looks "kind of small but not extreme") via the existing `panelDebug` logcat lines, and confirm the generator's decision on that real panel matches what actually looks right on-device. Adjust the two constants if it doesn't, the same iterative way every other threshold in this pipeline (`BASE_MARGIN`, `TALL_PANEL_ASPECT_THRESHOLD`, etc.) was originally tuned.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/ReaderPageImageView.kt \
  app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/PagerPageHolder.kt
git commit -m "feat(reader): react to the bubble-stops preference changing mid-read"
```

---

### Task 7: Rotation-restore fix

**Files:**
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/ReaderPageImageView.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/PagerPageHolder.kt`

**Interfaces:**
- Consumes: `List<Panel>.resumeIndexAfterReshape(...)` (Task 4).
- Produces: `ReaderViewModel.PanelStopPosition(pageIndex: Int, stopIndex: Int, anchorRect: PanelRect?)` — `anchorRect` is new.
- Produces: `ReaderViewModel.savePanelStop(pageIndex: Int, stopIndex: Int, anchorRect: PanelRect?)` — signature widened.

- [ ] **Step 1: Widen `PanelStopPosition` and `savePanelStop`**

In `ReaderViewModel.kt`, change:

```kotlin
data class PanelStopPosition(val pageIndex: Int, val stopIndex: Int)
```

to:

```kotlin
data class PanelStopPosition(val pageIndex: Int, val stopIndex: Int, val anchorRect: PanelRect?)
```

and change:

```kotlin
/** Records the panel-by-panel stop currently shown, so it survives the viewer being recreated. */
fun savePanelStop(pageIndex: Int, stopIndex: Int) {
    mutableState.update {
        it.copy(savedPanelStop = PanelStopPosition(pageIndex, stopIndex))
    }
}
```

to:

```kotlin
/** Records the panel-by-panel stop currently shown, so it survives the viewer being recreated. */
fun savePanelStop(pageIndex: Int, stopIndex: Int, anchorRect: PanelRect?) {
    mutableState.update {
        it.copy(savedPanelStop = PanelStopPosition(pageIndex, stopIndex, anchorRect))
    }
}
```

Add the import for `PanelRect` if not already present (`eu.kanade.tachiyomi.ui.reader.viewer.panel.PanelRect`).

- [ ] **Step 2: Update the call site in `PagerPageHolder`**

In `PagerPageHolder.kt`, change:

```kotlin
onPanelStopChanged = { index ->
    // Guard against offscreen-neighbor holders (see isCurrentReaderPage) overwriting
    // the actually-visible page's saved position with their own entry stop.
    if (viewer.isCurrentReaderPage(page)) viewModel.savePanelStop(page.index, index)
}
```

to:

```kotlin
onPanelStopChanged = { index ->
    // Guard against offscreen-neighbor holders (see isCurrentReaderPage) overwriting
    // the actually-visible page's saved position with their own entry stop.
    if (viewer.isCurrentReaderPage(page)) viewModel.savePanelStop(page.index, index, currentPanelStopRect())
}
```

- [ ] **Step 3: Add `panelStopAnchorOverride` to `ReaderPageImageView` and update `setPanelStops`' resume priority**

In `ReaderPageImageView.kt`, add a new field alongside `panelStopIndexOverride`:

```kotlin
var panelStopIndexOverride: Int? = null
```

becomes:

```kotlin
var panelStopIndexOverride: Int? = null

/**
 * Rotation-restore anchor, preferred over [panelStopIndexOverride] when present. A raw saved
 * index isn't safe once a page's stop list can reshape based on view orientation (see
 * SpeechBubblePanelSubStopGenerator) — this stores the actual stop being read instead, resolved
 * back to an index in the new (possibly differently-shaped) list via the same panel-aware
 * grow/shrink rule used for the live preference-toggle case.
 */
var panelStopAnchorOverride: PanelRect? = null
```

Change `setPanelStops`' resume-priority `when` block from:

```kotlin
panelStopIndex = when {
    forceFirstStop -> 0
    anchorRect != null -> nearestPanelStopIndex(anchorRect)
    else -> panelStopIndexOverride?.coerceIn(0, panelStops.lastIndex)
        ?: if (panelStopsEnterForward) 0 else panelStops.lastIndex
}
panelStopIndexOverride = null
```

to:

```kotlin
panelStopIndex = when {
    forceFirstStop -> 0
    anchorRect != null -> nearestPanelStopIndex(anchorRect)
    panelStopAnchorOverride != null -> nearestPanelStopIndex(panelStopAnchorOverride!!)
    else -> panelStopIndexOverride?.coerceIn(0, panelStops.lastIndex)
        ?: if (panelStopsEnterForward) 0 else panelStops.lastIndex
}
panelStopIndexOverride = null
panelStopAnchorOverride = null
```

(`nearestPanelStopIndex` is reused as-is here rather than the full `resumeIndexAfterReshape` grow/shrink rule — this restore path only has the anchor *rect*, not the old panel list's shape, by the time it reaches this generic `ReaderPageImageView` method. The panel-aware grow/shrink resolution happens one layer up, in `PagerPageHolder`, per the next step — this field is just the transport for the resolved anchor rect down to `setPanelStops`.)

- [ ] **Step 4: Resolve the anchor with the grow/shrink rule before restoring, in `PagerPageHolder`**

In `PagerPageHolder.kt`'s `init`, change:

```kotlin
val viewModel = viewer.activity.viewModel
viewModel.state.value.savedPanelStop?.let { saved ->
    if (saved.pageIndex == page.index) panelStopIndexOverride = saved.stopIndex
}
```

to:

```kotlin
val viewModel = viewer.activity.viewModel
viewModel.state.value.savedPanelStop?.let { saved ->
    if (saved.pageIndex == page.index) {
        if (saved.anchorRect != null) {
            panelStopAnchorOverride = saved.anchorRect
        } else {
            panelStopIndexOverride = saved.stopIndex
        }
    }
}
```

Note: this restores using the *saved* anchor rect directly via `nearestPanelStopIndex` (Step 3's generic path in `ReaderPageImageView`), not the fuller `resumeIndexAfterReshape` grow/shrink rule — that rule needs the *old* panel list's shape to compare against, which isn't available at rotation-restore time (the old `PagerPageHolder` instance, and its `detectedPanels`, is gone; this is a brand new holder). This is an accepted, smaller gap than the pre-existing behavior: a plain nearest-rect match already correctly finds the same bubble if it's still present in the new orientation's expansion (bubble rects are identical either side, since they're orientation-agnostic cached data) — the only case it doesn't handle ideally is the panel growing across the rotation and the saved anchor happening to be that panel's old single full-bounds stop, where nearest-rect could land on the new trailing full-panel reveal instead of the first bubble. Documented as a known limitation, not silently glossed over: if this turns out to matter in practice, the fix is threading the pre-rotation panel list through `savedPanelStop` too, deferred until it's confirmed to actually happen (this project's `PanelStopPosition` already crosses a full `PagerPageHolder` recreation, so plumbing a whole `List<Panel>` through saved `ViewModel` state is a bigger change than is justified without confirming the edge case is real).

- [ ] **Step 5: Compile-check**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Manual on-device verification**

1. With the bubble-stops preference on, open a chapter with an oversized panel with bubbles in portrait, advance to its second bubble.
2. Rotate to landscape. Confirm the reader resumes on the same or a reasonable equivalent stop (per the documented limitation above), not a jarring jump to an unrelated panel.
3. Rotate back to portrait. Confirm the same.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt \
  app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/ReaderPageImageView.kt \
  app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/PagerPageHolder.kt
git commit -m "fix(reader): restore panel-by-panel position by anchor rect across rotation, not raw index"
```

---

### Task 8: Settings UI toggle

**Files:**
- Modify: `app/src/main/java/eu/kanade/presentation/reader/settings/ReadingModePage.kt`
- Modify: `i18n/src/commonMain/moko-resources/base/strings.xml`

**Interfaces:**
- Consumes: `ReaderPreferences.panelByPanelBubbleStopsEnabled()` (Task 5).

- [ ] **Step 1: Add the string resource**

In `strings.xml`, add near the other `pref_panel_by_panel_*` strings (e.g. next to `pref_panel_by_panel_show_full_page_outro`):

```xml
<string name="pref_panel_by_panel_bubble_stops_enabled">Step through speech bubbles in oversized panels</string>
```

- [ ] **Step 2: Add the `CheckboxItem` to `PanelByPanelViewerSettings`**

In `ReadingModePage.kt`, add to the `PanelByPanelViewerSettings` composable, alongside the existing `CheckboxItem`s (e.g. right after the `pref_panel_by_panel_show_full_page_outro` one):

```kotlin
CheckboxItem(
    label = stringResource(MR.strings.pref_panel_by_panel_bubble_stops_enabled),
    pref = viewModel.preferences.panelByPanelBubbleStopsEnabled(),
)
```

- [ ] **Step 3: Compile-check**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Manual verification**

Open Settings > Reader > Guided view (or wherever `PanelByPanelViewerSettings` renders) and confirm the new toggle appears, labeled correctly, and reading its state reflects `ReaderPreferences.panelByPanelBubbleStopsEnabled()`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/eu/kanade/presentation/reader/settings/ReadingModePage.kt \
  i18n/src/commonMain/moko-resources/base/strings.xml
git commit -m "feat(reader): expose the bubble-stops toggle in Guided view settings"
```
