package eu.kanade.tachiyomi.ui.reader.viewer.panel

import android.content.Context
import android.graphics.Canvas
import android.graphics.PointF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import eu.kanade.tachiyomi.ui.reader.viewer.isCompactWidth
import eu.kanade.tachiyomi.ui.reader.viewer.panelStopScaleAndCenter
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Custom SubsamplingScaleImageView for Guided View (Panel-by-Panel mode).
 *
 * Enforces strict geometric bounding boxes on zoom and pan so that:
 * 1. Double-tapping zooms in and out centered precisely on the panel's true center on the very first try.
 * 2. Dedicated tap-timing logic ensures flings never desynchronize or block double-tap gestures.
 * 3. The camera can never zoom out smaller than the panel's base framing scale.
 * 4. When zoomed in, panning and flinging are locked within the active panel rectangle (never exposes black void or other panels).
 * 5. High-velocity fling physics are pre-clamped to stop seamlessly at the panel barrier.
 * 6. Screen rotation (portrait <-> landscape) dynamically recalculates base scales and re-frames the active panel.
 * 7. Panning (drag or fling, at any zoom level) never moves past the panel's own edge on either
 *    axis, and an axis whose zoomed content already fits the viewport is locked to the panel's
 *    center on that axis rather than drifting inside slack space it doesn't need (see
 *    [clampAxis]) — enforced every frame in [onDraw] via [constrainPanLive], not just from touch
 *    events, because the base view's own internal fling/momentum (confirmed against the library
 *    source: it runs through the same per-frame [onDraw] animation-tick as the explicit
 *    AnimationBuilder API, invalidating itself each frame) can keep moving the view for many
 *    frames after the last ACTION_UP with no further onTouchEvent calls arriving.
 */
class PanelSubsamplingImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SubsamplingScaleImageView(context, attrs) {

    var activePanelRect: PanelRect? = null
    var panelBaseScale: Float = 0f
    var panelBaseCenter: PointF = PointF()
    var isProgrammaticAnimating: Boolean = false

    private var lastTapTime: Long = 0L
    private var lastTapX: Float = 0f
    private var lastTapY: Float = 0f
    private var downX: Float = 0f
    private var downY: Float = 0f
    private var downTime: Long = 0L

    init {
        setQuickScaleEnabled(false)
        installDedicatedTileExecutor()
    }

    /**
     * Reflectively replaces the base view's private `executor` field (confirmed against the
     * library source: `private Executor executor = AsyncTask.THREAD_POOL_EXECUTOR`, with no
     * public setter) with [dedicatedTileExecutor], a pool used only by
     * [PanelSubsamplingImageView] instances. Panel-by-panel mode does much more aggressive,
     * fast panning across a single high-res page than normal page browsing, which can burst many
     * tile-decode requests at once — on the shared app-wide `AsyncTask.THREAD_POOL_EXECUTOR`,
     * those queue behind whatever other unrelated AsyncTasks happen to be running elsewhere in
     * the app at that moment. This doesn't make any individual tile decode faster (still
     * CPU-bound native bitmap-region decoding) or eliminate contention between a page and its own
     * preloaded neighbors, but it does stop *unrelated* background work from adding to the
     * pop-in/pixelation window during a fast swipe.
     *
     * Best-effort: this is a private library internal with no supported API for it, so a failure
     * here (e.g. a future library version renames or removes the field) silently falls back to
     * the shared pool rather than crashing the reader over a decode-latency optimization.
     */
    private fun installDedicatedTileExecutor() {
        try {
            val field = SubsamplingScaleImageView::class.java.getDeclaredField("executor")
            field.isAccessible = true
            field.set(this, dedicatedTileExecutor)
        } catch (e: ReflectiveOperationException) {
            logcat(LogPriority.WARN) {
                "PanelSubsamplingImageView: couldn't install dedicated tile executor, " +
                    "falling back to the shared pool: $e"
            }
        }
    }

    private val panelFlingDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            val rect = activePanelRect ?: return false
            if (!isReady || sWidth <= 0 || sHeight <= 0 || width <= 0 || height <= 0) return false
            val s = scale
            if (s <= 0f || s.isNaN()) return false
            if (s <= panelBaseScale * 1.08f) return false

            val vW = width.toFloat() / s
            val vH = height.toFloat() / s

            val pLeft = rect.left * sWidth
            val pRight = rect.right * sWidth
            val pTop = rect.top * sHeight
            val pBottom = rect.bottom * sHeight

            val currentCenter = center ?: return false
            val vTranslateEnd = PointF(
                (width / 2f - currentCenter.x * s) + (velocityX * 0.25f),
                (height / 2f - currentCenter.y * s) + (velocityY * 0.25f),
            )
            val rawCenterXEnd = ((width / 2f) - vTranslateEnd.x) / s
            val rawCenterYEnd = ((height / 2f) - vTranslateEnd.y) / s

            val targetCenterX = clampAxis(rawCenterXEnd, vW, pLeft, pRight)
            val targetCenterY = clampAxis(rawCenterYEnd, vH, pTop, pBottom)

            animateCenter(PointF(targetCenterX, targetCenterY))
                ?.withEasing(EASE_OUT_QUAD)
                ?.withDuration(300)
                ?.withInterruptible(true)
                ?.start()
            return true
        }
    })

    fun performDoubleTapZoom() {
        val rect = activePanelRect ?: return
        if (!isReady || sWidth <= 0 || sHeight <= 0 || width <= 0 || height <= 0) return

        // Always dynamically calculate base target for current live width & height
        val (baseScale, baseCenter) = computePanelTarget(rect, width, height)
        panelBaseScale = baseScale
        panelBaseCenter = baseCenter
        setMinimumScaleType(SCALE_TYPE_CUSTOM)
        minScale = baseScale
        maxScale = baseScale * 3.0f

        val currentScale = scale

        if (currentScale > baseScale * 1.08f) {
            // Zoom OUT: Animate smoothly to exact panel base scale and panel base center (non-interruptible so 1 double-tap completes 100%)
            isProgrammaticAnimating = true
            animateScaleAndCenter(baseScale, baseCenter)
                ?.withDuration(250)
                ?.withEasing(EASE_OUT_QUAD)
                ?.withInterruptible(false)
                ?.withOnAnimationEventListener(object : DefaultOnAnimationEventListener() {
                    override fun onComplete() {
                        isProgrammaticAnimating = false
                        clampToPanelBounds()
                    }
                    override fun onInterruptedByUser() {
                        isProgrammaticAnimating = false
                    }
                    override fun onInterruptedByNewAnim() {
                        isProgrammaticAnimating = false
                    }
                })
                ?.start()
        } else {
            // Zoom IN: 2x zoom centered on panel
            val targetZoomScale = (baseScale * 2f).coerceIn(baseScale, maxScale)
            isProgrammaticAnimating = true
            animateScaleAndCenter(targetZoomScale, baseCenter)
                ?.withDuration(250)
                ?.withEasing(EASE_OUT_QUAD)
                ?.withInterruptible(false)
                ?.withOnAnimationEventListener(object : DefaultOnAnimationEventListener() {
                    override fun onComplete() {
                        isProgrammaticAnimating = false
                        clampToPanelBounds()
                    }
                    override fun onInterruptedByUser() {
                        isProgrammaticAnimating = false
                    }
                    override fun onInterruptedByNewAnim() {
                        isProgrammaticAnimating = false
                    }
                })
                ?.start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val rect = activePanelRect
        if (rect != null && isReady && sWidth > 0 && sHeight > 0 && (w != oldw || h != oldh)) {
            val (newScale, newCenter) = computePanelTarget(rect, w, h)
            panelBaseScale = newScale
            panelBaseCenter = newCenter
            setMinimumScaleType(SCALE_TYPE_CUSTOM)
            minScale = newScale
            maxScale = newScale * 3.0f
            setDoubleTapZoomScale(newScale * 2.0f)
            setScaleAndCenter(newScale, newCenter)
        }
    }

    /**
     * Delegates to the shared [panelStopScaleAndCenter] so this view's own rotation/clamp paths
     * frame a panel identically to [ReaderPageImageView]'s navigation path — both write
     * [panelBaseScale], so a divergence here would make a panel jump size on rotate. See the
     * "two independent pipelines must agree" note in CLAUDE.md.
     */
    fun computePanelTarget(rect: PanelRect, viewW: Int, viewH: Int): Pair<Float, PointF> {
        val t = panelStopScaleAndCenter(rect, viewW, viewH, sWidth, sHeight, isCompactWidth(context))
        return t.scale to PointF(t.centerX, t.centerY)
    }

    override fun onDraw(canvas: Canvas) {
        // Catches every source of motion uniformly — touch-driven pan, our own panelFlingDetector,
        // and (the gap the onTouchEvent-only correction missed) the base view's own internal
        // fling/momentum, which keeps calling onDraw + invalidate() on its own for many frames
        // after the last real touch event.
        //
        // Must run BEFORE super.onDraw, not after: confirmed against the library source,
        // setScaleAndCenter nulls its internal 'anim' and stages the position as pending, which
        // onDraw's own animation-tick block (skipped once anim is null) would otherwise apply —
        // i.e. the correction only actually reaches this frame's painted tiles if it lands before
        // super.onDraw runs. Correcting after (the first attempt here) left the image itself
        // painted one frame behind, while PanelSpotlightOverlay — a sibling view that reads this
        // view's live scale/center fresh in its own onDraw, later in the same traversal — used the
        // already-corrected value immediately, so the overlay visibly led the image during a fling.
        if (activePanelRect != null && isReady && !isProgrammaticAnimating) {
            constrainPanLive()
        }
        super.onDraw(canvas)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isProgrammaticAnimating) {
            return true
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downTime = SystemClock.uptimeMillis()
            }
            MotionEvent.ACTION_UP -> {
                val upTime = SystemClock.uptimeMillis()
                val moveDistX = abs(event.x - downX)
                val moveDistY = abs(event.y - downY)
                val isStaticTap = moveDistX < 35f && moveDistY < 35f && (upTime - downTime) < 300L

                if (isStaticTap) {
                    val tapInterval = upTime - lastTapTime
                    val tapDistX = abs(event.x - lastTapX)
                    val tapDistY = abs(event.y - lastTapY)
                    val isDoubleTap = tapInterval < 320L && tapDistX < 100f && tapDistY < 100f

                    if (isDoubleTap) {
                        lastTapTime = 0L
                        performDoubleTapZoom()
                        return true
                    } else {
                        lastTapTime = upTime
                        lastTapX = event.x
                        lastTapY = event.y
                    }
                } else {
                    // Motion/fling resets tap tracking so flings can never prime a double tap
                    lastTapTime = 0L
                }
            }
        }

        // Delegate flings to panel fling handler (only active when zoomed in)
        val flingHandled = panelFlingDetector.onTouchEvent(event)
        if (flingHandled) {
            return true
        }

        // When at base panel scale with a single touch, prevent SubsamplingScaleImageView from dragging/panning the page!
        val isZoomed = isReady && panelBaseScale > 0f && scale > panelBaseScale * 1.08f
        if (!isZoomed && event.pointerCount == 1) {
            if (event.actionMasked == MotionEvent.ACTION_MOVE) {
                return false
            }
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                if (panelBaseScale > 0f && panelBaseCenter.x > 0f && abs(scale - panelBaseScale) < 0.05f) {
                    setScaleAndCenter(panelBaseScale, panelBaseCenter)
                }
                return false
            }
        }

        val handled = super.onTouchEvent(event)
        if (activePanelRect != null && isReady && !isProgrammaticAnimating) {
            // The base view computes pan as an offset from the ACTION_DOWN touch point (not
            // incrementally frame-to-frame — confirmed against the library source), so correcting
            // the center back here on every move/up/cancel is safe: it can never fight or desync
            // from the library's own next-frame calculation, which is independent of this
            // correction. Must also run on ACTION_UP/CANCEL, not just MOVE — the terminating event
            // carries its own (possibly slightly off-axis) touch position, and leaving it
            // uncorrected here meant clampToPanelBounds' animated settle pass right below had a
            // real gap to close, which read as an unwanted little vertical "fling" on release.
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> constrainPanLive()
            }
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                clampToPanelBounds()
            }
        }
        return handled
    }

    /**
     * Clamps the live center into the active panel's range on each axis independently (see
     * [clampAxis]), applied instantly (no animation) so it tracks the finger 1:1 during a drag or
     * pinch — see the class kdoc. Called after every touch move/up/cancel, before
     * [clampToPanelBounds]' own settle pass, so a plain release finds nothing left to correct.
     */
    private fun constrainPanLive() {
        if (panelBaseScale <= 0f) return
        val rect = activePanelRect ?: return
        if (sWidth <= 0 || sHeight <= 0 || width <= 0 || height <= 0) return
        val s = scale
        if (s <= 0f || s.isNaN()) return
        val currentCenter = center ?: return
        if (currentCenter.x.isNaN() || currentCenter.y.isNaN()) return

        val vW = width.toFloat() / s
        val vH = height.toFloat() / s
        val targetX = clampAxis(currentCenter.x, vW, rect.left * sWidth, rect.right * sWidth)
        val targetY = clampAxis(currentCenter.y, vH, rect.top * sHeight, rect.bottom * sHeight)

        if (abs(targetX - currentCenter.x) > 0.5f || abs(targetY - currentCenter.y) > 0.5f) {
            // Throttled: this runs every onDraw frame and floods the buffer during any pan/fling,
            // pushing the page-transition logs we actually care about out of the 5 MiB ring.
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastConstrainLogMs > 400L) {
                lastConstrainLogMs = now
                logcat(LogPriority.DEBUG) {
                    "panelZoomDbg constrainPanLive correcting center ($currentCenter -> ($targetX,$targetY)) s=$s rect=(${rect.left},${rect.top},${rect.right},${rect.bottom}) sWxH=${sWidth}x$sHeight"
                }
            }
            setScaleAndCenter(s, PointF(targetX, targetY))
        }
    }

    private var lastConstrainLogMs = 0L

    /**
     * The clamped center for one axis: if the viewport is already at least as large as the
     * panel's extent on this axis (zoomed content fits, nothing to pan into), locks to the
     * panel's own center on that axis; otherwise keeps [current] as-is unless it would show past
     * the panel's edge, in which case it's pulled back to the nearest in-bounds position. Shared
     * by [constrainPanLive], [clampToPanelBounds], and the fling handler so X and Y — and live
     * drag, settle, and fling — can never disagree on where an axis's edge actually is.
     */
    private fun clampAxis(current: Float, viewportSize: Float, panelMin: Float, panelMax: Float): Float {
        val panelSize = panelMax - panelMin
        return if (viewportSize >= panelSize) {
            (panelMin + panelMax) / 2f
        } else {
            val lo = minOf(panelMin + viewportSize / 2f, panelMax - viewportSize / 2f)
            val hi = maxOf(panelMin + viewportSize / 2f, panelMax - viewportSize / 2f)
            current.coerceIn(lo, hi)
        }
    }

    fun clampToPanelBounds() {
        if (isProgrammaticAnimating) return
        val rect = activePanelRect ?: return
        if (!isReady || sWidth <= 0 || sHeight <= 0 || width <= 0 || height <= 0) return

        val s = scale
        if (s <= 0f || s.isNaN()) return

        val effectiveMinScale = if (panelBaseScale > 0f) panelBaseScale else minScale
        val clampedScale = if (s < effectiveMinScale) effectiveMinScale else s

        val vW = width.toFloat() / clampedScale
        val vH = height.toFloat() / clampedScale

        val currentCenter = center ?: return
        if (currentCenter.x.isNaN() || currentCenter.y.isNaN()) return

        val targetCenterX = clampAxis(currentCenter.x, vW, rect.left * sWidth, rect.right * sWidth)
        val targetCenterY = clampAxis(currentCenter.y, vH, rect.top * sHeight, rect.bottom * sHeight)

        val scaleDiff = abs(clampedScale - s)
        val xDiff = abs(targetCenterX - currentCenter.x)
        val yDiff = abs(targetCenterY - currentCenter.y)

        if (scaleDiff > 0.001f || xDiff > 1f || yDiff > 1f) {
            if (scaleDiff > 0.001f) {
                setScaleAndCenter(clampedScale, PointF(targetCenterX, targetCenterY))
            } else {
                animateCenter(PointF(targetCenterX, targetCenterY))
                    ?.withDuration(200)
                    ?.withEasing(EASE_OUT_QUAD)
                    ?.start()
            }
        }
    }

    companion object {
        // Shared across every PanelSubsamplingImageView instance (the current page and any
        // preloaded neighbors), not one pool per view — tile decoding is CPU-bound, so one pool
        // sized to the device's core count avoids oversubscribing regardless of how many panel
        // views exist at once. See installDedicatedTileExecutor's kdoc for why this exists at all.
        private val dedicatedTileExecutor = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors().coerceAtLeast(2),
        )
    }
}
