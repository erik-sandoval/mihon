package eu.kanade.tachiyomi.ui.reader.viewer.panel

import android.content.Context
import android.graphics.BitmapFactory
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.util.storage.DiskUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.storage.service.StorageManager
import java.io.IOException
import kotlin.math.max

/**
 * What the user is flagging a page as — the three failure categories, or [GOOD_EXAMPLE] for a page
 * where detection worked correctly. A training set needs positive examples as much as failures, so
 * this isn't just "what's wrong" — it lets a desktop curation pass filter/sort flags by cause either way.
 */
@Serializable
enum class PanelFlagReason {
    BAD_DETECTION,
    WRONG_ORDER,
    MISSED_TEXT,
    GOOD_EXAMPLE,
}

/** What happened when flagging a page — see [PanelFlagExporter.export]. */
enum class PanelFlagOutcome {
    Flagged,
    Removed,
    Failed,
}

/**
 * Builds the flagged-page export filename stem: sanitized "<manga> - <chapter> - p<page>" —
 * deliberately stable across reasons and calls (no reason/timestamp suffix), shared by both the
 * "good" and "bad" flag exports (kept in separate folders — see [PanelFlagExporter.categoryDirectory]
 * — so the two can never collide despite using identical stems). Re-flagging the same page under a
 * *different* reason has to resolve to this exact same stem, or it writes a second full image copy
 * instead of updating the one entry for that page — see [toggleReason]. `<stem>.jpg`/`.png`/`.webp`
 * and `<stem>.json` for one export share this stem, differing only in extension, rather than living
 * in their own subfolder — deliberately flat, so a plain multi-file picker (the only option on
 * browsers without folder-selection support, e.g. Firefox for Android) can still pair an image with
 * its JSON correctly by matching filenames, with no folder-structure information needed at all.
 */
internal fun panelFlagPageStem(mangaTitle: String, chapterName: String, pageNumber: Int): String =
    DiskUtil.buildValidFilename("$mangaTitle - $chapterName - p$pageNumber")

/**
 * Toggles [reason] in [existing]: removes it if already present, adds it otherwise. A page can be
 * flagged for more than one reason at once (e.g. both bad detection and wrong order) — picking a
 * reason again is "undo just that one", not "undo the whole flag"; the caller only deletes the
 * export entirely once this returns an empty set.
 */
internal fun toggleReason(existing: Set<PanelFlagReason>, reason: PanelFlagReason): Set<PanelFlagReason> =
    if (reason in existing) existing - reason else existing + reason

/**
 * Everything captured for one flagged page: enough to reproduce what the model saw (including
 * sub-threshold near-misses, via [MlPanelBoundaryDetector.diagnose]) without re-running detection,
 * plus the series/chapter/page identifiers needed to find the source page again after future model
 * or pipeline changes. Ground truth is never part of this — see [PanelFlagExporter]'s doc.
 */
@Serializable
data class PanelFlagPayload(
    val mangaTitle: String,
    val chapterName: String,
    val chapterId: Long?,
    val pageIndex: Int,
    val pageNumber: Int,
    val direction: String,
    /** Every reason the page has been flagged for so far — a page can be both bad detection *and*
        wrong order at once; see [toggleReason]. Always a single-element `{GOOD_EXAMPLE}` set for
        a good export, which never toggles/accumulates the way failure reasons do. */
    val reasons: Set<PanelFlagReason>,
    val timestampMs: Long,
    val detectorVersion: Int,
    val imageWidth: Int,
    val imageHeight: Int,
    /** Every candidate the model scored above [YoloPanelDecoder.DIAGNOSTIC_CONFIDENCE], not just what clears the display threshold. */
    val rawDetections: List<ScoredBox>,
    /** What the reader actually shows for this page right now — the post-pipeline zoom regions. */
    val finalPanels: List<PanelRect>,
)

/**
 * Exports a flagged page's original image plus everything [MlPanelBoundaryDetector] saw for it —
 * including sub-threshold near-misses that never make it into the normal display pipeline — as a
 * flat pair of files (`<stem>.<ext>` + `<stem>.json`, no per-page subfolder — see [panelFlagPageStem])
 * under `panel_flags/good/` or `panel_flags/bad/` (see [categoryDirectory]) in this app's own
 * storage-location tree (see [StorageManager]) — the folder
 * the user already granted on first install for downloads/backups, reachable from their file
 * manager without any extra permission dance. Earlier attempts at a MediaStore-based location are
 * why this reuses the app's own SAF grant instead:
 * - `MediaStore.Downloads` only accepts a `RELATIVE_PATH` under "Download/" — a bare top-level
 *   folder isn't reachable through it at all.
 * - `MediaStore.Files`' generic collection was tried next for a bare top-level "Mihon/" folder,
 *   but is rejected by the platform: confirmed on-device via `IllegalArgumentException`,
 *   "Primary directory Mihon not allowed for content://media/external_primary/file; allowed
 *   directories are [Download, Documents]".
 * - Getting a genuine arbitrary root-level folder via MediaStore isn't possible without
 *   `MANAGE_EXTERNAL_STORAGE`, far too heavy a permission for this — whereas the app's existing
 *   SAF tree grant already covers exactly this with no new permission at all.
 *
 * This never captures ground truth: that's added separately, outside the app, once the export is
 * pulled off-device (see CLAUDE.md's panel-model-finetuning-deferred memory).
 */
@Inject
@SingleIn(AppScope::class)
class PanelFlagExporter(
    private val context: Context,
    private val storageManager: StorageManager,
) {

    /**
     * For a failure reason ([PanelFlagReason.GOOD_EXAMPLE] excluded — see below), this toggles
     * [reason] within the page's existing [PanelFlagPayload.reasons] set ([toggleReason]) rather
     * than toggling the whole export's existence: picking a reason that's already on the page
     * removes just that reason, updating the one export in place; picking a new one adds it,
     * likewise in place — flagging the same page under a second reason must never write a second
     * copy of the image. Only once the set empties out entirely does this delete the export
     * ([PanelFlagOutcome.Removed]); otherwise it (re-)runs detection and writes/overwrites the
     * stable [panelFlagPageStem] pair ([PanelFlagOutcome.Flagged]).
     *
     * [PanelFlagReason.GOOD_EXAMPLE] does not toggle here — it always writes/overwrites the stable
     * stem unconditionally with a `{GOOD_EXAMPLE}` reason set. Its own mark/unmark toggle is
     * decided by the caller instead (`ReaderViewModel`, backed by the `panel_good_flag` DB row),
     * since that same state also drives the reader's thumbs-up icon and needs to be readable fast
     * on every page turn — a directory listing on every page turn would not be.
     */
    suspend fun export(
        mangaTitle: String,
        chapterName: String,
        chapterId: Long?,
        page: ReaderPage,
        direction: PanelDirection,
        reason: PanelFlagReason,
    ): PanelFlagOutcome = withContext(Dispatchers.IO) {
        val isGood = reason == PanelFlagReason.GOOD_EXAMPLE
        val dir = categoryDirectory(isGood) ?: run {
            logcat(LogPriority.ERROR) { "panelFlag: no storage location set — can't export" }
            return@withContext PanelFlagOutcome.Failed
        }

        val stem = panelFlagPageStem(mangaTitle, chapterName, page.number)
        val reasons = if (isGood) {
            setOf(PanelFlagReason.GOOD_EXAMPLE)
        } else {
            val toggled = toggleReason(readExistingReasons(dir, stem), reason)
            if (toggled.isEmpty()) {
                val removed = dir.listFiles().orEmpty()
                    .filter { it.name?.startsWith("$stem.") == true }
                    .all { it.delete() }
                return@withContext if (removed) PanelFlagOutcome.Removed else PanelFlagOutcome.Failed
            }
            toggled
        }

        val streamFactory = page.stream ?: return@withContext PanelFlagOutcome.Failed
        val originalBytes = try {
            streamFactory().use { it.readBytes() }
        } catch (t: Throwable) {
            logcat(LogPriority.ERROR, t) { "panelFlag: failed to read page bytes" }
            return@withContext PanelFlagOutcome.Failed
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(originalBytes, 0, originalBytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext PanelFlagOutcome.Failed

        val sample = sampleSizeFor(bounds.outWidth, bounds.outHeight, MAX_DETECTION_DIMENSION)
        val smallBitmap = BitmapFactory.decodeByteArray(
            originalBytes,
            0,
            originalBytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return@withContext PanelFlagOutcome.Failed

        val detector = MlPanelBoundaryDetector.tryCreate(context)
        if (detector == null) {
            smallBitmap.recycle()
            return@withContext PanelFlagOutcome.Failed
        }

        val label = "$chapterName#${page.index}#$direction#flag"
        val rawDetections = detector.diagnose(smallBitmap)
        val finalPanels = detector.detect(smallBitmap, rightToLeft = direction == PanelDirection.RTL, label = label)
        detector.close()
        smallBitmap.recycle()

        val timestampMs = System.currentTimeMillis()
        val payload = PanelFlagPayload(
            mangaTitle = mangaTitle,
            chapterName = chapterName,
            chapterId = chapterId,
            pageIndex = page.index,
            pageNumber = page.number,
            direction = direction.name,
            reasons = reasons,
            timestampMs = timestampMs,
            detectorVersion = PanelDetector.DETECTOR_VERSION,
            imageWidth = bounds.outWidth,
            imageHeight = bounds.outHeight,
            rawDetections = rawDetections,
            finalPanels = finalPanels.map { it.bounds },
        )

        try {
            // Always overwrite now — stem is stable per page (see panelFlagPageStem), so a
            // second flag on the same page updates this exact pair of files in place.
            val ext = extensionFor(bounds.outMimeType)
            writeFile(dir, "$stem.$ext", originalBytes, overwrite = true)
            writeFile(dir, "$stem.json", JSON.encodeToString(payload).encodeToByteArray(), overwrite = true)
            PanelFlagOutcome.Flagged
        } catch (t: Throwable) {
            logcat(LogPriority.ERROR, t) { "panelFlag: failed to write export" }
            PanelFlagOutcome.Failed
        }
    }

    /** Deletes the stable flag export for a page — the disk-side half of un-marking "good". */
    suspend fun removeGoodExport(mangaTitle: String, chapterName: String, pageNumber: Int): Boolean =
        withContext(Dispatchers.IO) {
            val dir = categoryDirectory(isGood = true) ?: return@withContext true
            val stem = panelFlagPageStem(mangaTitle, chapterName, pageNumber)
            dir.listFiles().orEmpty()
                .filter { it.name?.startsWith("$stem.") == true }
                .all { it.delete() }
        }

    /**
     * `panel_flags/good/` or `panel_flags/bad/` — the three failure reasons all share `bad/` (which
     * reasons a page has been flagged for live inside its JSON's [PanelFlagPayload.reasons], not the
     * filename, since one page's export can carry more than one), split from `good/` so reviewing
     * either category means opening one folder instead of picking through a single flat mix of both.
     */
    private fun categoryDirectory(isGood: Boolean): UniFile? =
        storageManager.getPanelFlagsDirectory()?.createDirectory(if (isGood) "good" else "bad")

    private fun writeFile(dir: UniFile, displayName: String, bytes: ByteArray, overwrite: Boolean) {
        val file = (if (overwrite) dir.findFile(displayName) else null)
            ?: dir.createFile(displayName)
            ?: throw IOException("panelFlag: couldn't create $displayName")
        file.openOutputStream().use { it.write(bytes) }
    }

    /** The reason set already recorded for [stem]'s existing export, or empty if it isn't flagged
        yet (or its JSON can't be read) — the starting point [toggleReason] is applied to. */
    private fun readExistingReasons(dir: UniFile, stem: String): Set<PanelFlagReason> {
        val file = dir.findFile("$stem.json") ?: return emptySet()
        return try {
            val text = file.openInputStream().use { it.readBytes() }.decodeToString()
            JSON.decodeFromString<PanelFlagPayload>(text).reasons
        } catch (t: Throwable) {
            logcat(LogPriority.ERROR, t) { "panelFlag: failed to read existing reasons for $stem" }
            emptySet()
        }
    }

    private fun extensionFor(mimeType: String?): String = when (mimeType) {
        "image/png" -> "png"
        "image/webp" -> "webp"
        else -> "jpg"
    }

    private fun sampleSizeFor(width: Int, height: Int, maxDimension: Int): Int {
        var sample = 1
        while (max(width, height) / sample > maxDimension) sample *= 2
        return sample
    }

    companion object {
        private const val MAX_DETECTION_DIMENSION = 900
        private val JSON = Json { prettyPrint = true }
    }
}
