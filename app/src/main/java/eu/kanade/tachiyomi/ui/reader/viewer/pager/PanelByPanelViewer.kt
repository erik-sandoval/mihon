package eu.kanade.tachiyomi.ui.reader.viewer.pager

import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.viewer.panel.PanelDetector
import eu.kanade.tachiyomi.ui.reader.viewer.panel.PanelDirection

/**
 * Implementation of a PagerViewer that navigates panel-by-panel within each page before
 * flipping to the next page, generalizing the dual-page-split pan mechanism in
 * [eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView] to N detected panel stops.
 */
class PanelByPanelViewer(activity: ReaderActivity) : PagerViewer(activity) {

    val panelDetector = PanelDetector(
        context = activity.applicationContext,
        panelCacheRepository = graph.panelCacheRepository,
    )

    // ReadingMode.PANEL_BY_PANEL has no direction of its own (unlike LEFT_TO_RIGHT/RIGHT_TO_LEFT),
    // so panel order is tracked by a dedicated preference instead.
    val panelDirection: PanelDirection
        get() = if (readerPreferences.panelByPanelRightToLeft.get()) PanelDirection.RTL else PanelDirection.LTR

    /**
     * Direction of the most recent real page transition. [PagerPageHolder] seeds a freshly
     * created page's entry stop (first vs last) from this at construction time, since
     * [ViewPager][androidx.viewpager.widget.ViewPager]'s offscreen prefetching creates a page's
     * holder — and runs its panel detection/entry-stop selection — before the user actually
     * swipes onto it. Without this, holders created ahead of the user while continuously
     * navigating backward would fall back to the "enter forward" default and always land on the
     * first panel instead of the last.
     */
    var lastNavigationForward: Boolean = true

    override fun createPager(): Pager = Pager(activity)

    override fun destroy() {
        super.destroy()
        panelDetector.close()
    }
}
