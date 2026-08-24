package eu.kanade.tachiyomi.data.reader

import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.data.Database

/**
 * User-marked pages that should always show the whole page rather than panel-by-panel stops —
 * e.g. title/cast-intro pages the panel detector can't meaningfully decompose (see CLAUDE.md).
 * Backed by the same persistent app database as [PanelCacheRepository], not the OS cache
 * partition, so a marked page survives a normal "Clear Cache" and is only lost on app-data wipe
 * or uninstall. Deliberately its own table (and its own [PanelDetector][eu.kanade.tachiyomi.ui.reader.viewer.panel.PanelDetector]
 * short-circuit) rather than folded into panel_cache — an override is a user annotation, not a
 * cached detection outcome, so it must survive a `DETECTOR_VERSION` bump rather than being
 * invalidated by one.
 */
@Inject
@SingleIn(AppScope::class)
class PanelFullPageOverrideRepository(
    private val database: Database,
) {

    suspend fun isOverridden(chapterId: Long, pageIndex: Int): Boolean =
        database.panel_full_page_overrideQueries
            .isOverridden(chapterId, pageIndex.toLong())
            .awaitAsOneOrNull() != null

    suspend fun setOverridden(chapterId: Long, pageIndex: Int) {
        database.panel_full_page_overrideQueries.setOverridden(chapterId, pageIndex.toLong())
    }

    suspend fun removeOverride(chapterId: Long, pageIndex: Int) {
        database.panel_full_page_overrideQueries.removeOverride(chapterId, pageIndex.toLong())
    }
}
