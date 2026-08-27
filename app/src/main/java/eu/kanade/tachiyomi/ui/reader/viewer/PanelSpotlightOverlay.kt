package eu.kanade.tachiyomi.ui.reader.viewer

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import androidx.core.animation.doOnEnd
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import eu.kanade.tachiyomi.ui.reader.viewer.panel.PanelRect

/**
 * Dims everything outside the active panel on top of [sourceView], so the panel currently being read
 * in panel-by-panel mode stands out even when neighboring content bleeds into view.
 *
 * Smoothly morphs and tracks the cutout rectangle across camera transitions without disappearing or flickering.
 */
class PanelSpotlightOverlay(context: Context) : View(context) {

    var sourceView: SubsamplingScaleImageView? = null

    var currentDisplayRect: PanelRect? = null
    private var rectAnimator: ValueAnimator? = null

    var targetRect: PanelRect? = null
        set(value) {
            rectAnimator?.cancel()
            currentDisplayRect = null
            field = value
            invalidate()
        }

    fun animateToRect(newRect: PanelRect, duration: Long = 250L) {
        val startRect = currentDisplayRect ?: targetRect ?: newRect
        targetRect = newRect
        rectAnimator?.cancel()
        rectAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            interpolator = FastOutSlowInInterpolator()
            addUpdateListener { anim ->
                val f = anim.animatedFraction
                currentDisplayRect = PanelRect(
                    left = startRect.left + (newRect.left - startRect.left) * f,
                    top = startRect.top + (newRect.top - startRect.top) * f,
                    right = startRect.right + (newRect.right - startRect.right) * f,
                    bottom = startRect.bottom + (newRect.bottom - startRect.bottom) * f,
                )
                invalidate()
            }
            doOnEnd {
                currentDisplayRect = null
                invalidate()
            }
            start()
        }
    }

    /** User-configured scrim strength, 0 (transparent, effectively off) to 100 (opaque black). */
    var opacityPercent: Int = DEFAULT_OPACITY_PERCENT
        set(value) {
            field = value.coerceIn(0, 100)
            invalidate()
        }

    /**
     * Debug aid, user-toggleable from the panel-by-panel settings: when set, draws every stop's
     * outline (in reading order, numbered) on top of the page — for visually verifying detection
     * and reading order against a real page.
     */
    var debugStops: List<PanelRect>? = null
        set(value) {
            field = value
            invalidate()
        }

    private val debugPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }

    private val debugTextPaint = Paint().apply {
        textSize = 96f
        isFakeBoldText = true
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        style = Paint.Style.FILL
    }

    /** Drawn underneath [debugTextPaint] to outline each number so it stays readable against any color. */
    private val debugTextOutlinePaint = Paint().apply {
        color = Color.BLACK
        textSize = 96f
        isFakeBoldText = true
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        style = Paint.Style.STROKE
        strokeWidth = 10f
        strokeJoin = Paint.Join.ROUND
    }

    init {
        setWillNotDraw(false)
    }

    override fun onDraw(canvas: Canvas) {
        val view = sourceView ?: return

        debugStops?.let { stops ->
            // Two passes: every outline first, then every number on top — otherwise a later
            // panel's rectangle can be drawn over an earlier (adjacent) panel's number.
            stops.forEachIndexed { index, rect ->
                val tl = view.sourceToViewCoord(rect.left * view.sWidth, rect.top * view.sHeight) ?: return@forEachIndexed
                val br = view.sourceToViewCoord(rect.right * view.sWidth, rect.bottom * view.sHeight) ?: return@forEachIndexed
                // A different color per panel (instead of uniform red) so it's obvious at a glance
                // which outline a given number belongs to, especially once panels sit close together.
                debugPaint.color = debugColorFor(index)
                canvas.drawRect(tl.x, tl.y, br.x, br.y, debugPaint)
            }
            stops.forEachIndexed { index, rect ->
                val tl = view.sourceToViewCoord(rect.left * view.sWidth, rect.top * view.sHeight) ?: return@forEachIndexed
                val br = view.sourceToViewCoord(rect.right * view.sWidth, rect.bottom * view.sHeight) ?: return@forEachIndexed
                debugTextPaint.color = debugColorFor(index)

                val label = "${index + 1}"
                val cx = (tl.x + br.x) / 2f
                val cy = (tl.y + br.y) / 2f
                val textY = cy - (debugTextPaint.ascent() + debugTextPaint.descent()) / 2f
                canvas.drawText(label, cx, textY, debugTextOutlinePaint)
                canvas.drawText(label, cx, textY, debugTextPaint)
            }
        }

        val rect = currentDisplayRect ?: targetRect ?: return
        // A stop covering (close to) the whole page has nothing meaningful to dim.
        if (rect.width >= FULL_PAGE_THRESHOLD && rect.height >= FULL_PAGE_THRESHOLD) return
        if (opacityPercent <= 0) return

        val topLeft = view.sourceToViewCoord(rect.left * view.sWidth, rect.top * view.sHeight) ?: return
        val bottomRight = view.sourceToViewCoord(rect.right * view.sWidth, rect.bottom * view.sHeight) ?: return

        val alpha = (opacityPercent * 255 / 100).coerceIn(0, 255)
        canvas.save()
        canvas.clipOutRect(topLeft.x, topLeft.y, bottomRight.x, bottomRight.y)
        canvas.drawColor(alpha shl 24)
        canvas.restore()
    }

    /**
     * A vibrant, high-contrast color for the panel at [index]. Hues are spaced by the golden angle
     * (~137.5°) rather than evenly divided by count, so consecutive panels always land far apart on
     * the color wheel no matter how many panels a page has — an even split would put panel N and
     * N+1 right next to each other in hue once there are enough panels to make the step small.
     */
    private fun debugColorFor(index: Int): Int {
        val hue = (index * GOLDEN_ANGLE_DEGREES) % 360f
        return Color.HSVToColor(floatArrayOf(hue, 0.85f, 0.95f))
    }

    companion object {
        private const val FULL_PAGE_THRESHOLD = 0.98f
        private const val GOLDEN_ANGLE_DEGREES = 137.508f
        const val DEFAULT_OPACITY_PERCENT = 65
    }
}
