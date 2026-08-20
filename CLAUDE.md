# CLAUDE.md

Notes for working in this repo, written from real debugging sessions. Read the relevant section before touching the areas below.

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

## Debugging methodology: don't over-assert from correlation

Twice in one session, a plausible-looking correlation (an unrelated log line appearing right before "Pager first layout", a `System.loadLibrary` call worth theorizing about) got reported as a confirmed root cause before it was actually verified against a controlled repro. Both turned out to be coincidental/irrelevant, and both required backtracking after the user pointed it out. When logs show a correlation:
- State it as a hypothesis, not a finding, until a targeted repro (ideally with purpose-built tracing at the exact call sites in question) confirms it.
- Prefer adding narrow, temporary `logcat { "someTag ..." }` lines at the exact suspected call sites over reasoning from ambient/incidental log noise.
- Remove temporary debug logging once the root cause is confirmed and fixed — don't leave it in the codebase.
