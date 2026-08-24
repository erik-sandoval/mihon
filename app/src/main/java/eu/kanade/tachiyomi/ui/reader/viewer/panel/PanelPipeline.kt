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
        // Add any large, roughly-rectangular region the model left uncovered as a panel, so missed
        // panels get numbered too — then order and plan as usual.
        // Panel-by-panel is paced one beat at a time regardless of reading direction, so both LTR
        // and RTL comics use the gentle manga profile: only truly tiny panels merge, and panels are
        // never further divided (see [PanelPlanner.Config.MANGA]).
        val config = PanelPlanner.Config.MANGA
        val isSpread = pageW.toFloat() / pageH.toFloat() >= SPREAD_ASPECT_MIN
        val filled = PanelGapFiller.fill(panels)
        logcat {
            "PanelOrderDebug rightToLeft=$rightToLeft isSpread=$isSpread pageW=$pageW pageH=$pageH filled=" +
                filled.joinToString(prefix = "[", postfix = "]") { "(l=${it.left},t=${it.top},r=${it.right},b=${it.bottom})" }
        }
        val ordered = PanelOrdering.order(filled, rightToLeft, isSpread)
        logcat {
            "PanelOrderDebug ordered=" +
                ordered.joinToString(prefix = "[", postfix = "]") { "(l=${it.left},t=${it.top},r=${it.right},b=${it.bottom})" }
        }
        val planned = PanelPlanner.plan(ordered, bubbles, pageW, pageH, rightToLeft, config)
        if (planned.size >= 2) return closeInteriorGaps(extendToPageEdges(pad(planned, bubbles)))
        // Detection found too little to work with (nothing, or one region covering most of the
        // page — a background too noisy/textured for the model to resolve real panel boundaries on
        // is a common cause). There's no real panel geometry here to zoom into, so show the whole
        // page rather than guessing at a geometric split (panning through quarters of a page that's
        // actually one panel, or a busy background, reads as broken rather than helpful).
        return listOf(PanelRect.FULL_PAGE)
    }

    /**
     * Grows each panel by [BASE_MARGIN] of its own size per side — capped so it never crosses more
     * than halfway into a neighbouring panel's gap (see [cappedMargin]) — then further, only as far
     * as actually needed, to fully contain any speech bubble that belongs to it (its centre falls
     * inside the panel) but bleeds past the panel's own tight detected edge, which real bubbles
     * routinely do. A flat margin sized to cover the worst overflowing bubble would waste zoom on
     * every ordinary panel that has none at all; this only grows a panel that actually needs it,
     * and only by as much as that specific bubble needs. Bubble growth is intentionally NOT capped
     * by neighbour distance — a bubble genuinely bleeding into a neighbour's territory still needs
     * to be shown whole.
     *
     * A bubble that straddles the narrow gutter between two panels can have its centre fall inside
     * *neither* panel's raw box — confirmed via logcat on Official Bleach ch.17 p16: a "COME IN FOR
     * A MINUTE" bubble's centre sat in the ~1%-of-page-width gap between two panels' raw edges, so
     * neither panel claimed it, neither grew to include it, and the shared cut line ran straight
     * through it. [unclaimedBubbles] falls back to "does it overlap this panel's raw box at all" for
     * that case only, so at least one adjacent panel grows to show it whole; a bubble with a clear
     * single owner (the normal case) is unaffected.
     */
    private fun pad(panels: List<PanelRect>, bubbles: List<PanelRect>): List<PanelRect> {
        val unclaimed = unclaimedBubbles(panels, bubbles)
        return panels.map { p ->
            var left = p.left - marginLeft(p, panels)
            var top = p.top - marginTop(p, panels)
            var right = p.right + marginRight(p, panels)
            var bottom = p.bottom + marginBottom(p, panels)

            for (b in bubbles) {
                val owns = (b.centerX in p.left..p.right && b.centerY in p.top..p.bottom) ||
                    (b in unclaimed && overlaps(p, b))
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
        bubbles.filterNot { b -> panels.any { b.centerX in it.left..it.right && b.centerY in it.top..it.bottom } }

    private fun overlaps(p: PanelRect, b: PanelRect) =
        b.left < p.right && b.right > p.left && b.top < p.bottom && b.bottom > p.top

    /**
     * [BASE_MARGIN] scales with a panel's own size, so a panel that's most of the page next to a
     * narrow sibling gets a margin many times the sibling's entire width — enough to swallow most of
     * it. Capping each side's margin at half the raw gap to the nearest panel in that direction (if
     * any) means two neighbours split their shared gutter evenly regardless of the size mismatch
     * between them; a side with no neighbour keeps the full proportional margin; [extendToPageEdges]
     * picks up whatever's still short of the page edge afterward.
     */
    private fun marginLeft(p: PanelRect, panels: List<PanelRect>): Float {
        val gap = panels.filter { it !== p && verticalOverlap(it, p) && it.right <= p.left }
            .minOfOrNull { p.left - it.right }
        return cappedMargin(p.width, gap)
    }

    private fun marginRight(p: PanelRect, panels: List<PanelRect>): Float {
        val gap = panels.filter { it !== p && verticalOverlap(it, p) && it.left >= p.right }
            .minOfOrNull { it.left - p.right }
        return cappedMargin(p.width, gap)
    }

    private fun marginTop(p: PanelRect, panels: List<PanelRect>): Float {
        val gap = panels.filter { it !== p && horizontalOverlap(it, p) && it.bottom <= p.top }
            .minOfOrNull { p.top - it.bottom }
        return cappedMargin(p.height, gap)
    }

    private fun marginBottom(p: PanelRect, panels: List<PanelRect>): Float {
        val gap = panels.filter { it !== p && horizontalOverlap(it, p) && it.top >= p.bottom }
            .minOfOrNull { it.top - p.bottom }
        return cappedMargin(p.height, gap)
    }

    private fun cappedMargin(panelSize: Float, gapToNeighbor: Float?): Float {
        val proportional = panelSize * BASE_MARGIN
        return if (gapToNeighbor == null) proportional else min(proportional, gapToNeighbor / 2f)
    }

    /**
     * A panel whose own margin (see [pad]) doesn't happen to reach the page edge leaves a strip of
     * page content permanently unreachable during panel-by-panel navigation, since nothing else will
     * ever show it either. Whichever panel is outermost on a given side (no other panel already
     * reaches further that way within its row or column) gets extended toward that edge, capped at
     * [MAX_EDGE_EXTENSION] — enough to absorb a real sliver of art the model just under-detected,
     * without dragging a panel across a large decorative border or blank margin to chase a page edge
     * that's genuinely just empty space.
     */
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

    /**
     * Even after [pad] and [extendToPageEdges], two adjacent panels can still leave a strip of
     * real page content between them that neither's own margin reaches — confirmed on a real page
     * (Official One Piece ch.944 p15): two panels 7% apart in their raw detection each grew inward
     * by their own BASE_MARGIN-derived amount ([cappedMargin]'s proportional cap, not the full
     * half-the-gap split its own kdoc describes — that only kicks in once proportional growth
     * would otherwise exceed it), leaving a ~2.1% strip in the middle that was never shown by
     * either panel during panel-by-panel navigation. Splits any such leftover interior gap evenly
     * between the two panels bordering it, so nothing between two real detected panels is ever
     * permanently unreachable — the same "don't lose real content" principle as
     * [extendToPageEdges], just for a gap with a panel on both sides instead of the page edge on
     * one.
     */
    private fun closeInteriorGaps(panels: List<PanelRect>): List<PanelRect> = panels.map { p ->
        var left = p.left
        var top = p.top
        var right = p.right
        var bottom = p.bottom

        panels.filter { it !== p && verticalOverlap(it, p) && it.left >= right }
            .minByOrNull { it.left - right }
            ?.let { right += (it.left - right) / 2f }
        panels.filter { it !== p && verticalOverlap(it, p) && it.right <= left }
            .minByOrNull { left - it.right }
            ?.let { left -= (left - it.right) / 2f }
        panels.filter { it !== p && horizontalOverlap(it, p) && it.top >= bottom }
            .minByOrNull { it.top - bottom }
            ?.let { bottom += (it.top - bottom) / 2f }
        panels.filter { it !== p && horizontalOverlap(it, p) && it.bottom <= top }
            .minByOrNull { top - it.bottom }
            ?.let { top -= (top - it.bottom) / 2f }

        PanelRect(left, top, right, bottom)
    }

    private fun verticalOverlap(a: PanelRect, b: PanelRect) = a.top < b.bottom && b.top < a.bottom
    private fun horizontalOverlap(a: PanelRect, b: PanelRect) = a.left < b.right && b.left < a.right
}
