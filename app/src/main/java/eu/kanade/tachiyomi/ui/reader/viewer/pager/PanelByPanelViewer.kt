package eu.kanade.tachiyomi.ui.reader.viewer.pager

import eu.kanade.domain.manga.model.panelByPanelDirection
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.setting.PanelByPanelDirection
import eu.kanade.tachiyomi.ui.reader.viewer.panel.PanelDetector
import eu.kanade.tachiyomi.ui.reader.viewer.panel.PanelDirection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Implementation of a PagerViewer that navigates panel-by-panel within each page before
 * flipping to the next page, generalizing the dual-page-split pan mechanism in
 * [eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView] to N detected panel stops.
 */
class PanelByPanelViewer(activity: ReaderActivity) : PagerViewer(activity) {

    val panelDetector = PanelDetector(
        context = activity.applicationContext,
        panelCacheRepository = graph.panelCacheRepository,
        panelFullPageOverrideRepository = graph.panelFullPageOverrideRepository,
    )

    // ReadingMode.PANEL_BY_PANEL has no direction of its own (unlike LEFT_TO_RIGHT/RIGHT_TO_LEFT),
    // so panel order is tracked by a per-series override (PanelByPanelDirection, stored on the
    // manga itself, same as reading mode/orientation) that falls back to the app-wide
    // panelByPanelRightToLeft preference when unset — this is what keeps one series' direction
    // from bleeding into every other series read in panel-by-panel mode.
    val panelDirection: PanelDirection
        get() = resolveDirection(activity.viewModel.manga?.panelByPanelDirection?.toInt(), readerPreferences.panelByPanelRightToLeft.get())

    /** Reactive counterpart of [panelDirection], for re-running detection when either the per-series override or the app-wide default changes. */
    val panelDirectionFlow: Flow<PanelDirection> = combine(
        activity.viewModel.state.map { it.manga?.panelByPanelDirection }.distinctUntilChanged(),
        readerPreferences.panelByPanelRightToLeft.changes(),
    ) { mangaOverride, globalRtl -> resolveDirection(mangaOverride?.toInt(), globalRtl) }

    private fun resolveDirection(mangaOverrideFlag: Int?, globalRtl: Boolean): PanelDirection {
        val rtl = when (PanelByPanelDirection.fromPreference(mangaOverrideFlag)) {
            PanelByPanelDirection.LEFT_TO_RIGHT -> false
            PanelByPanelDirection.RIGHT_TO_LEFT -> true
            PanelByPanelDirection.DEFAULT -> globalRtl
        }
        return if (rtl) PanelDirection.RTL else PanelDirection.LTR
    }

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
