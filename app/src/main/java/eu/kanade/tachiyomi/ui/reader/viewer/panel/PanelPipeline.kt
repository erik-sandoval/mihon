package eu.kanade.tachiyomi.ui.reader.viewer.panel

import tachiyomi.core.common.util.system.logcat
import kotlin.math.max
import kotlin.math.min

/**
 * The full shared post-detection pipeline in one call: raw detected boxes go in, final zoom
 * regions in reading order come out.
 */
object PanelPipeline {
    /**
     * Baseline breathing room: every panel is grown by this fraction of its own size on every
     * side regardless of bubble overflow, so the reader shows a little context instead of hugging
     * the detected box edge-to-edge even on an ordinary panel with no overflowing bubble.
     */
    private const val BASE_MARGIN = 0.057f

    /**
     * Once a panel is grown to fully contain an overflowing bubble, this much further clearance
     * (a fraction of the bubble's own size) is added so the bubble's own edge isn't touching the
     * screen edge.
     */
    private const val BUBBLE_CLEARANCE = 0.06f

    /** Image aspect (w/h) at or above which a page is treated as a double-page spread. */
    private const val SPREAD_ASPECT_MIN = 1.15f

    /** Max distance (as a fraction of the page) [extendToPageEdges] will stretch a panel toward a page edge. */
    private const val MAX_EDGE_EXTENSION = 0.043f

    fun zoomRegions(
        panels: List<PanelRect>,
        bubbles: List<PanelRect>,
        pageW: Int,
        pageH: Int,
        rightToLeft: Boolean,
    ): List<PanelRect> {
        val config = PanelPlanner.Config.MANGA
        val isSpread = pageW.toFloat() / pageH.toFloat() >= SPREAD_ASPECT_MIN
        val filled = PanelGapFiller.fill(panels)
        val bubbleAligned = alignBoundariesToSpeechBubbles(filled, bubbles)
        logcat {
            "PanelOrderDebug rightToLeft=$rightToLeft isSpread=$isSpread pageW=$pageW pageH=$pageH filled=" +
                bubbleAligned.joinToString(prefix = "[", postfix = "]") { "(l=${it.left},t=${it.top},r=${it.right},b=${it.bottom})" }
        }
        val ordered = PanelOrdering.order(bubbleAligned, rightToLeft, isSpread)
        logcat {
            "PanelOrderDebug ordered=" +
                ordered.joinToString(prefix = "[", postfix = "]") { "(l=${it.left},t=${it.top},r=${it.right},b=${it.bottom})" }
        }
        val planned = PanelPlanner.plan(ordered, bubbles, pageW, pageH, rightToLeft, config)
        if (planned.size >= 2) return closeInteriorGaps(extendToPageEdges(pad(planned, bubbles)))
        return listOf(PanelRect.FULL_PAGE)
    }

    /**
     * Adjusts panel boundaries so they don't awkwardly slice through speech bubbles or bleeding character heads
     * belonging to a neighboring panel (especially on frameless / borderless character panels where background
     * detection overshoots into dialogue and portrait artwork).
     */
    private fun alignBoundariesToSpeechBubbles(panels: List<PanelRect>, bubbles: List<PanelRect>): List<PanelRect> {
        if (panels.size <= 1 || bubbles.isEmpty()) return panels
        val result = panels.toMutableList()

        for (i in result.indices) {
            val p = result[i]
            // Check vertical neighbors below 'p'
            val neighborsBelow = result.indices.filter { it != i && result[it].centerY > p.centerY && horizontalOverlap(p, result[it]) }
            for (belowIdx in neighborsBelow) {
                val belowPanel = result[belowIdx]
                // Find bubbles that straddle or penetrate the boundary between p and belowPanel
                val straddlingBubbles = bubbles.filter { b ->
                    horizontalOverlap(b, belowPanel) &&
                        b.top < p.bottom &&
                        b.bottom >= belowPanel.top &&
                        b.top > (p.top + p.height * 0.35f)
                }
                if (straddlingBubbles.isNotEmpty()) {
                    val highestBubbleTop = straddlingBubbles.minOf { it.top }
                    // Generous clearance above the highest speech bubble of the cluster to capture character hair/head
                    val headClearance = 0.075f
                    val cutY = (highestBubbleTop - headClearance).coerceAtLeast(p.top + 0.15f)
                    if (cutY < p.bottom) {
                        result[i] = PanelRect(p.left, p.top, p.right, cutY)
                        result[belowIdx] = PanelRect(belowPanel.left, minOf(belowPanel.top, cutY), belowPanel.right, belowPanel.bottom)
                    }
                }
            }
        }
        return result
    }

    /**
     * Grows each panel by [BASE_MARGIN] of its own size per side — capped so it never crosses more
     * than halfway into a neighbouring panel's gap (see [cappedMargin]) — then further, only as far
     * as actually needed, to fully contain any speech bubble that belongs to it.
     */
    private fun pad(panels: List<PanelRect>, bubbles: List<PanelRect>): List<PanelRect> {
        val unclaimed = unclaimedBubbles(panels, bubbles)
        return panels.map { p ->
            var left = p.left - marginLeft(p, panels)
            var top = p.top - marginTop(p, panels)
            var right = p.right + marginRight(p, panels)
            var bottom = p.bottom + marginBottom(p, panels)

            for (b in bubbles) {
                val owns = containsCenter(p, b) || (b in unclaimed && overlaps(p, b))
                if (!owns) continue
                val clearanceX = b.width * BUBBLE_CLEARANCE
                val clearanceY = b.height * BUBBLE_CLEARANCE
                left = min(left, b.left - clearanceX)
                top = min(top, b.top - clearanceY)
                right = max(right, b.right + clearanceX)
                bottom = max(bottom, b.bottom + clearanceY)
            }

            PanelRect(left.coerceAtLeast(0f), top.coerceAtLeast(0f), right.coerceAtMost(1f), bottom.coerceAtMost(1f))
        }
    }

    private fun unclaimedBubbles(panels: List<PanelRect>, bubbles: List<PanelRect>): List<PanelRect> =
        bubbles.filterNot { b -> panels.any { containsCenter(it, b) } }

    private fun overlaps(p: PanelRect, b: PanelRect) =
        b.left < p.right && b.right > p.left && b.top < p.bottom && b.bottom > p.top

    private fun marginLeft(p: PanelRect, panels: List<PanelRect>): Float {
        val neighbors = panels.filter { it !== p && verticalOverlap(it, p) && it.centerX < p.centerX }
        if (neighbors.isEmpty()) return p.width * BASE_MARGIN
        val gap = neighbors.minOf { (p.left - it.right) }
        return cappedMargin(p.width, gap)
    }

    private fun marginRight(p: PanelRect, panels: List<PanelRect>): Float {
        val neighbors = panels.filter { it !== p && verticalOverlap(it, p) && it.centerX > p.centerX }
        if (neighbors.isEmpty()) return p.width * BASE_MARGIN
        val gap = neighbors.minOf { (it.left - p.right) }
        return cappedMargin(p.width, gap)
    }

    private fun marginTop(p: PanelRect, panels: List<PanelRect>): Float {
        val neighbors = panels.filter { it !== p && horizontalOverlap(it, p) && it.centerY < p.centerY }
        if (neighbors.isEmpty()) return p.height * BASE_MARGIN
        val gap = neighbors.minOf { (p.top - it.bottom) }
        return cappedMargin(p.height, gap)
    }

    private fun marginBottom(p: PanelRect, panels: List<PanelRect>): Float {
        val neighbors = panels.filter { it !== p && horizontalOverlap(it, p) && it.centerY > p.centerY }
        if (neighbors.isEmpty()) return p.height * BASE_MARGIN
        val gap = neighbors.minOf { (it.top - p.bottom) }
        return cappedMargin(p.height, gap)
    }

    private fun cappedMargin(panelSize: Float, gapToNeighbor: Float): Float {
        if (gapToNeighbor <= 0f) return 0f
        val proportional = panelSize * BASE_MARGIN
        return min(proportional, gapToNeighbor / 2f)
    }

    private fun extendToPageEdges(panels: List<PanelRect>): List<PanelRect> = panels.map { p ->
        var left = p.left
        var top = p.top
        var right = p.right
        var bottom = p.bottom
        if (left > 0f && panels.none { it !== p && verticalOverlap(it, p) && it.left < left }) {
            left = (left - MAX_EDGE_EXTENSION).coerceAtLeast(0f)
        }
        if (top > 0f && panels.none { it !== p && horizontalOverlap(it, p) && it.top < top }) {
            top = (top - MAX_EDGE_EXTENSION).coerceAtLeast(0f)
        }
        if (right < 1f && panels.none { it !== p && verticalOverlap(it, p) && it.right > right }) {
            right = (right + MAX_EDGE_EXTENSION).coerceAtMost(1f)
        }
        if (bottom < 1f && panels.none { it !== p && horizontalOverlap(it, p) && it.bottom > bottom }) {
            bottom = (bottom + MAX_EDGE_EXTENSION).coerceAtMost(1f)
        }
        PanelRect(left, top, right, bottom)
    }

    private fun closeInteriorGaps(panels: List<PanelRect>): List<PanelRect> = panels.map { p ->
        var left = p.left
        var top = p.top
        var right = p.right
        var bottom = p.bottom

        panels.filter { it !== p && verticalOverlap(it, p) && it.centerX > p.centerX }
            .minByOrNull { it.left }
            ?.let { nearest -> if (nearest.left > right) right += (nearest.left - right) / 2f }
        panels.filter { it !== p && verticalOverlap(it, p) && it.centerX < p.centerX }
            .maxByOrNull { it.right }
            ?.let { nearest -> if (nearest.right < left) left -= (left - nearest.right) / 2f }
        panels.filter { it !== p && horizontalOverlap(it, p) && it.centerY > p.centerY }
            .minByOrNull { it.top }
            ?.let { nearest -> if (nearest.top > bottom) bottom += (nearest.top - bottom) / 2f }
        panels.filter { it !== p && horizontalOverlap(it, p) && it.centerY < p.centerY }
            .maxByOrNull { it.bottom }
            ?.let { nearest -> if (nearest.bottom < top) top -= (top - nearest.bottom) / 2f }

        PanelRect(left, top, right, bottom)
    }

    private fun verticalOverlap(a: PanelRect, b: PanelRect) = a.top < b.bottom && b.top < a.bottom
    private fun horizontalOverlap(a: PanelRect, b: PanelRect) = a.left < b.right && b.left < a.right

    fun associateBubbles(panels: List<PanelRect>, bubbles: List<PanelRect>, rightToLeft: Boolean): List<Panel> {
        val bubblesByOwner = bubbles.groupBy { b -> panels.indexOfFirst { containsCenter(it, b) } }
        return panels.mapIndexed { panelIndex, panel ->
            val owned = bubblesByOwner[panelIndex].orEmpty()
            val ordered = if (owned.size > 1) PanelOrdering.order(owned, rightToLeft, isSpread = false) else owned
            Panel(bounds = panel, bubbles = ordered)
        }
    }

    private fun containsCenter(p: PanelRect, b: PanelRect) =
        b.centerX in p.left..p.right && b.centerY in p.top..p.bottom
}
