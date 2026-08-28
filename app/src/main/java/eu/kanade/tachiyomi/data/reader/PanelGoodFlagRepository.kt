package eu.kanade.tachiyomi.data.reader

import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.data.Database

/**
 * Pages the user has marked, via the reader's one-tap thumbs-up, as correct panel detection —
 * good training data. Separate from [PanelFullPageOverrideRepository] (a different per-page
 * annotation) but the same shape: its own table, backed by the persistent app database so a mark
 * survives a "Clear Cache" and is only lost on app-data wipe or uninstall. The mark itself is just
 * a boolean the reader's toggle button reflects; the actual exported image + detection JSON live
 * on disk (see [eu.kanade.tachiyomi.ui.reader.viewer.panel.PanelFlagExporter]), written/removed
 * together with this row so the two never drift out of sync.
 */
@Inject
@SingleIn(AppScope::class)
class PanelGoodFlagRepository(
    private val database: Database,
) {

    suspend fun isMarkedGood(chapterId: Long, pageIndex: Int): Boolean =
        database.panel_good_flagQueries
            .isMarkedGood(chapterId, pageIndex.toLong())
            .awaitAsOneOrNull() != null

    suspend fun setMarkedGood(chapterId: Long, pageIndex: Int) {
        database.panel_good_flagQueries.setMarkedGood(chapterId, pageIndex.toLong())
    }

    suspend fun removeMarkedGood(chapterId: Long, pageIndex: Int) {
        database.panel_good_flagQueries.removeMarkedGood(chapterId, pageIndex.toLong())
    }
}
