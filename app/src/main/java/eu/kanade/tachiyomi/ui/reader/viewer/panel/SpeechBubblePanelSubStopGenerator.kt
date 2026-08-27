package eu.kanade.tachiyomi.ui.reader.viewer.panel

import android.graphics.Bitmap

/**
 * Expands an oversized panel into its detected speech bubbles (in reading order), then the
 * full panel, instead of leaving it scaled down to fit both axes at once. Mirrors the existing
 * page-level "show each panel, then the full page" pattern one level down.
 *
 * A panel with no bubbles is never expanded regardless of fit — there's no dialogue continuity
 * to preserve, so it's shown as a single stop at whatever scale is achievable (same as today's
 * behavior). The [PanelRect.FULL_PAGE] "no real panels detected" fallback is never expanded
 * either, checked explicitly and first — see [Panel.bounds] doc and the design spec
 * (docs/superpowers/specs/2026-08-26-guided-view-panel-fit-design.md) for why this must be a
 * hard, explicit guard rather than an incidental consequence of the fit-quality check below.
 *
 * The fit-quality check is a deliberate simplification versus [ReaderPageImageView]'s own
 * `panelStopTarget()` scale math, which also factors in the page's real pixel aspect ratio.
 * Using only the panel's own normalized aspect ratio against the viewport's avoids needing the
 * page's real decoded pixel dimensions plumbed through the (cached) detection pipeline for what
 * is fundamentally a "does this shape mismatch this orientation" question. It's also a
 * deliberately *different* question from `panelStopTarget()`'s own tall/narrow-panel cap: that
 * one exists because such a panel renders too large/dominant at fit-both-axes scale; this one
 * exists because a panel whose shape badly mismatches the viewport's own shape renders too
 * *small* on whichever axis binds the fit-scale, prompting rotation to get more room on that
 * axis. They're solving different problems and reuse none of each other's constants. Per the
 * design spec's testing section, the exact threshold constants here are a starting point to be
 * tuned against real captured pages, not a final, precise formula.
 */
object SpeechBubblePanelSubStopGenerator : PanelSubStopGenerator {

    /**
     * How far the panel's own aspect ratio (width:height) is allowed to diverge from the
     * viewport's own aspect ratio, as a ratio of the two, before the panel counts as a poor fit.
     * A panel whose aspect is between [MIN_FIT_RATIO] and [MAX_FIT_RATIO] times the viewport's
     * own aspect is considered to use the screen reasonably well; further outside that range
     * means one axis will end up significantly under-using the available space once the other
     * axis binds the fit-scale (e.g. a wide/short panel in a tall/narrow portrait viewport ends
     * up constrained by the scarce width, rendering small with a lot of unused vertical margin).
     */
    private const val MIN_FIT_RATIO = 0.4f
    private const val MAX_FIT_RATIO = 2.5f

    override suspend fun generate(
        panel: Panel,
        direction: PanelDirection,
        viewWidth: Int,
        viewHeight: Int,
        cropPanel: suspend () -> Bitmap?,
    ): List<PanelRect> {
        if (panel.bounds == PanelRect.FULL_PAGE) return emptyList()
        if (panel.bubbles.isEmpty()) return emptyList()
        if (fitsCurrentOrientation(panel.bounds, viewWidth, viewHeight)) return emptyList()
        return panel.bubbles + panel.bounds
    }

    private fun fitsCurrentOrientation(bounds: PanelRect, viewWidth: Int, viewHeight: Int): Boolean {
        if (bounds.height <= 0f || viewHeight <= 0) return true
        val panelAspect = bounds.width / bounds.height
        val viewportAspect = viewWidth.toFloat() / viewHeight.toFloat()
        val mismatch = panelAspect / viewportAspect
        return mismatch in MIN_FIT_RATIO..MAX_FIT_RATIO
    }
}
