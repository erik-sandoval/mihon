# CLAUDE.md

Notes for working in this repo, written from real debugging sessions. Read the relevant section before touching the areas below.

## Before implementing a new feature or fix, check for independent components that could interact with it

Don't scope a change to just the file/class you're editing. Before writing code — not after testing reveals a problem — check whether other, independent components in the area also observe or react to the same state/events your change touches (other listeners on the same touch stream, other consumers of the same callback, other code with its own copy of similar logic). This repo has repeatedly had multi-round debugging sessions where a fix to the "obvious" component was correct in isolation but got undermined by a separate, pre-existing component doing its own thing with the same input — see "Reader touch handling: multiple independent components watch the same touch stream" below for a concrete map of one such area. When starting work in a gesture/event-heavy area, look for that area's full set of listeners/observers first.

## Confirm before building and pushing to the phone

Don't run `assembleDebug` + `adb install` on your own initiative after making a change — ask first. Only build and push when the user explicitly asks for it (e.g. "push to phone", "build it", "let's test this"), even mid-session after a string of prior pushes. This applies to rebuild-after-revert too, not just rebuild-after-new-code.

## Reader: `state.manga` changes tear down the whole viewer

`ReaderActivity.kt` has:

```kotlin
viewModel.state
    .map { it.manga }
    .distinctUntilChanged()
    .filterNotNull()
    .onEach { updateViewer() }
    .launchIn(lifecycleScope)
```

`updateViewer()` destroys and recreates the entire `PagerViewer`/adapter. This fires on **any** structural change to the `Manga` object in `ReaderViewModel.state`, not just reading-mode/orientation changes — `Manga` is a data class, so a changed `viewerFlags` bit is enough to make `distinctUntilChanged()` see it as new.

**If you write a function that calls `mutableState.update { it.copy(manga = ...) }` in `ReaderViewModel`, you must also send `Event.ReloadViewerChapters` afterward** (see `setMangaReadingMode`/`setMangaOrientationType` for the established pattern: save `requestedPage` from the current chapter, update state, then `eventChannel.send(Event.ReloadViewerChapters)`). Skipping this leaves the freshly-recreated viewer with no chapters/pages loaded into it — the page goes blank with a stuck spinner, and nothing in the UI signals why. This is easy to miss because the bug only reproduces when the setting is toggled *while a chapter is actively open*, not on next chapter load.

Real incident: `PanelByPanelDirection` (per-series panel-by-panel RTL/LTR override) was added without this reload call. Toggling it while reading silently detached the current page's view and never reattached it. Confirmed via logcat: `state.manga updated` was followed within ~10ms by `PagerPageHolder.onDetachedFromWindow` for the visible pages, with nothing recreating them afterward.

## Panel-by-panel current-panel state does not survive rotation without explicit restore plumbing

Rotating the device recreates the Activity (and with it `PagerViewer`) — a different trigger than the `state.manga` teardown above (configuration change, not a manga-model change), but the same class of "viewer gets rebuilt from scratch" problem. Confirmed bug: rotating away and back while mid-panel (e.g. page 5, panel 4) landed back on panel 1 of that page instead of resuming where you were.

Established fix pattern, now in place:
- `PagerPageHolder`'s panel-stop callback only persists state for the page actually on screen: `onPanelStopChanged = { index -> if (viewer.isCurrentReaderPage(page)) viewModel.savePanelStop(page.index, index) }`. Without the `isCurrentReaderPage` gate, a non-visible/recycled holder (e.g. an offscreen-prefetched neighbor) can clobber the saved stop for the page that's actually visible.
- `Viewer.moveToPage(page, forceEnterForward: Boolean = false)` and `PagerViewer`'s `forceForwardOnNextPageChange` field distinguish "resume at the last saved panel" (default, used by rotation restore) from "enter fresh at panel 1" (`forceEnterForward = true`, used by page-grid selection — see below). Any future "jump to a page" entry point needs to pick the right one of these explicitly rather than relying on the default.

## Page-grid ("show all pages") selection must force forward-entry

`ReaderActivity`'s `onSelectPage` (wired to `PanelPageGridSheet`) must call `moveToPageIndex(index, forceEnterForward = true)`, not the bare default. Confirmed bug without the flag: selecting a page from the grid could land the panel-by-panel viewer in "full page" view — as if you'd already stepped through all its panels — instead of starting at panel 1, because `moveToPage` without `forceEnterForward` preserves whatever panel-stop was previously saved for that page/position rather than starting fresh (same plumbing as the rotation-restore case above, used the opposite way here). Any new "jump to an arbitrary page" entry point (search result, bookmark jump, etc.) should default to `forceEnterForward = true` unless it specifically wants to resume a previous panel-stop.

## Panel-by-panel swipe direction is a fixed physical gesture, not RTL-aware

In `PagerViewer.kt`, the panel-by-panel swipe listener is intentionally **not** conditioned on RTL/LTR:

```kotlin
pager.panelSwipeListener = { leftward ->
    if (leftward) moveRight() else moveLeft()
}
```

Swipe left = forward, swipe right = backward, **for both LTR and RTL** manga. This was confirmed directly by the user after two rounds of "fix" attempts that tried to flip the mapping based on `PanelDirection` — panel order already accounts for reading direction via `PanelOrdering`/`PanelPlanner` upstream, so the swipe gesture itself doesn't need to. Don't reintroduce an RTL-conditional swipe mapping here without re-confirming with the user; it's been wrong twice already.

## Per-manga viewer flag bit-packing (`Manga.viewerFlags`)

Reader settings that are per-series (not global preferences) are packed into the single `viewerFlags` Long column on `mangas`, each with its own enum + bitmask, mirrored by a domain extension in `eu/kanade/domain/manga/model/Manga.kt`:

| Setting | Enum | Mask |
|---|---|---|
| Reading mode (LTR/RTL/webtoon/panel-by-panel/...) | `ReadingMode` | `0x7` (bits 0-2) |
| Screen orientation | `ReaderOrientation` | `0x38` (bits 3-5) |
| Panel-by-panel direction override | `PanelByPanelDirection` | `0xC0` (bits 6-7) |

Next free bit range starts at `0x100` (bit 8). When adding a new per-series toggle, follow this exact pattern: enum with `flagValue`/`MASK`/`fromPreference`, a domain extension property, a `SetMangaViewerFlags.awaitSet*` method, and — per the section above — remember to reload the viewer if the setter is reachable while a chapter is open.

## Panel detection results are cached — bump `DETECTOR_VERSION` on any pipeline change

`PanelDetector.kt` caches detection output in `PanelCacheRepository`, keyed by `chapterId, pageIndex, hash, version` where `version = DETECTOR_VERSION * 10 + direction.ordinal`. Any change to detection/pipeline logic that can alter the output for an already-visited page (confidence thresholds in `YoloPanelDecoder`/`MlPanelBoundaryDetector`, anything in `PanelPipeline` — ordering, merging, splitting, padding) **must** bump the `DETECTOR_VERSION` constant, or revisited pages keep serving stale cached results and the fix appears to do nothing on-device even though the code is correct.

This was missed repeatedly in one session — each time, a real code fix was shipped, tested on a page already visited during debugging, and looked like it didn't work purely because the cache wasn't invalidated. Always bump the version in the same commit/build as the pipeline change, not after confirming it "didn't help."

The same bump is also the way to get **fresh evidence** for a page already visited — not just to ship a fix. If you're adding temporary debug logging (e.g. in `PanelPipeline`) to see the real detected coordinates for a specific failing page, a cache hit skips the whole pipeline (including your new log lines) and just returns the previously-cached panel list — bump `DETECTOR_VERSION` *before* asking the user to revisit that page, or the logs won't fire at all and you'll wrongly conclude nothing happened.

**A bump for evidence-gathering does *not* also cover the fix that follows it — that fix needs its own, separate bump.** Got this wrong in a live session: bumped once to force fresh detection so debug logging would fire, had the user revisit the page (which — running the *old*, still-buggy pipeline, just now with logging attached — wrote a fresh cache entry under that same bumped version, still holding the wrong order), then implemented the real fix afterward without bumping again. Pushed and asked the user to verify; they'd have just gotten the same cached-wrong result back, because that visit's cache entry already existed under the current version. The rule from the section above ("bump in the same commit/build as the pipeline change") still applies even when an earlier bump *already happened this session* for a different reason — each pipeline-output-changing event (an evidence-gathering visit that runs the old code, and later the actual fix) needs to invalidate whatever the previous one cached. Don't treat "I already bumped this recently" as covering a change made after that bump.

## PanelOrdering's recursive X-Y cut needs both start and end coordinates as cut-candidate lines

Confirmed bug in `PanelOrdering.kt`: `findCut`'s candidate cut-line generation only used panels' trailing edges (`panels.map(end).distinct().sorted()`) as places to split the recursive X-Y cut. This missed the correct cut on a real page (Official Bleach ch.16 p5): a narrow bottom-right panel that *starts* to the left of its column-mate (but shares the same end edge) needs a cut candidate at its start, not just its end, to be grouped into the correct column — without it, reading order came out `1,2,3,4,5,6` instead of the correct `1,3,2,4,5,6`. Fixed by unioning both: `(panels.map(end) + panels.map(start)).distinct().sorted()`.

If reading order comes out wrong for a page with panels of uneven start position within what should be a "column," check whether the candidate-line set is missing a start-edge cut before assuming the ordering heuristic itself is flawed. Regression-tested in `PanelOrderingTest.kt` with the exact captured coordinates from that page (`narrowRightColumnPanelStartingLeftOfItsColumnmateStillReadsAsAColumn`). Any change here also needs a `DETECTOR_VERSION` bump (see below) — this class of fix is easy to ship and then have look like "did nothing" purely because a previously-visited page is still serving its old cached order.

## Panel overlap/split heuristics: known open issue, approaches already tried and reverted

Some raw ML-detected panel boxes in `PanelPipeline.kt` overlap each other slightly even before padding is applied (confirmed via real page data, e.g. "The Boys" p128/p134), and some LTR western-style pages have panels that should split further based on bubble clustering but don't (bundled model has no signal for this beyond the bubble/text boxes it already emits).

Tried and reverted (not currently in the codebase — discarded back to commit `cfd74577d` after on-device testing showed no visible improvement):
- Lowering the bubble/text confidence threshold for LTR only (`LTR_TEXT_CONFIDENCE` in `MlPanelBoundaryDetector`) — didn't visibly help the target pages.
- `splitOnBubbleFreeMargin` in `PanelPipeline.kt` — split a merged panel region when detected bubbles cluster away from one edge, LTR-only. Unit-tested and logically sound but did not visibly fix the target pages on-device.
- Rewriting the padding cap functions (`capLeft/capRight/capTop/capBottom`) to use an absolute midpoint between neighboring panels' raw edges instead of a growth-only cap. This does fix genuine overlap in isolated unit tests, but combined with the above, the user reported "same result nothing changed" on-device and asked to discard all of it.

Before attempting this class of fix again: get fresh on-device logcat evidence (raw panel/bubble coordinates for the specific failing page) *first*, confirm which exact stage of the pipeline produces the wrong output for that page, and verify the fix against that same page's data before considering it done — don't assume a unit-test pass or a plausible heuristic transfers to the real page. This matches the general debugging-methodology lesson below: a fix that looks correct in isolation is not confirmed until it's verified against the actual repro.

## Panel-by-panel pinch-zoom / double-tap-zoom: attempted and rolled back — read this before trying again

A full session was spent building per-panel pinch-zoom and double-tap-zoom on top of the existing panel-by-panel reader (`ReaderPageImageView`'s `SubsamplingScaleImageView`, `Pager`'s independent gesture detector). Each individual bug found along the way was real and got a confirmed, root-caused fix — but the fixes kept surfacing *new* problems elsewhere in the same area faster than they closed the old ones (`setPanEnabled`'s recenter side effect → `Pager`'s own parallel fling detector → edge-park state triggering on plain taps → double-tap using a page-relative target → `landscapeZoom` firing unaware of panels → the library's own internal `maxScale` bleeding through mid-pinch → more still after that), and it never converged — the user's own assessment partway through was "seems like a lot of these debugging sessions ends up with you finding something about another thing about the app that causes a thing you do to make it not work." It was reverted back to plain panel-by-panel navigation (no zoom) rather than keep patching forward.

**The two sections below are kept anyway** — they're genuine, confirmed findings about how `SubsamplingScaleImageView` and this reader's touch-handling stack actually behave, and directly useful if per-panel zoom is attempted again. But they describe mechanisms discovered along the way, not currently-live code — the zoom implementation itself (clamp math, edge-park gesture, double-tap correction, `Pager`'s `navigationBlockedGate`/`wasMultiTouch` guards) is gone from the codebase. If picking this back up: given how many rounds it took to *not* converge, seriously consider whether a narrower first cut (e.g. pinch-zoom only, no double-tap, no edge-park-swipe-to-change-panel) would be more tractable than reintroducing the full scope at once — the double-tap-specific and edge-park-specific bugs were a large fraction of the total churn.

The panel-by-panel viewer is built on `SubsamplingScaleImageView`, a pre-existing component with its own internal state machine (scale/pan gesture detection, animation, `minimumScaleType`-driven bounds). Adding new behavior on top of it (e.g. per-panel pinch-zoom) doesn't get a clean slate — the existing machinery keeps running underneath and can silently corrupt what looks like unrelated code. Confirmed gotchas from real debugging sessions:

- **`setMinScale()` is a no-op when `minimumScaleType` isn't `SCALE_TYPE_CUSTOM`.** With `SCALE_TYPE_CENTER_INSIDE` (the default here), the library keeps auto-computing its own minimum from the fit-to-view formula and silently ignores whatever you explicitly set — confirmed by reading back `minScale` immediately after setting it and seeing the old value. **`setMaxScale()` is different — it's a genuinely safe, side-effect-free field write** (`this.maxScale = maxScale;`, confirmed straight from source, no recenter, no anim reset), unlike `setPanEnabled`/`setMinScale` below — safe to call from anywhere, including right before `setScaleAndCenter`. (An earlier pass in this same session lumped it in with the dangerous ones and removed a legitimate, harmless per-panel `maxScale` update as a result — don't repeat that; verify against the actual source per-method rather than generalizing from one bad method to its neighbors.)
- **Every per-page setup call needs an explicit "did I check whether this makes sense for panel-by-panel mode" pass.** Confirmed bug: `landscapeZoom` (auto-zooms a wide *page* to fill height, triggered by `scale == minScale`, both page-relative) was wired unconditionally into `setNonAnimatedImage`'s `onReady`, with no `panelModeActive` check — on a wide page in panel mode, it could fire its own page-relative reposition ~500ms after load, *after* panel detection had already correctly positioned the view, silently overriding it. This is the general failure mode to watch for whenever adding a panel-relative system on top of an already-page-relative reader: audit every page-level side effect (auto-zoom, initial scale/center setup, anything gated on the library's own `minScale`/fit-scale) for whether it's aware panels exist at all, not just the code you're actively changing.
- **`setPanEnabled(false)` unconditionally recenters the whole page at the view's current scale as a side effect — this is documented, intended library behavior, not a bug.** Straight from the library source (`mihonapp/subsampling-scale-image-view`, `SubsamplingScaleImageView.java`):
  ```java
  public final void setPanEnabled(boolean panEnabled) {
      this.panEnabled = panEnabled;
      if (!panEnabled && vTranslate != null) {
          vTranslate.x = (getWidth() / 2) - (scale * (getEffectiveSWidth() / 2));
          vTranslate.y = (getHeight() / 2) - (scale * (getEffectiveSHeight() / 2));
          ...
  ```
  its own doc comment says outright: "Disabling pan causes the image to be centered." Calling it at *any* point discards whatever center you'd set, unless something re-applies the correct position immediately afterward, before anything else reads/snapshots the view's position:
  - Called **before** `setScaleAndCenter`/`animateScaleAndCenter`, the immediately-following call overwrites the recenter — safe for an instant `setScaleAndCenter`, but for `animateScaleAndCenter` it still poisons that animation's own *start* state (`.start()` snapshots current scale/center at that instant), producing a visible flash to "page recentered at the old scale" right as the transition begins.
  - Called **after** (in an animation's `onComplete`, or deferred via `view.post {}`) — nothing overwrites the recenter afterward, so it's left showing dead-center, permanently wrong. Confirmed on-device (via logcat, requested vs. actual center) across three separate placements before landing on the fix below.
  - **The fix:** capture the true current `scale`/`center` before calling `setPanEnabled(false)`, then explicitly restore them via `setScaleAndCenter` immediately after it, *before* starting any animation — so the animation's own start-state snapshot sees the correct position, not the transient recenter.
- **`onScaleChanged`/`onCenterChanged` fire for *programmatic* changes too**, reported with `ORIGIN_ANIM` (confirmed via logcat, not assumed) — any reactive logic hooked to those callbacks (e.g. clamping) must gate on `origin == ORIGIN_TOUCH || origin == ORIGIN_FLING`, or it'll fire during a scripted transition's own interpolation and fight it.
- When something in this viewer looks subtly wrong (wrong final position, a visible "flash" before settling, jitter), suspect an interaction with this existing machinery before assuming the new code's own logic is wrong. The library (`mihonapp/subsampling-scale-image-view`) is itself open source — pull the actual method source (e.g. `curl` the raw file from GitHub) instead of guessing from the public API surface or decompiled signatures; its behavior around bounds/pan/animation has real, documented side effects the method signatures alone don't hint at, and reading the real implementation settles it in one shot instead of several rounds of on-device trial and error.
- **A single "wait N ms then correct" pass on a library-internal animation isn't reliable — verify and retry instead of trusting one shot.** Double-tap-zoom is `withInterruptible(false)`, and its "quick scale" pre-phase (a double-tap-then-hold is tracked internally before it's known whether it resolves into a drag or a plain double-tap) means the *actual* zoom animation can start later than expected — confirmed on-device: a correction computed and applied correctly at a fixed delay, then the scale had drifted away from it again ~150ms later with nothing logged in between to explain it. Fixed by making the correction a bounded retry loop (re-verify a few times, a short interval apart) instead of a single fire-and-forget delayed call — and cancelling that loop the moment a genuine new touch gesture starts, so it can't fight real user input either.

## Reader touch handling: multiple independent components watch the same touch stream

Layering a new gesture (panel-by-panel pinch-zoom) onto this reader kept breaking in ways that traced back not to the new code itself, but to a *different*, pre-existing component independently observing the same touch events. Before adding or debugging any gesture in this stack, know the full list of things that see raw touch, not just the one you're editing:

- **`ReaderPageImageView`'s child `SubsamplingScaleImageView`** — owns pinch/pan/double-tap when `zoomEnabled`/`panEnabled`. See the section above for its internal quirks.
- **`Pager` (`Pager.kt`) runs its own, fully independent `GestureDetector`** on every raw event, regardless of what a child view does with it:
  ```kotlin
  override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
      val handled = super.dispatchTouchEvent(ev)
      if (isGestureDetectorEnabled) {
          gestureDetector.onTouchEvent(ev)
      }
      return handled
  }
  ```
  Confirmed bug from this: a fast two-finger pinch's release can still carry enough single-pointer velocity residue for Android's standard `GestureDetector` to report a fling — and `Pager`'s `onFling` calls `panelSwipeListener` directly, the same path a real swipe uses, which falls through to an actual page turn once there's no more panel to advance to. The standard `GestureDetector` does **not** guard against this itself — it computes fling from the tracked pointer's velocity history regardless of whether a second pointer was ever involved. Fixed by tracking `wasMultiTouch` (set on `ACTION_POINTER_DOWN`, reset on `ACTION_DOWN`) and gating both `onFling` and `onSingleTapConfirmed` on it — this is also the tap-zone-interference case: a pinch ending near a screen edge could otherwise register as a tap in that zone's `NavigationRegion`.
  - `wasMultiTouch` only covers the two-finger case, though. A genuine **single-finger** pan while zoomed into a panel is legitimately consumed by the child `SubsamplingScaleImageView` for panning (confirmed via on-device logcat: the whole gesture correctly drove `clampPanelPan`'s own overscroll logic throughout, which measured **zero** overscroll) — but `Pager`'s independent detector has no idea that gesture was "spoken for", and its own `onFling` still fires on release, triggering a real panel/page change with no overscroll involved at all. Disabling the reader's tap-zone setting did **not** fix this (ruling out `onSingleTapConfirmed`/tap zones as the mechanism and pointing specifically at `onFling`). Fixed with a second, more general gate — `navigationBlockedGate`, consulted by both `onFling` and `onSingleTapConfirmed` — wired from `PagerViewer` to return true whenever the current holder reports `isZoomedIn()` (`SubsamplingScaleImageView.isPanEnabled`). The lesson: a multi-touch guard doesn't cover "child view is handling this single-finger gesture itself" — that needs its own explicit state check, not an inferred one (pointer count, velocity, etc).
- **`DirectionalViewPager`** (external lib, `tachiyomiorg/DirectionalViewPager`, wraps AOSP `ViewPager`) has its own drag-to-scroll + fling-to-turn-page logic, gated by `mIsBeingDragged` and properly pointer-ID-tracked (`ACTION_POINTER_DOWN`/`UP` update `mActivePointerId`) — this is standard, long-tested AOSP behavior and, audited, doesn't appear to misfire on multi-touch the way `Pager`'s custom detector did. `Pager.onInterceptTouchEvent` bypasses it entirely (`interceptingForPanelSwipe`) whenever a panel stop is available to step to; it only runs when panel-by-panel has nothing left to step to (i.e., a real page-turn is the correct fallback).
- **`GestureDetectorWithLongTap`** (`GestureDetectorWithLongTap.kt`) wraps the standard `GestureDetector` to add long-tap detection; it correctly cancels its own long-tap timer on `ACTION_POINTER_DOWN`, but delegates fling/single-tap straight to `super.onTouchEvent(ev)` — i.e., to the same standard detector `Pager` needed the `wasMultiTouch` guard for.
- `ReaderActivity` only has `dispatchGenericMotionEvent` (stylus/scroll-wheel `ACTION_SCROLL`/hover events) — unrelated to the touch pipeline above.

When a gesture does something unexpected in this stack, check whether it's actually one of these *other* components reacting to the same raw stream before assuming the code you're looking at is wrong.

## Debugging methodology: don't over-assert from correlation

Twice in one session, a plausible-looking correlation (an unrelated log line appearing right before "Pager first layout", a `System.loadLibrary` call worth theorizing about) got reported as a confirmed root cause before it was actually verified against a controlled repro. Both turned out to be coincidental/irrelevant, and both required backtracking after the user pointed it out. When logs show a correlation:
- State it as a hypothesis, not a finding, until a targeted repro (ideally with purpose-built tracing at the exact call sites in question) confirms it.
- Prefer adding narrow, temporary `logcat { "someTag ..." }` lines at the exact suspected call sites over reasoning from ambient/incidental log noise.
- This is a personal project, not one being prepared for outside contributors — temporary debug `logcat {}` instrumentation added while root-causing a bug does NOT need to be removed once the fix is confirmed. Leave it in the codebase; it's cheap to have around for the next debugging session in the same area. (Older guidance in this file said to strip it after confirming — that's superseded by explicit user preference.)

**When the user reports a fix didn't work, don't defend the theory — drop it and gather new evidence.** In this session, after a warm-up-hack "fix" for a stuck-spinner bug, the user reported it did nothing; the response was to keep explaining why the theory could still be right, prompting the user to say **"you are gaslighting yourself."** A negative result from the actual user/device is stronger evidence than any amount of plausible reasoning about why a fix *should* have worked. Treat "didn't work" as confirmed and go back to Phase 1 (fresh targeted tracing), not as something to argue with.

**Change one variable at a time against the real repro, especially for ML/heuristic tuning.** The panel-overlap/split work above shipped two independent speculative heuristics (bubble confidence threshold + `splitOnBubbleFreeMargin`) in the same round before testing either individually on-device against the specific failing pages. When the combined result didn't help, it wasn't possible to tell whether either piece was doing anything. For detection-quality work specifically: pick one target page, change one thing, rebuild, reinstall, check that exact page, *then* decide whether to keep it or add the next change.

**Verify a user-pasted external analysis against the actual image/data before implementing on top of it.** A pasted third-party "analysis" of a failing page (panel ordering, layout description) contained at least one factually wrong claim about the image (asserted panels were "stacked vertically" when they weren't) but was still used as the basis for a splitting/reordering scheme. Re-derive claims about pixel layout, coordinates, or ordering from the actual image/logcat data before coding against them, even when the user supplies a detailed-looking write-up.
