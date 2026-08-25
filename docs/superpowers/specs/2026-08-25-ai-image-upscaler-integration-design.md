# AI Image Upscaler Integration — Design

## Goal

Port the AI upscaling feature from `HaoweiLi97/mihon_img_upscale` (a fork of
`mihonapp/mihon`, not of this repo) into this repo, so manga pages can be
upscaled on-device (Real-CUGAN, Real-ESRGAN, Waifu2x, Anime4K) before display.

## Why this isn't a `git merge`

The source fork branched directly from `mihonapp/mihon`, not from this repo,
so it shares no git history with this fork — GitHub itself reports "entirely
different commit histories" when comparing them. There is no common ancestor
to merge or cherry-pick from. Integration is a manual port: read the source
fork's code, and reproduce the relevant parts on top of this repo's current
state.

## Scope

**In scope (this pass):**
- All 13 bundled models across Real-CUGAN, Real-ESRGAN, Waifu2x/derivatives,
  SPAN-NomosUni, and Anime4K, on the **Vulkan/NCNN backend only**.
- The full background enhancement pipeline (priority queue, disk cache,
  Coil decoder hook) and its settings UI.

**Explicitly deferred:**
- The Qualcomm QNN/NPU backend (`qnn_backend.cpp`, the QNN SDK dependency,
  per-model offline-context files). The source fork's own `CMakeLists.txt`
  already gates this behind `QNN_SDK_DIR` being set (`MIHON_ENABLE_QNN=0`
  when unset), so `qnn_backend.cpp`/`.h` are ported as inert source — present,
  compiled out, harmless — rather than stripped. Turning NPU on later is a
  build-config change (supply `QNN_SDK_DIR` + offline contexts), not another
  code port.

Rationale for deferring NPU: it only covers 6 of the 13 models, requires a
second native SDK (Qualcomm AI Runtime) and per-model quantized offline
contexts, and the source fork's own `IMAGE_ENHANCEMENT_FIXES.md` documents
real precision-artifact problems ("flower screen") from reduced-precision
inference on the Vulkan path — quantized NPU inference carries similar risk.
Shipping the proven, fully-tuned Vulkan path across all models first, then
adding NPU as a scoped follow-up once that's validated on-device, is lower
risk than debugging two new native subsystems at once.

## Source fork inventory (what actually exists there)

Cloned read-only from `https://github.com/HaoweiLi97/mihon_img_upscale`
(`master` branch) for inspection. Single-module Android app (`app/`), size
~230MB, mostly vendored native SDK + model assets:

- `app/src/main/cpp/` (145KB): `waifu2x.cpp/.h`, `waifu2x_jni.cpp`,
  `anime4k.cpp/.h`, `qnn_backend.cpp/.h`, `shaders.h`,
  `waifu2x_fused_{preproc,postproc}.comp`, `CMakeLists.txt`.
- `third_party/ncnn-20260113-android-vulkan/` (99MB): vendored prebuilt NCNN
  Android Vulkan SDK, committed to git, referenced by `CMakeLists.txt` via
  `NCNN_SDK_DIR` (resolved from Gradle property → `local.properties` →
  `NCNN_SDK_DIR` env var → this bundled path, in that order).
- `app/src/main/assets/` (92MB): `anime4k/`, `animejanai-ncnn-vulkan/`,
  `realcugan-models/`, `realcugan-pro-models/`, `realesrgan-models/`,
  `span-nomosuni/`, `sudo-ultracompact/`, `waifu2x-models/`,
  `waifu2x-models-nose/`, `waifu2x-models-upconv7/` — `.bin`/`.param` NCNN
  model pairs.
- `app/src/main/java/eu/kanade/tachiyomi/util/waifu2x/`: `Waifu2x.kt` (JNI
  wrapper, model/engine constants), `ImageEnhancer.kt` (background
  priority-queue orchestrator — prioritizes visible/near pages, preempts
  in-flight work when the target page changes), `ImageEnhancementCache.kt`
  (disk cache keyed by manga/chapter/page/config-hash/variant).
- Settings are **global** `ReaderPreferences` (`pref_waifu2x_enabled`,
  `pref_realcugan_model`, noise level, scale, processing backend) — not
  per-manga `Manga.viewerFlags`. No interaction with this repo's per-series
  bit-packing scheme.
- Enhancement is triggered by re-requesting the page through Coil with a
  `.enhanced(true)` request tag; a renamed/rewritten decoder
  (`TachiyomiImageDecoder.kt`, replacing this repo's `ImageDecoder.kt`) checks
  that tag and calls into `Waifu2x` instead of (or in addition to) the normal
  libvips-based decode path.

## Two-track port

### Track A — direct port (no existing counterpart in this repo)

Copied over essentially as-is; nothing here conflicts with existing code:

- `app/src/main/cpp/*` — all native sources and `CMakeLists.txt`, including
  `qnn_backend.cpp/.h` (inert, per Scope above).
- `third_party/ncnn-20260113-android-vulkan/` — vendored SDK.
- `app/src/main/assets/{anime4k,animejanai-ncnn-vulkan,realcugan-models,realcugan-pro-models,realesrgan-models,span-nomosuni,sudo-ultracompact,waifu2x-models,waifu2x-models-nose,waifu2x-models-upconv7}/`
  — all model assets (full parity, per Scope above).
- `app/src/main/java/eu/kanade/tachiyomi/util/waifu2x/{Waifu2x,ImageEnhancer,ImageEnhancementCache}.kt`.
- `app/build.gradle.kts` — `externalNativeBuild`/CMake wiring, `ncnnSdkDir`
  resolution, QNN SDK path plumbing (inert without `QNN_SDK_DIR` set).

**Repo size impact:** adds ~230MB to this repo (vendored SDK + model assets),
checked into git. This is a one-way door once committed — flagged for
awareness, not a decision point (already agreed to "port everything").

### Track B — surgical merge (file exists on both sides, diverged independently)

These files have been modified independently by this repo's panel-by-panel
reader work and by the source fork's upscaler. None can be copied wholesale
in either direction without deleting the other side's work. For each, the
method is: diff the source fork's version against **plain upstream
`mihonapp/mihon`** (not against this repo) to isolate exactly which hunks are
upscaler-specific, then hand-apply those hunks onto this repo's current
version, resolving overlaps explicitly.

Ranked by size of the divergence (line counts: this repo → source fork) and
therefore rough merge risk:

1. **`ui/reader/viewer/ReaderPageImageView.kt`** (725 → 1396 lines) — highest
   risk. This repo's version owns panel-by-panel zoom/pan/`SubsamplingScaleImageView`
   logic (see CLAUDE.md's `panelStopTarget`/pinch-zoom sections); the source
   fork's version owns enhanced-image polling, cache-hit display swaps, and
   progress-overlay state. Both sets of logic must coexist in the same
   `onImageLoaded`/view-lifecycle callbacks.
2. **`ui/reader/viewer/pager/PagerPageHolder.kt`** (492 → 1155 lines) — high
   risk. This repo's version owns `panelImageBytes`/`refreshPanels`/panel-stop
   persistence (the `isCurrentReaderPage` gate from CLAUDE.md); the source
   fork's version owns enhancement request lifecycle
   (enqueue/cancel/reprioritize) tied to page visibility. Must confirm
   `panelImageBytes` stays wired to the **original** source stream, never the
   enhanced-decoder output — panel detection must not silently start reading
   upscaled bytes.
3. **`data/coil/ImageDecoder.kt` → `TachiyomiImageDecoder.kt`** (128 → 483
   lines, renamed) — medium risk. This repo's version handles libvips-based
   decoding for formats the platform decoder can't (AVIF/JXL/HEIF); the
   source fork's version adds the enhanced-decode branch. Must preserve the
   existing multi-format decode path while adding the branch, keep the
   existing class/file name (renaming is a source-fork choice, not required).
4. **`presentation/reader/settings/ColorFilterPage.kt`** (133 → 521 lines) —
   medium risk, but purely additive Compose UI (a new settings section for
   model/noise/scale/backend). Low logical risk, moderate size to review.
5. **`ui/reader/setting/ReaderPreferences.kt`** (361 → 291 lines) — low risk.
   This repo's version is already larger due to per-manga `viewerFlags`
   plumbing the source fork doesn't have; the merge is additive (new global
   preference declarations alongside existing ones), not overlapping logic.
6. **`ui/reader/ReaderActivity.kt`** (1039 → 1068 lines) — low risk. Only
   ~7 touch points (`Waifu2x.init`/`setUiBusy` calls, a settings-toggle
   callback), all additive.
7. **`data/coil/Utils.kt`** (52 → 89 lines) — low risk. Purely additive
   `ImageRequest.Builder` extension functions (`enhanced`, `mangaId`,
   `chapterId`, `pageIndex`, `pageVariant`, `customDecoder`).

## Interaction with panel detection

**The crop math itself is fine.** `PagerPageHolder.panelImageBytes` is read
independently of Coil's decode path, and `PanelDetector`/`PanelPipeline`
output normalized (0-1) crop rectangles — resolution-independent by
construction (see CLAUDE.md's `panelStopTarget` section). Upscaling changes
pixel resolution, not aspect ratio, so a given panel's crop rectangle stays
valid whether the underlying bitmap is native-resolution or upscaled 2x/3x.

Track B item 2 must still confirm `panelImageBytes` continues to come from
the original source stream. If it were accidentally rewired to the
enhanced-decoder output, the ML panel detector's input would change without
a corresponding `DETECTOR_VERSION` bump, silently producing stale/wrong
cached panel results (per CLAUDE.md's `DETECTOR_VERSION` guidance).

**The real risk is view state across an in-place bitmap swap, not crop math.**
Confirmed by reading the source fork's `ReaderPageImageView.setProcessedSource()`:
when background enhancement finishes for a page that's already the *currently
displayed* page, it swaps the live `SubsamplingScaleImageView`'s source via
`setImage(ImageSource...)` (behind a crossfade animation), with no positional
bookkeeping around that call. This is safe in the source fork because it has
no concept of panel stops — it only ever shows whole pages, so "reset to
default fit-to-view" is indistinguishable from "already showing the whole
page."

That is not true here. The enhancement queue prioritizes the visible page but
still runs in the background with real latency (worse at 3x on larger
models), so the original image can already be displayed — with the user
mid-panel-stop on it (e.g. panel 3 of 5) — when the swap happens. `setImage()`
resets the view to its default centered/fit-to-view position, discarding the
active panel stop. This is the same "viewer rebuilt from scratch, position
lost" bug class already documented three times in CLAUDE.md
(`state.manga` teardown, rotation restore, page-grid selection) — a fourth
trigger for it, not one the source fork's code ever had to solve.

**Correction after closer reading of the actual swap code (`animateProcessedSwap`):**
the source fork's swap is not a bare `setImage()` call — it's a dual-view
crossfade (`createSubsamplingPageView()` loads the new bitmap into a second,
overlaid `SubsamplingScaleImageView`; once *that* view reports `onReady`, it
crossfades in and replaces `pageView`). Its `onReady` handler, when the
pre-swap view was zoomed in past `minScale`, already remaps center and scale
as **fractions** of the old view's `sWidth`/`sHeight` onto the new view's
`sWidth`/`sHeight` — not as raw pixel values. That's the same
resolution-independent reasoning this repo's panel rects already use, so it
turns out to reproduce the correct panel-relative position across a
resolution change *for free*, without needing a bespoke capture/restore pass.

The one piece that **is** a real gap: when the pre-swap view was **not**
zoomed in (sitting at `minScale` — which includes panel-by-panel's full-page
stop), the fork's `onReady` handler falls through to an unconditional
`landscapeZoom(true)` call, with no `panelModeActive` check. This repo's own
existing `landscapeZoom` call sites are already gated on `panelModeActive`
for exactly this reason (see CLAUDE.md's per-page-setup-call guidance) — the
ported swap code needs the same gate, substituting a `jumpToPanelStop(panelStopIndex)`
call when panel mode is active instead of falling through to page-relative
`landscapeZoom`. This is a small, targeted fix (one branch), not a new
capture/restore mechanism. Validation (Rollout step 3) should cover both the
already-zoomed case (a non-first panel stop, confirming the free remap works)
and the not-zoomed case (the first/full-page stop, confirming the
`landscapeZoom` gate fix works) for a swap completing while the page is still
on screen.

## Rollout / validation plan

1. Land Track A only. Confirm Gradle sync and native build succeed
   (`externalNativeBuild`/CMake compiles `waifu2x-jni` for at least
   `arm64-v8a`). Feature is inert at this point (no UI wiring yet) — this is
   a build-health checkpoint, not a usable state.
2. Wire Track B file-by-file, in the risk order above (settings/prefs first,
   since they're lowest-risk and let later steps toggle the feature; the two
   viewer files last, since they're highest-risk and benefit from everything
   else already working). Verify the build after each file.
3. On-device smoke test (wireless adb, per established workflow):
   - Toggle upscaling on/off on a real chapter in plain paged mode.
   - Toggle upscaling on/off in webtoon mode.
   - **The actual regression scenario:** enable panel-by-panel mode with
     upscaling on — confirm panel detection still runs correctly, panel
     crops are still correctly positioned, and panel-stop persistence
     (rotation restore, page-grid jump) still works per the existing
     CLAUDE.md guidance for that subsystem.
   - **Specifically:** land on a page, wait for it to enter panel-by-panel
     mode and step to a non-first panel stop (e.g. panel 3 of 5), *then* let
     the background upscale finish for that same page while it's still on
     screen — confirm the view stays on that panel stop rather than
     snapping back to a default/full-page position when the enhanced bitmap
     swaps in (see "Interaction with panel detection" above). Use a slower
     model/3x scale if needed to make the timing window easy to hit.
   - Confirm the enhancement priority queue behaves sensibly while paging
     quickly back and forth (this is where the source fork's own
     preemption/reprioritization logic gets exercised).
4. No unit tests exist for this feature in the source fork. New tests are
   only warranted where Track B reconciliation introduces genuinely new
   branching (e.g., if `PagerPageHolder`'s panel-stop gate needs new
   conditions for enhanced-image load states) — per CLAUDE.md's own
   regression-test pattern for interaction bugs in this area, not as
   speculative coverage.

## Additional finding: enhancement cache needs a pipeline-version salt

`ImageEnhancementCache.getConfigHash()` in the source fork hashes every
user-facing setting (model, noise, scale, tile size, precision, backend,
max-size limits) plus two narrow hardcoded revision bumps for specific
NPU/INT8 cases (`PHOTO_NPU_INT8_CACHE_REVISION`,
`REAL_CUGAN_NPU_INT8_CACHE_REVISION`) — but has no general version salt.
Internal native tuning that isn't exposed as a user setting (e.g. the
tile-size/padding/precision fixes documented in the source fork's own
`IMAGE_ENHANCEMENT_FIXES.md`) is invisible to the hash. A future change to
those internals would silently keep serving stale cached output with nothing
to invalidate it — the same class of bug this repo's `DETECTOR_VERSION`
scheme (`PanelDetector.kt`) exists to prevent for panel detection.

**Action for the port:** add a general `ENHANCEMENT_PIPELINE_VERSION`
constant into `getConfigHash()`'s output (alongside the existing
model/setting fields), bumped whenever a native-side change to
`waifu2x.cpp`/`anime4k.cpp` alters output for already-cached pages — mirroring
the discipline already established for `DETECTOR_VERSION`. This is a small
addition on top of the Track A port of `ImageEnhancementCache.kt`, not a
structural change.

## Out of scope / not addressed by this design

- Qualcomm NPU/QNN backend (deferred, see Scope).
- Any change to per-manga `viewerFlags` (not needed — upscaling is a global
  preference).
- Any change to panel-detection crop math (not needed — see Interaction
  section above).
- The manual panel editor (`worktree-manual-panel-editor`, not yet merged to
  `main`): out of scope since it doesn't exist on `main` today. Worth a quick
  recheck for interaction with the enhancement pipeline once that branch
  lands, since it also reads page image data.
