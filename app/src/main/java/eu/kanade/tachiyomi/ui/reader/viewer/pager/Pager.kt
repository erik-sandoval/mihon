package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.viewpager.widget.DirectionalViewPager
import eu.kanade.tachiyomi.ui.reader.viewer.GestureDetectorWithLongTap
import kotlin.math.abs

/**
 * Pager implementation that listens for tap and long tap and allows temporarily disabling touch
 * events in order to work with child views that need to disable touch events on this parent. The
 * pager can also be declared to be vertical by creating it with [isHorizontal] to false.
 */
open class Pager(
    context: Context,
    isHorizontal: Boolean = true,
) : DirectionalViewPager(context, isHorizontal) {

    /**
     * Tap listener function to execute when a tap is detected.
     */
    var tapListener: ((MotionEvent) -> Unit)? = null

    /**
     * Long tap listener function to execute when a long tap is detected.
     */
    var longTapListener: ((MotionEvent) -> Boolean)? = null

    /**
     * Consulted on every new touch sequence, before the pager would decide to start its own
     * drag-to-turn-page gesture. Returning true claims the whole gesture for [panelSwipeListener]
     * instead — used so panel-by-panel can step through panel stops on a swipe, only falling back
     * to a (programmatic, via [panelSwipeListener]'s own handling) page turn when no stop is left
     * to step to. Left null (or returning false) preserves the pager's normal native swipe.
     */
    var panelSwipeInterceptGate: (() -> Boolean)? = null

    /**
     * Consulted before fling or swipe processing. Returning true blocks page/panel navigation gestures
     * (e.g. while the user is zoomed in inspecting a panel).
     */
    var navigationBlockedGate: (() -> Boolean)? = null

    /** Invoked with the physical drag direction (true = leftward) once such a swipe is detected. */
    var panelSwipeListener: ((leftward: Boolean) -> Unit)? = null

    /** Whether the gesture currently in progress was claimed via [panelSwipeInterceptGate]. */
    private var interceptingForPanelSwipe = false

    /** Tracks whether the current gesture had multiple active pointers (e.g. pinch-to-zoom). */
    private var wasMultiTouch = false
    private var swipeStartX = 0f
    private var swipeStartY = 0f
    private var swipeFired = false

    /**
     * Gesture listener that implements tap, long tap, and (when claimed via
     * [panelSwipeInterceptGate]) swipe events.
     */
    private val gestureListener = object : GestureDetectorWithLongTap.Listener() {
        override fun onSingleTapConfirmed(ev: MotionEvent): Boolean {
            tapListener?.invoke(ev)
            return true
        }

        override fun onLongTapConfirmed(ev: MotionEvent) {
            val listener = longTapListener
            if (listener != null && listener.invoke(ev)) {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (wasMultiTouch) return false
            if (navigationBlockedGate?.invoke() == true) return false
            if (!interceptingForPanelSwipe || abs(velocityX) <= abs(velocityY)) return false
            if (!swipeFired) {
                swipeFired = true
                panelSwipeListener?.invoke(velocityX < 0)
            }
            return true
        }
    }

    /**
     * Gesture detector which handles motion events.
     */
    private val gestureDetector = GestureDetectorWithLongTap(context, gestureListener)

    /**
     * Whether the gesture detector is currently enabled.
     */
    private var isGestureDetectorEnabled = true

    /**
     * Dispatches a touch event.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            wasMultiTouch = true
        }
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            wasMultiTouch = false
            swipeStartX = ev.x
            swipeStartY = ev.y
            swipeFired = false
            interceptingForPanelSwipe = panelSwipeInterceptGate?.invoke() == true
        }
        if (interceptingForPanelSwipe && !wasMultiTouch && navigationBlockedGate?.invoke() != true) {
            if ((ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) && !swipeFired) {
                val dx = ev.x - swipeStartX
                val dy = ev.y - swipeStartY
                if (abs(dx) > 60f && abs(dx) > abs(dy) * 1.2f) {
                    swipeFired = true
                    panelSwipeListener?.invoke(dx < 0)
                }
            }
        }
        val handled = super.dispatchTouchEvent(ev)
        if (isGestureDetectorEnabled) {
            gestureDetector.onTouchEvent(ev)
        }
        return handled
    }

    /**
     * Whether the given [ev] should be intercepted. Also decides, once per touch sequence (on
     * [MotionEvent.ACTION_DOWN]), whether this gesture is claimed for panel-swipe handling via
     * [panelSwipeInterceptGate] — if so, the pager never intercepts for its own drag-to-turn-page
     * behavior for the rest of this sequence, leaving the raw events for [gestureListener]'s
     * [GestureDetectorWithLongTap.Listener.onFling] to resolve instead.
     */
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (navigationBlockedGate?.invoke() == true) return false
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            interceptingForPanelSwipe = panelSwipeInterceptGate?.invoke() == true
        }
        if (interceptingForPanelSwipe) return false
        return try {
            super.onInterceptTouchEvent(ev)
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    /**
     * Handles a touch event. Only used to prevent crashes when child views manipulate
     * [requestDisallowInterceptTouchEvent].
     */
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (interceptingForPanelSwipe) return false
        return try {
            super.onTouchEvent(ev)
        } catch (e: NullPointerException) {
            false
        } catch (e: IndexOutOfBoundsException) {
            false
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    /**
     * Executes the given key event when this pager has focus. Just do nothing because the reader
     * already dispatches key events to the viewer and has more control than this method.
     */
    override fun executeKeyEvent(event: KeyEvent): Boolean {
        // Disable viewpager's default key event handling
        return false
    }

    /**
     * Enables or disables the gesture detector.
     */
    fun setGestureDetectorEnabled(enabled: Boolean) {
        isGestureDetectorEnabled = enabled
    }
}
