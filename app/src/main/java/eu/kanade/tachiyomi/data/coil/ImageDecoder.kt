package eu.kanade.tachiyomi.data.coil

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import ca.mpreg.imagedecoder.ImageDecoder
import coil3.Canvas
import coil3.Image
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.DecodeUtils
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import eu.kanade.domain.manga.model.upscaleEnabledOverride
import eu.kanade.domain.manga.model.upscaleOverride
import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.UpscaleEnabledOverride
import eu.kanade.tachiyomi.ui.reader.setting.resolve
import eu.kanade.tachiyomi.util.system.GLUtil
import eu.kanade.tachiyomi.util.waifu2x.ImageEnhancementCache
import eu.kanade.tachiyomi.util.waifu2x.Waifu2x
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import logcat.LogPriority
import mihon.app.di.appGraph
import okio.BufferedSource
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A [Decoder] that uses [ImageDecoder] (libvips-based) to decode image formats not supported
 * by the Android system decoder (AVIF, JXL, HEIF, etc.).
 *
 * It also applies on-the-fly Waifu2x-family image enhancement (Real-CUGAN/Real-ESRGAN/etc.)
 * when the request is tagged as [Options.isEnhanced], serving an already-enhanced disk-cache
 * hit directly when available and otherwise upscaling the freshly decoded bitmap.
 */
class ImageDecoder(private val resources: ImageSource, private val options: Options) : Decoder {

    class DecodeResultImage(val res: ImageDecoder.DecodeResult) : Image {
        override val size: Long get() = res.image.capacity().toLong()
        override val width: Int get() = res.width
        override val height: Int get() = res.height
        override val shareable: Boolean get() = true
        override fun draw(canvas: Canvas) {}
    }

    override suspend fun decode(): DecodeResult {
        // Serve an already-enhanced cache hit without decoding the original source at all.
        decodeCachedEnhancedImage()?.let { cachedBitmap ->
            return DecodeResult(
                image = cachedBitmap.asImage(),
                isSampled = false,
            )
        }

        val decoder = resources.source().use {
            try {
                ImageDecoder.new(it.inputStream())
            } catch (e: ImageDecoder.DecodeException) {
                logcat(LogPriority.ERROR, e) { "ImageDecoder.new failed: ${e.message}" }
                null
            }
        }

        check(decoder != null && decoder.pages > 0) { "Failed to initialize decoder" }

        val res = decoder.decode()

        val srcWidth = res.width
        val srcHeight = res.height

        if (options.newDecoder) {
            return DecodeResult(
                image = DecodeResultImage(res),
                isSampled = false,
            )
        }

        val dstWidth = options.size.widthPx(options.scale) { srcWidth }
        val dstHeight = options.size.heightPx(options.scale) { srcHeight }
        val sampleSize = DecodeUtils.calculateInSampleSize(
            srcWidth = srcWidth,
            srcHeight = srcHeight,
            dstWidth = dstWidth,
            dstHeight = dstHeight,
            scale = options.scale,
        )

        val fullBitmap = createBitmap(srcWidth, srcHeight)
        res.image.rewind()
        fullBitmap.copyPixelsFromBuffer(res.image)

        val bitmap = if (sampleSize > 1) {
            val scaledWidth = (srcWidth / sampleSize).coerceAtLeast(1)
            val scaledHeight = (srcHeight / sampleSize).coerceAtLeast(1)
            val scaled = fullBitmap.scale(scaledWidth, scaledHeight)
            fullBitmap.recycle()
            scaled
        } else {
            fullBitmap
        }

        val finalBitmap = applyEnhancementIfNeeded(bitmap)

        return DecodeResult(
            image = finalBitmap.asImage(),
            isSampled = sampleSize > 1,
        )
    }

    /**
     * Serves an already-enhanced bitmap straight from disk cache, skipping the source decode
     * entirely. Returns null when enhancement isn't applicable/enabled, the request is missing
     * its manga/chapter/page tags, or nothing is cached yet for the current config (including
     * when a cached file exists but fails to decode/display, in which case it's removed).
     */
    private suspend fun decodeCachedEnhancedImage(): Bitmap? {
        if (!options.isEnhanced()) return null

        val preferences = readerPreferences()
        val mangaId = options.mangaIdOrNull() ?: return null
        val settings = resolveUpscaleSettings(mangaId, preferences)
        if (!settings.enabled) return null

        val chapterId = options.chapterIdOrNull() ?: return null
        val pageIndex = options.pageIndexOrNull() ?: return null
        val pageVariant = options.pageVariantOrNull() ?: ""

        val context = Injekt.get<Context>()
        ImageEnhancementCache.init(context)

        val configHash = buildConfigHash(preferences, settings)
        val cachedFile = ImageEnhancementCache.getCachedImage(mangaId, chapterId, pageIndex, configHash, pageVariant)
            ?: return null

        val cachedBitmap = BitmapFactory.decodeFile(cachedFile.absolutePath)
        if (cachedBitmap != null && ImageEnhancementCache.isDisplayable(cachedBitmap)) {
            logcat(LogPriority.DEBUG) {
                "ImageDecoder: Page $pageIndex/$pageVariant served from enhanced cache before source decode"
            }
            return cachedBitmap
        }

        cachedBitmap?.recycle()
        ImageEnhancementCache.removeCachedImage(mangaId, chapterId, pageIndex, configHash, pageVariant)
        return null
    }

    /**
     * Runs the configured Waifu2x-family model over [bitmap] when the request is tagged for
     * enhancement and the feature is enabled. Falls back to returning [bitmap] unchanged when
     * enhancement isn't applicable, or on any failure during processing — enhancement failure
     * must never break the whole decode.
     */
    private suspend fun applyEnhancementIfNeeded(bitmap: Bitmap): Bitmap {
        if (!options.isEnhanced()) return bitmap

        val preferences = readerPreferences()
        val mangaId = options.mangaIdOrNull() ?: return bitmap
        val settings = resolveUpscaleSettings(mangaId, preferences)
        if (!settings.enabled) return bitmap

        val chapterId = options.chapterIdOrNull() ?: return bitmap
        val pageIndex = options.pageIndexOrNull() ?: return bitmap
        val pageVariant = options.pageVariantOrNull() ?: ""

        return enhance(bitmap, mangaId, chapterId, pageIndex, pageVariant, preferences, settings)
    }

    /**
     * The effective per-manga upscale settings this decoder actually needs — [enabled] and the
     * [eu.kanade.tachiyomi.ui.reader.setting.MangaUpscaleSettings] content fields resolved
     * separately (see [UpscaleEnabledOverride]'s doc comment for why they're independent
     * per-series overrides rather than one bundled struct), then flattened back into a single
     * object here since every downstream caller (`buildConfigHash`/`enhance`) just wants to read
     * all five values together.
     */
    private data class ResolvedUpscaleSettings(
        val enabled: Boolean,
        val model: Int,
        val noiseLevel: Int,
        val scale: Int,
        val style: Int,
        val incognito: Boolean,
    )

    /**
     * Resolves the effective upscale settings for [mangaId] — its per-series overrides
     * ([eu.kanade.domain.manga.model.upscaleEnabledOverride]/[eu.kanade.domain.manga.model.upscaleOverride])
     * where set, otherwise the app-wide [ReaderPreferences] values. Device-performance knobs
     * (tile size, precision, backend, max/skip resolution, etc.) are always read straight from
     * [preferences] by callers — they're never part of either per-series override.
     *
     * Also resolves [ResolvedUpscaleSettings.incognito] here since it's the same manga lookup —
     * [enhance] uses it to skip [ImageEnhancementCache] writes entirely for incognito reading, the
     * same way [eu.kanade.tachiyomi.ui.reader.ReaderViewModel] already skips history/progress.
     */
    private suspend fun resolveUpscaleSettings(mangaId: Long, preferences: ReaderPreferences): ResolvedUpscaleSettings {
        val appGraph = Injekt.get<Context>().appGraph
        val manga = try {
            appGraph.mangaRepository.getMangaById(mangaId)
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "ImageDecoder: Failed to resolve per-series upscale override for manga $mangaId" }
            null
        }
        val content = manga?.upscaleOverride
        val enabledOverride = manga?.upscaleEnabledOverride ?: UpscaleEnabledOverride.DEFAULT
        val incognito = GetIncognitoState(appGraph.basePreferences, appGraph.sourcePreferences, appGraph.extensionManager)
            .await(manga?.source)
        return ResolvedUpscaleSettings(
            enabled = enabledOverride.resolve(preferences.realCuganEnabled().get()),
            model = content?.model ?: preferences.realCuganModel().get(),
            noiseLevel = content?.noiseLevel ?: preferences.realCuganNoiseLevel().get(),
            scale = content?.scale ?: preferences.realCuganScale().get(),
            style = content?.style ?: preferences.realEsrganStyle().get(),
            incognito = incognito,
        )
    }

    private suspend fun enhance(
        source: Bitmap,
        mangaId: Long,
        chapterId: Long,
        pageIndex: Int,
        pageVariant: String,
        preferences: ReaderPreferences,
        settings: ResolvedUpscaleSettings,
    ): Bitmap {
        // A single mutable reference is used throughout so that, whatever happens (including an
        // exception caught below), it always points to a valid, non-recycled bitmap to return.
        var bitmap = source
        val context = Injekt.get<Context>()
        ImageEnhancementCache.init(context)
        val configHash = buildConfigHash(preferences, settings)

        try {
            val model = settings.model
            val realEsrganStyle = settings.style
            val noise = settings.noiseLevel
            val scale = settings.scale

            // --- Resolution limit gate: skip enhancement entirely for oversized sources ---
            val skipMaxWidth = preferences.realCuganSkipMaxSizeWidth().get()
            val skipMaxHeight = preferences.realCuganSkipMaxSizeHeight().get()
            val exceedsSkipLimit = (skipMaxWidth > 0 && bitmap.width > skipMaxWidth) ||
                (skipMaxHeight > 0 && bitmap.height > skipMaxHeight)

            if (exceedsSkipLimit) {
                logcat(LogPriority.DEBUG) {
                    "ImageDecoder: Skipping enhancement for page $pageIndex - source " +
                        "${bitmap.width}x${bitmap.height} exceeds max resolution ${skipMaxWidth}x$skipMaxHeight"
                }
                if (!settings.incognito) {
                    ImageEnhancementCache.saveSkippedToCache(mangaId, chapterId, pageIndex, configHash, pageVariant)
                }
                return bitmap
            }

            // --- Prescale down to the configured processing max size, if any ---
            val processMaxWidth = preferences.realCuganMaxSizeWidth().get()
            val processMaxHeight = preferences.realCuganMaxSizeHeight().get()
            val hasProcessMaxResolution = processMaxWidth > 0 || processMaxHeight > 0
            if (hasProcessMaxResolution) {
                val widthRatio = if (processMaxWidth > 0) {
                    processMaxWidth / bitmap.width.toFloat()
                } else {
                    Float.POSITIVE_INFINITY
                }
                val heightRatio = if (processMaxHeight > 0) {
                    processMaxHeight / bitmap.height.toFloat()
                } else {
                    Float.POSITIVE_INFINITY
                }
                val ratio = min(widthRatio, heightRatio)
                if (ratio in 0f..<1f) {
                    val newWidth = max(1, (bitmap.width * ratio).roundToInt())
                    val newHeight = max(1, (bitmap.height * ratio).roundToInt())
                    logcat(LogPriority.DEBUG) {
                        "ImageDecoder: Prescaling page $pageIndex input ${bitmap.width}x${bitmap.height} -> " +
                            "${newWidth}x$newHeight"
                    }
                    val scaledBitmap = scaleBitmap(bitmap, newWidth, newHeight)
                    if (scaledBitmap !== bitmap) {
                        bitmap.recycle()
                        bitmap = scaledBitmap
                    }
                }
            }

            currentCoroutineContext().ensureActive()

            // --- Performance mode ---
            val perfMode = preferences.realCuganPerformanceMode().get()
            val tileSleepMs = when (perfMode) {
                1 -> 5
                2 -> 15
                else -> 0
            }
            val tileSize = preferences.realCuganTileSize().get().coerceAtLeast(32)
            val precision = preferences.realCuganPrecision().get().coerceIn(0, 3)
            val fp16Arithmetic = preferences.realCuganFp16Arithmetic().get()

            val effectiveScale = ImageEnhancementCache.getEffectiveScale(model, scale, realEsrganStyle)
            val processingBackend = Waifu2x.resolveProcessingBackend(
                preferences.realCuganProcessingBackend().get(),
                model,
                effectiveScale,
            )

            val initialized = when (model) {
                0 -> Waifu2x.initRealCugan(
                    context, noise, effectiveScale, isPro = false, tileSleepMs = tileSleepMs,
                    tileSize = tileSize, precision = precision, fp16Arithmetic = fp16Arithmetic,
                    processingBackend = processingBackend,
                )
                1 -> Waifu2x.initRealCugan(
                    context, noise, effectiveScale, isPro = true, tileSleepMs = tileSleepMs,
                    tileSize = tileSize, precision = precision, fp16Arithmetic = fp16Arithmetic,
                    processingBackend = processingBackend,
                )
                Waifu2x.MODEL_REAL_ESRGAN_ANIME -> Waifu2x.initRealESRGAN(
                    context, effectiveScale, style = realEsrganStyle, tileSleepMs = tileSleepMs,
                    tileSize = tileSize, precision = precision, fp16Arithmetic = fp16Arithmetic,
                    processingBackend = processingBackend,
                )
                3 -> Waifu2x.initNose(
                    context, tileSleepMs = tileSleepMs, tileSize = tileSize,
                    precision = precision, fp16Arithmetic = fp16Arithmetic,
                )
                4 -> Waifu2x.initWaifu2x(
                    context, noise, effectiveScale, tileSleepMs = tileSleepMs, tileSize = tileSize,
                    precision = precision, fp16Arithmetic = fp16Arithmetic,
                )
                5 -> Waifu2x.initWaifu2xUpconv7(
                    context, noise, effectiveScale, tileSleepMs = tileSleepMs, tileSize = tileSize,
                    precision = precision, fp16Arithmetic = fp16Arithmetic,
                )
                else -> if (Waifu2x.isW2xExModel(model)) {
                    Waifu2x.initW2xEx(
                        context, model, scale = effectiveScale, tileSleepMs = tileSleepMs,
                        tileSize = tileSize, precision = precision, fp16Arithmetic = fp16Arithmetic,
                        processingBackend = processingBackend,
                    )
                } else {
                    Waifu2x.initRealCugan(
                        context, noise, effectiveScale, tileSleepMs = tileSleepMs, tileSize = tileSize,
                        precision = precision, fp16Arithmetic = fp16Arithmetic,
                        processingBackend = processingBackend,
                    )
                }
            }

            val processed = if (initialized) {
                when (model) {
                    0, 1 -> Waifu2x.processRealCugan(bitmap, pageIndex)
                    Waifu2x.MODEL_REAL_ESRGAN_ANIME -> Waifu2x.processRealESRGAN(bitmap, pageIndex)
                    3 -> Waifu2x.processNose(bitmap, pageIndex)
                    4, 5 -> Waifu2x.processWaifu2x(bitmap, pageIndex)
                    else -> if (Waifu2x.isW2xExModel(model)) {
                        Waifu2x.processW2xEx(bitmap, pageIndex)
                    } else {
                        Waifu2x.processRealCugan(bitmap, pageIndex)
                    }
                }
            } else {
                null
            }

            if (processed == null) return bitmap

            var result: Bitmap = processed
            var ownsResult = true
            try {
                currentCoroutineContext().ensureActive()

                // --- Output resolution limit (avoid exceeding the device's max GL texture size) ---
                val textureLimit = GLUtil.DEVICE_TEXTURE_LIMIT
                if (result.width > textureLimit || result.height > textureLimit) {
                    val widthRatio = textureLimit.toFloat() / result.width
                    val heightRatio = textureLimit.toFloat() / result.height
                    val ratio = min(widthRatio, heightRatio)
                    val newWidth = (result.width * ratio).toInt().coerceAtLeast(1)
                    val newHeight = (result.height * ratio).toInt().coerceAtLeast(1)
                    logcat(LogPriority.DEBUG) {
                        "ImageDecoder: Output downscale page $pageIndex: ${result.width}x${result.height} -> " +
                            "${newWidth}x$newHeight (texture limit $textureLimit)"
                    }
                    val downscaled = scaleBitmap(result, newWidth, newHeight)
                    if (downscaled !== result) {
                        result.recycle()
                        result = downscaled
                    }
                }

                return if (ImageEnhancementCache.isDisplayable(result)) {
                    // Still enhance and display the page for incognito reading — only the
                    // persisted disk-cache write-back is skipped, same as ReaderViewModel already
                    // skips history/progress for incognito.
                    if (!settings.incognito) {
                        // enqueueSaveToCache takes ownership of whatever bitmap it's given and
                        // recycles it (synchronously on a cold cache dir/pending-save collision,
                        // or asynchronously once the background save completes — either way,
                        // unconditionally, success or reject; see its doc comment). `result` is
                        // also what we're about to return for display, so it must never be the
                        // same object handed to the cache — otherwise the cache pipeline can
                        // recycle the page's own display bitmap out from under Coil/the pager
                        // mid-draw. Hand the cache a private copy instead.
                        val cacheBitmap = result.copy(result.config ?: Bitmap.Config.ARGB_8888, false)
                        if (cacheBitmap != null) {
                            ImageEnhancementCache.enqueueSaveToCache(
                                mangaId,
                                chapterId,
                                pageIndex,
                                configHash,
                                cacheBitmap,
                                pageVariant,
                            )
                        } else {
                            logcat(LogPriority.WARN) {
                                "ImageDecoder: Failed to copy enhanced result for cache write-back, page $pageIndex/$pageVariant"
                            }
                        }
                    }
                    ownsResult = false
                    // The pre-enhancement source was consumed to produce `result` and is no
                    // longer needed now that we're committed to returning `result` for display.
                    if (bitmap !== result && !bitmap.isRecycled) {
                        bitmap.recycle()
                    }
                    result
                } else {
                    logcat(LogPriority.ERROR) {
                        "ImageDecoder: Page $pageIndex/$pageVariant produced a nearly transparent result, " +
                            "keeping original image"
                    }
                    bitmap
                }
            } finally {
                if (ownsResult && result !== bitmap && !result.isRecycled) {
                    result.recycle()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "ImageDecoder: Failed to enhance image on-the-fly for page $pageIndex/$pageVariant" }
            return bitmap
        }
    }

    private fun readerPreferences(): ReaderPreferences = Injekt.get<Context>().appGraph.readerPreferences

    private fun buildConfigHash(preferences: ReaderPreferences, settings: ResolvedUpscaleSettings): String {
        return ImageEnhancementCache.getConfigHash(
            noise = settings.noiseLevel,
            scale = settings.scale,
            model = settings.model,
            realEsrganStyle = settings.style,
            maxWidth = preferences.realCuganMaxSizeWidth().get(),
            maxHeight = preferences.realCuganMaxSizeHeight().get(),
            skipMaxWidth = preferences.realCuganSkipMaxSizeWidth().get(),
            skipMaxHeight = preferences.realCuganSkipMaxSizeHeight().get(),
            tileSize = preferences.realCuganTileSize().get(),
            precision = preferences.realCuganPrecision().get(),
            fp16Arithmetic = preferences.realCuganFp16Arithmetic().get(),
            processingBackend = preferences.realCuganProcessingBackend().get(),
        )
    }

    private fun scaleBitmap(source: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        if (source.width == targetWidth && source.height == targetHeight) return source
        return Waifu2x.scaleBitmapNative(source, max(1, targetWidth), max(1, targetHeight))
            ?: Bitmap.createScaledBitmap(source, max(1, targetWidth), max(1, targetHeight), true)
    }

    class Factory : Decoder.Factory {
        override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? {
            return if (options.newDecoder || options.customDecoder || isApplicable(result.source.source())) {
                ImageDecoder(result.source, options)
            } else {
                null
            }
        }

        private fun isApplicable(source: BufferedSource): Boolean {
            val type = source.peek().inputStream().use {
                ImageUtil.findImageType(it)
            }
            return when (type) {
                ImageUtil.ImageType.AVIF,
                ImageUtil.ImageType.JXL,
                ImageUtil.ImageType.HEIF,
                ImageUtil.ImageType.JP2,
                -> true

                else -> false
            }
        }

        override fun equals(other: Any?) = other is Factory

        override fun hashCode() = javaClass.hashCode()
    }
}
