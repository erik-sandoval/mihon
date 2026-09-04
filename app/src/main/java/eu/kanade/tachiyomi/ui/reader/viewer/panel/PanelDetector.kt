package eu.kanade.tachiyomi.ui.reader.viewer.panel

import android.content.Context
import android.graphics.BitmapFactory
import eu.kanade.tachiyomi.data.reader.PanelCacheRepository
import eu.kanade.tachiyomi.data.reader.PanelFullPageOverrideRepository
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okio.Buffer
import tachiyomi.core.common.util.system.logcat
import java.security.MessageDigest
import kotlin.math.max

class PanelDetector(
    context: Context,
    private val panelCacheRepository: PanelCacheRepository,
    private val panelFullPageOverrideRepository: PanelFullPageOverrideRepository,
    /**
     * Whether the current reading session is incognito — while true, [detect] still reads and
     * returns detection results normally but skips persisting new ones via
     * [PanelCacheRepository.save], the same way [ImageEnhancementCache][eu.kanade.tachiyomi.util.waifu2x.ImageEnhancementCache]
     * skips its disk-cache write-back and [eu.kanade.tachiyomi.ui.reader.ReaderViewModel] skips
     * history/progress for incognito.
     */
    private val incognito: Boolean = false,
) {
    private val mlDetector by lazy { MlPanelBoundaryDetector.tryCreate(context) }

    suspend fun detect(page: ReaderPage, imageBytes: Buffer, direction: PanelDirection): List<Panel> {
        val chapterId = page.chapter.chapter.id ?: return listOf(Panel(PanelRect.FULL_PAGE))

        // User-marked "always show whole page" pages (e.g. title/cast-intro art the detector
        // can't meaningfully decompose) skip detection entirely — this must win over even a
        // cached real detection, and isn't invalidated by a DETECTOR_VERSION bump since it's an
        // annotation, not a detection outcome.
        if (withContext(Dispatchers.IO) { panelFullPageOverrideRepository.isOverridden(chapterId, page.index) }) {
            return listOf(Panel(PanelRect.FULL_PAGE))
        }

        val hash = imageBytes.contentHash()
        // Reading direction changes both the reading order AND the merge/divide profile the
        // pipeline applies (see PanelPipeline), so it's part of what the cached result depends
        // on — fold it into the version key or toggling RTL/LTR would keep serving whichever
        // direction a page was first detected under.
        val version = cacheVersion(direction)

        withContext(Dispatchers.IO) {
            panelCacheRepository.get(chapterId, page.index, hash, version)
        }?.let { return it.panels }

        val label = "${page.chapter.chapter.name}#${page.index}#$direction"
        val timedOutOrPanels = withTimeoutOrNull(DETECTION_BUDGET_MS) {
            withContext(Dispatchers.Default) { runDetection(imageBytes, direction, label) }
        }
        val panels = timedOutOrPanels ?: listOf(Panel(PanelRect.FULL_PAGE))

        // Only persist a genuine detection outcome. A timeout is transient (system load,
        // not a property of the image), so don't pin the page to "no panels" permanently —
        // low-confidence/decode-failure fallbacks inside runDetection ARE deterministic
        // given the same bytes+version and are fine to cache; they'll re-run automatically
        // on a future DETECTOR_VERSION bump.
        if (timedOutOrPanels != null && !incognito) {
            withContext(Dispatchers.IO) {
                panelCacheRepository.save(chapterId, page.index, hash, version, PanelPageData(panels))
            }
        }
        return panels
    }

    private fun cacheVersion(direction: PanelDirection): Int = DETECTOR_VERSION * 10 + direction.ordinal

    private suspend fun runDetection(imageBytes: Buffer, direction: PanelDirection, label: String): List<Panel> {
        val detector = mlDetector ?: return listOf(Panel(PanelRect.FULL_PAGE))

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(imageBytes.copy().inputStream(), null, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return listOf(Panel(PanelRect.FULL_PAGE))

        val sample = sampleSizeFor(bounds.outWidth, bounds.outHeight, MAX_DETECTION_DIMENSION)
        val smallBitmap = BitmapFactory.decodeStream(
            imageBytes.copy().inputStream(),
            null,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return listOf(Panel(PanelRect.FULL_PAGE))

        val panels = detector.detect(smallBitmap, rightToLeft = direction == PanelDirection.RTL, label = label)
        smallBitmap.recycle()
        return panels
    }

    private fun sampleSizeFor(width: Int, height: Int, maxDimension: Int): Int {
        var sample = 1
        while (max(width, height) / sample > maxDimension) sample *= 2
        return sample
    }

    private fun Buffer.contentHash(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(snapshot().toByteArray())
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun close() {
        mlDetector?.close()
    }

    companion object {
        private const val DETECTION_BUDGET_MS = 2000L
        private const val MAX_DETECTION_DIMENSION = 900
        // 50: (Externally added, later reverted) speech-bubble boundary alignment in PanelPipeline.
        // 51: Evidence-gathering bump only (forced fresh detection to capture logcat for a specific bug).
        // 52: Reverted alignBoundariesToSpeechBubbles (PanelPipeline.kt) and consolidateFragments
        //     (YoloPanelDecoder.kt) back to the original pipeline — confirmed on real pages
        //     (Blue Lock ch.1 p7/p11/p17) that alignBoundariesToSpeechBubbles misclassified two
        //     side-by-side full-height columns as a stacked pair and used a bubble from one column
        //     to slice a chunk off the bottom of the other, and consolidateFragments over-merged
        //     distinct panels (2 pre-existing regression tests failed against it).
        // 53: Evidence-gathering bump only (forced fresh detection so panelDebug raw bubbles= logs
        //     fire for Blue Lock ch.2 p59/p38, to check whether the model detects rectangular
        //     narration-caption boxes as class-1 Text at all, vs. round speech bubbles).
        // 54: ContentAwarePanelExpander added before PanelPipeline (uses the page pixels):
        //     - merges two detected boxes with no gutter across their seam (a panel the model split)
        //     - walks each panel edge out to the real white/solid-black gutter (recovers a clipped
        //       frame-breaking character / bleeding bubble)
        //     - absorbs an undetected caption/narration bar just past a panel's gutter, if the bar
        //       is about that panel's own width
        //     No-op where a box is already at a gutter or the page is borderless.
        //     Also: PanelGapFiller now keeps a wide/short uncovered region that fails only its
        //     aspect-ratio check if the page pixels show it's full of ink (a missed full-bleed
        //     panel on black), and PanelPipeline.BASE_MARGIN 0.057->0.025 / MAX_EDGE_EXTENSION
        //     0.043->0.02 (the expander reaches the real boundary, so less padding is wanted).
        // Not private: PanelFlagExporter labels its exports with this so a future re-run against a
        // newer model/pipeline version can tell which flags predate it.
        const val DETECTOR_VERSION = 54
    }
}
