package eu.kanade.tachiyomi.ui.reader.viewer.panel

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Grayscale pixel access over a page, abstracted so [ContentAwarePanelExpander] can be unit-tested
 * without a real `Bitmap`. Luma values are 0..255. Callers stay within `[0, width)` / `[0, height)`.
 */
interface LumaField {
    val width: Int
    val height: Int

    /** Luma values for column [x], rows `[y0, y1)`. */
    fun column(x: Int, y0: Int, y1: Int): IntArray

    /** Luma values for row [y], columns `[x0, x1)`. */
    fun row(y: Int, x0: Int, x1: Int): IntArray
}

/**
 * [LumaField] over a packed ARGB pixel array (row-major, `argb[y * width + x]`), converting each
 * pixel to integer Rec.601-ish luma on read: `(R*77 + G*150 + B*29) shr 8`. This is the production
 * adapter — call `Bitmap.getPixels` into the array once and hand it here.
 */
class ArgbLumaField(
    private val argb: IntArray,
    override val width: Int,
    override val height: Int,
) : LumaField {
    private fun luma(p: Int): Int =
        ((p ushr 16 and 0xFF) * 77 + (p ushr 8 and 0xFF) * 150 + (p and 0xFF) * 29) ushr 8

    override fun column(x: Int, y0: Int, y1: Int) = IntArray(y1 - y0) { luma(argb[(y0 + it) * width + x]) }
    override fun row(y: Int, x0: Int, x1: Int) = IntArray(x1 - x0) { luma(argb[y * width + (x0 + it)]) }
}

/**
 * Refines raw ML panel boxes by walking each edge outward until it hits a real between-panel
 * gutter, so a box that clipped a frame-breaking character, an undetected caption, or a bubble
 * that bleeds past the drawn border grows to show the whole thing. Pixels decide where the art
 * actually ends — the model only ever sees the drawn rectangle.
 *
 * Per edge: if the lines just past the detected edge are already a clean gutter, the box is
 * correctly placed and left alone. Otherwise the scan walks outward and commits the edge to the
 * start of the first gutter run it finds. If it reaches the cap without ever finding a gutter, the
 * edge doesn't move — that means borderless / full-bleed art, where there's no true boundary to
 * find and guessing big only makes things worse.
 *
 * Tunables were fitted against ~100 real flagged pages (Blue Lock + One Piece); see
 * `ContentAwarePanelExpanderTest` for the behaviours they encode.
 */
object ContentAwarePanelExpander {

    /** Adjacent-pixel luma jump that counts as an "edge". */
    private const val EDGE_DELTA = 24

    /** A gutter line: edge density at or below this... */
    private const val GUTTER_MAX_DENSITY = 0.030f

    /** ...and near-uniform tone (std)... */
    private const val GUTTER_MAX_STD = 14f

    /** ...and clearly white paper (mean >= HI) or a solid-black gutter bar (mean <= LO) — never
     *  mid-grey screentone in between. */
    private const val GUTTER_LUMA_LO = 65f
    private const val GUTTER_LUMA_HI = 200f

    /** If this many lines right past the detected edge are already a gutter, don't touch the edge. */
    private const val EDGE_AT_GUTTER_LINES = 3

    /** A gutter run must be at least this fraction of the page dimension to stop the scan. */
    private const val MIN_GUTTER_FRACTION = 0.010f

    /** No gutter found within this fraction of the page dimension → the edge doesn't move. */
    private const val MAX_EXPAND_FRACTION = 0.16f

    /** Overlap allowed into a neighbour box (a character bleeding across the gutter). */
    private const val NEIGHBOUR_OVERLAP_FRACTION = 0.015f

    /** A neighbour only blocks an edge if it shares at least this much of the perpendicular span. */
    private const val NEIGHBOUR_MIN_PERP_OVERLAP = 0.35f

    // --- caption absorption (top/bottom edges) -------------------------------------------------- #
    // A narration / caption bar the model didn't detect sits as: panel | thin gutter | busy bar |
    // gutter. If the bar is about as wide as the panel, the panel grows through it so the caption
    // reads with its scene; a bar wider than the panel belongs to a wider panel, not this one.

    /** Gutter between the panel and a caption it should absorb — at most this fraction of the page. */
    private const val CAPTION_GAP_MAX_FRACTION = 0.045f

    /** In the gap between the panel and the caption, a line this sparse still counts as blank (so a
     *  stray border speck or a wisp of screentone doesn't abort the reach for the caption). */
    private const val CAPTION_GAP_MAX_DENSITY = 0.06f

    /** A caption band is at most this fraction of the page tall (a bar, not another panel). */
    private const val CAPTION_MAX_HEIGHT_FRACTION = 0.11f

    /** The caption's ink may stick out past the panel's edge by at most this fraction of the page. */
    private const val CAPTION_STICKOUT_FRACTION = 0.06f

    /** ...and must cover at least this fraction of the panel's width to count as *its* caption. */
    private const val CAPTION_MIN_COVER = 0.45f

    // --- fragment merging -------------------------------------------------------------------------- #
    // Two detected boxes that overlap in 2D with no gutter anywhere across their seam are fragments
    // of one panel (the model split it) — union them before expanding. A real between-panel border
    // shows up as a gutter line in the seam, which keeps side-by-side / stacked panels separate.

    // Fragment merging is deliberately conservative: on a busy, diagonal, or borderless page there
    // are no white/black gutters for [isGutterLine] to find, so "no gutter ⇒ merge" collapses
    // correctly-detected pages. It only fires on two shapes with strong evidence.

    /** Near-duplicate: this much of the smaller box's area lies inside the other (an over-detection
     *  NMS's IoU/containment thresholds let through). */
    private const val FRAGMENT_NEAR_DUP_COVER = 0.42f

    /** Edge-strip clip: the model boxed a busy strip (a curtain, a rope) off a panel's side. The
     *  strip is at most this fraction of the main box's span, shares most of the perpendicular
     *  span, and is at most [FRAGMENT_MAX_GAP] of the page away. */
    private const val FRAGMENT_STRIP_RATIO = 0.28f
    private const val FRAGMENT_STRIP_MIN_SHARED = 0.6f
    private const val FRAGMENT_MAX_GAP = 0.02f

    fun expand(panels: List<PanelRect>, luma: LumaField): List<PanelRect> {
        if (panels.isEmpty() || luma.width <= 0 || luma.height <= 0) return panels
        val merged = mergeFragments(panels, luma)
        return merged.mapIndexed { i, panel -> expandOne(panel, merged, i, luma) }
    }

    /** Overlap of the merged box with a third panel that disqualifies the merge (fraction of the
     *  smaller of the two areas). Diagonal / borderless pages have no gutter to stop a merge; this
     *  is what keeps every panel from collapsing into one. */
    private const val FRAGMENT_SWALLOW_MAX = 0.15f

    /** Repeatedly unions any pair of boxes with no visible border between them, unless the union
     *  would swallow a third panel (⇒ there really is panel structure there). */
    private fun mergeFragments(panels: List<PanelRect>, luma: LumaField): List<PanelRect> {
        if (panels.size < 2) return panels
        val current = panels.toMutableList()
        var changed = true
        while (changed) {
            changed = false
            loop@ for (i in current.indices) {
                for (j in i + 1 until current.size) {
                    if (!noBorderBetween(current[i], current[j], luma)) continue
                    val union = PanelRect(
                        min(current[i].left, current[j].left),
                        min(current[i].top, current[j].top),
                        max(current[i].right, current[j].right),
                        max(current[i].bottom, current[j].bottom),
                    )
                    val swallows = current.withIndex().any { (k, p) ->
                        k != i && k != j && overlapArea(union, p) > FRAGMENT_SWALLOW_MAX * p.area
                    }
                    if (swallows) continue
                    current[i] = union
                    current.removeAt(j)
                    changed = true
                    break@loop
                }
            }
        }
        return current
    }

    private fun overlapArea(a: PanelRect, b: PanelRect): Float {
        val x = (min(a.right, b.right) - max(a.left, b.left)).coerceAtLeast(0f)
        val y = (min(a.bottom, b.bottom) - max(a.top, b.top)).coerceAtLeast(0f)
        return x * y
    }

    /**
     * True when [a] and [b] are two pieces of one panel — a near-duplicate over-detection, or a
     * busy strip the model clipped off a panel's side — with no gutter line across the seam.
     */
    private fun noBorderBetween(a: PanelRect, b: PanelRect, luma: LumaField): Boolean {
        val w = luma.width
        val h = luma.height
        val xOverlap = min(a.right, b.right) - max(a.left, b.left) // <0 ⇒ horizontal gap
        val yOverlap = min(a.bottom, b.bottom) - max(a.top, b.top)

        // Near-duplicate: most of the smaller box sits inside the other, with no gutter in the overlap.
        if (xOverlap > 0f && yOverlap > 0f) {
            val cover = (xOverlap * yOverlap) / min(a.area, b.area)
            if (cover >= FRAGMENT_NEAR_DUP_COVER) {
                val sLo = (max(a.left, b.left) * w).toInt().coerceIn(0, w - 1)
                val sHi = (min(a.right, b.right) * w).toInt().coerceIn(sLo + 1, w)
                val tLo = (max(a.top, b.top) * h).toInt().coerceIn(0, h - 1)
                val tHi = (min(a.bottom, b.bottom) * h).toInt().coerceIn(tLo + 1, h)
                return (tLo until tHi).none { y -> isGutterLine(luma.row(y, sLo, sHi)) }
            }
        }

        // Edge-strip clip: a thin strip hairline-gapped off one side, sharing most of the other axis.
        val gapX = -xOverlap
        val gapY = -yOverlap
        val xStrip = min(a.width, b.width) <= FRAGMENT_STRIP_RATIO * max(a.width, b.width)
        val yStrip = min(a.height, b.height) <= FRAGMENT_STRIP_RATIO * max(a.height, b.height)
        if (gapX in 0f..FRAGMENT_MAX_GAP && xStrip &&
            yOverlap >= FRAGMENT_STRIP_MIN_SHARED * min(a.height, b.height)
        ) {
            val lo = (min(a.right, b.right) * w).toInt().coerceIn(0, w - 1)
            val hi = (max(a.left, b.left) * w).toInt().coerceIn(lo + 1, w)
            val sLo = (max(a.top, b.top) * h).toInt().coerceIn(0, h - 1)
            val sHi = (min(a.bottom, b.bottom) * h).toInt().coerceIn(sLo + 1, h)
            return (lo until hi).none { x -> isGutterLine(luma.column(x, sLo, sHi)) }
        }
        if (gapY in 0f..FRAGMENT_MAX_GAP && yStrip &&
            xOverlap >= FRAGMENT_STRIP_MIN_SHARED * min(a.width, b.width)
        ) {
            val lo = (min(a.bottom, b.bottom) * h).toInt().coerceIn(0, h - 1)
            val hi = (max(a.top, b.top) * h).toInt().coerceIn(lo + 1, h)
            val sLo = (max(a.left, b.left) * w).toInt().coerceIn(0, w - 1)
            val sHi = (min(a.right, b.right) * w).toInt().coerceIn(sLo + 1, w)
            return (lo until hi).none { y -> isGutterLine(luma.row(y, sLo, sHi)) }
        }
        return false
    }

    private fun expandOne(box: PanelRect, all: List<PanelRect>, self: Int, luma: LumaField): PanelRect {
        val w = luma.width
        val h = luma.height
        val maxExX = (MAX_EXPAND_FRACTION * w).toInt()
        val maxExY = (MAX_EXPAND_FRACTION * h).toInt()
        val minGutX = max(2, (MIN_GUTTER_FRACTION * w).toInt())
        val minGutY = max(2, (MIN_GUTTER_FRACTION * h).toInt())

        val l = (box.left * w).toInt().coerceIn(0, w - 1)
        val r = (box.right * w).toInt().coerceIn(1, w)
        val t = (box.top * h).toInt().coerceIn(0, h - 1)
        val b = (box.bottom * h).toInt().coerceIn(1, h)

        val others = all.filterIndexed { idx, _ -> idx != self }
        val cx = (box.left + box.right) / 2f
        val cy = (box.top + box.bottom) / 2f

        fun perpX(o: PanelRect) = spanOverlapFraction(box.top, box.bottom, o.top, o.bottom) >= NEIGHBOUR_MIN_PERP_OVERLAP
        fun perpY(o: PanelRect) = spanOverlapFraction(box.left, box.right, o.left, o.right) >= NEIGHBOUR_MIN_PERP_OVERLAP

        val ovX = (NEIGHBOUR_OVERLAP_FRACTION * w).toInt()
        val ovY = (NEIGHBOUR_OVERLAP_FRACTION * h).toInt()
        val stopRight = others.filter { perpX(it) && (it.left + it.right) / 2f > cx }
            .minOfOrNull { (it.left * w).toInt() }?.plus(ovX)
        val stopLeft = others.filter { perpX(it) && (it.left + it.right) / 2f < cx }
            .maxOfOrNull { (it.right * w).toInt() }?.minus(ovX)
        val stopDown = others.filter { perpY(it) && (it.top + it.bottom) / 2f > cy }
            .minOfOrNull { (it.top * h).toInt() }?.plus(ovY)
        val stopUp = others.filter { perpY(it) && (it.top + it.bottom) / 2f < cy }
            .maxOfOrNull { (it.bottom * h).toInt() }?.minus(ovY)

        // Caption absorption is vertical-only and must never cross a real neighbour.
        val captionGap = (CAPTION_GAP_MAX_FRACTION * h).toInt()
        val captionMaxH = max(2, (CAPTION_MAX_HEIGHT_FRACTION * h).toInt())
        val stickout = (CAPTION_STICKOUT_FRACTION * w).toInt()
        fun absorbCaptionAt(gutterLine: Int, step: Int, neighbourStop: Int?): Int =
            captionExtendedEdge(gutterLine, step, h, l, r, stickout, captionGap, captionMaxH, neighbourStop) { y ->
                luma.row(y, 0, w)
            }

        val newR = scan(r, +1, maxExX, w, minGutX, stopRight, null) { x -> luma.column(x, t, b) }
        val newL = scan(l, -1, maxExX, w, minGutX, stopLeft, null) { x -> luma.column(x, t, b) }
        val newB = scan(b, +1, maxExY, h, minGutY, stopDown, { g -> absorbCaptionAt(g, +1, stopDown) }) { y -> luma.row(y, l, r) }
        val newT = scan(t, -1, maxExY, h, minGutY, stopUp, { g -> absorbCaptionAt(g, -1, stopUp) }) { y -> luma.row(y, l, r) }

        return PanelRect(
            (newL.toFloat() / w).coerceIn(0f, 1f),
            (newT.toFloat() / h).coerceIn(0f, 1f),
            (newR.toFloat() / w).coerceIn(0f, 1f),
            (newB.toFloat() / h).coerceIn(0f, 1f),
        )
    }

    /**
     * Walks from [start] in direction [step] (+1 / -1). Returns the new edge coordinate: the start
     * of the first gutter run at least [minGut] lines long, or a neighbour stop, or [start] itself
     * if the edge is already at a gutter or no gutter is found within [maxMove].
     */
    private fun scan(
        start: Int,
        step: Int,
        maxMove: Int,
        axisLen: Int,
        minGut: Int,
        neighbourStop: Int?,
        onGutter: ((Int) -> Int)?,
        lineAt: (Int) -> IntArray,
    ): Int {
        if (neighbourStop != null &&
            ((step > 0 && neighbourStop <= start) || (step < 0 && neighbourStop >= start))
        ) {
            return start
        }

        // Already at a gutter? Leave the edge alone.
        var probe = start
        var allGutter = true
        repeat(EDGE_AT_GUTTER_LINES) {
            probe += step
            if (probe !in 0 until axisLen || !isGutterLine(lineAt(probe))) allGutter = false
        }
        // Already correctly placed at a gutter — but a caption bar may still sit just past it.
        if (allGutter) return onGutter?.invoke(start) ?: start

        var gutterRun = 0
        var c = start
        repeat(maxMove) {
            c += step
            if (c !in 0 until axisLen) return c - step
            if (neighbourStop != null && ((step > 0 && c >= neighbourStop) || (step < 0 && c <= neighbourStop))) {
                return neighbourStop
            }
            if (isGutterLine(lineAt(c))) {
                gutterRun++
                if (gutterRun >= minGut) {
                    val g = c - step * gutterRun
                    return onGutter?.invoke(g) ?: g
                }
            } else {
                gutterRun = 0
            }
        }
        // Cap hit without a gutter → borderless art, no real boundary. Don't move.
        return start
    }

    /**
     * Given [gutterLine] where a top/bottom scan would stop, checks for an undetected caption bar
     * just beyond it — `gutter | busy bar | gutter`. Returns the far side of the bar if the bar is
     * about as wide as the panel (`[panelL, panelR]` ± [stickout]); otherwise returns [gutterLine]
     * unchanged. [step] is +1 (bottom edge, bar below) or -1 (top edge, bar above).
     */
    private fun captionExtendedEdge(
        gutterLine: Int,
        step: Int,
        axisLen: Int,
        panelL: Int,
        panelR: Int,
        stickout: Int,
        maxGap: Int,
        maxBandHeight: Int,
        neighbourStop: Int?,
        fullRowAt: (Int) -> IntArray,
    ): Int {
        fun inBounds(y: Int) = y in 0 until axisLen
        fun blocked(y: Int) = neighbourStop != null &&
            ((step > 0 && y >= neighbourStop) || (step < 0 && y <= neighbourStop))

        // Reach across the blank gap to the caption, starting one line past the panel's own edge
        // (bounded — a wide blank strip means there is no caption, just margin).
        var y = gutterLine + step
        var gap = 0
        while (inBounds(y) && edgeDensity(fullRowAt(y)) <= CAPTION_GAP_MAX_DENSITY && gap <= maxGap && !blocked(y)) {
            y += step; gap++
        }
        if (!inBounds(y) || gap == 0 || gap > maxGap || blocked(y)) return gutterLine

        // Walk the caption band, tracking the horizontal span of its ink.
        val bandStart = y
        var capL = Int.MAX_VALUE
        var capR = Int.MIN_VALUE
        var bandH = 0
        while (inBounds(y) && !isGutterLine(fullRowAt(y)) && bandH <= maxBandHeight && !blocked(y)) {
            val (rl, rr) = inkExtent(fullRowAt(y))
            if (rr >= rl) {
                capL = min(capL, rl)
                capR = max(capR, rr)
            }
            y += step; bandH++
        }
        val bandEnd = y
        if (bandH < 2 || bandH > maxBandHeight || capR < capL) return gutterLine
        // Needs a gutter (or the page edge) closing the far side.
        if (inBounds(bandEnd) && !isGutterLine(fullRowAt(bandEnd))) return gutterLine

        val panelWidth = panelR - panelL
        val coversEnough = (capR - capL) >= CAPTION_MIN_COVER * panelWidth
        val fitsWidth = capL >= panelL - stickout && capR <= panelR + stickout
        return if (coversEnough && fitsWidth) bandEnd else gutterLine
    }

    /** First and last column index in [vals] adjacent to a strong luma edge; `(MAX, MIN)` if none. */
    private fun inkExtent(vals: IntArray): Pair<Int, Int> {
        var lo = Int.MAX_VALUE
        var hi = Int.MIN_VALUE
        for (i in 1 until vals.size) {
            if (abs(vals[i] - vals[i - 1]) > EDGE_DELTA) {
                if (i - 1 < lo) lo = i - 1
                if (i > hi) hi = i
            }
        }
        return lo to hi
    }

    /** A near-uniform strip of a single tone — white paper OR a solid-black gutter bar. */
    private fun isGutterLine(vals: IntArray): Boolean {
        if (vals.isEmpty()) return false
        if (edgeDensity(vals) > GUTTER_MAX_DENSITY) return false
        var sum = 0L
        for (v in vals) sum += v
        val mean = sum.toFloat() / vals.size
        var sq = 0.0
        for (v in vals) sq += (v - mean).toDouble() * (v - mean)
        val std = sqrt(sq / vals.size).toFloat()
        if (std > GUTTER_MAX_STD) return false
        return mean >= GUTTER_LUMA_HI || mean <= GUTTER_LUMA_LO
    }

    private fun edgeDensity(vals: IntArray): Float {
        if (vals.size < 2) return 0f
        var edges = 0
        for (i in 1 until vals.size) if (abs(vals[i] - vals[i - 1]) > EDGE_DELTA) edges++
        return edges.toFloat() / (vals.size - 1)
    }

    /** Overlap of `[a0,a1]` and `[b0,b1]` as a fraction of the shorter span. */
    private fun spanOverlapFraction(a0: Float, a1: Float, b0: Float, b1: Float): Float {
        val span = min(a1 - a0, b1 - b0)
        if (span <= 0f) return 0f
        return (min(a1, b1) - max(a0, b0)) / span
    }
}
