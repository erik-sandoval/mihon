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
 * Maps a flat stop index (as produced by [flattenToStops] with the same [showIntro]/[showOutro])
 * back to the index of the panel in this list that owns it, or `null` if [flatIndex] is an
 * intro/outro full-page bracket stop, which belongs to no panel.
 */
fun List<Panel>.panelIndexForFlatStop(flatIndex: Int, showIntro: Boolean, showOutro: Boolean): Int? {
    val stopsPerPanel = map { it.subStops.ifEmpty { listOf(it.bounds) }.size }
    if (stopsPerPanel.size == 1 && this.single().bounds == PanelRect.FULL_PAGE && stopsPerPanel.single() == 1) {
        // flattenToStops' own no-duplicate-fallback rule: exactly one stop, no brackets at all.
        return if (flatIndex == 0) 0 else null
    }
    var cursor = if (showIntro) 1 else 0
    for ((panelIndex, count) in stopsPerPanel.withIndex()) {
        if (flatIndex in cursor until cursor + count) return panelIndex
        cursor += count
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
        ?: return 0
    val oldStopsForOwner = oldPanels[ownerPanelIndex].subStops.ifEmpty { listOf(oldPanels[ownerPanelIndex].bounds) }
    val oldAnchorRect = run {
        val cursorStart = (if (oldShowIntro) 1 else 0) +
            oldPanels.take(ownerPanelIndex).sumOf { it.subStops.ifEmpty { listOf(it.bounds) }.size }
        oldStopsForOwner[oldFlatIndex - cursorStart]
    }

    val newStopsPerPanel = map { it.subStops.ifEmpty { listOf(it.bounds) } }
    val newStopsForOwner = newStopsPerPanel.getOrNull(ownerPanelIndex) ?: return 0
    val newCursorStart = (if (newShowIntro) 1 else 0) +
        newStopsPerPanel.take(ownerPanelIndex).sumOf { it.size }

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
