# CLAUDE.md

Notes for working in this repo, written from real debugging sessions. Read the relevant section before touching the areas below.

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

## Panel overlap/split heuristics: known open issue, approaches already tried and reverted

Some raw ML-detected panel boxes in `PanelPipeline.kt` overlap each other slightly even before padding is applied (confirmed via real page data, e.g. "The Boys" p128/p134), and some LTR western-style pages have panels that should split further based on bubble clustering but don't (bundled model has no signal for this beyond the bubble/text boxes it already emits).

Tried and reverted (not currently in the codebase — discarded back to commit `cfd74577d` after on-device testing showed no visible improvement):
- Lowering the bubble/text confidence threshold for LTR only (`LTR_TEXT_CONFIDENCE` in `MlPanelBoundaryDetector`) — didn't visibly help the target pages.
- `splitOnBubbleFreeMargin` in `PanelPipeline.kt` — split a merged panel region when detected bubbles cluster away from one edge, LTR-only. Unit-tested and logically sound but did not visibly fix the target pages on-device.
- Rewriting the padding cap functions (`capLeft/capRight/capTop/capBottom`) to use an absolute midpoint between neighboring panels' raw edges instead of a growth-only cap. This does fix genuine overlap in isolated unit tests, but combined with the above, the user reported "same result nothing changed" on-device and asked to discard all of it.

Before attempting this class of fix again: get fresh on-device logcat evidence (raw panel/bubble coordinates for the specific failing page) *first*, confirm which exact stage of the pipeline produces the wrong output for that page, and verify the fix against that same page's data before considering it done — don't assume a unit-test pass or a plausible heuristic transfers to the real page. This matches the general debugging-methodology lesson below: a fix that looks correct in isolation is not confirmed until it's verified against the actual repro.

## Debugging methodology: don't over-assert from correlation

Twice in one session, a plausible-looking correlation (an unrelated log line appearing right before "Pager first layout", a `System.loadLibrary` call worth theorizing about) got reported as a confirmed root cause before it was actually verified against a controlled repro. Both turned out to be coincidental/irrelevant, and both required backtracking after the user pointed it out. When logs show a correlation:
- State it as a hypothesis, not a finding, until a targeted repro (ideally with purpose-built tracing at the exact call sites in question) confirms it.
- Prefer adding narrow, temporary `logcat { "someTag ..." }` lines at the exact suspected call sites over reasoning from ambient/incidental log noise.
- Remove temporary debug logging once the root cause is confirmed and fixed — don't leave it in the codebase.

**When the user reports a fix didn't work, don't defend the theory — drop it and gather new evidence.** In this session, after a warm-up-hack "fix" for a stuck-spinner bug, the user reported it did nothing; the response was to keep explaining why the theory could still be right, prompting the user to say **"you are gaslighting yourself."** A negative result from the actual user/device is stronger evidence than any amount of plausible reasoning about why a fix *should* have worked. Treat "didn't work" as confirmed and go back to Phase 1 (fresh targeted tracing), not as something to argue with.

**Change one variable at a time against the real repro, especially for ML/heuristic tuning.** The panel-overlap/split work above shipped two independent speculative heuristics (bubble confidence threshold + `splitOnBubbleFreeMargin`) in the same round before testing either individually on-device against the specific failing pages. When the combined result didn't help, it wasn't possible to tell whether either piece was doing anything. For detection-quality work specifically: pick one target page, change one thing, rebuild, reinstall, check that exact page, *then* decide whether to keep it or add the next change.

**Verify a user-pasted external analysis against the actual image/data before implementing on top of it.** A pasted third-party "analysis" of a failing page (panel ordering, layout description) contained at least one factually wrong claim about the image (asserted panels were "stacked vertically" when they weren't) but was still used as the basis for a splitting/reordering scheme. Re-derive claims about pixel layout, coordinates, or ordering from the actual image/logcat data before coding against them, even when the user supplies a detailed-looking write-up.
