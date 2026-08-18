package eu.kanade.tachiyomi.util.chapter

import eu.kanade.domain.chapter.model.applyFilters
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.ui.manga.ChapterList
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

/**
 * Gets next unread chapter with filters and sorting applied
 */
fun List<Chapter>.getNextUnread(manga: Manga, downloadManager: DownloadManager): Chapter? {
    return applyFilters(manga, downloadManager).let { chapters ->
        if (manga.sortDescending()) {
            chapters.findLast { !it.read }
        } else {
            chapters.find { !it.read }
        }
    }
}

/**
 * Gets next unread chapter with filters and sorting applied
 */
fun List<ChapterList.Item>.getNextUnread(manga: Manga): Chapter? {
    return applyFilters(manga).let { chapters ->
        if (manga.sortDescending()) {
            chapters.findLast { !it.chapter.read }
        } else {
            chapters.find { !it.chapter.read }
        }
    }?.chapter
}

/**
 * Gets the chapter the chapter list should open scrolled to: the next unread chapter, or — if
 * every chapter is already read — the furthest-read one, so returning to a finished series still
 * lands where you left off instead of back at the top.
 */
fun List<ChapterList.Item>.getScrollTarget(manga: Manga): Chapter? {
    getNextUnread(manga)?.let { return it }
    val chapters = applyFilters(manga)
    return (if (manga.sortDescending()) chapters.firstOrNull() else chapters.lastOrNull())?.chapter
}
