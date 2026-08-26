package eu.kanade.tachiyomi.ui.reader.viewer

import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters

/**
 * Interface for implementing a viewer.
 */
interface Viewer {

    /**
     * Returns the view this viewer uses.
     */
    fun getView(): View

    /**
     * Destroys this viewer. Called when leaving the reader or swapping viewers.
     */
    fun destroy() {}

    /**
     * Called when the system reports memory pressure (see [android.app.Activity.onTrimMemory]).
     * Default no-op — only viewers holding onto extra per-page state beyond what's on screen
     * (e.g. [eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerViewer] retaining raw page bytes for
     * panel re-detection) need to override this to release what's safe to drop for pages that
     * aren't actually visible right now.
     */
    fun onTrimMemory() {}

    /**
     * Tells this viewer to set the given [chapters] as active.
     */
    fun setChapters(chapters: ViewerChapters)

    /**
     * Tells this viewer to move to the given [page]. [forceEnterForward] overrides whatever
     * forward/backward heuristic the viewer would normally use to decide how [page] is entered
     * (e.g. panel-by-panel's first vs. last panel stop) — for an explicit jump like picking a page
     * from the page grid, the reader should always start that page from its first panel, not from
     * whatever "backward" would normally mean when the target happens to sit earlier in the
     * chapter than where the reader currently is.
     */
    fun moveToPage(page: ReaderPage, forceEnterForward: Boolean = false)

    /**
     * Called from the containing activity when a key [event] is received. It should return true
     * if the event was handled, false otherwise.
     */
    fun handleKeyEvent(event: KeyEvent): Boolean

    /**
     * Called from the containing activity when a generic motion [event] is received. It should
     * return true if the event was handled, false otherwise.
     */
    fun handleGenericMotionEvent(event: MotionEvent): Boolean
}
