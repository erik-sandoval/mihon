# AI Image Upscaler Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port the AI image upscaling feature (Real-CUGAN, Real-ESRGAN, Waifu2x, Anime4K on NCNN/Vulkan, all 13 models, no NPU) from `HaoweiLi97/mihon_img_upscale` into this repo, coexisting with the existing panel-by-panel reader.

**Architecture:** Two tracks. Track A ports self-contained new code/assets with no existing counterpart (native engine, JNI wrapper, background enhancement queue, disk cache). Track B surgically merges the upscaler's changes into five files this repo has already modified for panel-by-panel reading, by diffing the source fork against plain upstream `mihonapp/mihon` to isolate upscaler-only hunks, then hand-applying those hunks onto this repo's current versions.

**Tech Stack:** Kotlin, Coil 3 (custom `Decoder`), NDK/CMake native build, NCNN (Vulkan backend), JUnit 5 + MockK for unit tests, wireless adb for on-device verification (no `androidTest` dir exists in this repo — Android-framework-dependent code is verified manually on-device, matching established convention).

**Spec:** `docs/superpowers/specs/2026-08-25-ai-image-upscaler-integration-design.md`

## Global Constraints

- Source fork pinned at commit `a0c9350d99f9be756120a5b692b4a7b5b9b88d2e` on branch `master` of `https://github.com/HaoweiLi97/mihon_img_upscale` — use this exact commit for every "port" or "diff" step below, not a re-fetched HEAD, so results are reproducible.
- Vulkan/NCNN backend only. `qnn_backend.cpp`/`.h` are ported as inert source (compiled out via the existing `MIHON_ENABLE_QNN` CMake gate) — never call QNN-specific Kotlin APIs from new integration code.
- All 13 models are in scope (full parity on the Vulkan path).
- No changes to per-manga `Manga.viewerFlags` — upscaling is a global `ReaderPreferences` toggle.
- No changes to `PanelDetector`/`PanelPipeline`/`PanelOrdering` crop math — normalized rects are resolution-independent by construction (see spec's "Interaction with panel detection").
- Every Track B task must build (`./gradlew :app:assembleDebug`) before being considered complete — a task that doesn't compile is not done.

---

## Setting up the source reference

Before Task 1, clone the pinned source fork commit into a scratch location outside this repo, so every later task can reference it without re-fetching:

```bash
git clone https://github.com/HaoweiLi97/mihon_img_upscale.git /tmp/upscale-fork
cd /tmp/upscale-fork
git checkout a0c9350d99f9be756120a5b692b4a7b5b9b88d2e
```

All paths like `$FORK/app/src/main/cpp/waifu2x.cpp` below refer to this checkout (`$FORK=/tmp/upscale-fork`).

---

### Task 1: Vendor native engine + wire CMake/NDK build

**Files:**
- Create: `app/src/main/cpp/CMakeLists.txt`, `waifu2x.cpp`, `waifu2x.h`, `anime4k.cpp`, `anime4k.h`, `qnn_backend.cpp`, `qnn_backend.h`, `shaders.h`, `waifu2x_jni.cpp`, `waifu2x_fused_preproc.comp`, `waifu2x_fused_postproc.comp` (copied verbatim from `$FORK/app/src/main/cpp/`)
- Create: `third_party/ncnn-20260113-android-vulkan/` (copied verbatim from `$FORK/third_party/ncnn-20260113-android-vulkan/`, ~99MB)
- Modify: `app/build.gradle.kts:1-9` (top-of-file imports/vals), `app/build.gradle.kts:33-46` (`defaultConfig` block), and the `android {}` block (add a new `externalNativeBuild { cmake { ... } }` block, sibling to `defaultConfig`)

**Interfaces:**
- Produces: a `waifu2x-jni` native shared library, built for at least `arm64-v8a`, exporting the JNI symbols declared in `waifu2x_jni.cpp` (consumed by Task 3's `Waifu2x.kt`).

**Test:** no unit test (native build config) — verified by a successful Gradle native build.

- [ ] **Step 1: Copy native sources**

```bash
mkdir -p app/src/main/cpp
cp /tmp/upscale-fork/app/src/main/cpp/{CMakeLists.txt,waifu2x.cpp,waifu2x.h,anime4k.cpp,anime4k.h,qnn_backend.cpp,qnn_backend.h,shaders.h,waifu2x_jni.cpp,waifu2x_fused_preproc.comp,waifu2x_fused_postproc.comp} app/src/main/cpp/
```

- [ ] **Step 2: Vendor the NCNN Vulkan SDK**

```bash
cp -r /tmp/upscale-fork/third_party/ncnn-20260113-android-vulkan third_party/ncnn-20260113-android-vulkan
```

- [ ] **Step 3: Add `local.properties`-based SDK path resolution to `app/build.gradle.kts`**

Add near the top of the file, after the existing imports (after line 8, before `plugins {}`):

```kotlin
private val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        FileInputStream(file).use { load(it) }
    }
}

val bundledNcnnSdkDir = rootProject.file("third_party/ncnn-20260113-android-vulkan")

val ncnnSdkDir = providers.gradleProperty("ncnnSdkDir").orNull
    ?: localProperties.getProperty("ncnn.sdk.dir")
    ?: System.getenv("NCNN_SDK_DIR")
    ?: bundledNcnnSdkDir.takeIf { it.exists() }?.absolutePath
```

- [ ] **Step 4: Wire `externalNativeBuild` into `defaultConfig` (`app/build.gradle.kts:33-46`)**

Add inside `defaultConfig { ... }`, before the closing brace at line 46:

```kotlin
        externalNativeBuild {
            cmake {
                if (!ncnnSdkDir.isNullOrBlank()) {
                    arguments += "-DNCNN_SDK_DIR=$ncnnSdkDir"
                }
                abiFilters += "arm64-v8a"
            }
        }
```

- [ ] **Step 5: Add the top-level `externalNativeBuild` block inside `android {}`**

Add as a new top-level member of the `android {}` block (sibling to `defaultConfig`, anywhere inside `android { ... }`):

```kotlin
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
```

- [ ] **Step 6: Verify the native build compiles**

Run: `./gradlew :app:externalNativeBuildDebug`
Expected: `BUILD SUCCESSFUL`, and `app/.cxx/.../arm64-v8a/libwaifu2x-jni.so` exists.
If it fails with an NCNN `ncnnConfig.cmake` not found error, confirm Step 2 copied the SDK to the exact path `third_party/ncnn-20260113-android-vulkan` (case-sensitive, must match `bundledNcnnSdkDir` in Step 3).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/cpp third_party/ncnn-20260113-android-vulkan app/build.gradle.kts
git commit -m "feat(upscale): vendor NCNN/Vulkan native engine and wire CMake build"
```

---

### Task 2: Vendor model assets

**Files:**
- Create: `app/src/main/assets/{anime4k,animejanai-ncnn-vulkan,realcugan-models,realcugan-pro-models,realesrgan-models,span-nomosuni,sudo-ultracompact,waifu2x-models,waifu2x-models-nose,waifu2x-models-upconv7}/` (copied verbatim from `$FORK/app/src/main/assets/`, ~92MB)

**Interfaces:**
- Produces: on-device model files under each of these asset directory names, extracted at runtime by `Waifu2x.kt`'s `extractModelsToCache(context, dirName)` (Task 3) — directory names must match exactly, since `Waifu2x.kt` references them by string literal.

**Test:** no unit test (static assets) — verified by build + asset packaging.

- [ ] **Step 1: Copy all model asset directories**

```bash
mkdir -p app/src/main/assets
cp -r /tmp/upscale-fork/app/src/main/assets/{anime4k,animejanai-ncnn-vulkan,realcugan-models,realcugan-pro-models,realesrgan-models,span-nomosuni,sudo-ultracompact,waifu2x-models,waifu2x-models-nose,waifu2x-models-upconv7} app/src/main/assets/
```

- [ ] **Step 2: Verify the assets package into the APK**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`. Then confirm packaging:

```bash
unzip -l app/build/outputs/apk/standard/debug/app-standard-universal-debug.apk | grep "realcugan-models/" | head -5
```

Expected: at least one `.bin`/`.param` file listed (confirms assets weren't excluded by `aaptOptions`/`.gitattributes`).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/assets
git commit -m "feat(upscale): vendor Real-CUGAN, Real-ESRGAN, Waifu2x, and Anime4K model assets"
```

---

### Task 3: Port `Waifu2x.kt` JNI wrapper

**Files:**
- Create: `app/src/main/java/eu/kanade/tachiyomi/util/waifu2x/Waifu2x.kt` (copied verbatim from `$FORK`, same path)
- Test: `app/src/test/java/eu/kanade/tachiyomi/util/waifu2x/WaifuxBackendResolutionTest.kt`

**Interfaces:**
- Consumes: `waifu2x-jni` native library (Task 1), model assets (Task 2).
- Produces: `Waifu2x` object with `init(context, noiseLevel, scale)`, `resolveProcessingBackend(requestedBackend, model, scale): Int`, `resolvePrecision(requestedPrecision, processingBackend, model, scale): Int`, model/backend `Int` constants (e.g. `MODEL_REAL_ESRGAN_ANIME`, `PROCESSING_BACKEND_VULKAN`, `PROCESSING_BACKEND_QUALCOMM_NPU`), `abortProcessing()`, `resetRealCugan()`, `setUiBusy(Boolean)`, `getProgressPercent()`, `getProgressId()`, `updatePerformance(sleepMs, tileSize)` — all consumed by Tasks 6, 7, 9, 10, 11, 12.

- [ ] **Step 1: Copy the file**

```bash
mkdir -p app/src/main/java/eu/kanade/tachiyomi/util/waifu2x
cp /tmp/upscale-fork/app/src/main/java/eu/kanade/tachiyomi/util/waifu2x/Waifu2x.kt app/src/main/java/eu/kanade/tachiyomi/util/waifu2x/Waifu2x.kt
```

- [ ] **Step 2: Write a failing test for backend fallback behavior**

The Kotlin-side `resolveProcessingBackend`/`resolvePrecision` are pure logic (no native call), safe to unit-test directly — `Waifu2x`'s `init {}` block catches `UnsatisfiedLinkError` from `System.loadLibrary`, so referencing the object in a JVM test doesn't crash even without the `.so` present. Since this port defers NPU (Global Constraints), lock down that requesting NPU always falls back to Vulkan when the model doesn't support it:

```kotlin
package eu.kanade.tachiyomi.util.waifu2x

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WaifuxBackendResolutionTest {

    @Test
    fun requestingNpuBackendFallsBackToVulkanWhenUnsupported() {
        // model = 2 is not among the NPU-compatible models in this port.
        val resolved = Waifu2x.resolveProcessingBackend(
            requestedBackend = Waifu2x.PROCESSING_BACKEND_QUALCOMM_NPU,
            model = 2,
            scale = 2,
        )
        assertEquals(Waifu2x.PROCESSING_BACKEND_VULKAN, resolved)
    }

    @Test
    fun requestingVulkanBackendStaysVulkan() {
        val resolved = Waifu2x.resolveProcessingBackend(
            requestedBackend = Waifu2x.PROCESSING_BACKEND_VULKAN,
            model = 0,
            scale = 2,
        )
        assertEquals(Waifu2x.PROCESSING_BACKEND_VULKAN, resolved)
    }
}
```

- [ ] **Step 3: Run the test**

Run: `./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.tachiyomi.util.waifu2x.WaifuxBackendResolutionTest"`
Expected: PASS (the ported file already implements this fallback correctly — this test documents and locks down that existing behavior, since we're relying on it to make NPU deferral safe).
If it fails, read `Waifu2x.resolveProcessingBackend`'s actual model-compatibility list and adjust the test's `model` value to one genuinely outside it — don't change the assertion to match unexpected behavior without understanding why first.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/eu/kanade/tachiyomi/util/waifu2x/Waifu2x.kt app/src/test/java/eu/kanade/tachiyomi/util/waifu2x/WaifuxBackendResolutionTest.kt
git commit -m "feat(upscale): port Waifu2x JNI wrapper"
```

---

### Task 4: Merge Coil request-tag extension functions (`Utils.kt`)

**Files:**
- Modify: `app/src/main/java/eu/kanade/tachiyomi/data/coil/Utils.kt` (52 lines → merge in ~6 new extension functions from `$FORK`'s version, 89 lines)
- Test: `app/src/test/java/eu/kanade/tachiyomi/data/coil/UtilsEnhancementTagsTest.kt`

**Interfaces:**
- Produces: `ImageRequest.Builder.enhanced(Boolean)`, `.mangaId(Long)`, `.chapterId(Long)`, `.pageIndex(Int)`, `.pageVariant(String)`, `.customDecoder(Boolean)` extension functions, and matching reader extensions on `Options`/`ImageRequest` to read them back — consumed by Tasks 6, 7, 8.

- [ ] **Step 1: Diff the source fork's file against current-repo's version to isolate additions**

```bash
diff -u app/src/main/java/eu/kanade/tachiyomi/data/coil/Utils.kt /tmp/upscale-fork/app/src/main/java/eu/kanade/tachiyomi/data/coil/Utils.kt
```

The current file (52 lines) already has `customDecoder`; the fork's version (89 lines) adds `enhanced`, `mangaId`, `chapterId`, `pageIndex`, `pageVariant` as parallel `ImageRequest.Builder` extensions following the exact same pattern (each sets a request tag via `.memoryCacheKeyExtra`/`.setExtra`-equivalent — match whatever mechanism `customDecoder` already uses in the current file, for consistency).

- [ ] **Step 2: Apply the diff's additions to the current file**

Append the new extension functions after the existing `customDecoder` function, using the same tag-setting mechanism already present in the current file. Each new function must have a matching reader (e.g. `Options.mangaId: Long?` or a top-level `fun Options.mangaId(): Long?`) — check the fork's `ImageEnhancer.kt` (already ported in a later task's dependency, but its imports listed in the spec's investigation confirm the exact 6 names) for the exact reader-side call shape it expects, and match it.

- [ ] **Step 3: Write a test confirming a tag round-trips through the builder**

```kotlin
package eu.kanade.tachiyomi.data.coil

import coil3.request.ImageRequest
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UtilsEnhancementTagsTest {

    @Test
    fun enhancedAndMangaIdTagsRoundTripThroughBuilder() {
        val context = mockk<android.content.Context>(relaxed = true)
        val request = ImageRequest.Builder(context)
            .data("dummy")
            .enhanced(true)
            .mangaId(42L)
            .chapterId(7L)
            .pageIndex(3)
            .pageVariant("double")
            .build()

        assertTrue(request.isEnhanced())
        assertEquals(42L, request.mangaIdOrNull())
        assertEquals(7L, request.chapterIdOrNull())
        assertEquals(3, request.pageIndexOrNull())
        assertEquals("double", request.pageVariantOrNull())
    }
}
```

Name the reader functions (`isEnhanced()`, `mangaIdOrNull()`, etc.) to match whatever you actually implemented in Step 2 — adjust this test's call sites to the real names before running it, and note the final names in this task's own file (they're consumed verbatim by Task 6/7/8).

- [ ] **Step 4: Run the test**

Run: `./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.tachiyomi.data.coil.UtilsEnhancementTagsTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/eu/kanade/tachiyomi/data/coil/Utils.kt app/src/test/java/eu/kanade/tachiyomi/data/coil/UtilsEnhancementTagsTest.kt
git commit -m "feat(upscale): add enhancement request-tag extensions to Coil Utils"
```

---

### Task 5: Merge global upscaler preferences (`ReaderPreferences.kt`)

**Files:**
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/reader/setting/ReaderPreferences.kt` (361 lines currently; fork's version is 291 lines with ~10-15 new preference declarations to add — the fork's smaller total is because it lacks this repo's per-manga `viewerFlags` plumbing, not because it has fewer upscaler preferences)
- Test: `app/src/test/java/eu/kanade/tachiyomi/ui/reader/setting/ReaderPreferencesUpscaleTest.kt`

**Interfaces:**
- Produces: `waifu2xEnabled(): Preference<Boolean>` (default `false`), `waifu2xNoiseLevel(): Preference<Int>` (default `2`), `realCuganModel(): Preference<Int>` (default `0`), `realCuganNoiseLevel()`, `realCuganScale()`, `realCuganProcessingBackend()`, `realCuganPrecision()`, `realCuganFp16Arithmetic()`, `realEsrganStyle()`, `realCuganMaxSizeWidth()`/`Height()`, `realCuganSkipMaxSizeWidth()`/`Height()` — consumed by Tasks 6, 7, 9, 10, 11, 12.

- [ ] **Step 1: Diff to find every new preference key**

```bash
diff -u app/src/main/java/eu/kanade/tachiyomi/ui/reader/setting/ReaderPreferences.kt /tmp/upscale-fork/app/src/main/java/eu/kanade/tachiyomi/ui/reader/setting/ReaderPreferences.kt
```

Collect every `fun ...() = preferenceStore.get*("pref_...", default)` line that appears only in the fork's version.

- [ ] **Step 2: Append the new preference declarations**

Add each one as its own function in the current file, following the exact existing style (`preferenceStore.getBoolean`/`getInt` with a string key and default, matching the fork's key names and defaults verbatim — other code, including the source fork's own `ColorFilterPage.kt` in Task 9, references these by name).

- [ ] **Step 3: Write a defaults test**

```kotlin
package eu.kanade.tachiyomi.ui.reader.setting

import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.PreferenceStore

class ReaderPreferencesUpscaleTest {

    @Test
    fun upscalerPreferencesHaveExpectedDefaults() {
        val preferenceStore = mockk<PreferenceStore>(relaxed = true)
        // Real PreferenceStore implementations in this codebase return a live
        // Preference<T> backed by the mock; if PreferenceStore is an interface
        // without a default in-memory test double here, use whichever fake/in-memory
        // PreferenceStore this codebase's other preference tests already use instead
        // of mocking it directly.
        val prefs = ReaderPreferences(preferenceStore)

        assertFalse(prefs.waifu2xEnabled().get())
        assertEquals(2, prefs.waifu2xNoiseLevel().get())
        assertEquals(0, prefs.realCuganModel().get())
    }
}
```

Before writing this test, check whether other tests in `app/src/test/java/eu/kanade/tachiyomi/ui/reader/setting/` (or anywhere else preferences are tested in this repo) already use a concrete in-memory `PreferenceStore` test double — reuse that instead of hand-mocking, matching this codebase's existing pattern rather than inventing a new one.

- [ ] **Step 4: Run the test**

Run: `./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferencesUpscaleTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/eu/kanade/tachiyomi/ui/reader/setting/ReaderPreferences.kt app/src/test/java/eu/kanade/tachiyomi/ui/reader/setting/ReaderPreferencesUpscaleTest.kt
git commit -m "feat(upscale): add global upscaler preferences"
```

---

### Task 6: Port `ImageEnhancementCache.kt` + add pipeline-version cache salt

**Files:**
- Create: `app/src/main/java/eu/kanade/tachiyomi/util/waifu2x/ImageEnhancementCache.kt` (copied from `$FORK`, then modified per Step 2 below)
- Test: `app/src/test/java/eu/kanade/tachiyomi/util/waifu2x/ImageEnhancementCacheTest.kt`

**Interfaces:**
- Consumes: `Waifu2x` constants/resolvers (Task 3).
- Produces: `ImageEnhancementCache.init(context)`, `getConfigHash(noise, scale, model, ...): String`, `getCachedImage(mangaId, chapterId, pageIndex, configHash, variant): File?`, `isCached(...)`, `isSkipped(...)`, `isSavePending(...)`, `removeCachedImage(...)`, `checkAndTrim(context)`, `clearChapterCache(mangaId, chapterId)` — consumed by Tasks 7, 11, 12. Adds a new `ENHANCEMENT_PIPELINE_VERSION` constant baked into `getConfigHash()`'s output (spec's "Additional finding" section).

- [ ] **Step 1: Copy the file**

```bash
cp /tmp/upscale-fork/app/src/main/java/eu/kanade/tachiyomi/util/waifu2x/ImageEnhancementCache.kt app/src/main/java/eu/kanade/tachiyomi/util/waifu2x/ImageEnhancementCache.kt
```

- [ ] **Step 2: Add the pipeline-version salt**

Add a constant near the top of the object (alongside `MAX_CACHE_SIZE`):

```kotlin
    // Bump whenever a native-side change to waifu2x.cpp/anime4k.cpp alters output for
    // already-cached pages (tile size, padding, precision defaults, etc.) — mirrors
    // PanelDetector.DETECTOR_VERSION's role for panel-detection caching.
    private const val ENHANCEMENT_PIPELINE_VERSION = 1
```

Then append it to the returned string in `getConfigHash()` (find the `return "${noise}x${effectiveScale}_m${model}..."` line and add `_pv$ENHANCEMENT_PIPELINE_VERSION` to the end of the interpolated string, before `$modelRevision`).

- [ ] **Step 3: Write a failing test for the version salt**

```kotlin
package eu.kanade.tachiyomi.util.waifu2x

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImageEnhancementCacheTest {

    @Test
    fun configHashIncludesPipelineVersion() {
        val hash = ImageEnhancementCache.getConfigHash(noise = 2, scale = 2, model = 0)
        assertTrue(hash.contains("_pv1"), "expected config hash to embed pipeline version, was: $hash")
    }

    @Test
    fun differentSettingsProduceDifferentHashes() {
        val hashA = ImageEnhancementCache.getConfigHash(noise = 2, scale = 2, model = 0)
        val hashB = ImageEnhancementCache.getConfigHash(noise = 3, scale = 2, model = 0)
        assertTrue(hashA != hashB)
    }
}
```

- [ ] **Step 4: Run the test**

Run: `./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.tachiyomi.util.waifu2x.ImageEnhancementCacheTest"`
Expected: PASS.

- [ ] **Step 5: Write a cache-file-lifecycle test using a mocked `Context` over a real temp directory**

`ImageEnhancementCache.init(context)` only needs `context.cacheDir` to resolve its storage root — everything past that is real `File` I/O, which is safe to exercise in a plain JVM test with MockK stubbing just that one property:

```kotlin
package eu.kanade.tachiyomi.util.waifu2x

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Test
import java.io.File

class ImageEnhancementCacheLifecycleTest {

    @TempDir
    lateinit var tempDir: File

    private fun fakeContext(): Context = mockk(relaxed = true) {
        every { cacheDir } returns tempDir
    }

    @Test
    fun uncachedPageIsNeitherCachedNorSkipped() {
        val context = fakeContext()
        ImageEnhancementCache.init(context)
        val hash = ImageEnhancementCache.getConfigHash(noise = 2, scale = 2, model = 0)

        assertFalse(ImageEnhancementCache.isCached(1L, 1L, 0, hash, ""))
        assertFalse(ImageEnhancementCache.isSkipped(1L, 1L, 0, hash, ""))
    }
}
```

- [ ] **Step 6: Run the test**

Run: `./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.tachiyomi.util.waifu2x.ImageEnhancementCacheLifecycleTest"`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/eu/kanade/tachiyomi/util/waifu2x/ImageEnhancementCache.kt app/src/test/java/eu/kanade/tachiyomi/util/waifu2x/ImageEnhancementCacheTest.kt app/src/test/java/eu/kanade/tachiyomi/util/waifu2x/ImageEnhancementCacheLifecycleTest.kt
git commit -m "feat(upscale): port enhancement disk cache with pipeline-version salt"
```

---

### Task 7: Port `ImageEnhancer.kt` priority-queue orchestrator

**Files:**
- Create: `app/src/main/java/eu/kanade/tachiyomi/util/waifu2x/ImageEnhancer.kt` (copied verbatim from `$FORK`)
- Test: `app/src/test/java/eu/kanade/tachiyomi/util/waifu2x/ImageEnhancerPriorityTest.kt`

**Interfaces:**
- Consumes: `Waifu2x` (Task 3), `ImageEnhancementCache` (Task 6), the Coil tag extensions (Task 4).
- Produces: `ImageEnhancer.enhance(context, page, highPriority)`, `.enhanceLazy(...)`, `.reset(initialPageIndex)`, `.cancelAll(reason, resetNative)`, `.reprioritizeAround(pageIndex, variant, secondaryPageIndex, secondaryVariant)`, `.hasRequest(...)`, `.isFocusedTarget(...)`, `.isActivelyProcessing(...)`, `.cancel(...)`, `.cancelRequestsLessThan(...)`, `.cancelRequestsGreaterThan(...)`, `.targetPageIndex: Int` — consumed by Tasks 11, 12.

- [ ] **Step 1: Copy the file**

```bash
cp /tmp/upscale-fork/app/src/main/java/eu/kanade/tachiyomi/util/waifu2x/ImageEnhancer.kt app/src/main/java/eu/kanade/tachiyomi/util/waifu2x/ImageEnhancer.kt
```

- [ ] **Step 2: Write a test for the priority ordering (the actual business logic in this file)**

`EnhanceRequest.compareTo` is pure logic given `ImageEnhancer.targetPageIndex`/`targetPageVariant` state — test it directly by driving that state via `reset()`/`reprioritizeAround()` and constructing requests with a mocked `Context` and no-op `dataProvider`:

```kotlin
package eu.kanade.tachiyomi.util.waifu2x

import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ImageEnhancerPriorityTest {

    @BeforeEach
    fun resetQueueState() {
        ImageEnhancer.reset(initialPageIndex = 5)
    }

    private fun request(pageIndex: Int, priority: Int, seq: Int) = ImageEnhancer.EnhanceRequest(
        context = mockk(relaxed = true),
        mangaId = 1L,
        chapterId = 1L,
        pageIndex = pageIndex,
        pageVariant = "",
        dataProvider = { null },
        priority = priority,
        generation = 0,
        seq = seq,
    )

    @Test
    fun targetPageAlwaysSortsBeforeNonTargetPages() {
        val target = request(pageIndex = 5, priority = 0, seq = 0)
        val nearby = request(pageIndex = 6, priority = 1, seq = 1)

        assertEquals(-1, target.compareTo(nearby).coerceIn(-1, 1))
    }

    @Test
    fun amongEqualPriorityRequestsCloserToTargetSortsFirst() {
        val near = request(pageIndex = 6, priority = 0, seq = 0)
        val far = request(pageIndex = 9, priority = 0, seq = 1)

        assertEquals(-1, near.compareTo(far).coerceIn(-1, 1))
    }
}
```

Note: `EnhanceRequest` is currently a private nested `data class` inside the `ImageEnhancer` object in the source fork — if it isn't accessible from a test in a different file as written, change its visibility to `internal` (not `public`) as part of this port so it stays testable without becoming external API. Confirm this change compiles cleanly against the rest of `ImageEnhancer.kt`, since the object's own methods already construct it directly.

- [ ] **Step 3: Run the test**

Run: `./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.tachiyomi.util.waifu2x.ImageEnhancerPriorityTest"`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/eu/kanade/tachiyomi/util/waifu2x/ImageEnhancer.kt app/src/test/java/eu/kanade/tachiyomi/util/waifu2x/ImageEnhancerPriorityTest.kt
git commit -m "feat(upscale): port background enhancement priority queue"
```

---

### Task 8: Merge enhanced-decode branch into `ImageDecoder.kt`

**Files:**
- Modify: `app/src/main/java/eu/kanade/tachiyomi/data/coil/ImageDecoder.kt` (128 lines currently; fork's renamed `TachiyomiImageDecoder.kt` is 483 lines — keep this repo's existing file name, do not rename)

**Interfaces:**
- Consumes: `Waifu2x` (Task 3), the `enhanced()`/`isEnhanced()` tag readers (Task 4).
- Produces: unchanged public `Decoder` contract — the merge only changes what happens *inside* decode when the `enhanced` tag is set, not the class's external shape.

- [ ] **Step 1: Diff the fork's file against plain upstream mihon to isolate upscaler-only hunks**

```bash
git show upstream/main:app/src/main/java/eu/kanade/tachiyomi/data/coil/ImageDecoder.kt > /tmp/upstream-ImageDecoder.kt
diff -u /tmp/upstream-ImageDecoder.kt /tmp/upscale-fork/app/src/main/java/eu/kanade/tachiyomi/data/coil/TachiyomiImageDecoder.kt
```

This isolates exactly what the fork added relative to vanilla mihon (the enhanced-decode branch, calling `Waifu2x` when the request's `enhanced` tag is set), separate from any incidental rename/reformatting noise.

- [ ] **Step 2: Read this repo's current `ImageDecoder.kt` in full and identify where the decode path branches**

The current file (128 lines) decodes via `ca.mpreg.imagedecoder.ImageDecoder` (libvips) for formats the platform decoder doesn't handle (AVIF/JXL/HEIF — see the class doc comment). The merge must add a branch, checked before the existing libvips path, that: reads the `enhanced`/`mangaId`/`chapterId`/`pageIndex`/`pageVariant` tags off the `Options`, and if `enhanced` is true, decodes normally first, then calls into `Waifu2x`'s processing entry point (find the exact method name in `Waifu2x.kt` from Task 3 — it's the one taking a `Bitmap` and returning a `Bitmap?`, wrapping the native `nativeProcess`/model-specific native calls) before returning the `DecodeResult`.

- [ ] **Step 3: Apply the isolated hunks from Step 1, adapted to this file's existing structure**

Preserve every existing code path (AVIF/JXL/HEIF decode via libvips, the `DecodeResult` wrapping, the `ca.mpreg.imagedecoder.ImageDecoder` usage) exactly as-is. Add the enhanced-decode branch as new code, not a replacement of the existing `decode()` function body.

- [ ] **Step 4: Verify existing multi-format decode still compiles and the module builds**

Run: `./gradlew :app:compileStandardDebugKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Manual on-device verification (no existing test coverage for this decoder to extend — matches this repo's convention of manual verification for Android-framework-dependent code)**

Via wireless adb, open a chapter containing at least one AVIF or JXL page (if none available in test content, skip this specific check and note it) and confirm it still decodes correctly with the upscaler feature toggled off. This confirms Step 3 didn't regress the pre-existing decode path.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/eu/kanade/tachiyomi/data/coil/ImageDecoder.kt
git commit -m "feat(upscale): add enhanced-decode branch to ImageDecoder"
```

---

### Task 9: Merge upscaler settings section into `ColorFilterPage.kt`

**Files:**
- Modify: `app/src/main/java/eu/kanade/presentation/reader/settings/ColorFilterPage.kt` (133 lines currently; fork's version is 521 lines — the added ~390 lines are a new Compose section for model/noise/scale/backend controls, additive relative to the existing color-filter controls)

**Interfaces:**
- Consumes: `ReaderPreferences` upscaler preferences (Task 5), `Waifu2x` model/backend constants (Task 3).
- Produces: no new symbols consumed elsewhere — this is a leaf UI file.

- [ ] **Step 1: Diff to isolate the new section**

```bash
git show upstream/main:app/src/main/java/eu/kanade/presentation/reader/settings/ColorFilterPage.kt > /tmp/upstream-ColorFilterPage.kt
diff -u /tmp/upstream-ColorFilterPage.kt /tmp/upscale-fork/app/src/main/java/eu/kanade/presentation/reader/settings/ColorFilterPage.kt
```

- [ ] **Step 2: Append the new Compose section to the current file**

Add the upscaler settings section (toggle, model picker, noise/scale/backend controls) after the existing color-filter controls in `ColorFilterPage`, preserving every existing control (`customBrightness`, `colorFilter`, `grayscale`, `invertedColors`) exactly as-is. Reference this repo's `ReaderPreferences` upscaler properties from Task 5 (they were named to match the fork's originals, so this section should paste in with minimal renaming).

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :app:compileStandardDebugKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Manual on-device verification**

Via wireless adb, open the reader's settings sheet → Color Filter page. Confirm: existing brightness/color-filter/grayscale/inverted-colors controls are still present and functional, and the new upscaler section renders with a working enable toggle and model picker.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/eu/kanade/presentation/reader/settings/ColorFilterPage.kt
git commit -m "feat(upscale): add upscaler settings section to Color Filter page"
```

---

### Task 10: Merge upscaler lifecycle calls into `ReaderActivity.kt`

**Files:**
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderActivity.kt` (1039 lines currently; fork's version is 1068 — a small, additive diff)

**Interfaces:**
- Consumes: `Waifu2x.init(context, noiseLevel)`, `Waifu2x.setUiBusy(Boolean)` (Task 3), `readerPreferences.waifu2xNoiseLevel()` (Task 5).
- Produces: `Waifu2x.init` called once on reader creation, `setUiBusy(true/false)` toggled around user-interaction windows (already-established touch-handling call sites in this file — see this repo's own touch-handling map in CLAUDE.md before adding a new one).

- [ ] **Step 1: Diff to find all upscaler touch points**

```bash
git show upstream/main:app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderActivity.kt > /tmp/upstream-ReaderActivity.kt
diff -u /tmp/upstream-ReaderActivity.kt /tmp/upscale-fork/app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderActivity.kt
```

Expect ~7 touch points: an import, `Waifu2x.init(...)` near activity creation, `Waifu2x.setUiBusy(true/false)` around touch-start/touch-end handling, and one settings-toggle callback wire-up (`onClickImageEnhancement`).

- [ ] **Step 2: Apply each hunk to the current file's equivalent location**

This repo's `ReaderActivity.kt` has already-modified touch-handling and settings-callback code compared to vanilla mihon (per CLAUDE.md's touch-handling section) — apply each hunk next to its nearest equivalent existing call site rather than assuming identical line numbers to the diff.

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :app:compileStandardDebugKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Manual on-device verification**

Via wireless adb, open the reader and confirm no crash on entry (`Waifu2x.init` runs without throwing) and that toggling the upscaler setting from Task 9's UI is reflected via `onClickImageEnhancement`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderActivity.kt
git commit -m "feat(upscale): wire Waifu2x lifecycle calls into ReaderActivity"
```

---

### Task 11: Merge enhancement request lifecycle into `PagerPageHolder.kt`

**Files:**
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/PagerPageHolder.kt` (492 lines currently; fork's version is 1155 lines)

**Interfaces:**
- Consumes: `ImageEnhancer` (Task 7), `ImageEnhancementCache` (Task 6), `ReaderPreferences` upscaler prefs (Task 5).
- Produces: no new symbols consumed by later tasks, but this repo's existing `panelImageBytes` field and `loadPanels`/`refreshPanels` functions (used by Task 12 indirectly via the panel-stop system) must remain wired exactly as they are today.

- [ ] **Step 1: Diff to isolate every upscaler-only hunk**

```bash
git show upstream/main:app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/PagerPageHolder.kt > /tmp/upstream-PagerPageHolder.kt
diff -u /tmp/upstream-PagerPageHolder.kt /tmp/upscale-fork/app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/PagerPageHolder.kt
```

- [ ] **Step 2: Apply enhancement enqueue/cancel/reprioritize hunks, preserving every existing panel-by-panel call site**

This repo's current file (`app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/PagerPageHolder.kt`) has, at minimum, these existing call sites that must not be touched or reordered by this merge:
- Line ~166: `if (viewer.isCurrentReaderPage(page)) viewModel.savePanelStop(page.index, index)` (the panel-stop persistence gate — CLAUDE.md's rotation-restore fix).
- Line ~299: `panelImageBytes = panelSourceBytes` (must keep reading from the **original** source stream — do not rewire this to any enhanced/upscaled byte source; see spec's "Interaction with panel detection").
- Line ~356: `val panels = viewer.panelDetector.detect(page, imageBytes, viewer.panelDirection)`.

Add the fork's enqueue/cancel/reprioritize calls (`ImageEnhancer.enhance(...)`, `.reprioritizeAround(...)`, `.cancelRequestsLessThan/GreaterThan(...)`) and cache-status checks (`ImageEnhancementCache.isCached/isSkipped/getConfigHash(...)`) as new code alongside these, in whatever lifecycle callback the fork attaches them to (page-bind/page-selected), without altering the three call sites above.

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :app:compileStandardDebugKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Manual on-device verification — confirm panel detection is unaffected**

Via wireless adb: enable panel-by-panel mode, enable upscaling, open a chapter, and confirm panel boundaries are detected identically to upscaling being disabled (same panel count, same reading order) — this directly verifies Step 2's requirement that `panelImageBytes` still comes from the original stream, not an upscaled one.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/PagerPageHolder.kt
git commit -m "feat(upscale): wire enhancement request lifecycle into PagerPageHolder"
```

---

### Task 12: Merge enhanced-bitmap display into `ReaderPageImageView.kt`, adapted for panel stops

**Files:**
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/ReaderPageImageView.kt` (725 lines currently; fork's version is 1396 lines)

**Interfaces:**
- Consumes: `ImageEnhancer`/`ImageEnhancementCache` (Tasks 6, 7), `Waifu2x.getProgressPercent()`/`getProgressId()` (Task 3), this repo's own `panelStops`/`panelStopIndex`/`jumpToPanelStop`/`panelStopTarget`/`panelModeActive` (all pre-existing in this file).

- [ ] **Step 1: Diff to isolate every upscaler-only hunk**

```bash
git show upstream/main:app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/ReaderPageImageView.kt > /tmp/upstream-ReaderPageImageView.kt
diff -u /tmp/upstream-ReaderPageImageView.kt /tmp/upscale-fork/app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/ReaderPageImageView.kt
```

- [ ] **Step 2: Port `setProcessedSource`/`animateProcessedSwap` (the enhanced-bitmap crossfade swap)**

Port these largely as-is — investigation for this plan (see spec) found that `animateProcessedSwap`'s existing zoom-preservation logic is already dimension-proportional (it remaps `targetCenter`/`targetScale` as *fractions* of the old `sWidth`/`sHeight` onto the new swapped-in view's `sWidth`/`sHeight`, not as raw pixel values), which is mathematically compatible with this repo's panel-stop rects (also fraction-based) — a 3x upscale changes `sWidth`/`sHeight` uniformly, and the existing remap already produces the same scale/center a fresh `panelStopTarget()` call would. This is *not* something you need to build from scratch; it already generalizes correctly by construction.

- [ ] **Step 3: Fix the one real collision — `animateProcessedSwap`'s unconditional `landscapeZoom(true)` call**

In the ported `animateProcessedSwap`, the `else if (isVisibleOnScreen()) { landscapeZoom(true) }` branch (taken when the pre-swap view was at `minScale`, i.e. not "zoomed in") is **not** gated on `panelModeActive`. This repo's existing `landscapeZoom` call sites (lines ~191, ~197 in the current file) are already gated with `if (panelModeActive) { ... } else { landscapeZoom(forward) }` — this is the exact "per-page setup call not aware of panel mode" collision class documented in this repo's CLAUDE.md. Fix it the same way: change this branch to

```kotlin
} else if (panelModeActive) {
    jumpToPanelStop(panelStopIndex)
} else if (isVisibleOnScreen()) {
    landscapeZoom(true)
}
```

so that when the pre-swap view was showing the panel-by-panel full-page/first stop (not "zoomed in" by the fork's own `wasZoomed` check) and the background upscale swap completes, the view re-derives its position from the current panel stop against the new (upscaled) dimensions, instead of falling through to page-relative `landscapeZoom`.

- [ ] **Step 4: Verify it compiles**

Run: `./gradlew :app:compileStandardDebugKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Manual on-device verification — the actual regression scenario from the spec**

Via wireless adb: enable panel-by-panel mode and upscaling (pick a slower model or 3x scale to widen the timing window), open a wide page, land on it, and:
   - (a) Step to a non-first panel stop (e.g. panel 3 of 5) *before* the background upscale finishes for that page, then let it finish while still on that stop — confirm the view stays on that panel stop rather than snapping to a different position when the enhanced bitmap swaps in.
   - (b) Separately, land on a page and let the upscale finish while still on the *first* (full-page) panel stop on a wide page — confirm it doesn't jump into `landscapeZoom`'s page-relative auto-zoom (Step 3's fix) and instead stays correctly positioned per the panel-by-panel full-page stop.
   - (c) Confirm plain (non-panel-by-panel) paged mode and webtoon mode still display the enhanced image correctly with the crossfade animation.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/ReaderPageImageView.kt
git commit -m "feat(upscale): wire enhanced-bitmap display into ReaderPageImageView, gate landscapeZoom fallback on panel mode"
```

---

### Task 13: Full on-device validation pass

**Files:** none (validation only).

- [ ] **Step 1: Run the full unit test suite**

Run: `./gradlew :app:testStandardDebugUnitTest`
Expected: all tests pass, including every test added in Tasks 3-7 plus this repo's pre-existing `PanelOrderingTest`/`PanelPipelineTest` (confirming Track B didn't regress panel detection's own test coverage).

- [ ] **Step 2: Full release-profile build sanity check**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`. Confirm APK size grew by roughly the expected ~190MB (native SDK + assets) via `ls -lh app/build/outputs/apk/standard/debug/`.

- [ ] **Step 3: Install and smoke-test on the S24 Ultra via wireless adb**

Toggle upscaling on/off in plain paged mode; toggle in webtoon mode; run the panel-by-panel scenarios from Task 12 Step 5 again end-to-end on-device (not just per-file); page quickly back and forth with upscaling enabled to exercise `ImageEnhancer`'s preemption/reprioritization logic; confirm a page that fails to enhance falls back to the raw image with a status indicator rather than blocking.

- [ ] **Step 4: Confirm cache trim behavior**

Read several chapters with upscaling enabled, then check cache size on-device:

```bash
adb shell run-as app.mihon du -sh files/*enhance* 2>/dev/null || adb shell run-as app.mihon du -sh cache/*enhance* 2>/dev/null
```

(Adjust the path once you've confirmed where `ImageEnhancementCache.init()` actually resolves its storage root from `Step 1`'s `context.cacheDir` usage.) Confirm it stays bounded and does not exceed the ~3GB cap during normal use.

- [ ] **Step 5: Final commit if any fixes were needed during validation**

If Steps 1-4 surfaced any bugs, fix them, re-run the relevant checks, and commit each fix separately with a message describing the specific on-device symptom found — matching this repo's established debugging-methodology convention (evidence before assertions, one root cause per commit).
