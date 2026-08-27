package eu.kanade.tachiyomi.ui.reader.viewer.panel

/**
 * Letterbox geometry for fitting a [pageW]×[pageH] image into a square `inputSize` model input,
 * preserving aspect with centered gray padding (YOLO's standard preprocessing). Used to build the
 * model input *and* to undo the transform when mapping detections back to the page, so both steps
 * stay consistent.
 */
data class Letterbox(
    val scale: Float,
    val padX: Int,
    val padY: Int,
    val newW: Int,
    val newH: Int,
) {
    companion object {
        fun fit(pageW: Int, pageH: Int, inputSize: Int): Letterbox {
            val scale = minOf(inputSize / pageW.toFloat(), inputSize / pageH.toFloat())
            val newW = (pageW * scale).toInt().coerceAtLeast(1)
            val newH = (pageH * scale).toInt().coerceAtLeast(1)
            return Letterbox(scale, (inputSize - newW) / 2, (inputSize - newH) / 2, newW, newH)
        }
    }
}

/** Detected panels and speech bubbles in normalized page coordinates, plus the page pixel size. */
data class DetectResult(
    val panels: List<PanelRect>,
    val bubbles: List<PanelRect>,
    val pageW: Int,
    val pageH: Int,
)

/**
 * Decodes a YOLO panel/text detector's raw output tensor into [PanelRect]s in normalized page
 * coordinates. Class 0 = Panel, class 1 = Text/speech-balloon.
 *
 * Handles the two common output layouts:
 *  - end-to-end (NMS-free, e.g. YOLO26): `[1, numDet, 6]` rows of `[x1,y1,x2,y2,score,cls]`.
 *  - raw: `[1, 4+nc, anchors]` or `[1, anchors, 4+nc]` of `[cx,cy,w,h,cls0,cls1]` → filter + NMS.
 * Coordinates may be normalized (≤1) or in input pixels; both are detected and handled.
 */
class YoloPanelDecoder(
    val inputSize: Int = DEFAULT_INPUT_SIZE,
    private val confidenceThreshold: Float = DEFAULT_CONFIDENCE,
    private val nmsIoU: Float = DEFAULT_NMS_IOU,
    private val containmentThreshold: Float = DEFAULT_CONTAINMENT,
    private val minAreaFraction: Float = DEFAULT_MIN_AREA_FRACTION,
    private val minSideFraction: Float = DEFAULT_MIN_SIDE_FRACTION,
) {

    fun decode(raw: FloatArray, shape: IntArray, lb: Letterbox, pageW: Int, pageH: Int): DetectResult {
        if (shape.size != 3) return DetectResult(emptyList(), emptyList(), pageW, pageH)
        val d1 = shape[1]
        val d2 = shape[2]
        val transposed = d1 < d2 // [1, attrs, anchors]
        val attrs = if (transposed) d1 else d2
        val preds = if (transposed) d2 else d1
        fun at(pred: Int, attr: Int) = if (transposed) raw[attr * preds + pred] else raw[pred * attrs + attr]

        if (attrs < 6) return DetectResult(emptyList(), emptyList(), pageW, pageH)

        val endToEnd = preds <= 1000

        // Detect coordinate normalization by peeking at a few values.
        var maxCoord = 0f
        var sampled = 0
        var p = 0
        while (p < preds && sampled < 64) {
            val v = maxOf(at(p, 0), at(p, 1), at(p, 2), at(p, 3))
            if (v.isFinite()) { maxCoord = maxOf(maxCoord, v); sampled++ }
            p++
        }
        val coordScale = if (maxCoord <= 1.5f) inputSize.toFloat() else 1f

        val panelBoxes = ArrayList<FloatArray>() // x1,y1,x2,y2,score (input-pixel space)
        val bubbleBoxes = ArrayList<FloatArray>()

        for (i in 0 until preds) {
            val cls: Int
            val score: Float
            if (endToEnd) {
                score = at(i, 4)
                cls = at(i, 5).toInt()
            } else {
                val cls0 = at(i, 4); val cls1 = at(i, 5)
                if (cls0 >= cls1) { cls = PANEL_CLASS; score = cls0 } else { cls = TEXT_CLASS; score = cls1 }
            }
            if (score < confidenceThreshold || (cls != PANEL_CLASS && cls != TEXT_CLASS)) continue

            val a = at(i, 0) * coordScale
            val b = at(i, 1) * coordScale
            val c = at(i, 2) * coordScale
            val d = at(i, 3) * coordScale
            val x1: Float; val y1: Float; val x2: Float; val y2: Float
            if (endToEnd) { x1 = a; y1 = b; x2 = c; y2 = d } // xyxy
            else { x1 = a - c / 2f; y1 = b - d / 2f; x2 = a + c / 2f; y2 = b + d / 2f } // cxcywh
            val box = floatArrayOf(x1, y1, x2, y2, score)
            if (cls == PANEL_CLASS) panelBoxes.add(box) else bubbleBoxes.add(box)
        }

        // Suppress overlapping/nested duplicates within each class.
        val panels = toPanels(suppress(panelBoxes), lb, pageW, pageH, minAreaFraction, minSideFraction)
        val bubbles = toPanels(suppress(bubbleBoxes), lb, pageW, pageH, 0f, 0f)
        return DetectResult(panels, bubbles, pageW, pageH)
    }

    /** Filters by min area, undoes the letterbox, and normalizes boxes to [0,1] page coordinates. */
    private fun toPanels(
        boxes: List<FloatArray>,
        lb: Letterbox,
        pageW: Int,
        pageH: Int,
        minAreaFrac: Float,
        minSideFrac: Float,
    ): List<PanelRect> {
        val minArea = minAreaFrac * inputSize * inputSize
        val rects = boxes.mapNotNull { box ->
            val w = (box[2] - box[0]).coerceAtLeast(0f)
            val h = (box[3] - box[1]).coerceAtLeast(0f)
            if (w * h < minArea) return@mapNotNull null
            val l = ((box[0] - lb.padX) / lb.scale / pageW).coerceIn(0f, 1f)
            val t = ((box[1] - lb.padY) / lb.scale / pageH).coerceIn(0f, 1f)
            val r = ((box[2] - lb.padX) / lb.scale / pageW).coerceIn(0f, 1f)
            val bo = ((box[3] - lb.padY) / lb.scale / pageH).coerceIn(0f, 1f)
            if (r <= l || bo <= t) return@mapNotNull null
            PanelRect(l, t, r, bo)
        }
        val afterSlivers = if (minSideFrac > 0f) mergeSlivers(rects, minSideFrac) else rects
        return consolidateFragments(afterSlivers)
    }

    /**
     * Merges heavily overlapping, aligned fragments of the same panel.
     * When ML detection fractures a single panel into multiple pieces (e.g. a title banner or
     * ceiling strip plus the main scene below), the pieces share the same column/row alignment
     * and overlap each other significantly along the perpendicular axis without a separating gutter.
     */
    private fun consolidateFragments(rects: List<PanelRect>): List<PanelRect> {
        if (rects.size <= 1) return rects
        val current = rects.toMutableList()
        var changed = true
        while (changed) {
            changed = false
            var i = 0
            while (i < current.size) {
                var j = i + 1
                while (j < current.size) {
                    val a = current[i]
                    val b = current[j]
                    if (shouldConsolidate(a, b, current)) {
                        current[i] = union(a, b)
                        current.removeAt(j)
                        changed = true
                    } else {
                        j++
                    }
                }
                i++
            }
        }
        return current
    }

    private fun shouldConsolidate(a: PanelRect, b: PanelRect, all: List<PanelRect>): Boolean {
        val xOverlap = overlap(a.left, a.right, b.left, b.right)
        val yOverlap = overlap(a.top, a.bottom, b.top, b.bottom)
        val minW = minOf(a.width, b.width)
        val maxW = maxOf(a.width, b.width)
        val minH = minOf(a.height, b.height)
        val maxH = maxOf(a.height, b.height)
        if (minW <= 0f || minH <= 0f || maxW <= 0f || maxH <= 0f) return false

        // Vertical stacking (same column): both panels share similar column width and horizontal alignment
        val alignedVertically = (minW / maxW >= 0.75f) && (xOverlap / minW >= 0.80f)
        val overlappingVertically = (yOverlap / minH) >= 0.20f
        val isVerticalFragment = (minH < 0.22f || minOf(a.area, b.area) < 0.12f)

        // Horizontal stacking (same row): both panels share similar row height and vertical alignment
        val alignedHorizontally = (minH / maxH >= 0.75f) && (yOverlap / minH >= 0.80f)
        val overlappingHorizontally = (xOverlap / minW) >= 0.20f
        val isHorizontalFragment = (minW < 0.22f || minOf(a.area, b.area) < 0.12f)

        val candidate = if (alignedVertically && overlappingVertically && isVerticalFragment) {
            union(a, b)
        } else if (alignedHorizontally && overlappingHorizontally && isHorizontalFragment) {
            union(a, b)
        } else {
            return false
        }

        // Ensure merging doesn't swallow a third distinct panel on the page
        return all.none { it !== a && it !== b && containsCenter(candidate, it) }
    }

    private fun containsCenter(outer: PanelRect, inner: PanelRect): Boolean {
        val cx = (inner.left + inner.right) / 2f
        val cy = (inner.top + inner.bottom) / 2f
        return cx > outer.left && cx < outer.right && cy > outer.top && cy < outer.bottom
    }

    /**
     * A real panel is essentially never this thin — a box narrower than [minSideFrac] along either
     * axis is almost always a secondary false-positive detection running along a real panel's edge,
     * one that survives NMS because it isn't >60% contained within the real panel (it pokes slightly
     * past its border), so containment-based suppression alone doesn't catch it. That sliver's area is
     * still real page content though — usually a thin strip of the panel it's sitting against that the
     * real detection just under-shot — so fold it into whichever kept panel it belongs to (see
     * [bestHost]), rather than throwing that area away. Every sliver merges into some panel as long as
     * the page has at least one — there's no "too far to bother" cutoff, since a dropped sliver is
     * literally unaccounted-for page content, worse than a slightly-imprecise merge.
     */
    private fun mergeSlivers(rects: List<PanelRect>, minSideFrac: Float): List<PanelRect> {
        val (slivers, real) = rects.partition { it.width < minSideFrac || it.height < minSideFrac }
        if (slivers.isEmpty()) return real
        if (real.isEmpty()) return emptyList()
        val merged = real.toMutableList()
        for (sliver in slivers) {
            val hostIndex = bestHost(sliver, merged)
            merged[hostIndex] = union(merged[hostIndex], sliver)
        }
        return merged
    }

    /**
     * Picks which kept panel a sliver belongs to. A sliver is thin along one axis, so its true owner
     * is usually whichever panel shares its extent along the OTHER axis (same row for a vertical
     * sliver, same column for a horizontal one) with little to no gap along the thin axis. That
     * overlap is scored as a fraction of the *smaller* of the two spans, not raw intersection area — a
     * raw-area score would let a huge splash panel that merely reaches the sliver outrank the small
     * panel it's actually sitting against, since the splash panel's sheer size inflates the
     * intersection. Gap and candidate size are both continuous penalties rather than hard cutoffs, so
     * there's always a winner — a weak or distant match still beats leaving the sliver's area unclaimed.
     */
    private fun bestHost(sliver: PanelRect, candidates: List<PanelRect>): Int {
        val vertical = sliver.width <= sliver.height
        return candidates.indices.maxBy { index ->
            val c = candidates[index]
            val overlapFrac: Float
            val gap: Float
            if (vertical) {
                overlapFrac = overlap(sliver.top, sliver.bottom, c.top, c.bottom) /
                    minOf(sliver.height, c.height).coerceAtLeast(1e-4f)
                gap = (maxOf(sliver.left, c.left) - minOf(sliver.right, c.right)).coerceAtLeast(0f)
            } else {
                overlapFrac = overlap(sliver.left, sliver.right, c.left, c.right) /
                    minOf(sliver.width, c.width).coerceAtLeast(1e-4f)
                gap = (maxOf(sliver.top, c.top) - minOf(sliver.bottom, c.bottom)).coerceAtLeast(0f)
            }
            // Tiebreak toward the closer, tighter candidate so a near-tie doesn't fall to whichever
            // panel happens to be largest or farthest away.
            overlapFrac - gap * GAP_PENALTY - c.area * AREA_PENALTY
        }
    }

    private fun overlap(a0: Float, a1: Float, b0: Float, b1: Float): Float =
        (minOf(a1, b1) - maxOf(a0, b0)).coerceAtLeast(0f)

    private fun union(a: PanelRect, b: PanelRect): PanelRect =
        PanelRect(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom))

    /**
     * Greedy suppression by confidence: a box is dropped if it overlaps an already-kept box too
     * much (IoU) or is largely contained within one. Removes duplicate detections and panels nested
     * inside a larger panel.
     */
    private fun suppress(boxes: List<FloatArray>): List<FloatArray> {
        val sorted = boxes.sortedByDescending { it[4] }
        val kept = ArrayList<FloatArray>()
        for (box in sorted) {
            val redundant = kept.any { iou(it, box) > nmsIoU || containedFraction(box, it) > containmentThreshold }
            if (redundant) continue
            // A larger box can arrive after a smaller one it engulfs (the smaller scored higher), so
            // also evict any already-kept boxes now nested inside this one — we never keep a panel
            // inside another panel, whatever order they're processed in.
            kept.removeAll { containedFraction(it, box) > containmentThreshold }
            kept.add(box)
        }
        return kept
    }

    /** Fraction of [inner]'s area that lies inside [outer]. */
    private fun containedFraction(inner: FloatArray, outer: FloatArray): Float {
        val ix = (minOf(inner[2], outer[2]) - maxOf(inner[0], outer[0])).coerceAtLeast(0f)
        val iy = (minOf(inner[3], outer[3]) - maxOf(inner[1], outer[1])).coerceAtLeast(0f)
        val inter = ix * iy
        val innerArea = (inner[2] - inner[0]) * (inner[3] - inner[1])
        return if (innerArea <= 0f) 0f else inter / innerArea
    }

    private fun iou(a: FloatArray, b: FloatArray): Float {
        val ix = (minOf(a[2], b[2]) - maxOf(a[0], b[0])).coerceAtLeast(0f)
        val iy = (minOf(a[3], b[3]) - maxOf(a[1], b[1])).coerceAtLeast(0f)
        val inter = ix * iy
        val areaA = (a[2] - a[0]) * (a[3] - a[1])
        val areaB = (b[2] - b[0]) * (b[3] - b[1])
        val union = areaA + areaB - inter
        return if (union <= 0f) 0f else inter / union
    }

    companion object {
        const val PANEL_CLASS = 0
        const val TEXT_CLASS = 1

        const val DEFAULT_INPUT_SIZE = 640
        const val DEFAULT_CONFIDENCE = 0.25f
        const val DEFAULT_NMS_IOU = 0.45f
        const val DEFAULT_CONTAINMENT = 0.6f
        const val DEFAULT_MIN_AREA_FRACTION = 0.008f
        /** Panel detections whose narrower side is under this fraction of the page are merged into a neighbor. */
        const val DEFAULT_MIN_SIDE_FRACTION = 0.08f
        /** How strongly a gap between a sliver and a candidate panel counts against that candidate in [bestHost]. */
        const val GAP_PENALTY = 2f
        /** How strongly a candidate panel's own size counts against it in [bestHost] (tiebreak only). */
        const val AREA_PENALTY = 1e-3f
    }
}
