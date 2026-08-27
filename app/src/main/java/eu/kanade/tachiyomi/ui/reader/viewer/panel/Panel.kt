package eu.kanade.tachiyomi.ui.reader.viewer.panel

import kotlinx.serialization.Serializable

enum class PanelDirection { LTR, RTL }

@Serializable
data class PanelRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
    val area: Float get() = width * height
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    companion object {
        val FULL_PAGE = PanelRect(0f, 0f, 1f, 1f)
    }
}

@Serializable
data class Panel(
    val bounds: PanelRect,
    /**
     * This panel's detected speech-bubble boxes, in reading order, normalized to page
     * coordinates (same convention as [bounds]). Populated once at detection time from the
     * same ML inference pass that detects panels — orientation-agnostic, cached alongside
     * [bounds]. Whether these get used to expand this panel into multiple stops is a
     * separate, live decision (see [PanelSubStopGenerator]), never baked in here.
     */
    val bubbles: List<PanelRect> = emptyList(),
    val subStops: List<PanelRect> = emptyList(),
)

@Serializable
data class PanelPageData(val panels: List<Panel>)

/**
 * Flattens each panel's substops (or its own bounds, if it has none) into one ordered
 * navigation list, optionally bracketed with a full-page reveal — [showIntro] shows the whole
 * page before stepping into the first panel, [showOutro] shows it again after the last one,
 * before turning the page. Skipped entirely when the only stop is already the full page (the
 * detector's no-panels-found fallback), so the reader never taps once for a visually identical
 * stop no matter how intro/outro are configured.
 */
fun List<Panel>.flattenToStops(showIntro: Boolean = false, showOutro: Boolean = true): List<PanelRect> {
    val stops = flatMap { panel -> panel.subStops.ifEmpty { listOf(panel.bounds) } }
    if (stops.size == 1 && stops.single() == PanelRect.FULL_PAGE) return stops
    val withIntro = if (showIntro) listOf(PanelRect.FULL_PAGE) + stops else stops
    return if (showOutro) withIntro + PanelRect.FULL_PAGE else withIntro
}

/**
 * Each panel's own stops — its [Panel.subStops] if present, otherwise a single-element list of
 * just its own [Panel.bounds]. This is the exact per-panel breakdown [flattenToStops] concatenates
 * (before intro/outro bracketing), factored out so every consumer of that breakdown — including
 * the single-full-page-fallback special case below — agrees on it, instead of each re-deriving it
 * inline and risking one of them skipping the special case.
 */
private fun List<Panel>.stopsPerPanel(): List<List<PanelRect>> =
    map { it.subStops.ifEmpty { listOf(it.bounds) } }

/**
 * True when this panel list is exactly [flattenToStops]' no-panels-detected fallback: a single
 * panel whose only stop is the full page. [flattenToStops] special-cases this to a single stop
 * with **no** intro/outro brackets, regardless of [showIntro]/[showOutro] — any code computing a
 * flat-stop cursor from [showIntro] must check this first, or it'll look for a bracket that was
 * never actually added.
 */
private fun List<Panel>.isSingleFullPageFallback(stopsPerPanel: List<List<PanelRect>> = stopsPerPanel()): Boolean =
    stopsPerPanel.size == 1 && single().bounds == PanelRect.FULL_PAGE && stopsPerPanel.single().size == 1

/**
 * The flat-stop index at which [panelIndex]'s own stops begin, mirroring [flattenToStops]' own
 * offset math exactly — including its no-brackets special case for the single-full-page fallback
 * (see [isSingleFullPageFallback]), where the answer is always `0` regardless of [showIntro].
 */
private fun List<Panel>.cursorStartForPanel(panelIndex: Int, showIntro: Boolean): Int {
    val stopsPerPanel = stopsPerPanel()
    if (isSingleFullPageFallback(stopsPerPanel)) return 0
    return (if (showIntro) 1 else 0) + stopsPerPanel.take(panelIndex).sumOf { it.size }
}

/**
 * Maps a flat stop index (as produced by [flattenToStops] with the same [showIntro]/[showOutro])
 * back to the index of the panel in this list that owns it, or `null` if [flatIndex] is an
 * intro/outro full-page bracket stop, which belongs to no panel.
 */
fun List<Panel>.panelIndexForFlatStop(flatIndex: Int, showIntro: Boolean, showOutro: Boolean): Int? {
    val stopsPerPanel = stopsPerPanel()
    if (isSingleFullPageFallback(stopsPerPanel)) {
        // flattenToStops' own no-duplicate-fallback rule: exactly one stop, no brackets at all.
        return if (flatIndex == 0) 0 else null
    }
    var cursor = if (showIntro) 1 else 0
    for ((panelIndex, stops) in stopsPerPanel.withIndex()) {
        if (flatIndex in cursor until cursor + stops.size) return panelIndex
        cursor += stops.size
    }
    return null // falls in the outro bracket (or out of range)
}

/**
 * Resumes across a stop list that may have reshaped (a preference toggle changed, or the
 * device rotated) — this receiver is the *new* flattened panel list. Finds which panel owned
 * [oldFlatIndex] under [oldPanels], then: if that panel's stop count grew, resumes at its first
 * new stop; otherwise (shrank or unchanged), resumes at the nearest-by-distance stop among only
 * that panel's new stops. See the design spec's "Resuming across a stop-list reshape" section
 * for why one rule serves both triggers.
 *
 * Both the old- and new-side cursor math go through [cursorStartForPanel], which itself defers to
 * [isSingleFullPageFallback] — without that, a no-panels-detected page (a single
 * `Panel(bounds = PanelRect.FULL_PAGE)`, no brackets ever added by [flattenToStops] regardless of
 * [oldShowIntro]/[newShowIntro]) would have this function assume a bracket exists that
 * [flattenToStops] never actually emitted, throwing or returning an out-of-range index.
 *
 * When [oldFlatIndex] was an intro/outro bracket (owned by no panel, so there's no panel shape to
 * compare), the two are resolved separately: the intro resumes at the new list's first stop, the
 * outro at its last. Collapsing both to `0` — as this originally did — meant toggling the
 * preference while sitting on the trailing full-page reveal bounced the reader all the way back to
 * the start of the page. Which bracket it actually was can only be told from the index itself
 * (both brackets are the identical [PanelRect.FULL_PAGE] rect, and [oldShowIntro]/[oldShowOutro]
 * only say a bracket *could* be there), so both are checked against the old flattened list's real
 * extents rather than inferred from the flags alone.
 */
fun List<Panel>.resumeIndexAfterReshape(
    oldFlatIndex: Int,
    oldPanels: List<Panel>,
    oldShowIntro: Boolean,
    oldShowOutro: Boolean,
    newShowIntro: Boolean,
    newShowOutro: Boolean,
): Int {
    val ownerPanelIndex = oldPanels.panelIndexForFlatStop(oldFlatIndex, oldShowIntro, oldShowOutro)
    if (ownerPanelIndex == null) {
        // oldFlatIndex was an intro/outro bracket (panelIndexForFlatStop already special-cases
        // the single-full-page-fallback page, so reaching here means a real bracket, not that).
        // Both brackets are the identical PanelRect.FULL_PAGE rect, so which one it actually was
        // can only be told from the index itself: the intro is always flat index 0, the outro is
        // always the old list's last flat index.
        val oldTotalStops = (if (oldShowIntro) 1 else 0) +
            oldPanels.stopsPerPanel().sumOf { it.size } +
            (if (oldShowOutro) 1 else 0)
        val wasOutroBracket = oldShowOutro && oldFlatIndex == oldTotalStops - 1
        return if (wasOutroBracket) flattenToStops(newShowIntro, newShowOutro).lastIndex else 0
    }
    val oldStopsForOwner = oldPanels.stopsPerPanel()[ownerPanelIndex]
    val oldCursorStart = oldPanels.cursorStartForPanel(ownerPanelIndex, oldShowIntro)
    val oldAnchorRect = oldStopsForOwner[oldFlatIndex - oldCursorStart]

    val newStopsPerPanel = stopsPerPanel()
    val newStopsForOwner = newStopsPerPanel.getOrNull(ownerPanelIndex) ?: return 0
    val newCursorStart = cursorStartForPanel(ownerPanelIndex, newShowIntro)

    return if (newStopsForOwner.size > oldStopsForOwner.size) {
        newCursorStart
    } else {
        val nearestLocalIndex = newStopsForOwner.indices.minByOrNull { i ->
            val s = newStopsForOwner[i]
            val dx = s.centerX - oldAnchorRect.centerX
            val dy = s.centerY - oldAnchorRect.centerY
            dx * dx + dy * dy
        } ?: 0
        newCursorStart + nearestLocalIndex
    }
}
