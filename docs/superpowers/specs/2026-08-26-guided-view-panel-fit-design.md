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

### Reusing the existing `PanelSubStopGenerator` extension point

There's already an interface built for exactly this purpose,
`PanelSubStopGenerator` (`PanelSubStopGenerator.kt`):

```kotlin
interface PanelSubStopGenerator {
    suspend fun generate(panel: PanelRect, direction: PanelDirection, cropPanel: suspend () -> Bitmap?): List<PanelRect>
}
```

("Returns ordered sub-stops for panel, or an empty list if it doesn't
need any... When non-empty, the last stop is always the full panel
bounds.") — exactly the "bubbles then full panel" contract this feature
needs. There's also an existing implementation,
`GeometricPanelSubStopGenerator`, which splits a wide panel into three
geometric left/center/right stops — this is dead code (defined and
tested, but never called from anywhere in the app) implementing the
approach explicitly rejected during design (an arbitrary geometric cut
risks severing scene context). It gets deleted as part of this work,
along with its test — this feature adds a new implementation of the same
interface rather than reviving the old one.

New implementation, e.g. `SpeechBubblePanelSubStopGenerator`, ignores
`cropPanel` entirely (no OCR/content-inspection needed — bubble
*locations* are already known from detection, see above) and instead:

- Takes the panel's already-associated `bubbles` (see above) plus the
  current view's fit-quality check (reusing/extracting the existing
  logic already implicit in `panelStopTarget()`'s scale-capping) as
  additional inputs beyond the interface's own parameters.
- Returns `emptyList()` if the panel fits the current view well, has no
  bubbles, **or is the `PanelRect.FULL_PAGE` "no real panels detected"
  fallback sentinel** — this last check is a hard, explicit guard, not
  an incidental consequence of aspect-ratio math. It's the fix for a
  real regression from an earlier, abandoned version of this idea: the
  existing `flattenToStops()`'s own "was this just the full-page
  fallback" check (`if (stops.size == 1 && stops.single() ==
  PanelRect.FULL_PAGE) return stops`) runs *after* flattening, so if a
  generator had already split that fallback sentinel upstream, this
  check would never catch it (the flattened list would no longer have
  exactly one stop). The guard belongs at the generator call site,
  before generation ever runs, not as a post-hoc check.
- Otherwise returns `bubbles + panel` (bubbles first, full panel last).

Call site: a new step between "get this page's `List<Panel>`" (from
cache or fresh detection) and the existing
`List<Panel>.flattenToStops(...)` call in
`PagerPageHolder.loadPanels()`/`refreshPanels()` — the same place that
already has the real, current `ReaderPageImageView`'s width/height
available — runs the generator per panel and sets `subStops` from its
result. `flattenToStops()` itself needs **no changes** — its existing
`flatMap { panel -> panel.subStops.ifEmpty { listOf(panel.bounds) } }`
already does exactly the right thing once `subStops` is populated
correctly upstream.

This keeps the cache fully orientation-agnostic: `bubbles` is cached,
`subStops` is always computed fresh (by running the generator live) and
never persisted.

### New preference

A `ReaderPreferences` boolean, off by default (e.g.
`panelByPanelBubbleStopsEnabled`), gating whether
`SpeechBubblePanelSubStopGenerator` runs at all — when off, every panel's
`subStops` stays empty (today's exact behavior). Exposed in the Guided
View settings alongside the other panel-by-panel toggles.

### Resuming across a stop-list reshape: one rule for both toggling and rotating

Two different triggers can change a page's stop list *shape* while the
reader is mid-page: toggling the preference live (handled by a new
`bubbleStopsJob` in `PagerPageHolder`, reacting the same way the existing
`introOutroJob` reacts to the intro/outro toggle), and rotating the
device (a fresh `PagerPageHolder` re-detects/re-flattens against the new
orientation's dimensions — see below). Both need the same answer to "the
stop list for this page just changed shape — where do I resume?", so
they should share one rule and one helper rather than two bespoke ones.

Plain `anchorRect`-based nearest-stop lookup (today's
`nearestPanelStopIndex`, matching by rect distance) is *almost* right,
but has one blind spot: when a panel's stop count for you specifically
*grows* (e.g. it had one stop — its full bounds — and now has three:
two bubbles plus that same full bounds), the trailing full-panel stop is
geometrically identical to whatever single stop you were just viewing.
Nearest-by-distance would resolve to that trailing stop, not `bubble1` —
backwards, since growing should start the new sequence from the
beginning. When a panel's stop count *shrinks or stays the same*,
nearest-by-distance already gives the right answer (shrinking to one
stop trivially matches; staying the same size means the underlying
bubble rects are identical either side, since `bubbles` is
orientation-agnostic cached data — this is exactly what makes rotation
resume correctly find the same bubble you were on, not just the same
panel).

**The rule:** compare the current stop's owning panel's *old* stop count
to its *new* stop count.
- If new > old (this panel just grew), resume at that panel's **first**
  new stop.
- Otherwise (same size, or shrank), resume at the **nearest-by-distance**
  stop among that panel's new stops only (not the whole page's stops).

This needs one supporting piece: a way to know which original panel a
flattened stop index belongs to, since `flattenToStops()`'s
`List<PanelRect>` return type discards that association once flattened.
Add a helper alongside it:
`List<Panel>.panelIndexForFlatStop(flatIndex: Int, showIntro: Boolean, showOutro: Boolean): Int?`
(`null` for an intro/outro full-page bracket stop, which belongs to no
panel). With it, both the toggle handler and the rotation-restore path
can: find which panel owned the old stop, count that panel's stops in
the old and new flattened lists, and apply the rule above — restricting
`nearestPanelStopIndex`'s search to just the flat-index range belonging
to that one panel when the "same or shrank" branch applies.

### Rotation-restore gap: index-based restore isn't safe anymore

`ReaderViewModel.PanelStopPosition(pageIndex: Int, stopIndex: Int)` is
today's entire rotation-restore mechanism — `PagerPageHolder.init` restores
by setting `panelStopIndexOverride = saved.stopIndex` directly. This is
only safe if the stop list has the same shape before and after rotation.
With this feature on, it usually won't be: a panel that renders as one
stop in portrait can become three (two bubbles + full panel) in landscape,
so a raw saved index can silently land on the wrong panel or bubble after
a rotation.

Fix: `PanelStopPosition` gains an `anchorRect: PanelRect?` field
(`ReaderViewModel.savePanelStop` already runs from
`PagerPageHolder.onPanelStopChanged`, which has `currentPanelStopRect()`
available to pass through). On restore, `PagerPageHolder.init` stashes
this as a new `panelStopAnchorOverride: PanelRect?` field (parallel to
today's `panelStopIndexOverride: Int?`) rather than setting the raw index
directly. `setPanelStops()`'s resume-priority order becomes: explicit
`anchorRect` parameter (settings-change resume) > `panelStopAnchorOverride`
(rotation resume, using the same panel-aware rule above instead of a
bare `nearestPanelStopIndex` call) > `panelStopIndexOverride` (kept only
as a last-resort fallback for the rare case `currentPanelStopRect()`
returned null when saving, e.g. detection hadn't produced any stops
yet) > the existing enter-forward default. `savedPanelStop` only needs
to survive a configuration change within the same app process (it's
plain `ViewModel` state, never serialized to disk), so there's no
existing-persisted-data shape to migrate.

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
- `SpeechBubblePanelSubStopGenerator`'s decision logic: unit-testable with
  synthetic view dimensions and panel rects covering the portrait/landscape
  cases already handled by `panelStopTarget()`, plus the new in-between
  cases this feature targets — and explicitly assert it returns
  `emptyList()` for a `PanelRect.FULL_PAGE` panel regardless of view
  dimensions or bubble content, covering the exact regression this design
  guards against.
- On-device validation: per this project's established methodology, pull
  real captured coordinates (existing `panelDebug`/`PanelOrderDebug`
  logcat lines) for a genuinely oversized panel before considering this
  done — don't tune the fit-quality threshold from theory alone.
- Rotation-restore: manually verify resuming mid-bubble-stop across a
  rotation lands on the same (or nearest equivalent) content, not a
  different panel entirely.
- `panelIndexForFlatStop()` and the grow/shrink resume rule: unit-testable
  directly — given an old flattened list, an old stop index, and a new
  flattened list (different shape), assert the resume index per the rule
  in "Resuming across a stop-list reshape" above. Explicitly cover, as
  separate cases (these are easy to get backwards, so assert them
  separately rather than spot-checking on-device):
  - Grow: turning the preference on while viewing a plain panel resumes
    at that panel's first bubble, not its trailing full-panel reveal.
  - Shrink: turning it off mid-bubble-sequence resumes at that panel's
    single collapsed stop.
  - Same size (the rotation case where the panel is expanded both
    before and after): resumes at the same bubble, via nearest-distance
    restricted to that panel's stops, not just the first one.
