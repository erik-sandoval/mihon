package eu.kanade.tachiyomi.ui.reader.viewer

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Animatable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.AttrRes
import androidx.annotation.CallSuper
import androidx.annotation.StyleRes
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.animation.doOnEnd
import androidx.core.os.postDelayed
import androidx.core.view.isVisible
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import coil3.BitmapImage
import coil3.asDrawable
import coil3.dispose
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Precision
import coil3.size.ViewSizeResolver
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView.EASE_IN_OUT_QUAD
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView.EASE_OUT_QUAD
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE
import com.github.chrisbanes.photoview.PhotoView
import eu.kanade.tachiyomi.data.coil.cropBorders
import eu.kanade.domain.manga.model.upscaleEnabledOverride
import eu.kanade.domain.manga.model.upscaleOverride
import eu.kanade.tachiyomi.data.coil.customDecoder
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.MangaUpscaleSettings
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.UpscaleEnabledOverride
import eu.kanade.tachiyomi.ui.reader.setting.resolve
import eu.kanade.tachiyomi.ui.reader.viewer.panel.PanelRect
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonSubsamplingImageView
import eu.kanade.tachiyomi.util.system.animatorDurationScale
import eu.kanade.tachiyomi.util.view.isVisibleOnScreen
import eu.kanade.tachiyomi.util.waifu2x.ImageEnhancementCache
import eu.kanade.tachiyomi.util.waifu2x.ImageEnhancer
import eu.kanade.tachiyomi.util.waifu2x.Waifu2x
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.isActive
import logcat.LogPriority
import mihon.app.di.appGraph
import okio.Buffer
import okio.BufferedSource
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A wrapper view for showing page image.
 *
 * Animated image will be drawn by [PhotoView] while [SubsamplingScaleImageView] will take non-animated image.
 *
 * @param isWebtoon if true, [WebtoonSubsamplingImageView] will be used instead of [SubsamplingScaleImageView]
 * and [AppCompatImageView] will be used instead of [PhotoView]
 */
open class ReaderPageImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    @AttrRes defStyleAttrs: Int = 0,
    @StyleRes defStyleRes: Int = 0,
    private val isWebtoon: Boolean = false,
) : FrameLayout(context, attrs, defStyleAttrs, defStyleRes) {

    private val viewScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val preferences: ReaderPreferences by lazy { Injekt.get<Context>().appGraph.readerPreferences }

    private val realCuganEnabled: Boolean
        get() = resolvedUpscaleEnabledOverride.resolve(preferences.realCuganEnabled().get()) && !dualPageSplitActive

    private val realCuganNoiseLevel: Int
        get() = resolvedUpscaleOverride?.noiseLevel ?: preferences.realCuganNoiseLevel().get()

    private val realCuganScale: Int
        get() = resolvedUpscaleOverride?.scale ?: preferences.realCuganScale().get()

    private val realCuganModel: Int
        get() = resolvedUpscaleOverride?.model ?: preferences.realCuganModel().get()

    private val realEsrganStyle: Int
        get() = resolvedUpscaleOverride?.style ?: preferences.realEsrganStyle().get()

    private val realCuganMaxSizeWidth: Int
        get() = preferences.realCuganMaxSizeWidth().get()

    private val realCuganMaxSizeHeight: Int
        get() = preferences.realCuganMaxSizeHeight().get()

    private val realCuganSkipMaxSizeWidth: Int
        get() = preferences.realCuganSkipMaxSizeWidth().get()

    private val realCuganSkipMaxSizeHeight: Int
        get() = preferences.realCuganSkipMaxSizeHeight().get()

    private val realCuganShowStatus: Boolean
        get() = preferences.realCuganShowStatus().get()

    private val preloadSize: Int
        get() = if (realCuganEnabled) preferences.realCuganPreloadSize().get() else 4

    // realCuganPerformanceMode has no per-view read left — it only ever drove tileSleepMs, which
    // fed the two global-state preference collectors now owned by ReaderActivity instead (see
    // ReaderActivity.onCreate and the init block below).

    private val tileSize: Int
        get() = preferences.realCuganTileSize().get().coerceAtLeast(32)

    private var pageView: View? = null
    private var currentLoadedUri: String? = null
    private var lastStatusText: String? = null

    private var config: Config? = null

    private var isSettingProcessedImage = false
    private var processingJob: Job? = null
    private var enhancedBitmap: Bitmap? = null
    private var processedSwapView: SubsamplingScaleImageView? = null
    private var outgoingProcessedView: SubsamplingScaleImageView? = null
    private var processedSwapAnimator: ValueAnimator? = null

    /** Repeatable factory for the raw (un-enhanced) page source — see [setImage]'s doc on it. */
    private var currentStreamFn: (() -> java.io.InputStream)? = null

    /**
     * True once [pageView] itself (not just [enhancedOverlay]) has been swapped to show an
     * enhanced file — either directly (the [setProcessedSource] `bitmap != null` branch) or via a
     * completed [animateProcessedSwap] crossfade, both of which replace [pageView]'s actual image
     * source rather than merely overlaying it. [refreshEnhancementForCurrentPage] uses this to
     * know whether turning enhancement off needs to reload the raw image, not just hide an
     * overlay.
     */
    private var isShowingEnhancedFile = false

    var onImageLoaded: (() -> Unit)? = null
    var onImageLoadError: ((Throwable?) -> Unit)? = null
    var onScaleChanged: ((newScale: Float) -> Unit)? = null
    var onViewClicked: (() -> Unit)? = null

    /**
     * This page's per-series [eu.kanade.domain.manga.model.upscaleOverride] and
     * [eu.kanade.domain.manga.model.upscaleEnabledOverride] — two independent overrides (see
     * [UpscaleEnabledOverride]'s doc comment for why) — resolved once asynchronously whenever the
     * effective manga id changes (see [refreshResolvedUpscaleOverride]) and cached here for the
     * synchronous `realCugan*` properties below to read — a DB lookup can't happen inline in a
     * plain property getter. Null/[UpscaleEnabledOverride.DEFAULT] while unresolved or absent, in
     * which case those properties fall back to the app-wide [ReaderPreferences] values, same as
     * before either override existed. Declared before [mangaId]/[readerPage] below: their setters
     * call [refreshResolvedUpscaleOverride] immediately, including during their own property
     * initializers, so these need to already be initialized by then, not merely declared later in
     * the file.
     */
    private var resolvedUpscaleOverride: MangaUpscaleSettings? = null
    private var resolvedUpscaleEnabledOverride: UpscaleEnabledOverride = UpscaleEnabledOverride.DEFAULT
    private var resolvedUpscaleOverrideForMangaId: Long = -1L

    /** Helper identity fields for the enhancement pipeline; set by the page holder before/at bind time. */
    var pageIndex: Int = -1
    var mangaId: Long = -1L
        set(value) {
            field = value
            refreshResolvedUpscaleOverride()
        }
    var chapterId: Long = -1L
    var readerPage: ReaderPage? = null
        set(value) {
            field = value
            refreshResolvedUpscaleOverride()
        }

    /**
     * The effective manga id for this view right now — [readerPage] is the primary source (set by
     * most page holders); [mangaId] is a fallback used directly by holders that don't go through a
     * [ReaderPage] (e.g. a composite/secondary view). Mirrors the same fallback already used
     * throughout this class (e.g. [refreshEnhancementForCurrentPage]'s `mId`).
     */
    private val effectiveMangaId: Long
        get() = readerPage?.chapter?.chapter?.manga_id ?: mangaId

    private fun refreshResolvedUpscaleOverride() {
        val targetMangaId = effectiveMangaId
        if (targetMangaId == resolvedUpscaleOverrideForMangaId) return
        resolvedUpscaleOverride = null
        resolvedUpscaleEnabledOverride = UpscaleEnabledOverride.DEFAULT
        resolvedUpscaleOverrideForMangaId = targetMangaId
        if (targetMangaId == -1L) return
        viewScope.launchIO {
            val manga = try {
                Injekt.get<Context>().appGraph.mangaRepository.getMangaById(targetMangaId)
            } catch (e: Exception) {
                null
            }
            withUIContext {
                // Guard against a slow lookup for a since-recycled/rebound-to-a-different-manga
                // view landing after the fact and clobbering a newer, already-correct result.
                if (effectiveMangaId == targetMangaId) {
                    resolvedUpscaleOverride = manga?.upscaleOverride
                    resolvedUpscaleEnabledOverride = manga?.upscaleEnabledOverride ?: UpscaleEnabledOverride.DEFAULT
                }
            }
        }
    }

    /** Alternate source for a page's enhancement key/stream, used when [readerPage] doesn't apply directly. */
    var enhancementVariantOverride: String? = null
    var enhancementStreamOverride: (() -> java.io.InputStream)? = null

    /** Supplies a transformed (already-cropped/adjusted) [BufferedSource] for a cached enhanced file, if needed. */
    var enhancedImageSourceFactory: ((java.io.File) -> BufferedSource?)? = null

    /** Hides the default status label even when [realCuganShowStatus] is on (e.g. a composite/secondary view). */
    var suppressDefaultStatus = false

    /**
     * Whether this instance drives the shared [currentGlobalPageIndex]/reprioritization on
     * [onPageSelected]. False for holders that manage page-selection semantics themselves through
     * a different mechanism (e.g. continuous webtoon scroll).
     */
    var controlsCurrentPageSelection: Boolean = true

    /**
     * Whether a call to [onPageSelected] reflects the page genuinely being read right now, as
     * opposed to a viewer-internal holder instantiation that happens to route through the same
     * call (e.g. `PagerPageHolder`'s `init` block calls `onPageSelected` for every holder
     * ViewPager instantiates, including offscreen neighbors prefetched — per this repo's own
     * `offscreenPageLimit` — before the user ever swipes onto them). The default (true) is
     * correct for every caller except `PagerPageHolder`, which overrides this to consult
     * `PagerViewer.isCurrentReaderPage` — without that distinction, a prefetched neighbor's
     * init-time call would prune the real current page's in-flight enhancement request out of
     * the active window just because the neighbor was instantiated first.
     */
    protected open fun isGenuinePageSelection(): Boolean = true

    private val statusView: TextView by lazy {
        TextView(context).apply {
            layoutParams = LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                setMargins(20, 0, 0, 20)
            }
            setTextColor(Color.WHITE)
            setShadowLayer(5f, 0f, 0f, Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(0, 0, 0, 0)
            isVisible = false
            this@ReaderPageImageView.addView(this)
        }
    }

    private val enhancedOverlay: AppCompatImageView by lazy {
        AppCompatImageView(context).apply {
            layoutParams = LayoutParams(MATCH_PARENT, MATCH_PARENT)
            scaleType = ImageView.ScaleType.FIT_CENTER
            isVisible = false
            this@ReaderPageImageView.addView(this)
        }
    }

    /**
     * The per-view collectors started in [init] (status-label visibility, and re-checking
     * enhancement for whatever page this view currently holds when a relevant setting changes)
     * rather than duplicating identical global state. Cancelled explicitly via
     * [cancelPerViewPreferenceCollector] by holders whose [ReaderPageImageView] instance is truly
     * one-shot (see that method's doc) — never by the shared [onDetachedFromWindow] below, since
     * that also runs for pooled/reused holders (webtoon) that must keep collecting across
     * bind/unbind cycles.
     */
    private var perViewPreferenceJob: Job? = null
    private var enhancementSettingsJob: Job? = null

    init {
        // Debounced internally to run at most once every few minutes; cheap and safe to call
        // from every view instance.
        viewScope.launchIO {
            ImageEnhancementCache.checkAndTrim(context)
        }

        // realCuganPerformanceMode/realCuganTileSize are NOT view-specific — they only ever
        // drive the global Waifu2x.updatePerformance(...) native call, so they're owned once at
        // the reader-activity level (ReaderActivity.onCreate) instead of duplicating an
        // identical collector per page view, each racing to set the same global state.
        perViewPreferenceJob = viewScope.launchIO {
            preferences.realCuganShowStatus().changes()
                .collect { enabled ->
                    withUIContext {
                        if (!enabled) {
                            statusView.isVisible = false
                        } else if (lastStatusText != null) {
                            statusView.text = lastStatusText
                            statusView.isVisible = true
                            statusView.bringToFront()
                        }
                    }
                }
        }

        // Without this, refreshEnhancementForCurrentPage() only ever ran from onPageSelected —
        // so changing e.g. the upscale model while already looking at a page had no visible
        // effect until the page was turned away from and back to (confirmed report: "I have to
        // change model/upscale settings and then switch page and back to see result"). React to
        // any setting that feeds ImageEnhancementCache.getConfigHash, so the currently-displayed
        // page's cache/enqueue status is re-checked against the new settings immediately.
        // debounce coalesces both the ~13 flows' simultaneous replay-on-subscribe emissions into
        // one initial check, and rapid successive changes (e.g. dragging a resolution-limit
        // field) into one re-check instead of firing on every intermediate value.
        enhancementSettingsJob = viewScope.launchIO {
            merge(
                preferences.realCuganEnabled().changes().map { },
                preferences.realCuganNoiseLevel().changes().map { },
                preferences.realCuganScale().changes().map { },
                preferences.realCuganModel().changes().map { },
                preferences.realEsrganStyle().changes().map { },
                preferences.realCuganMaxSizeWidth().changes().map { },
                preferences.realCuganMaxSizeHeight().changes().map { },
                preferences.realCuganSkipMaxSizeWidth().changes().map { },
                preferences.realCuganSkipMaxSizeHeight().changes().map { },
                preferences.realCuganTileSize().changes().map { },
                preferences.realCuganPrecision().changes().map { },
                preferences.realCuganFp16Arithmetic().changes().map { },
                preferences.realCuganProcessingBackend().changes().map { },
            )
                .debounce(400)
                .collectLatest {
                    withUIContext { refreshEnhancementForCurrentPage() }
                }
        }
    }

    /**
     * Cancels the per-view [realCuganShowStatus] and enhancement-settings preference collectors
     * started in [init]. Safe to call ONLY from a holder whose [ReaderPageImageView] instance is
     * truly one-shot — created fresh per page and discarded, never reused (e.g. [PagerPageHolder],
     * torn down by `ViewPagerAdapter.destroyItem` and never handed a different page afterward). Do
     * NOT call this from a pooled/reused holder's teardown (e.g. `WebtoonPageHolder`'s frame,
     * reused by RecyclerView across different pages) — its next `bind()` would silently stop
     * reacting to these preferences for the rest of that instance's life, since nothing would ever
     * restart these collectors.
     */
    protected fun cancelPerViewPreferenceCollector() {
        perViewPreferenceJob?.cancel()
        perViewPreferenceJob = null
        enhancementSettingsJob?.cancel()
        enhancementSettingsJob = null
    }

    private fun updateStatus(text: String?) {
        lastStatusText = text
        post {
            if (suppressDefaultStatus) {
                statusView.isVisible = false
                return@post
            }
            if (!realCuganShowStatus || text == null) {
                statusView.isVisible = false
                return@post
            }
            statusView.text = text
            statusView.isVisible = true
            statusView.bringToFront()
        }
    }

    /**
     * For automatic background. Will be set as background color when [onImageLoaded] is called.
     */
    var pageBackground: Drawable? = null

    @CallSuper
    open fun onImageLoaded() {
        onImageLoaded?.invoke()
        background = pageBackground

        // Keep the processed preview fully covering the page until the replacement image is
        // ready, then switch instantly to avoid exposing SSIV's blank loading state.
        if (isSettingProcessedImage) {
            pageView?.alpha = 1f
            enhancedOverlay.animate().cancel()
            enhancedOverlay.alpha = 0f
            enhancedOverlay.isVisible = false
            enhancedOverlay.setImageBitmap(null)
            enhancedBitmap?.recycle()
            enhancedBitmap = null
            isSettingProcessedImage = false
        }
    }

    @CallSuper
    open fun onImageLoadError(error: Throwable?) {
        onImageLoadError?.invoke(error)

        // Hide overlay and recycle temporary bitmap if enhanced image load failed
        if (isSettingProcessedImage) {
            pageView?.alpha = 1f
            enhancedOverlay.setImageBitmap(null)
            enhancedOverlay.isVisible = false
            enhancedBitmap?.recycle()
            enhancedBitmap = null
            isSettingProcessedImage = false
        }
    }

    @CallSuper
    open fun onScaleChanged(newScale: Float) {
        onScaleChanged?.invoke(newScale)

        if (processedSwapAnimator?.isRunning == true) {
            completeProcessedSwapTransition(notifyLoaded = true)
        }

        // If zooming, dismiss the static overlay immediately to show the zoomable image
        if (newScale != 1f && isSettingProcessedImage) {
            enhancedOverlay.animate().cancel()
            enhancedOverlay.isVisible = false
            enhancedOverlay.setImageBitmap(null)
            enhancedBitmap?.recycle()
            enhancedBitmap = null
            isSettingProcessedImage = false
            pageView?.alpha = 1f
        }
    }

    @CallSuper
    open fun onViewClicked() {
        onViewClicked?.invoke()
    }

    /** Set by [PagerPageHolder] before load starts, when this page belongs to a panel-by-panel viewer. */
    var panelModeActive: Boolean = false

    /**
     * Set by [PagerPageHolder] before load starts, true when dual-page splitting/rotate-to-fit is
     * active for this page. `InsertPage` gives a split half the *same* index and the *same*
     * (full, unsplit) stream as its parent, so it would resolve to the identical enhancement
     * cache/request key as its sibling half — `ImageEnhancer.enhanceLazy`'s de-duplication would
     * silently drop one, and the unsplit spread (nothing wires a per-half stream override) is what
     * would actually get fed to the upscaler. Once that finishes and gets cached, the enhanced
     * *whole spread* would get swapped onto both display halves, undoing the split. Enhancement is
     * skipped entirely for these pages via [realCuganEnabled] below rather than attempting full
     * per-half-variant wiring.
     */
    var dualPageSplitActive: Boolean = false

    private var panelStops: List<PanelRect> = emptyList()
    private var panelStopIndex: Int = -1
    private var panelStopsEnterForward: Boolean = true

    /**
     * Set by [PagerPageHolder] before load starts, to resume on a specific stop (e.g. after the
     * viewer was recreated by a device rotation) instead of the page's first/last stop. Consumed
     * (cleared) the first time [setPanelStops] runs.
     */
    var panelStopIndexOverride: Int? = null

    /** Notified whenever the current panel stop changes, so it can be persisted for restoration. */
    var onPanelStopChanged: ((index: Int) -> Unit)? = null

    /**
     * True once the user pinch-zooms/pans/flings away from the current stop, so
     * [syncPanelStopIndexToCurrentView] knows there's actually a drift to correct for. Without this
     * guard, that resync would also run right after a tap-driven [animateToPanelStop] — reading the
     * view's still-mid-flight center back as "nearest stop" snaps the index back near its start and
     * a rapid next tap re-plays the same short hop instead of advancing, so the reader never visibly
     * progresses through closely-spaced stops. Set on genuine touch/fling center changes; cleared
     * once a stop is set programmatically (its own [onCenterChanged] firing with `ORIGIN_ANIM` never
     * sets it in the first place).
     */
    private var userMovedAwayFromStop: Boolean = false

    /** Dims panels other than the current stop; created lazily the first time it's needed. */
    private var panelSpotlight: PanelSpotlightOverlay? = null

    /** Set by [PagerPageHolder] from the user's preference before load starts. */
    var panelOverlayOpacityPercent: Int = PanelSpotlightOverlay.DEFAULT_OPACITY_PERCENT
        set(value) {
            field = value
            panelSpotlight?.opacityPercent = value
        }

    /** Debug aid: outlines every detected panel with its reading-order number, toggled from settings. */
    var panelShowDebugOrder: Boolean = false
        set(value) {
            field = value
            panelSpotlight?.debugStops = if (value) panelStops.excludingFullPageStops() else null
        }

    /**
     * The intro/outro full-page reveal stops aren't real panels — excluding them from the debug
     * overlay keeps its numbering matching the actual detected/planned panels, instead of being
     * offset by one (or two) for whichever bracketing reveals are enabled. Shown at their actual
     * padded (zoom-region) bounds, same as real reading — a leftover un-merged sliver still shows up
     * as its own small box overlapping its neighbor's padded edge, which is what makes it findable.
     */
    private fun List<PanelRect>.excludingFullPageStops(): List<PanelRect> =
        filterNot { it.width >= FULL_PAGE_DEBUG_THRESHOLD && it.height >= FULL_PAGE_DEBUG_THRESHOLD }

    private fun spotlightFor(view: SubsamplingScaleImageView): PanelSpotlightOverlay {
        panelSpotlight?.let { return it }
        val overlay = PanelSpotlightOverlay(context).also {
            it.sourceView = view
            it.opacityPercent = panelOverlayOpacityPercent
        }
        addView(overlay, MATCH_PARENT, MATCH_PARENT)
        panelSpotlight = overlay
        return overlay
    }

    private fun setSpotlightVisible(visible: Boolean) {
        val overlay = panelSpotlight ?: return
        val target = if (visible) 1f else 0f
        if (overlay.alpha == target) return
        overlay.animate().alpha(target).setDuration(SPOTLIGHT_FADE_MS).start()
    }

    private fun enhancementVariant(): String {
        return enhancementVariantOverride ?: readerPage?.enhancementKeySuffix.orEmpty()
    }

    private fun buildEnhancementDataProvider(
        streamFn: (() -> java.io.InputStream)? = null,
        originalData: Any? = null,
    ): (() -> Any?)? {
        fun buffered(stream: (() -> java.io.InputStream)?): (() -> Any?)? {
            return stream?.let { source ->
                {
                    try {
                        source().use { input -> Buffer().readFrom(input) }
                    } catch (_: Exception) {
                        null
                    }
                }
            }
        }

        enhancementStreamOverride?.let { enhancedStream ->
            return buffered(enhancedStream)
        }

        readerPage?.let { page ->
            return buffered(page.stream) ?: page.imageUrl?.let { { it } }
        }

        return when (originalData) {
            is ReaderPage -> buffered(originalData.stream) ?: originalData.imageUrl?.let { { it } }
            null -> buffered(streamFn)
            else -> buffered(streamFn) ?: { originalData }
        }
    }

    private fun enqueueEnhancement(
        mId: Long,
        cId: Long,
        pIdx: Int,
        highPriority: Boolean,
        streamFn: (() -> java.io.InputStream)? = null,
        originalData: Any? = null,
    ) {
        val dataProvider = buildEnhancementDataProvider(streamFn, originalData) ?: return
        ImageEnhancer.enhanceLazy(
            context = context.applicationContext,
            mangaId = mId,
            chapterId = cId,
            pageIndex = pIdx,
            highPriority = highPriority,
            pageVariant = enhancementVariant(),
            dataProvider = dataProvider,
        )
    }

    fun promoteEnhancementRequest(highPriority: Boolean = true) {
        if (!realCuganEnabled) return

        val mId = readerPage?.chapter?.chapter?.manga_id ?: mangaId
        val cId = readerPage?.chapter?.chapter?.id ?: chapterId
        val pIdx = readerPage?.index ?: pageIndex
        if (pIdx < 0 || mId == -1L || cId == -1L) return

        ImageEnhancementCache.init(context)
        val configHash = ImageEnhancementCache.getConfigHash(
            noise = realCuganNoiseLevel,
            scale = realCuganScale,
            model = realCuganModel,
            realEsrganStyle = realEsrganStyle,
            maxWidth = realCuganMaxSizeWidth,
            maxHeight = realCuganMaxSizeHeight,
            skipMaxWidth = realCuganSkipMaxSizeWidth,
            skipMaxHeight = realCuganSkipMaxSizeHeight,
            tileSize = tileSize,
            precision = preferences.realCuganPrecision().get(),
            fp16Arithmetic = preferences.realCuganFp16Arithmetic().get(),
            processingBackend = preferences.realCuganProcessingBackend().get(),
        )
        val pageVariant = enhancementVariant()

        if (ImageEnhancementCache.getCachedImage(mId, cId, pIdx, configHash, pageVariant) != null) return
        if (ImageEnhancementCache.isSkipped(mId, cId, pIdx, configHash, pageVariant)) return

        enqueueEnhancement(mId, cId, pIdx, highPriority)
    }

    private fun requeueEnhancement(
        mId: Long,
        cId: Long,
        pIdx: Int,
        triggerData: Any?,
        streamFn: (() -> java.io.InputStream)? = null,
        forceCurrentPage: Boolean = false,
    ) {
        val dataProvider = buildEnhancementDataProvider(streamFn, triggerData)

        if (dataProvider == null) {
            logcat(LogPriority.WARN) {
                "ReaderPageImageView: Unable to re-enqueue page $pIdx because source data is unavailable"
            }
            return
        }

        val isCurrent = forceCurrentPage || pIdx == currentGlobalPageIndex || pIdx == ImageEnhancer.targetPageIndex
        logcat(LogPriority.WARN) {
            "ReaderPageImageView: Re-enqueueing page $pIdx after invalid enhanced cache (current=$isCurrent)"
        }

        ImageEnhancer.enhanceLazy(
            context = context.applicationContext,
            mangaId = mId,
            chapterId = cId,
            pageIndex = pIdx,
            highPriority = isCurrent,
            pageVariant = enhancementVariant(),
            dataProvider = dataProvider,
        )
    }

    private suspend fun healInvalidEnhancedCache(
        mId: Long,
        cId: Long,
        pIdx: Int,
        configHash: String,
        triggerData: Any?,
        streamFn: (() -> java.io.InputStream)? = null,
        forceCurrentPage: Boolean = false,
    ) {
        val pageVariant = enhancementVariant()
        val removed = ImageEnhancementCache.removeCachedImage(mId, cId, pIdx, configHash, pageVariant)
        logcat(if (removed) LogPriority.WARN else LogPriority.ERROR) {
            "ReaderPageImageView: Invalid enhanced cache for page $pIdx/$pageVariant removed=$removed"
        }

        withUIContext {
            pageView?.alpha = 1f
            enhancedOverlay.setImageBitmap(null)
            enhancedOverlay.isVisible = false
            enhancedBitmap?.recycle()
            enhancedBitmap = null
            isSettingProcessedImage = false
            updateStatus(context.stringResource(MR.strings.reader_status_processing))
        }

        requeueEnhancement(mId, cId, pIdx, triggerData, streamFn, forceCurrentPage)
    }

    private fun decodeEnhancedBitmap(file: java.io.File): Bitmap? {
        return try {
            val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath) ?: return null
            if (ImageEnhancementCache.isDisplayable(bitmap)) {
                bitmap
            } else {
                logcat(LogPriority.WARN) { "ReaderPageImageView: Ignoring invalid enhanced cache: ${file.absolutePath}" }
                bitmap.recycle()
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun displayedImageRect(view: SubsamplingScaleImageView): RectF? {
        val center = view.center ?: return null
        val scale = view.scale
        val sourceWidth = view.sWidth
        val sourceHeight = view.sHeight
        if (scale <= 0f || sourceWidth <= 0 || sourceHeight <= 0 || view.width <= 0 || view.height <= 0) {
            return null
        }

        val left = view.width / 2f - center.x * scale
        val top = view.height / 2f - center.y * scale
        return RectF(
            left,
            top,
            left + sourceWidth * scale,
            top + sourceHeight * scale,
        )
    }

    private fun clearProcessedSwapView() {
        processedSwapAnimator?.run {
            removeAllUpdateListeners()
            removeAllListeners()
            cancel()
        }
        processedSwapAnimator = null

        val swapView = processedSwapView
        val outgoingView = outgoingProcessedView
        if (swapView != null) {
            if (pageView === swapView) {
                pageView = outgoingView
            }
            swapView.recycle()
            removeView(swapView)
        }
        outgoingView?.alpha = 1f
        outgoingView?.clipBounds = null
        processedSwapView = null
        outgoingProcessedView = null
    }

    private fun completeProcessedSwapTransition(notifyLoaded: Boolean) {
        processedSwapAnimator?.run {
            removeAllUpdateListeners()
            removeAllListeners()
            cancel()
        }
        processedSwapAnimator = null

        val swapView = processedSwapView ?: return
        swapView.alpha = 1f
        swapView.clipBounds = null
        pageView = swapView

        outgoingProcessedView?.let { outgoingView ->
            if (outgoingView !== swapView) {
                outgoingView.recycle()
                removeView(outgoingView)
            }
        }
        processedSwapView = null
        outgoingProcessedView = null

        if (notifyLoaded) {
            onImageLoaded()
        }
    }

    /**
     * Swaps [activeView] (the currently displayed, likely raw-resolution view) for a freshly
     * created one showing the enhanced image, with a short crossfade. Preserves the previous
     * zoom/pan state by remapping it *proportionally* onto the new view's (usually larger)
     * source dimensions — this is dimension-agnostic by construction, so it's mathematically
     * compatible with panel-by-panel's fraction-based panel-stop rects too, without needing any
     * separate panel-aware capture/restore logic here.
     */
    private fun animateProcessedSwap(
        activeView: SubsamplingScaleImageView,
        targetConfig: Config?,
        setImageBlock: SubsamplingScaleImageView.() -> Unit,
    ) {
        // Captured BEFORE clearProcessedSwapView() below: if a second swap starts while an
        // earlier one is still mid-crossfade, `activeView` here is actually that first swap's
        // (already-onReady'd) new view, not a genuinely idle one — clearProcessedSwapView()
        // recycles exactly that view (it's still `processedSwapView` at this point), so reading
        // its geometry afterward would silently come back as post-reset defaults (scale 0,
        // center null), losing whatever zoom/panel-stop position was being carried forward.
        val targetScale = activeView.scale
        val targetCenter = activeView.center?.let { PointF(it.x, it.y) }
        val targetMinScale = activeView.minScale
        val targetWidth = activeView.sWidth
        val targetHeight = activeView.sHeight

        clearProcessedSwapView()

        // Prefer the current pageView over the (possibly now-stale/recycled, see above) activeView
        // parameter: in the normal case they're the same view and this is a no-op; in the
        // concurrent-swap case above, clearProcessedSwapView() already reassigned pageView back to
        // the true outgoing raw view, so using it here avoids re-registering the just-recycled
        // first-swap view as "outgoing" (which would leak the real outgoing view and later
        // double-recycle the first swap's).
        outgoingProcessedView = (pageView as? SubsamplingScaleImageView) ?: activeView
        val swapView = createSubsamplingPageView().apply {
            alpha = 0f
            isVisible = true
            setDoubleTapZoomDuration((targetConfig?.zoomDuration ?: 500).getSystemScaledDuration())
            setMinimumScaleType(targetConfig?.minimumScaleType ?: SCALE_TYPE_CENTER_INSIDE)
            setMinimumDpi(1)
            setCropBorders(targetConfig?.cropBorders ?: false)
            setOnImageEventListener(
                object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
                    override fun onReady() {
                        if (processedSwapView !== this@apply) return

                        setupZoom(targetConfig)

                        // Must run before the wasZoomed/panel-mode branch below: jumpToPanelStop
                        // (and anything else that resolves "the current view" via pageView) needs
                        // to see the NEW view here, not the outgoing one that's about to fade out
                        // and be discarded. Without this hoist, jumpToPanelStop would reposition
                        // the outgoing view (a no-op for what's actually on screen) while the new
                        // view stayed wherever setupZoom() left it — page-fit at minScale, exactly
                        // the fallback position this whole branch exists to avoid.
                        pageView = this@apply

                        val wasZoomed =
                            targetScale > 0f &&
                                targetCenter != null &&
                                targetMinScale > 0f &&
                                targetWidth > 0 &&
                                targetHeight > 0 &&
                                targetScale > targetMinScale + 0.01f

                        if (wasZoomed) {
                            val zoomFactor = targetScale / targetMinScale
                            val mappedCenter = PointF(
                                (targetCenter.x / targetWidth) * sWidth,
                                (targetCenter.y / targetHeight) * sHeight,
                            )
                            val mappedScale = (minScale * zoomFactor).coerceIn(minScale, maxScale)
                            setScaleAndCenter(mappedScale, mappedCenter)
                        } else if (panelModeActive) {
                            // The pre-swap view was showing panel-by-panel's full-page/first stop
                            // (not "zoomed in" per wasZoomed above) — re-derive position from the
                            // current panel stop against this view's own (possibly upscaled)
                            // dimensions instead of falling through to page-relative landscapeZoom,
                            // which has no concept of panel stops at all. Resolves correctly against
                            // this (new) view now that pageView points here (see above).
                            jumpToPanelStop(panelStopIndex)
                        } else if (isVisibleOnScreen()) {
                            landscapeZoom(true)
                        }

                        // spotlightFor() only sets sourceView the first time it creates the
                        // overlay — jumpToPanelStop's own spotlightFor(view) call above is a cache
                        // hit here (the overlay already exists from this page's initial panel
                        // setup) and never re-points it. Without this, sourceToViewCoord keeps
                        // resolving against the outgoing view, which is about to be recycled, and
                        // the dimming silently stops drawing for the rest of this page.
                        panelSpotlight?.sourceView = this@apply

                        bringToFront()
                        statusView.bringToFront()
                        // bringToFront() above just moved this new page view above the spotlight
                        // overlay in z-order (the overlay was added once, well before any swap, and
                        // never re-parented since) — pull it back on top so the dimming still
                        // renders over the page image instead of underneath it.
                        panelSpotlight?.bringToFront()
                        panelSpotlight?.invalidate()
                        val revealStart = 0f
                        val revealEnd = 1f
                        val imageRect = displayedImageRect(this@apply)
                        val contentLeft = imageRect?.left ?: 0f
                        val contentWidth = imageRect?.width() ?: width.toFloat()
                        val viewWidth = width.coerceAtLeast(1)
                        val clipLeft = (contentLeft + contentWidth * revealStart)
                            .roundToInt()
                            .coerceIn(0, viewWidth - 1)
                        val clipRight = (contentLeft + contentWidth * revealEnd)
                            .roundToInt()
                            .coerceIn(clipLeft + 1, viewWidth)
                        clipBounds = Rect(clipLeft, 0, clipRight, height)
                        alpha = 0f

                        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
                            duration = PROCESSED_SWAP_DURATION_MS
                            interpolator = FastOutSlowInInterpolator()
                            addUpdateListener { animator ->
                                alpha = animator.animatedValue as Float
                            }
                            doOnEnd {
                                if (processedSwapAnimator === this) {
                                    processedSwapAnimator = null
                                    completeProcessedSwapTransition(notifyLoaded = true)
                                }
                            }
                        }
                        processedSwapAnimator = animator
                        animator.start()
                    }

                    override fun onImageLoadError(e: Exception) {
                        clearProcessedSwapView()
                        this@ReaderPageImageView.onImageLoadError(e)
                    }
                },
            )
        }
        processedSwapView = swapView
        addView(swapView, 0, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        swapView.setImageBlock()
    }

    /**
     * Displays an already-enhanced page from [file], either via [transformedSource] (a caller
     * supplied, already-adjusted [BufferedSource]) or [bitmap] (a plain decoded bitmap). Crossfades
     * in via [animateProcessedSwap] when a live [SubsamplingScaleImageView] is already showing the
     * raw page; otherwise sets the image directly.
     */
    protected fun setProcessedSource(
        file: java.io.File,
        bitmap: Bitmap? = null,
        transformedSource: BufferedSource? = null,
    ) {
        val uriString = file.toURI().toString()
        updateStatus(context.stringResource(MR.strings.reader_status_processed))

        if (transformedSource != null) {
            val activeView = pageView as? SubsamplingScaleImageView
            if (activeView != null) {
                animateProcessedSwap(activeView, config) {
                    setImage(ImageSource.inputStream(transformedSource.inputStream()))
                }
                isShowingEnhancedFile = true
            } else {
                val previewBitmap = try {
                    android.graphics.BitmapFactory.decodeStream(transformedSource.peek().inputStream())
                } catch (_: Exception) {
                    null
                }

                if (previewBitmap != null) {
                    enhancedOverlay.scaleType = ImageView.ScaleType.FIT_CENTER
                    enhancedOverlay.bringToFront()
                    enhancedOverlay.setImageBitmap(previewBitmap)
                    enhancedOverlay.alpha = 1f
                    enhancedOverlay.isVisible = true
                    statusView.bringToFront()
                    enhancedBitmap = previewBitmap
                    isSettingProcessedImage = true
                    pageView?.alpha = 0f
                } else {
                    pageView?.alpha = 1f
                    enhancedOverlay.animate().cancel()
                    enhancedOverlay.setImageBitmap(null)
                    enhancedOverlay.isVisible = false
                    enhancedBitmap?.recycle()
                    enhancedBitmap = null
                    isSettingProcessedImage = false
                }
            }
            currentLoadedUri = uriString
            isVisible = true
            return
        }

        if (bitmap != null) {
            val activeView = pageView as? SubsamplingScaleImageView
            if (activeView != null) {
                animateProcessedSwap(activeView, config) {
                    val uri = android.net.Uri.fromFile(file)
                    setImage(ImageSource.uri(context, uri))
                }
                // The swap above re-reads the enhanced image straight from the file URI — the
                // caller's already-decoded `bitmap` (used upstream only to validate the cache
                // entry) is never actually displayed on this path and must be recycled here, or
                // it's abandoned as tens of MB of native allocation per swap until GC gets to it.
                bitmap.recycle()
                currentLoadedUri = uriString
                isShowingEnhancedFile = true
                isVisible = true
                return
            }
            enhancedOverlay.scaleType = ImageView.ScaleType.FIT_CENTER
            enhancedOverlay.bringToFront()
            enhancedOverlay.setImageBitmap(bitmap)
            enhancedOverlay.alpha = 1f
            enhancedOverlay.isVisible = true
            statusView.bringToFront()
            enhancedBitmap = bitmap
            isSettingProcessedImage = true
            pageView?.alpha = 0f
        }

        val uri = android.net.Uri.fromFile(file)
        (pageView as? SubsamplingScaleImageView)?.setImage(ImageSource.uri(context, uri))
        currentLoadedUri = uriString
        isShowingEnhancedFile = true
        isVisible = true
    }

    /**
     * Kicks off (or continues) enhancement for the page currently displayed by this SSIV, once
     * its raw image has been set. No-ops when enhancement is off or already in progress for this
     * view, or when the page's identity (manga/chapter/page index) isn't known.
     */
    private fun SubsamplingScaleImageView.processImageHelper(
        streamFn: (() -> java.io.InputStream)? = null,
        originalData: Any? = null,
    ) {
        if (isSettingProcessedImage || !realCuganEnabled) {
            updateStatus(if (realCuganEnabled) null else context.stringResource(MR.strings.reader_status_raw))
            return
        }

        val mId = readerPage?.chapter?.chapter?.manga_id ?: mangaId
        val cId = readerPage?.chapter?.chapter?.id ?: chapterId
        val pIdx = readerPage?.index ?: pageIndex

        if (pIdx < 0 || mId == -1L || cId == -1L) {
            logcat(LogPriority.DEBUG) { "ReaderPageImageView: Skipping enhancement, invalid IDs (m=$mId, c=$cId, p=$pIdx)" }
            return
        }

        ImageEnhancementCache.init(context)
        val configHash = ImageEnhancementCache.getConfigHash(
            noise = realCuganNoiseLevel,
            scale = realCuganScale,
            model = realCuganModel,
            realEsrganStyle = realEsrganStyle,
            maxWidth = realCuganMaxSizeWidth,
            maxHeight = realCuganMaxSizeHeight,
            skipMaxWidth = realCuganSkipMaxSizeWidth,
            skipMaxHeight = realCuganSkipMaxSizeHeight,
            tileSize = tileSize,
            precision = preferences.realCuganPrecision().get(),
            fp16Arithmetic = preferences.realCuganFp16Arithmetic().get(),
            processingBackend = preferences.realCuganProcessingBackend().get(),
        )
        val pageVariant = enhancementVariant()

        val cachedFile = ImageEnhancementCache.getCachedImage(mId, cId, pIdx, configHash, pageVariant)
        if (cachedFile != null) {
            logcat(LogPriority.DEBUG) { "ReaderPageImageView: Page $pIdx found in cache on first check: ${cachedFile.absolutePath}" }
            val transformedSource = enhancedImageSourceFactory?.invoke(cachedFile)
            if (transformedSource != null) {
                setProcessedSource(cachedFile, transformedSource = transformedSource)
            } else {
                val bitmap = decodeEnhancedBitmap(cachedFile)
                if (bitmap != null) {
                    val uri = android.net.Uri.fromFile(cachedFile)
                    setImage(ImageSource.uri(context, uri))
                    currentLoadedUri = cachedFile.toURI().toString()
                    isVisible = true
                    updateStatus(context.stringResource(MR.strings.reader_status_processed))
                } else {
                    viewScope.launchIO {
                        healInvalidEnhancedCache(
                            mId = mId,
                            cId = cId,
                            pIdx = pIdx,
                            configHash = configHash,
                            triggerData = originalData,
                            streamFn = streamFn,
                            forceCurrentPage = pIdx == currentGlobalPageIndex,
                        )
                        startEnhancementPolling(mId, cId, pIdx, configHash, originalData, streamFn)
                    }
                }
            }
            return
        }

        if (ImageEnhancementCache.isSkipped(mId, cId, pIdx, configHash, pageVariant)) {
            logcat(LogPriority.DEBUG) { "ReaderPageImageView: Page $pIdx marked as skipped, showing RAW" }
            updateStatus(context.stringResource(MR.strings.reader_status_raw))
            return
        }

        logcat(LogPriority.DEBUG) { "ReaderPageImageView: Page $pIdx NOT in cache, starting monitoring (m=$mId, c=$cId, config=$configHash)" }

        val triggerDataProvider = buildEnhancementDataProvider(streamFn, originalData)

        if (triggerDataProvider != null) {
            // Use High Priority if this is the current target page (the one user is viewing).
            val isCurrentPage = pIdx == ImageEnhancer.targetPageIndex
            val isInitialTargetPage = currentGlobalPageIndex < 0 && isCurrentPage
            val canEnqueueNow = currentGlobalPageIndex >= 0 || isInitialTargetPage

            if (canEnqueueNow) {
                logcat(LogPriority.DEBUG) { "ReaderPageImageView: Triggering enhancement for page $pIdx. isCurrentPage=$isCurrentPage (target=${ImageEnhancer.targetPageIndex})" }

                ImageEnhancer.enhanceLazy(
                    context = context.applicationContext,
                    mangaId = mId,
                    chapterId = cId,
                    pageIndex = pIdx,
                    highPriority = isCurrentPage,
                    pageVariant = pageVariant,
                    dataProvider = triggerDataProvider,
                )
            } else {
                logcat(LogPriority.DEBUG) {
                    "ReaderPageImageView: Delaying enhancement enqueue for page $pIdx until initial target page ${ImageEnhancer.targetPageIndex} starts"
                }
            }
        }

        startEnhancementPolling(mId, cId, pIdx, configHash, originalData, streamFn)
    }

    /**
     * Start or restart the polling job to monitor enhancement progress and update status.
     */
    private fun startEnhancementPolling(
        mId: Long,
        cId: Long,
        pIdx: Int,
        configHash: String,
        originalData: Any? = null,
        streamFn: (() -> java.io.InputStream)? = null,
    ) {
        processingJob?.cancel()
        processingJob = viewScope.launchIO {
            try {
                val pageVariant = enhancementVariant()
                var attempts = 0
                var wasEnhancing = false
                while (attempts < 120 && isActive) {
                    if (ImageEnhancementCache.isSkipped(mId, cId, pIdx, configHash, pageVariant)) {
                        withUIContext { updateStatus(context.stringResource(MR.strings.reader_status_raw)) }
                        return@launchIO
                    }
                    val file = ImageEnhancementCache.getCachedImage(mId, cId, pIdx, configHash, pageVariant)
                    if (file != null) {
                        logcat(LogPriority.DEBUG) { "ReaderPageImageView: Page $pIdx/$pageVariant found in cache during polling: ${file.absolutePath}" }
                        val transformedSource = enhancedImageSourceFactory?.invoke(file)
                        if (transformedSource != null) {
                            withUIContext {
                                setProcessedSource(file, transformedSource = transformedSource)
                            }
                            return@launchIO
                        }

                        val bitmap = decodeEnhancedBitmap(file)

                        if (bitmap != null) {
                            withUIContext {
                                setProcessedSource(file, bitmap = bitmap)
                            }
                        } else {
                            healInvalidEnhancedCache(
                                mId = mId,
                                cId = cId,
                                pIdx = pIdx,
                                configHash = configHash,
                                triggerData = originalData,
                                streamFn = streamFn,
                                forceCurrentPage = pIdx == currentGlobalPageIndex,
                            )
                            delay(200)
                            continue
                        }

                        return@launchIO
                    }

                    // Check progress status
                    val pid = Waifu2x.getProgressId()
                    if (pid == pIdx) {
                        wasEnhancing = true
                        val rawProgress = Waifu2x.getProgressPercent()
                        if (rawProgress in 0..100) {
                            updateStatus(context.stringResource(MR.strings.reader_status_enhancing_progress, rawProgress))
                        } else {
                            val dots = (rawProgress % 3).let { if (it < 0) -it else it } + 1
                            updateStatus(context.stringResource(MR.strings.reader_status_enhancing) + ".".repeat(dots))
                        }
                    } else if (ImageEnhancer.hasRequest(mId, cId, pIdx, pageVariant)) {
                        if (!wasEnhancing) {
                            updateStatus(context.stringResource(MR.strings.reader_status_queued))
                        } else {
                            updateStatus(context.stringResource(MR.strings.reader_status_finishing))
                        }
                    } else {
                        // Not in queue, not cached - might need to re-enqueue
                        val current = currentGlobalPageIndex
                        val shouldHeal = pIdx >= current && pIdx <= current + preloadSize
                        if (shouldHeal && !ImageEnhancementCache.isSkipped(mId, cId, pIdx, configHash, pageVariant)) {
                            logcat(LogPriority.WARN) { "ReaderPageImageView: Polling re-enqueue page $pIdx/$pageVariant (cur=$current)" }
                            val isCurrent = pIdx == current
                            enqueueEnhancement(mId, cId, pIdx, highPriority = isCurrent, originalData = originalData, streamFn = streamFn)
                        } else {
                            updateStatus(context.stringResource(MR.strings.reader_status_raw))
                            delay(2000)
                            return@launchIO
                        }
                    }

                    delay(500)
                    attempts++
                }
            } catch (_: CancellationException) {
                return@launchIO
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "ReaderPageImageView: Error in polling for page $pIdx" }
            }
        }
    }

    /**
     * Checks cache status and (re)triggers enhancement for the page identity currently held by
     * this view, against whatever the enhancement settings currently are. Called both from
     * [onPageSelected] (a genuine page selection) and reactively from the settings-change
     * collector in [init] — without the latter, changing e.g. the upscale model while already
     * looking at a page had no visible effect until the page was turned away from and back to,
     * since nothing else re-ran this check for a page that was never re-selected.
     */
    private fun refreshEnhancementForCurrentPage() {
        val mId = readerPage?.chapter?.chapter?.manga_id ?: mangaId
        val cId = readerPage?.chapter?.chapter?.id ?: chapterId
        val pIdx = readerPage?.index ?: pageIndex
        if (pIdx < 0 || mId == -1L || cId == -1L) return

        if (realCuganEnabled) {
            ImageEnhancementCache.init(context)
            val configHash = ImageEnhancementCache.getConfigHash(
                noise = realCuganNoiseLevel,
                scale = realCuganScale,
                model = realCuganModel,
                realEsrganStyle = realEsrganStyle,
                maxWidth = realCuganMaxSizeWidth,
                maxHeight = realCuganMaxSizeHeight,
                skipMaxWidth = realCuganSkipMaxSizeWidth,
                skipMaxHeight = realCuganSkipMaxSizeHeight,
                tileSize = tileSize,
                precision = preferences.realCuganPrecision().get(),
                fp16Arithmetic = preferences.realCuganFp16Arithmetic().get(),
                processingBackend = preferences.realCuganProcessingBackend().get(),
            )
            val pageVariant = enhancementVariant()

            val cachedFile = ImageEnhancementCache.getCachedImage(mId, cId, pIdx, configHash, pageVariant)
            if (cachedFile != null) {
                logcat(LogPriority.DEBUG) { "ReaderPageImageView: refreshEnhancementForCurrentPage - Page $pIdx found in cache" }
                val uriString = cachedFile.toURI().toString()
                if (currentLoadedUri != uriString) {
                    viewScope.launchIO {
                        val transformedSource = enhancedImageSourceFactory?.invoke(cachedFile)
                        if (transformedSource != null) {
                            withUIContext {
                                setProcessedSource(cachedFile, transformedSource = transformedSource)
                            }
                            return@launchIO
                        }

                        val bitmap = decodeEnhancedBitmap(cachedFile)

                        if (bitmap != null) {
                            withUIContext {
                                setProcessedSource(cachedFile, bitmap = bitmap)
                            }
                        } else {
                            healInvalidEnhancedCache(mId, cId, pIdx, configHash, readerPage, forceCurrentPage = true)
                            startEnhancementPolling(mId, cId, pIdx, configHash, readerPage)
                        }
                    }
                } else {
                    updateStatus(context.stringResource(MR.strings.reader_status_processed))
                }
            } else if (ImageEnhancementCache.isSkipped(mId, cId, pIdx, configHash, pageVariant)) {
                updateStatus(context.stringResource(MR.strings.reader_status_raw))
            } else {
                updateStatus(context.stringResource(MR.strings.reader_status_processing))
                enqueueEnhancement(mId, cId, pIdx, highPriority = true)
                startEnhancementPolling(mId, cId, pIdx, configHash)
            }
        } else {
            revertToRawImage()
        }

        ImageEnhancer.cancelRequestsLessThan(context.applicationContext, mId, cId, pIdx)
        ImageEnhancer.cancelRequestsGreaterThan(context.applicationContext, mId, cId, pIdx + preloadSize)
    }

    /**
     * Undoes whatever [setProcessedSource] did, so the page goes back to showing its original,
     * un-upscaled pixels once enhancement is turned off (globally, or via a per-series override —
     * see [realCuganEnabled]) for a page that's already displaying an enhanced version. No-ops
     * when nothing enhanced is currently showing.
     *
     * There are two things to undo, independently of each other: the lightweight [enhancedOverlay]
     * preview (tracked by [isSettingProcessedImage]) that sits on top without touching [pageView]
     * itself, and — once a crossfade in [setProcessedSource] completes, or its direct-bitmap
     * branch runs — [pageView]'s own image source having been replaced outright (tracked by
     * [isShowingEnhancedFile]). The latter needs the raw page reloaded from [currentStreamFn]; the
     * former is just a hide.
     */
    private fun revertToRawImage() {
        if (isSettingProcessedImage) {
            pageView?.alpha = 1f
            enhancedOverlay.animate().cancel()
            enhancedOverlay.setImageBitmap(null)
            enhancedOverlay.isVisible = false
            enhancedBitmap?.recycle()
            enhancedBitmap = null
            isSettingProcessedImage = false
        }

        if (isShowingEnhancedFile) {
            clearProcessedSwapView()
            val activeView = pageView as? SubsamplingScaleImageView
            val streamFn = currentStreamFn
            if (activeView != null && streamFn != null) {
                try {
                    activeView.setImage(ImageSource.inputStream(streamFn()))
                } catch (e: Exception) {
                    logcat(LogPriority.WARN, e) { "ReaderPageImageView: Failed to reload raw image after disabling enhancement" }
                }
            }
            currentLoadedUri = null
            isShowingEnhancedFile = false
            updateStatus(context.stringResource(MR.strings.reader_status_raw))
        }
    }

    open fun onPageSelected(forward: Boolean) {
        panelStopsEnterForward = forward

        // Reprioritize the enhancement queue and check cache status for the page actually being
        // viewed — this runs regardless of viewer mode (panel-by-panel or plain paged), but only
        // for a genuine page selection (see isGenuinePageSelection): PagerPageHolder also routes
        // its init-time, offscreen-prefetch call through here, and that call must not prune the
        // real current page's in-flight enhancement request out of the window just because a
        // neighbor holder happened to be instantiated first.
        if (isGenuinePageSelection()) {
            val pIdx = readerPage?.index ?: pageIndex

            if (controlsCurrentPageSelection && pIdx >= 0) {
                currentGlobalPageIndex = pIdx
                ImageEnhancer.reprioritizeAround(pIdx, enhancementVariant())
            }

            refreshEnhancementForCurrentPage()
        }

        if (panelModeActive) {
            // setPanelStops() only ever runs once per holder, the first time its page's image
            // loads — so on a revisit (the holder was never destroyed, panels are already known)
            // this is the only signal that the page is being (re-)entered. Without resetting here,
            // the stop index stays wherever advancing/retreating last left it instead of the
            // boundary stop matching how it's being entered now.
            if (panelStops.isNotEmpty()) {
                panelStopIndex = if (forward) 0 else panelStops.lastIndex
                userMovedAwayFromStop = false
                (pageView as? SubsamplingScaleImageView)?.let { spotlightFor(it).alpha = 1f }
                jumpToPanelStop(panelStopIndex)
                onPanelStopChanged?.invoke(panelStopIndex)
            }
            return
        }
        with(pageView as? SubsamplingScaleImageView) {
            if (this == null) return
            if (isReady) {
                landscapeZoom(forward)
            } else {
                setOnImageEventListener(
                    object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
                        override fun onReady() {
                            setupZoom(config)
                            landscapeZoom(forward)
                            this@ReaderPageImageView.onImageLoaded()
                        }

                        override fun onImageLoadError(e: Exception) {
                            onImageLoadError(e)
                        }
                    },
                )
            }
        }
    }

    /**
     * Sets the ordered list of panel stops (normalized 0f..1f image-fraction coordinates) for
     * panel-by-panel guided navigation, and jumps to the first (or last, if this page was
     * entered backward) stop. Pass an empty list to clear (falls back to a single full-page stop).
     */
    fun setPanelStops(stops: List<PanelRect>, anchorRect: PanelRect? = null, forceFirstStop: Boolean = false) {
        panelStops = stops.ifEmpty { listOf(PanelRect.FULL_PAGE) }
        panelStopIndex = when {
            // Removing the full-page override (see PanelFullPageOverrideRepository) is a fresh
            // entry into real panel detection, not a settings tweak on an already-open stop list —
            // anchoring to the old full-page rect's centre would land on whichever detected panel
            // happens to be closest to the page's geometric centre (not necessarily the first, or
            // even a sensible one), confirmed on-device to misbehave as if the page had already
            // been fully read. Force stop 0 explicitly instead, same as page-grid selection's
            // forceEnterForward.
            forceFirstStop -> 0
            // A settings change (reading direction, intro/outro toggle) re-supplied the stop list
            // for the page currently being read — land on whichever new stop covers roughly the
            // same content the reader was already looking at, instead of jumping to the entry stop.
            anchorRect != null -> nearestPanelStopIndex(anchorRect)
            else -> panelStopIndexOverride?.coerceIn(0, panelStops.lastIndex)
                ?: if (panelStopsEnterForward) 0 else panelStops.lastIndex
        }
        panelStopIndexOverride = null
        userMovedAwayFromStop = false
        if (panelModeActive) {
            (pageView as? SubsamplingScaleImageView)?.let {
                val overlay = spotlightFor(it)
                overlay.alpha = 1f
                if (panelShowDebugOrder) overlay.debugStops = panelStops.excludingFullPageStops()
            }
        }
        jumpToPanelStop(panelStopIndex)
        onPanelStopChanged?.invoke(panelStopIndex)
    }

    /** The stop currently being read, so it can be passed back into [setPanelStops] as an anchor. */
    fun currentPanelStopRect(): PanelRect? = panelStops.getOrNull(panelStopIndex)

    private fun nearestPanelStopIndex(anchor: PanelRect): Int = panelStops.indices.minByOrNull { i ->
        val s = panelStops[i]
        val dx = s.centerX - anchor.centerX
        val dy = s.centerY - anchor.centerY
        dx * dx + dy * dy
    } ?: 0

    fun hasPanelStops(): Boolean = panelStops.isNotEmpty()

    fun canAdvancePanelStop(): Boolean = panelStops.isNotEmpty() && panelStopIndex < panelStops.lastIndex

    fun canRetreatPanelStop(): Boolean = panelStops.isNotEmpty() && panelStopIndex > 0

    fun advancePanelStop() {
        syncPanelStopIndexToCurrentView()
        if (!canAdvancePanelStop()) return
        panelStopIndex++
        animateToPanelStop(panelStopIndex)
        onPanelStopChanged?.invoke(panelStopIndex)
    }

    fun retreatPanelStop() {
        syncPanelStopIndexToCurrentView()
        if (!canRetreatPanelStop()) return
        panelStopIndex--
        animateToPanelStop(panelStopIndex)
        onPanelStopChanged?.invoke(panelStopIndex)
    }

    /**
     * If the user pinch-zoomed away from the current panel stop, find the nearest stop to
     * where they actually are before advancing/retreating, so guided navigation resumes
     * from the right place instead of jumping relative to a stale index.
     */
    private fun syncPanelStopIndexToCurrentView() {
        if (!userMovedAwayFromStop) return
        val view = pageView as? SubsamplingScaleImageView ?: return
        val center = view.center ?: return
        if (panelStops.isEmpty()) return
        val nearestIndex = panelStops.indices.minByOrNull { index ->
            val (_, target) = view.panelStopTarget(panelStops[index])
            val dx = target.x - center.x
            val dy = target.y - center.y
            dx * dx + dy * dy
        } ?: return
        panelStopIndex = nearestIndex
        userMovedAwayFromStop = false
        setSpotlightVisible(true)
    }

    private fun jumpToPanelStop(index: Int) {
        val view = pageView as? SubsamplingScaleImageView ?: return
        val target = panelStops.getOrNull(index) ?: return
        if (panelModeActive) spotlightFor(view).targetRect = target
        if (view.isReady) {
            val (scale, center) = view.panelStopTarget(target)
            view.setScaleAndCenter(scale, center)
        } else {
            view.setOnImageEventListener(
                object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
                    override fun onReady() {
                        view.setupZoom(config)
                        val (scale, center) = view.panelStopTarget(target)
                        view.setScaleAndCenter(scale, center)
                        // targetRect was set above while the view wasn't ready yet, so that
                        // assignment's own invalidate() drew nothing (sourceToViewCoord needs a
                        // ready view). setScaleAndCenter usually re-triggers the state-changed
                        // listener too, but don't rely on that alone — explicitly redraw now that
                        // the view can actually resolve view coordinates, so the spotlight can't
                        // end up stuck undrawn until some later, unrelated stop change.
                        panelSpotlight?.invalidate()
                        this@ReaderPageImageView.onImageLoaded()
                    }

                    override fun onImageLoadError(e: Exception) {
                        onImageLoadError(e)
                    }
                },
            )
        }
    }

    private fun animateToPanelStop(index: Int) {
        val view = pageView as? SubsamplingScaleImageView ?: return
        val target = panelStops.getOrNull(index) ?: return
        if (panelModeActive) spotlightFor(view).targetRect = target
        if (!view.isReady) {
            // pageView is assigned the moment a fresh SubsamplingScaleImageView is created (see
            // prepareNonAnimatedImageView), before its image has actually decoded — a fast
            // advance/retreat tap in that window must not call panelStopTarget() against a view
            // whose sWidth/sHeight aren't set yet: the library's own minScale computation divides
            // by the (still zero) source dimensions, produces Infinity, and the coerceIn(minScale,
            // maxScale) below throws. Confirmed via a real crash (IllegalArgumentException:
            // "maximum ... is less than minimum Infinity") reachable from a plain tap-to-advance
            // gesture. Mirror jumpToPanelStop's existing onReady-deferred handling for the same
            // race instead of animating against an unready view.
            view.setOnImageEventListener(
                object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
                    override fun onReady() {
                        view.setupZoom(config)
                        val (scale, center) = view.panelStopTarget(target)
                        view.setScaleAndCenter(scale, center)
                        panelSpotlight?.invalidate()
                        this@ReaderPageImageView.onImageLoaded()
                    }

                    override fun onImageLoadError(e: Exception) {
                        onImageLoadError(e)
                    }
                },
            )
            return
        }
        val (scale, center) = view.panelStopTarget(target)
        view.animateScaleAndCenter(scale, center)!!
            .withEasing(EASE_OUT_QUAD)
            .withDuration(250)
            .withInterruptible(true)
            .start()
    }

    private fun SubsamplingScaleImageView.panelStopTarget(rect: PanelRect): Pair<Float, PointF> {
        // Uniform scaling can't change a rect's own shape — a fit-both-dimensions scale for a
        // genuinely tall/narrow panel (real width:height below [TALL_PANEL_ASPECT_THRESHOLD]) still
        // stretches it to fill the full screen height edge-to-edge, since that's the axis that
        // binds first. Capping the height budget for that case only shrinks the render (more margin
        // top/bottom too), without cropping or widening the shown content at all.
        //
        // Only applies in portrait (view taller than wide): that's the orientation where the screen
        // itself is generous with vertical space, so an extreme sliver stretches to fill all of it.
        // In landscape the view's own height is already scarce, so the uncapped fit-both scale
        // already shrinks a narrow panel sensibly on its own — piling this cap on top there just
        // makes it needlessly tiny (confirmed on-device: the same panel that looked "super tall and
        // skinny" in portrait looked like a small isolated sliver swimming in blank space once
        // rotated to landscape).
        val realAspect = (rect.width * sWidth) / (rect.height * sHeight)
        val isPortrait = height > width
        val heightBudget = if (isPortrait && realAspect < TALL_PANEL_ASPECT_THRESHOLD) {
            height * MAX_TALL_PANEL_HEIGHT_FRACTION
        } else {
            height.toFloat()
        }
        // In landscape, a panel whose own aspect happens to be close to the screen's own wide
        // aspect fits both dimensions almost exactly, filling the view completely edge-to-edge with
        // zero breathing room — confirmed on-device to look wrong ("I don't like how it fills the
        // entire screen"). Only the width side gets a margin (confirmed: top/bottom should stay as
        // they were) — landscape screens are wide, so left/right is where a panel-by-panel stop
        // actually has room to spare; capping height too would just shrink it for no reason since
        // height was never the complaint. Portrait doesn't get this: a panel-by-panel stop there is
        // rarely wide enough relative to a portrait screen to bind on both axes at once, so it
        // naturally keeps margin on one side already.
        val widthBudget = if (isPortrait) width.toFloat() else width * LANDSCAPE_MAX_FILL_FRACTION
        val targetScale = min(
            widthBudget / (rect.width * sWidth),
            heightBudget / (rect.height * sHeight),
        ).coerceIn(minScale, maxScale)
        val center = PointF(
            (rect.left + rect.width / 2f) * sWidth,
            (rect.top + rect.height / 2f) * sHeight,
        )
        return targetScale to center
    }

    private fun SubsamplingScaleImageView.landscapeZoom(forward: Boolean) {
        if (
            config != null &&
            config!!.landscapeZoom &&
            config!!.minimumScaleType == SCALE_TYPE_CENTER_INSIDE &&
            sWidth > sHeight &&
            scale == minScale
        ) {
            handler?.postDelayed(500) {
                val point = when (config!!.zoomStartPosition) {
                    ZoomStartPosition.LEFT -> if (forward) PointF(0F, 0F) else PointF(sWidth.toFloat(), 0F)
                    ZoomStartPosition.RIGHT -> if (forward) PointF(sWidth.toFloat(), 0F) else PointF(0F, 0F)
                    ZoomStartPosition.CENTER -> center
                }

                val targetScale = height.toFloat() / sHeight.toFloat()
                animateScaleAndCenter(targetScale, point)!!
                    .withDuration(500)
                    .withEasing(EASE_IN_OUT_QUAD)
                    .withInterruptible(true)
                    .start()
            }
        }
    }

    fun setImage(drawable: Drawable, config: Config) {
        this.config = config
        if (drawable is Animatable) {
            prepareAnimatedImageView()
            setAnimatedImage(drawable, config)
        } else {
            prepareNonAnimatedImageView()
            setNonAnimatedImage(drawable, config)
        }
    }

    fun setImage(
        source: BufferedSource,
        isAnimated: Boolean,
        config: Config,
        streamFn: (() -> java.io.InputStream)? = null,
    ) {
        this.config = config
        // Kept around so refreshEnhancementForCurrentPage() can reload the raw page if enhancement
        // gets turned off while this page's enhanced version is already on screen (see
        // isShowingEnhancedFile) — the original BufferedSource is consumed after one read, so a
        // repeatable stream factory is the only way back to the un-enhanced image without
        // re-fetching from the page loader.
        currentStreamFn = streamFn
        isShowingEnhancedFile = false
        if (isAnimated) {
            prepareAnimatedImageView()
            setAnimatedImage(source, config)
        } else {
            prepareNonAnimatedImageView()
            setNonAnimatedImage(source, config, streamFn)
        }
    }

    fun recycle() {
        clearProcessedSwapView()
        pageView?.let {
            processingJob?.cancel()
            processingJob = null

            when (it) {
                is SubsamplingScaleImageView -> it.recycle()
                is AppCompatImageView -> it.dispose()
            }
            it.isVisible = false
            enhancedOverlay.setImageBitmap(null)
            enhancedOverlay.isVisible = false
            enhancedBitmap?.recycle()
            enhancedBitmap = null
            isSettingProcessedImage = false
            isShowingEnhancedFile = false
            currentLoadedUri = null
            invalidate()
        }
    }

    /**
     * Check if the image can be panned to the left
     */
    fun canPanLeft(): Boolean = canPan { it.left }

    /**
     * Check if the image can be panned to the right
     */
    fun canPanRight(): Boolean = canPan { it.right }

    /**
     * Check whether the image can be panned.
     * @param fn a function that returns the direction to check for
     */
    private fun canPan(fn: (RectF) -> Float): Boolean {
        (pageView as? SubsamplingScaleImageView)?.let { view ->
            RectF().let {
                view.getPanRemaining(it)
                return fn(it) > 1
            }
        }
        return false
    }

    /**
     * Pans the image to the left by a screen's width worth.
     */
    fun panLeft() {
        pan { center, view -> center.also { it.x -= view.width / view.scale } }
    }

    /**
     * Pans the image to the right by a screen's width worth.
     */
    fun panRight() {
        pan { center, view -> center.also { it.x += view.width / view.scale } }
    }

    /**
     * Pans the image.
     * @param fn a function that computes the new center of the image
     */
    private fun pan(fn: (PointF, SubsamplingScaleImageView) -> PointF) {
        (pageView as? SubsamplingScaleImageView)?.let { view ->

            val target = fn(view.center ?: return, view)
            view.animateCenter(target)!!
                .withEasing(EASE_OUT_QUAD)
                .withDuration(250)
                .withInterruptible(true)
                .start()
        }
    }

    /**
     * Builds a fresh, fully-configured [SubsamplingScaleImageView] (or [WebtoonSubsamplingImageView]
     * when [isWebtoon]) — shared by [prepareNonAnimatedImageView] (the normal path) and
     * [animateProcessedSwap] (the enhanced-bitmap crossfade path), so both stay in sync on the
     * panel-mode-specific setup (pan limit, zoom/pan disabled) instead of it drifting between two
     * separately-maintained copies.
     */
    private fun createSubsamplingPageView(): SubsamplingScaleImageView {
        return if (isWebtoon) {
            WebtoonSubsamplingImageView(context)
        } else {
            SubsamplingScaleImageView(context)
        }.apply {
            setDoubleTapZoomStyle(SubsamplingScaleImageView.ZOOM_FOCUS_CENTER)
            // PAN_LIMIT_INSIDE (the default elsewhere) clamps the requested center so the image
            // never shows blank space past its edges — but that also stops a panel stop near a
            // page edge from ever actually reaching screen-center; the clamp pulls it back toward
            // the edge instead. Panel-by-panel trades that guarantee for PAN_LIMIT_CENTER, which
            // honors the requested center exactly (letterboxing with blank margin if needed) so
            // every panel, edge or not, lands centered.
            val panLimit = if (panelModeActive) {
                SubsamplingScaleImageView.PAN_LIMIT_CENTER
            } else {
                SubsamplingScaleImageView.PAN_LIMIT_INSIDE
            }
            setPanLimit(panLimit)
            setMinimumTileDpi(180)
            // Panel-by-panel is a fully guided flow — pinch/pan/double-tap would let the reader
            // zoom out from under it. Disabled here (not just left alone) so it can't happen at
            // all until the page is rebuilt for a different reading mode.
            if (panelModeActive) {
                setZoomEnabled(false)
                setPanEnabled(false)
            }
            setOnStateChangedListener(
                object : SubsamplingScaleImageView.OnStateChangedListener {
                    override fun onScaleChanged(newScale: Float, origin: Int) {
                        this@ReaderPageImageView.onScaleChanged(newScale)
                        panelSpotlight?.invalidate()
                    }

                    override fun onCenterChanged(newCenter: PointF?, origin: Int) {
                        if (origin != SubsamplingScaleImageView.ORIGIN_ANIM) {
                            userMovedAwayFromStop = true
                            setSpotlightVisible(false)
                        }
                        panelSpotlight?.invalidate()
                    }
                },
            )
            setOnClickListener { this@ReaderPageImageView.onViewClicked() }
        }
    }

    private fun prepareNonAnimatedImageView() {
        if (pageView is SubsamplingScaleImageView) return
        clearProcessedSwapView()
        removeView(pageView)

        pageView = createSubsamplingPageView()
        addView(pageView, MATCH_PARENT, MATCH_PARENT)
    }

    private fun SubsamplingScaleImageView.setupZoom(config: Config?) {
        // 5x zoom
        maxScale = scale * MAX_ZOOM_SCALE
        setDoubleTapZoomScale(scale * 2)

        when (config?.zoomStartPosition) {
            ZoomStartPosition.LEFT -> setScaleAndCenter(scale, PointF(0F, 0F))
            ZoomStartPosition.RIGHT -> setScaleAndCenter(scale, PointF(sWidth.toFloat(), 0F))
            ZoomStartPosition.CENTER -> setScaleAndCenter(scale, center)
            null -> {}
        }
    }

    private fun setNonAnimatedImage(
        data: Any,
        config: Config,
        streamFn: (() -> java.io.InputStream)? = null,
    ) = (pageView as? SubsamplingScaleImageView)?.apply {
        setDoubleTapZoomDuration(config.zoomDuration.getSystemScaledDuration())
        setMinimumScaleType(config.minimumScaleType)
        setMinimumDpi(1) // Just so that very small image will be fit for initial load
        setCropBorders(config.cropBorders)
        setOnImageEventListener(
            object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
                override fun onReady() {
                    setupZoom(config)
                    if (isVisibleOnScreen()) landscapeZoom(true)
                    this@ReaderPageImageView.onImageLoaded()
                }

                override fun onImageLoadError(e: Exception) {
                    this@ReaderPageImageView.onImageLoadError(e)
                }
            },
        )

        when (data) {
            is BitmapDrawable -> {
                setImage(ImageSource.bitmap(data.bitmap))
                isVisible = true
                processImageHelper(originalData = data.bitmap)
            }
            is BufferedSource -> {
                if (!isWebtoon) {
                    setImage(ImageSource.inputStream(data.inputStream()))
                    isVisible = true

                    if (realCuganEnabled && pageIndex >= 0 && mangaId != -1L) {
                        processImageHelper(streamFn = streamFn)
                    }
                    return@apply
                }

                ImageRequest.Builder(context)
                    .data(data)
                    .memoryCachePolicy(CachePolicy.DISABLED)
                    .diskCachePolicy(CachePolicy.DISABLED)
                    .target(
                        onSuccess = { result ->
                            val image = result as BitmapImage
                            setImage(ImageSource.bitmap(image.bitmap))
                            isVisible = true
                        },
                    )
                    .listener(
                        onError = { _, result ->
                            onImageLoadError(result.throwable)
                        },
                    )
                    .size(ViewSizeResolver(this@ReaderPageImageView))
                    .precision(Precision.INEXACT)
                    .cropBorders(config.cropBorders)
                    .customDecoder(true)
                    .crossfade(false)
                    .build()
                    .let(context.imageLoader::enqueue)

                if (realCuganEnabled && pageIndex >= 0 && mangaId != -1L) {
                    processImageHelper(streamFn = streamFn)
                }
            }
            else -> {
                throw IllegalArgumentException("Not implemented for class ${data::class.simpleName}")
            }
        }
    }

    private fun prepareAnimatedImageView() {
        if (pageView is AppCompatImageView) return
        clearProcessedSwapView()
        removeView(pageView)

        pageView = if (isWebtoon) {
            AppCompatImageView(context)
        } else {
            PhotoView(context)
        }.apply {
            adjustViewBounds = true

            if (this is PhotoView) {
                setScaleLevels(1F, 2F, MAX_ZOOM_SCALE)
                // Force 2 scale levels on double tap
                setOnDoubleTapListener(
                    object : GestureDetector.SimpleOnGestureListener() {
                        override fun onDoubleTap(e: MotionEvent): Boolean {
                            if (scale > 1F) {
                                setScale(1F, e.x, e.y, true)
                            } else {
                                setScale(2F, e.x, e.y, true)
                            }
                            return true
                        }

                        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                            this@ReaderPageImageView.onViewClicked()
                            return super.onSingleTapConfirmed(e)
                        }
                    },
                )
                setOnScaleChangeListener { _, _, _ ->
                    this@ReaderPageImageView.onScaleChanged(scale)
                }
            }
        }
        addView(pageView, MATCH_PARENT, MATCH_PARENT)
    }

    private fun setAnimatedImage(
        data: Any,
        config: Config,
    ) = (pageView as? AppCompatImageView)?.apply {
        if (this is PhotoView) {
            setZoomTransitionDuration(config.zoomDuration.getSystemScaledDuration())
        }

        val request = ImageRequest.Builder(context)
            .data(data)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .diskCachePolicy(CachePolicy.DISABLED)
            .target(
                onSuccess = { result ->
                    val drawable = result.asDrawable(context.resources)
                    setImageDrawable(drawable)
                    (drawable as? Animatable)?.start()
                    isVisible = true
                    this@ReaderPageImageView.onImageLoaded()
                },
            )
            .listener(
                onError = { _, result ->
                    onImageLoadError(result.throwable)
                },
            )
            .crossfade(false)
            .build()
        context.imageLoader.enqueue(request)
    }

    private fun Int.getSystemScaledDuration(): Int {
        return (this * context.animatorDurationScale).toInt().coerceAtLeast(1)
    }

    /**
     * All of the config except [zoomDuration] will only be used for non-animated image.
     */
    data class Config(
        val zoomDuration: Int,
        val minimumScaleType: Int = SCALE_TYPE_CENTER_INSIDE,
        val cropBorders: Boolean = false,
        val zoomStartPosition: ZoomStartPosition = ZoomStartPosition.CENTER,
        val landscapeZoom: Boolean = false,
    )

    enum class ZoomStartPosition {
        LEFT,
        CENTER,
        RIGHT,
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        processingJob?.cancel()
        processingJob = null

        enhancedOverlay.setImageBitmap(null)
        enhancedBitmap?.recycle()
        enhancedBitmap = null
        isSettingProcessedImage = false
        currentLoadedUri = null
        clearProcessedSwapView()
    }

    companion object {
        var currentGlobalPageIndex: Int = -1
    }
}

private const val MAX_ZOOM_SCALE = 5F
private const val SPOTLIGHT_FADE_MS = 150L
private const val FULL_PAGE_DEBUG_THRESHOLD = 0.98f

/** Real-pixel width:height below which a panel-by-panel stop counts as "tall/narrow" for [MAX_TALL_PANEL_HEIGHT_FRACTION]. */
private const val TALL_PANEL_ASPECT_THRESHOLD = 0.5f

/** Max fraction of the view's height a tall/narrow panel stop is allowed to render at, so it doesn't stretch edge-to-edge. */
private const val MAX_TALL_PANEL_HEIGHT_FRACTION = 0.6f

/** Max fraction of the view's width a panel-by-panel stop is allowed to fill in landscape, so it always keeps a small left/right margin. */
private const val LANDSCAPE_MAX_FILL_FRACTION = 0.92f

private const val PROCESSED_SWAP_DURATION_MS = 280L
