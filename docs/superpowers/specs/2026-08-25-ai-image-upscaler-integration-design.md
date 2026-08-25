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

No crop-math changes are needed. `PagerPageHolder.panelImageBytes` is read
independently of Coil's decode path, and `PanelDetector`/`PanelPipeline`
output normalized (0-1) crop rectangles — resolution-independent by
construction (see CLAUDE.md's `panelStopTarget` section). Upscaling changes
pixel resolution, not aspect ratio, so panel-by-panel crop rectangles remain
valid whether the underlying bitmap is native-resolution or upscaled.

The one thing Track B item 2 must get right: confirm `panelImageBytes`
continues to come from the original source stream. If it were accidentally
rewired to the enhanced-decoder output, the ML panel detector's input would
change without a corresponding `DETECTOR_VERSION` bump, silently producing
stale/wrong cached panel results (per CLAUDE.md's `DETECTOR_VERSION`
guidance).

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
   - Confirm the enhancement priority queue behaves sensibly while paging
     quickly back and forth (this is where the source fork's own
     preemption/reprioritization logic gets exercised).
4. No unit tests exist for this feature in the source fork. New tests are
   only warranted where Track B reconciliation introduces genuinely new
   branching (e.g., if `PagerPageHolder`'s panel-stop gate needs new
   conditions for enhanced-image load states) — per CLAUDE.md's own
   regression-test pattern for interaction bugs in this area, not as
   speculative coverage.

## Out of scope / not addressed by this design

- Qualcomm NPU/QNN backend (deferred, see Scope).
- Any change to per-manga `viewerFlags` (not needed — upscaling is a global
  preference).
- Any change to panel-detection crop math (not needed — see Interaction
  section above).
