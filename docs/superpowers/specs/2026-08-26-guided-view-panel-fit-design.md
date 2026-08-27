# Guided View: Bubble-Aware Panel Fit

## Overview

Part 1 of a broader Guided View (panel-by-panel reader) rework. The other
three pieces — entry/exit flow and transition feel, the navigation
state-model refactor, and a real interactive zoom feature — are separate,
later, independent efforts and are explicitly out of scope here.

**Problem:** in some chapters, panels whose natural aspect ratio doesn't
match the device's current orientation get scaled down to fit both axes at
once, sometimes shrinking enough that dialogue is hard to read without
physically rotating the device. `panelStopTarget()` (`ReaderPageImageView.kt`)
already has orientation-aware caps for two specific cases (an extreme
tall/narrow panel in portrait, a panel matching the landscape screen's own
aspect ratio) but panels in between those cases can still render too small
to read comfortably, forcing rotation to compensate.

**Fix:** when a panel doesn't fit the current orientation well, and it
contains speech bubbles, step through each bubble individually (each
scaled to be comfortably readable) before revealing the full panel — the
same "detail stops, then the whole thing" pattern already used at the page
level (`panelByPanelShowFullPageIntro`/`Outro`), one level down. A panel
with no bubbles (a wordless/scenery panel meant to be taken in as a whole)
isn't split at all — there's no dialogue continuity to preserve, so it
just renders at whatever fit-scale is achievable today.

This requires no new ML work: the existing panel detector already runs a
two-class YOLO model (Panel, Text) in one inference pass per page. Bubble
locations are already computed as `result.bubbles` and passed into
`PanelPipeline.zoomRegions()` — currently used only to influence panel
merge/pad decisions, then discarded. This feature retains and exposes
them instead of throwing them away.

## Goals

- A new, off-by-default reader preference. When off, behavior is
  identical to today. When on: an oversized panel with detected bubbles
  is stepped through bubble-by-bubble, then the full panel, before moving
  on to the next panel.
- Bubble locations are associated with their owning *final* panel (after
  merge/pad/split) and ordered in natural reading order, reusing the
  existing panel-ordering logic if it generalizes cleanly to ordering
  rects within a single panel.
- "Does this panel fit the current orientation well" is decided fresh,
  from the real current view dimensions, every time a page's panels are
  flattened into a stop list — never baked into anything cached. Rotating
  mid-chapter re-decides this correctly.
- Detection and its cache stay exactly as they are: orientation-agnostic,
  keyed by `chapterId/pageIndex/imageHash/detectorVersion` only. Only
  bubble *locations* are new cached data; whether they get used to expand
  a panel into multiple stops is decided later, live.
- Rotating mid-panel-read resumes at the nearest equivalent position even
  if the stop list's shape changed (a panel that was one stop before
  rotation might now be three, or vice versa) — fixing a real gap in the
  existing rotation-restore mechanism (see Architecture).

## Non-goals

- **No real interactive pinch/pan/double-tap zoom.** Explicitly deferred
  to the later navigation-state-model refactor + zoom sub-projects. This
  feature needs zero gesture-handling changes, which is exactly why it's
  being done first.
- **No geometric (position-based) splitting of oversized panels without
  bubbles.** Rejected during design: an arbitrary top/bottom or left/right
  cut risks severing a scene's visual composition, and there's no bubble
  content to justify multiple stops in the first place if there's nothing
  to read across them.
- **No manual "toggle out of bubble-stops into free zoom on this panel"
  escape hatch.** Noted as a future idea (the user wants this eventually,
  for inspecting detail in wordless panels), but explicitly dropped from
  this pass — it's real interactive zoom by another name, and belongs
  with the deferred zoom work, not bundled in here.
- No changes to entry/exit flow, transition animation feel, or the
  underlying discrete-stop-index navigation model itself — all separate,
  later sub-projects.

## Architecture

### Data model: `Panel` gains a `bubbles` field

```kotlin
@Serializable
data class Panel(
    val bounds: PanelRect,
    val bubbles: List<PanelRect> = emptyList(),
    val subStops: List<PanelRect> = emptyList(),
)
```

`bubbles` is the new, cached, orientation-agnostic field: this panel's
detected speech-bubble boxes, in reading order, normalized to page
coordinates (same convention as `bounds`). It's populated once at
detection time and never changes based on orientation.

`subStops` is left as-is — it's already exactly the right mechanism for
this feature and needs no changes to its own logic (see "Reusing
`flattenToStops`" below). The distinction matters: `bubbles` is a
detected, cached *fact* about the page; `subStops` is a *decision* about
how to present it, made fresh at render time from the current view.

### Populating `bubbles`: `PanelPipeline`/`MlPanelBoundaryDetector`

`MlPanelBoundaryDetector.detect()` currently returns `List<PanelRect>`
(`PanelPipeline.zoomRegions(...)`'s final planned panels). It needs to
return enough to reconstruct `List<Panel>` with each final panel's
surviving bubbles attached:

- `PanelPipeline.zoomRegions()` already threads `bubbles` through `pad()`
  and `PanelPlanner.plan()` for padding/merge decisions on the *raw*
  panel list. After the final planned/padded panel list is produced,
  associate each remaining bubble with whichever final panel's `bounds`
  contains its center (same containment test already used elsewhere in
  this pipeline for bubble-to-panel ownership, e.g. the gutter-straddling
  fix in `pad()`).
- Order each panel's associated bubbles in reading order. `PanelOrdering.order()`
  already orders a `List<PanelRect>` by reading direction — check during
  implementation whether it generalizes cleanly to ordering bubbles
  within one panel's local bounds, or needs a narrower variant.
- `PanelDetector.runDetection()` currently does
  `return rects.map { rect -> Panel(rect) }` — update to attach each
  rect's associated bubbles here.

### Deciding expansion: fresh, at flatten time, not cached

A new step runs between "get this page's `List<Panel>`" (from cache or
fresh detection) and the existing `List<Panel>.flattenToStops(...)` call
in `PagerPageHolder.loadPanels()`/`refreshPanels()` — the same place that
already has the real, current `ReaderPageImageView`'s width/height
available:

```kotlin
fun List<Panel>.expandOversizedPanels(viewWidth: Int, viewHeight: Int, enabled: Boolean): List<Panel>
```

For each panel: if `enabled` is true, the panel doesn't fit the current
`viewWidth`/`viewHeight` well (reusing/extracting the existing
fit-quality check already implicit in `panelStopTarget()`'s scale-capping
logic), and `panel.bubbles` is non-empty, set
`subStops = panel.bubbles + panel.bounds` (bubbles first, full panel
last). Otherwise leave `subStops` empty. `flattenToStops()` itself needs
**no changes** — its existing
`flatMap { panel -> panel.subStops.ifEmpty { listOf(panel.bounds) } }`
already does exactly the right thing once `subStops` is populated
correctly upstream.

This keeps the cache fully orientation-agnostic: `bubbles` is cached,
`subStops` is always computed fresh and never persisted.

### New preference

A `ReaderPreferences` boolean, off by default (e.g.
`panelByPanelBubbleStopsEnabled`), gating `enabled` in
`expandOversizedPanels()`. Exposed in the Guided View settings alongside
the other panel-by-panel toggles.

### Toggling mid-read: same panel, not nearest rect

The preference can change while a `PagerPageHolder` is alive and
currently showing one of its stops — same situation the existing
`introOutroJob` in `PagerPageHolder` already handles for the intro/outro
toggle, and it needs the identical reactive treatment: a new
`bubbleStopsJob` collecting `readerPreferences.panelByPanelBubbleStopsEnabled.changes()`,
re-running `expandOversizedPanels()` + `flattenToStops()` against the
already-cached `detectedPanels` and calling `setPanelStops(newStops,
anchorRect = ...)`, exactly like `introOutroJob` does today.

The subtlety: plain `anchorRect`-based nearest-stop lookup (today's
`nearestPanelStopIndex`, matching by rect distance) gives the right
answer when *turning off* — the panel collapses to one stop and that's
trivially the nearest match — but the wrong answer when *turning on*.
Expanding a panel produces `[bubble1, bubble2, ..., full-panel]`, and the
trailing full-panel stop is geometrically identical to whatever single
stop you were just viewing — so nearest-by-distance would resolve to that
trailing stop, not `bubble1`. That's backwards: turning the feature on
mid-panel should start that panel's bubble sequence from the beginning,
and turning it off mid-sequence should always drop you back to that
panel's single plain view.

The correct rule is **same panel, first of its (possibly new) stops** —
not nearest rect. This needs one more piece: a way to know which original
panel a flattened stop index belongs to, since `flattenToStops()`'s
`List<PanelRect>` return type discards that association once flattened.
Add a small helper alongside it, e.g.
`List<Panel>.panelIndexForFlatStop(flatIndex: Int, showIntro: Boolean, showOutro: Boolean): Int?`
(`null` for an intro/outro full-page bracket stop, which belongs to no
panel), so the toggle handler can: look up which panel owned the
*current* stop under the old expansion state, re-flatten under the new
state, then resume at the first flat index that maps back to that same
panel.

### Rotation-restore gap: index-based restore isn't safe anymore

`ReaderViewModel.PanelStopPosition(pageIndex: Int, stopIndex: Int)` is
today's entire rotation-restore mechanism — `PagerPageHolder.init` restores
by setting `panelStopIndexOverride = saved.stopIndex` directly. This is
only safe if the stop list has the same shape before and after rotation.
With this feature on, it usually won't be: a panel that renders as one
stop in portrait can become three (two bubbles + full panel) in landscape,
so a raw saved index can silently land on the wrong panel or bubble after
a rotation.

Fix: extend the save side to also capture the *rect* of the stop being
read (not just its index), and change the restore path to prefer an
anchor-based lookup — reusing the existing `nearestPanelStopIndex(anchor:
PanelRect)` helper already used for the intro/outro-toggle resume case —
falling back to the raw index only if no anchor was saved (e.g. state
persisted before this change).

### `DETECTOR_VERSION`

Per this project's established rule, any pipeline change that alters
output for an already-visited page must bump `DETECTOR_VERSION`
(`PanelDetector.kt`). Adding `bubbles` to what gets cached is exactly that
kind of change — without a bump, every page visited before this ships
would deserialize with `bubbles = emptyList()` (kotlinx.serialization's
default-value fallback for a missing field) and silently never get
bubble-stop expansion until independently re-detected. Bump it in the
same commit as this change, not after.

## Testing

- Bubble-to-panel association and reading-order logic: unit-testable the
  same way `PanelPipelineTest`/`PanelOrderingTest` already test this
  pipeline — synthetic panel/bubble rect fixtures, no device needed.
- `expandOversizedPanels()`'s fit-quality decision: unit-testable with
  synthetic view dimensions and panel rects covering the portrait/landscape
  cases already handled by `panelStopTarget()`, plus the new in-between
  cases this feature targets.
- On-device validation: per this project's established methodology, pull
  real captured coordinates (existing `panelDebug`/`PanelOrderDebug`
  logcat lines) for a genuinely oversized panel before considering this
  done — don't tune the fit-quality threshold from theory alone.
- Rotation-restore: manually verify resuming mid-bubble-stop across a
  rotation lands on the same (or nearest equivalent) content, not a
  different panel entirely.
- `panelIndexForFlatStop()` and the toggle-resume rule: unit-testable
  directly — given an old flattened list, an old stop index, and a new
  flattened list (different shape), assert the resume index is the new
  list's first stop belonging to the same panel. Explicitly cover both
  directions: turning the preference on while viewing a plain panel
  (must resume at that panel's first bubble, not its trailing full-panel
  reveal), and turning it off mid-bubble-sequence (must resume at that
  panel's single collapsed stop). These two directions are easy to get
  backwards, so they should be asserted separately, not just spot-checked
  on-device.
