package eu.kanade.presentation.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import tachiyomi.presentation.core.components.material.padding
import kotlin.math.max

@Composable
fun PanelPageGridSheet(
    pages: List<ReaderPage>,
    currentPageIndex: Int,
    onSelectPage: (Int) -> Unit,
    onDismissRequest: () -> Unit,
    resolveDownloadedBytes: suspend (ReaderPage) -> ByteArray? = { null },
) {
    // Opens scrolled to the current page's row instead of always starting at page 1, so jumping
    // into the grid from deep in a chapter doesn't require scrolling to find where you are.
    // GridCells.Adaptive's column count isn't known until the grid is actually measured, so seeding
    // rememberLazyGridState's own initialFirstVisibleItemIndex guesses blind and can land one row
    // off (it did — see git history). scrollToItem is layout-aware: called after composition, it
    // resolves the item's real row against the grid's actual measured column count.
    val startIndex = remember(pages, currentPageIndex) {
        pages.indexOfFirst { it.index == currentPageIndex }.coerceAtLeast(0)
    }
    val gridState = rememberLazyGridState()
    LaunchedEffect(startIndex) {
        gridState.scrollToItem(startIndex)
    }

    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(minSize = 96.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(480.dp)
                .padding(MaterialTheme.padding.small),
        ) {
            items(pages, key = { it.index }) { page ->
                PageGridCell(
                    page = page,
                    isSelected = page.index == currentPageIndex,
                    onClick = {
                        onSelectPage(page.index)
                        onDismissRequest()
                    },
                    resolveDownloadedBytes = resolveDownloadedBytes,
                )
            }
        }
    }
}

@Composable
private fun PageGridCell(
    page: ReaderPage,
    isSelected: Boolean,
    onClick: () -> Unit,
    resolveDownloadedBytes: suspend (ReaderPage) -> ByteArray?,
) {
    val cacheKey = remember(page) { "${page.chapter.chapter.id}_${page.index}" }
    val thumbnail by produceState<Bitmap?>(initialValue = PanelPageThumbnailCache.get(cacheKey), key1 = cacheKey) {
        value = PanelPageThumbnailCache.get(cacheKey) ?: decodePageThumbnail(page, resolveDownloadedBytes)?.also {
            PanelPageThumbnailCache.put(cacheKey, it)
        }
    }

    Box(
        modifier = Modifier
            .aspectRatio(0.7f)
            .padding(2.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .background(
                if (isSelected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
            )
            .then(
                if (isSelected) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = thumbnail
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            CircularProgressIndicator(modifier = Modifier.padding(24.dp))
        }
        Text(
            text = "${page.index + 1}",
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

private suspend fun decodePageThumbnail(
    page: ReaderPage,
    resolveDownloadedBytes: suspend (ReaderPage) -> ByteArray?,
): Bitmap? = withContext(Dispatchers.IO) {
    val bytes = readPageBytes(page, resolveDownloadedBytes) ?: return@withContext null
    try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > THUMBNAIL_MAX_DIMENSION) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (e: Throwable) {
        null
    }
}

/**
 * Prefers a fresh read of the downloaded chapter (via [resolveDownloadedBytes]) over [page]'s own
 * loader when the page hasn't been fetched yet — a chapter opened before its download finished
 * keeps the network loader it started with for the rest of the session (see
 * [eu.kanade.tachiyomi.ui.reader.model.ReaderChapter.pageLoader]), so falling straight to it here
 * would hit the source for pages the reader hasn't visited, risking rate limits on a chapter
 * that's since finished downloading.
 */
private suspend fun readPageBytes(page: ReaderPage, resolveDownloadedBytes: suspend (ReaderPage) -> ByteArray?): ByteArray? {
    var streamFn = page.stream
    if (streamFn == null) {
        resolveDownloadedBytes(page)?.let { return it }
        page.chapter.pageLoader?.loadPage(page)
        page.statusFlow.first { it == Page.State.Ready || it is Page.State.Error }
        streamFn = page.stream ?: return null
    }
    return try {
        streamFn.invoke().use { it.readBytes() }
    } catch (e: Throwable) {
        null
    }
}

// Long side, in px. Grid cells are sized from a 96dp adaptive minimum with a 0.7 aspect ratio,
// which on a high-density phone display is comfortably larger than the old 200px target — that
// mismatch was what made every thumbnail look blurry (the decoded bitmap was being upscaled to
// fill a cell well past its own resolution).
private const val THUMBNAIL_MAX_DIMENSION = 480

private object PanelPageThumbnailCache {
    private const val MAX_ENTRIES = 40
    private val cache = object : LinkedHashMap<String, Bitmap>(0, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>): Boolean =
            size > MAX_ENTRIES
    }

    @Synchronized
    fun get(key: String): Bitmap? = cache[key]

    @Synchronized
    fun put(key: String, bitmap: Bitmap) {
        cache[key] = bitmap
    }
}
