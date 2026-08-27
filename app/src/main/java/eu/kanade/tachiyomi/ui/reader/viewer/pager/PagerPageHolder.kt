package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.annotation.SuppressLint
import android.content.Context
import android.view.LayoutInflater
import androidx.core.view.isVisible
import eu.kanade.presentation.util.formattedMessage
import eu.kanade.tachiyomi.databinding.ReaderErrorBinding
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderProgressIndicator
import eu.kanade.tachiyomi.ui.reader.viewer.panel.Panel
import eu.kanade.tachiyomi.ui.reader.viewer.panel.PanelRect
import eu.kanade.tachiyomi.ui.reader.viewer.panel.SpeechBubblePanelSubStopGenerator
import eu.kanade.tachiyomi.ui.reader.viewer.panel.flattenToStops
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import eu.kanade.tachiyomi.widget.ViewPagerAdapter
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import logcat.LogPriority
import okio.Buffer
import okio.BufferedSource
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR

/**
 * View of the ViewPager that contains a page of a chapter.
 */
@SuppressLint("ViewConstructor")
class PagerPageHolder(
    readerThemedContext: Context,
    val viewer: PagerViewer,
    val page: ReaderPage,
) : ReaderPageImageView(readerThemedContext), ViewPagerAdapter.PositionableView {

    /**
     * Item that identifies this view. Needed by the adapter to not recreate views.
     */
    override val item
        get() = page

    /**
     * Loading progress bar to indicate the current progress.
     */
    private var progressIndicator: ReaderProgressIndicator? = null // = ReaderProgressIndicator(readerThemedContext)

    /**
     * Error layout to show when the image fails to load.
     */
    private var errorLayout: ReaderErrorBinding? = null

    private val scope = MainScope()

    /**
     * Job for loading the page and processing changes to the page's status.
     */
    private var loadJob: Job? = null

    /**
     * Job keeping the spotlight's opacity in sync with the user's setting. Needed because this
     * holder is created once and can stay alive (and on-screen) for as long as its page is, so a
     * one-shot read at init time would miss changes made from the settings dialog while reading.
     */
    private var opacityJob: Job? = null

    /**
     * Job keeping the intro/outro full-page stops in sync with the user's settings, for the same
     * reason as [opacityJob]. Re-flattens the already-detected [detectedPanels] rather than
     * re-running detection.
     */
    private var introOutroJob: Job? = null

    /**
     * Job re-detecting panels when reading direction changes, for the same reason as [opacityJob].
     * Direction isn't just a display concern — it changes both the reading order and the
     * merge/divide profile the pipeline applies (see PanelPipeline), so unlike the intro/outro
     * toggle this needs a full re-detect rather than just re-flattening [detectedPanels].
     */
    private var directionJob: Job? = null

    /** Job keeping the debug panel-order overlay in sync with the user's setting, for the same reason as [opacityJob]. */
    private var debugOrderJob: Job? = null

    /** The raw detected panels for this page, cached here so intro/outro toggles can be reapplied without re-detecting. */
    private var detectedPanels: List<Panel>? = null

    /**
     * True while this page's panel detection is still running. Detection is async (up to
     * [eu.kanade.tachiyomi.ui.reader.viewer.panel.PanelDetector]'s own budget), so there's a real
     * window right after a page appears where [hasPanelStops] is still false simply because
     * nothing has come back yet — not because the page truly has no panels. Tapping/swiping during
     * that window must not be read as "no stops here, turn the page" (see [PagerViewer.moveRight]/
     * [PagerViewer.moveLeft]), or a chapter's freshly-loaded first page skips straight to its
     * second page on the very first interaction.
     */
    fun isDetectingPanels(): Boolean = viewer is PanelByPanelViewer && detectedPanels == null

    /** The page's image bytes, kept around so [directionJob] can re-run detection without reloading the page. */
    private var panelImageBytes: Buffer? = null

    init {
        // Identity fields consumed by ReaderPageImageView's enhancement pipeline (Task 12) — must
        // be set before the first onPageSelected()/setImage() call below, since without them the
        // enhancement enqueue/cache-check logic can't resolve a manga/chapter/page identity and
        // silently no-ops.
        pageIndex = page.index
        mangaId = viewer.activity.viewModel.manga?.id ?: -1L
        chapterId = page.chapter.chapter.id ?: -1L
        readerPage = page
        // InsertPage (a dual-page-split half) shares its parent's index and full-spread stream,
        // which would otherwise collide with its sibling half's enhancement cache/request key and
        // let the unsplit spread get swapped in once cached (see dualPageSplitActive's doc).
        // Mirrors the panel-detection guard in setImage() below (viewer.config.dualPageSplit).
        dualPageSplitActive = viewer.config.dualPageSplit || viewer.config.dualPageRotateToFit

        if (viewer is PanelByPanelViewer) {
            panelModeActive = true
            // Seed the entry stop from the most recent real navigation direction, since ViewPager
            // prefetches an offscreen neighbor's holder (and its panel detection) before the user
            // actually swipes onto it — the "enter forward" default would otherwise win the race
            // and land on the first panel even while the user is navigating backward.
            onPageSelected(viewer.lastNavigationForward)
            panelOverlayOpacityPercent = viewer.readerPreferences.panelByPanelOverlayOpacity.get()
            opacityJob = scope.launch {
                viewer.readerPreferences.panelByPanelOverlayOpacity.changes().collectLatest {
                    panelOverlayOpacityPercent = it
                }
            }
            panelShowDebugOrder = viewer.readerPreferences.panelByPanelShowDebugOrder.get()
            debugOrderJob = scope.launch {
                viewer.readerPreferences.panelByPanelShowDebugOrder.changes().collectLatest {
                    panelShowDebugOrder = it
                }
            }
            introOutroJob = scope.launch {
                combine(
                    viewer.readerPreferences.panelByPanelShowFullPageIntro.changes(),
                    viewer.readerPreferences.panelByPanelShowFullPageOutro.changes(),
                ) { showIntro, showOutro -> showIntro to showOutro }
                    .collectLatest { (showIntro, showOutro) ->
                        val panels = detectedPanels ?: return@collectLatest
                        setPanelStops(
                            panels.flattenToStops(showIntro = showIntro && page.index == 0, showOutro = showOutro),
                            anchorRect = currentPanelStopRect(),
                        )
                    }
            }
            directionJob = scope.launch {
                // Skip the initial replay: it fires before the page has even loaded (panelImageBytes
                // is still null then), so it would be a redundant no-op racing the first natural
                // detect call from loadPanels() — only react to an actual later toggle (of either
                // the per-series override or the app-wide default it falls back to).
                viewer.panelDirectionFlow.drop(1).collectLatest {
                    val imageBytes = panelImageBytes ?: return@collectLatest
                    try {
                        loadPanels(viewer, imageBytes, anchorRect = currentPanelStopRect())
                    } catch (e: Throwable) {
                        logcat(LogPriority.ERROR, e) { "Panel re-detection failed for page ${page.index}" }
                    }
                }
            }
            val viewModel = viewer.activity.viewModel
            viewModel.state.value.savedPanelStop?.let { saved ->
                if (saved.pageIndex == page.index) panelStopIndexOverride = saved.stopIndex
            }
            onPanelStopChanged = { index ->
                // Guard against offscreen-neighbor holders (see isCurrentReaderPage) overwriting
                // the actually-visible page's saved position with their own entry stop.
                if (viewer.isCurrentReaderPage(page)) viewModel.savePanelStop(page.index, index)
            }
        }
        loadJob = scope.launch { loadPageAndProcessStatus() }
    }

    /**
     * Called when this view is detached from the window. Unsubscribes any active subscription.
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        loadJob?.cancel()
        loadJob = null
        opacityJob?.cancel()
        opacityJob = null
        introOutroJob?.cancel()
        introOutroJob = null
        directionJob?.cancel()
        directionJob = null
        debugOrderJob?.cancel()
        debugOrderJob = null
        // Safe here specifically because PagerPageHolder is one-shot: ViewPagerAdapter.destroyItem
        // discards this instance for good, it's never rebound to a different page afterward. See
        // cancelPerViewPreferenceCollector's own doc for why the shared base-class
        // onDetachedFromWindow can't do this unconditionally (WebtoonPageHolder's frame is reused).
        cancelPerViewPreferenceCollector()
    }

    private fun initProgressIndicator() {
        if (progressIndicator == null) {
            progressIndicator = ReaderProgressIndicator(context)
            addView(progressIndicator)
        }
    }

    /**
     * Loads the page and processes changes to the page's status.
     *
     * Returns immediately if the page has no PageLoader.
     * Otherwise, this function does not return. It will continue to process status changes until
     * the Job is cancelled.
     */
    private suspend fun loadPageAndProcessStatus() {
        val loader = page.chapter.pageLoader ?: return

        supervisorScope {
            launchIO {
                loader.loadPage(page)
            }
            page.statusFlow.collectLatest { state ->
                when (state) {
                    Page.State.Queue -> setQueued()
                    Page.State.LoadPage -> setLoading()
                    Page.State.DownloadImage -> {
                        setDownloading()
                        page.progressFlow.collectLatest { value ->
                            progressIndicator?.setProgress(value)
                        }
                    }
                    Page.State.Ready -> setImage()
                    is Page.State.Error -> setError(state.error)
                }
            }
        }
    }

    /**
     * Called when the page is queued.
     */
    private fun setQueued() {
        initProgressIndicator()
        progressIndicator?.show()
        removeErrorLayout()
    }

    /**
     * Called when the page is loading.
     */
    private fun setLoading() {
        initProgressIndicator()
        progressIndicator?.show()
        removeErrorLayout()
    }

    /**
     * Called when the page is downloading.
     */
    private fun setDownloading() {
        initProgressIndicator()
        progressIndicator?.show()
        removeErrorLayout()
    }

    /**
     * Called when the page is ready.
     */
    private suspend fun setImage() {
        progressIndicator?.setProgress(0)

        val streamFn = page.stream ?: return

        try {
            val (source, isAnimated, background, panelSourceBytes) = withIOContext {
                val source = streamFn().use { process(item, Buffer().readFrom(it)) }
                val isAnimated = ImageUtil.isAnimatedAndSupported(source)
                val background = if (!isAnimated && viewer.config.automaticBackground) {
                    ImageUtil.chooseBackground(context, source.peek().inputStream())
                } else {
                    null
                }
                val panelSourceBytes = if (
                    !isAnimated && viewer is PanelByPanelViewer && !viewer.config.dualPageSplit
                ) {
                    Buffer().apply { writeAll(source.peek()) }
                } else {
                    null
                }
                PageLoadResult(source, isAnimated, background, panelSourceBytes)
            }
            withUIContext {
                setImage(
                    source,
                    isAnimated,
                    Config(
                        zoomDuration = viewer.config.doubleTapAnimDuration,
                        minimumScaleType = viewer.config.imageScaleType,
                        cropBorders = viewer.config.imageCropBorders,
                        zoomStartPosition = viewer.config.imageZoomType,
                        landscapeZoom = viewer.config.landscapeZoom,
                    ),
                )
                if (!isAnimated) {
                    pageBackground = background
                }
                removeErrorLayout()
            }
            if (panelSourceBytes != null && viewer is PanelByPanelViewer) {
                panelImageBytes = panelSourceBytes
                try {
                    loadPanels(viewer, panelSourceBytes)
                } catch (e: Throwable) {
                    logcat(LogPriority.ERROR, e) { "Panel detection failed for page ${page.index}" }
                }
            }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e)
            withUIContext {
                setError(e)
            }
        }
    }

    private data class PageLoadResult(
        val source: BufferedSource,
        val isAnimated: Boolean,
        val background: android.graphics.drawable.Drawable?,
        val panelSourceBytes: Buffer?,
    )

    /**
     * Re-runs panel detection for this page on demand — e.g. after the user toggles the
     * full-page override from the page-actions menu (see [PanelFullPageOverrideRepository]).
     * A one-shot counterpart to [directionJob]'s reactive re-detect, for a change that's a
     * direct, immediate user action on this specific page rather than an ambient preference any
     * open page holder needs to react to.
     *
     * [forceFirstStop]: pass true when this re-detection is a fresh entry into real panels
     * rather than a tweak on an already-open stop list — e.g. the override was just removed —
     * so the reader lands on stop 0 instead of anchoring to wherever the old (single, full-page)
     * stop's centre happened to be.
     */
    fun refreshPanels(forceFirstStop: Boolean = false) {
        val panelByPanelViewer = viewer as? PanelByPanelViewer ?: return
        val imageBytes = panelImageBytes ?: return
        scope.launch {
            try {
                loadPanels(
                    panelByPanelViewer,
                    imageBytes,
                    anchorRect = if (forceFirstStop) null else currentPanelStopRect(),
                    forceFirstStop = forceFirstStop,
                )
            } catch (e: Throwable) {
                logcat(LogPriority.ERROR, e) { "Panel re-detection failed for page ${page.index}" }
            }
        }
    }

    private suspend fun loadPanels(
        viewer: PanelByPanelViewer,
        imageBytes: Buffer,
        anchorRect: PanelRect? = null,
        forceFirstStop: Boolean = false,
    ) {
        val panels = viewer.panelDetector.detect(page, imageBytes, viewer.panelDirection)
        detectedPanels = expandForCurrentView(panels, viewer)
        val stops = detectedPanels!!.flattenToStops(
            // Only the chapter's first page gets the reveal — showIntro isn't a "every page"
            // toggle, it's specifically for orienting the reader when a new chapter begins.
            showIntro = viewer.readerPreferences.panelByPanelShowFullPageIntro.get() && page.index == 0,
            showOutro = viewer.readerPreferences.panelByPanelShowFullPageOutro.get(),
        )
        withUIContext {
            setPanelStops(stops, anchorRect = anchorRect, forceFirstStop = forceFirstStop)
        }
    }

    /**
     * Runs [SpeechBubblePanelSubStopGenerator] per panel against this holder's *current* view
     * dimensions when the feature is enabled, producing a fresh [Panel] list with [Panel.subStops]
     * populated accordingly. Always orientation-agnostic detection stays cached; this step never
     * is — it re-runs every time this is called (initial load, direction change, rotation-fresh
     * holder, or the reactive toggle in [bubbleStopsJob]).
     */
    private suspend fun expandForCurrentView(panels: List<Panel>, viewer: PanelByPanelViewer): List<Panel> {
        if (!viewer.readerPreferences.panelByPanelBubbleStopsEnabled().get()) return panels
        return panels.map { panel ->
            val subStops = SpeechBubblePanelSubStopGenerator.generate(
                panel, viewer.panelDirection, width, height,
            ) { null }
            panel.copy(subStops = subStops)
        }
    }

    /**
     * Releases memory that's safe to drop when this page isn't the one currently on screen —
     * called from [PagerViewer.onTrimMemory] under system memory pressure. Only [panelImageBytes]
     * (the raw encoded page bytes kept around so [directionJob] can re-detect without reloading)
     * is meaningful to drop here; if a direction toggle arrives after this, [directionJob]'s
     * existing `imageBytes ?: return@collectLatest` guard already treats a missing buffer as a
     * safe no-op — the page just doesn't re-detect until it's revisited.
     */
    fun releaseOffscreenMemory() {
        if (viewer.isCurrentReaderPage(page)) return
        panelImageBytes = null
    }

    private fun process(page: ReaderPage, imageSource: BufferedSource): BufferedSource {
        if (viewer.config.dualPageRotateToFit) {
            return rotateDualPage(imageSource)
        }

        if (!viewer.config.dualPageSplit) {
            return imageSource
        }

        if (page is InsertPage) {
            return splitInHalf(imageSource)
        }

        val isDoublePage = ImageUtil.isWideImage(imageSource)
        if (!isDoublePage) {
            return imageSource
        }

        onPageSplit(page)

        return splitInHalf(imageSource)
    }

    private fun rotateDualPage(imageSource: BufferedSource): BufferedSource {
        val isDoublePage = ImageUtil.isWideImage(imageSource)
        return if (isDoublePage) {
            val rotation = if (viewer.config.dualPageRotateToFitInvert) -90f else 90f
            ImageUtil.rotateImage(imageSource, rotation)
        } else {
            imageSource
        }
    }

    private fun splitInHalf(imageSource: BufferedSource): BufferedSource {
        var side = when {
            viewer is L2RPagerViewer && page is InsertPage -> ImageUtil.Side.RIGHT
            viewer !is L2RPagerViewer && page is InsertPage -> ImageUtil.Side.LEFT
            viewer is L2RPagerViewer && page !is InsertPage -> ImageUtil.Side.LEFT
            viewer !is L2RPagerViewer && page !is InsertPage -> ImageUtil.Side.RIGHT
            else -> error("We should choose a side!")
        }

        if (viewer.config.dualPageInvert) {
            side = when (side) {
                ImageUtil.Side.RIGHT -> ImageUtil.Side.LEFT
                ImageUtil.Side.LEFT -> ImageUtil.Side.RIGHT
            }
        }

        return ImageUtil.splitInHalf(imageSource, side)
    }

    private fun onPageSplit(page: ReaderPage) {
        val newPage = InsertPage(page)
        viewer.onPageSplit(page, newPage)
    }

    /**
     * Called when the page has an error.
     */
    private fun setError(error: Throwable?) {
        progressIndicator?.hide()
        showErrorLayout(error)
    }

    /**
     * The base class's [onPageSelected] also drives enhancement-queue pruning
     * (`cancelRequestsLessThan`/`cancelRequestsGreaterThan`) and the shared
     * `currentGlobalPageIndex` — but [init] calls `onPageSelected` for every holder ViewPager
     * instantiates, including offscreen neighbors prefetched (`offscreenPageLimit`) before the
     * user actually swipes onto them. Without this override, a prefetched neighbor's init-time
     * call would prune the real current page's in-flight enhancement request out of the active
     * window just because the neighbor was instantiated first. [PagerViewer.isCurrentReaderPage]
     * is the same check already used by [onPanelStopChanged] above for an analogous problem
     * (a prefetched neighbor's own async result clobbering the real current page's saved state).
     */
    override fun isGenuinePageSelection(): Boolean = viewer.isCurrentReaderPage(page)

    override fun onImageLoaded() {
        super.onImageLoaded()
        progressIndicator?.hide()
    }

    /**
     * Called when an image fails to decode.
     */
    override fun onImageLoadError(error: Throwable?) {
        super.onImageLoadError(error)
        setError(error)
    }

    /**
     * Called when an image is zoomed in/out.
     */
    override fun onScaleChanged(newScale: Float) {
        super.onScaleChanged(newScale)
        viewer.activity.hideMenu()
    }

    private fun showErrorLayout(error: Throwable?): ReaderErrorBinding {
        if (errorLayout == null) {
            errorLayout = ReaderErrorBinding.inflate(LayoutInflater.from(context), this, true)
            errorLayout?.actionRetry?.viewer = viewer
            errorLayout?.actionRetry?.setOnClickListener {
                page.chapter.pageLoader?.retryPage(page)
            }
        }

        val imageUrl = page.imageUrl
        errorLayout?.actionOpenInWebView?.isVisible = imageUrl != null
        if (imageUrl != null) {
            if (imageUrl.startsWith("http", true)) {
                errorLayout?.actionOpenInWebView?.viewer = viewer
                errorLayout?.actionOpenInWebView?.setOnClickListener {
                    val sourceId = viewer.activity.viewModel.manga?.source

                    val intent = WebViewActivity.newIntent(context, imageUrl, sourceId)
                    context.startActivity(intent)
                }
            }
        }

        errorLayout?.errorMessage?.text = with(context) { error?.formattedMessage }
            ?: context.stringResource(MR.strings.decode_image_error)

        errorLayout?.root?.isVisible = true
        return errorLayout!!
    }

    /**
     * Removes the decode error layout from the holder, if found.
     *
     * Must actually detach the inflated view, not just hide it and drop the reference — leaving
     * it attached meant a page cycling through error -> ready -> error (e.g. a flaky source)
     * inflated and added a brand new [ReaderErrorBinding] each time via [showErrorLayout], while
     * every previous one stayed attached to this ViewGroup forever, invisible and orphaned.
     */
    private fun removeErrorLayout() {
        errorLayout?.root?.let { removeView(it) }
        errorLayout = null
    }
}
