package eu.kanade.tachiyomi.ui.reader.viewer.panel

import android.content.Context
import android.graphics.Canvas
import android.graphics.PointF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import kotlin.math.abs
import kotlin.math.min

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
            val pWidth = pRight - pLeft
            val pHeight = pBottom - pTop

            val currentCenter = center ?: return false
            val vTranslateEnd = PointF(
                (width / 2f - currentCenter.x * s) + (velocityX * 0.25f),
                (height / 2f - currentCenter.y * s) + (velocityY * 0.25f),
            )
            val rawCenterXEnd = ((width / 2f) - vTranslateEnd.x) / s
            val rawCenterYEnd = ((height / 2f) - vTranslateEnd.y) / s

            val targetCenterX = if (vW >= pWidth) {
                (pLeft + pRight) / 2f
            } else {
                rawCenterXEnd.coerceIn(pLeft + vW / 2f, pRight - vW / 2f)
            }

            val targetCenterY = if (vH >= pHeight) {
                (pTop + pBottom) / 2f
            } else {
                rawCenterYEnd.coerceIn(pTop + vH / 2f, pBottom - vH / 2f)
            }

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

    fun computePanelTarget(rect: PanelRect, viewW: Int, viewH: Int): Pair<Float, PointF> {
        val realAspect = (rect.width * sWidth) / (rect.height * sHeight)
        val isPortrait = viewH > viewW
        val heightBudget = if (isPortrait && realAspect < TALL_PANEL_ASPECT_THRESHOLD) {
            viewH * MAX_TALL_PANEL_HEIGHT_FRACTION
        } else {
            viewH.toFloat()
        }
        val widthBudget = if (isPortrait) viewW.toFloat() else viewW * LANDSCAPE_MAX_FILL_FRACTION
        val targetScale = min(
            widthBudget / (rect.width * sWidth),
            heightBudget / (rect.height * sHeight),
        )
        val center = PointF(
            (rect.left + rect.width / 2f) * sWidth,
            (rect.top + rect.height / 2f) * sHeight,
        )
        return targetScale to center
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
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                clampToPanelBounds()
            }
        }
        return handled
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

        val pLeft = rect.left * sWidth
        val pRight = rect.right * sWidth
        val pTop = rect.top * sHeight
        val pBottom = rect.bottom * sHeight
        val pWidth = pRight - pLeft
        val pHeight = pBottom - pTop

        val currentCenter = center ?: return
        if (currentCenter.x.isNaN() || currentCenter.y.isNaN()) return

        val targetCenterX = if (vW >= pWidth) {
            (pLeft + pRight) / 2f
        } else {
            val minX = minOf(pLeft + vW / 2f, pRight - vW / 2f)
            val maxX = maxOf(pLeft + vW / 2f, pRight - vW / 2f)
            currentCenter.x.coerceIn(minX, maxX)
        }

        val targetCenterY = if (vH >= pHeight) {
            (pTop + pBottom) / 2f
        } else {
            val minY = minOf(pTop + vH / 2f, pBottom - vH / 2f)
            val maxY = maxOf(pTop + vH / 2f, pBottom - vH / 2f)
            currentCenter.y.coerceIn(minY, maxY)
        }

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
        private const val TALL_PANEL_ASPECT_THRESHOLD = 0.5f
        private const val MAX_TALL_PANEL_HEIGHT_FRACTION = 0.6f
        private const val LANDSCAPE_MAX_FILL_FRACTION = 0.92f
    }
}
