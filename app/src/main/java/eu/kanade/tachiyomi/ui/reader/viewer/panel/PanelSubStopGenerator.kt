package eu.kanade.tachiyomi.ui.reader.viewer.panel

import android.graphics.Bitmap

interface PanelSubStopGenerator {
    /**
     * Returns ordered sub-stops for [panel] given the current [viewWidth]/[viewHeight], or an
     * empty list if it doesn't need any (the panel itself is the only stop). When non-empty,
     * the last stop is always the full [panel] bounds. [cropPanel] lazily crops the panel out
     * of the full-resolution page bitmap, for a generator that needs to inspect panel content
     * (e.g. OCR) — unused by a generator that only needs already-known geometry.
     */
    suspend fun generate(
        panel: Panel,
        direction: PanelDirection,
        viewWidth: Int,
        viewHeight: Int,
        cropPanel: suspend () -> Bitmap?,
    ): List<PanelRect>
}
