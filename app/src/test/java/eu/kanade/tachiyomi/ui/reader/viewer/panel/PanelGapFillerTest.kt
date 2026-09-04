package eu.kanade.tachiyomi.ui.reader.viewer.panel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PanelGapFillerTest {

    @Test
    fun emptyInputIsUnchanged() {
        assertEquals(emptyList<PanelRect>(), PanelGapFiller.fill(emptyList()))
    }

    @Test
    fun bigRectangularLeftoverBecomesAPanel() {
        // One panel covers the top 40%; the bottom 60% is a missed panel.
        val top = PanelRect(0.0f, 0.0f, 1.0f, 0.40f)
        val result = PanelGapFiller.fill(listOf(top))
        assertEquals(2, result.size)
        val gap = result.last()
        assertTrue(gap.top in 0.35f..0.45f, "gap top=$gap")
        assertTrue(gap.bottom >= 0.95f, "gap bottom=$gap")
        assertTrue(gap.width >= 0.95f, "gap width=$gap")
    }

    @Test
    fun missedPanelSurroundedByMarginsIsStillRecovered() {
        // The realistic case: panels don't reach the page edges, so a margin frame surrounds and
        // connects every uncovered cell. A detected panel sits in the top ~45%; the bottom panel is
        // missed. The largest empty rectangle must isolate the missed bottom region despite margins.
        val detectedTop = PanelRect(0.04f, 0.04f, 0.96f, 0.46f)
        val result = PanelGapFiller.fill(listOf(detectedTop))
        assertEquals(2, result.size)
        val gap = result.last()
        assertTrue(gap.top in 0.40f..0.55f, "gap top=$gap")
        assertTrue(gap.bottom >= 0.95f, "gap bottom=$gap")
        assertTrue(gap.width >= 0.90f, "gap width=$gap")
    }

    @Test
    fun smallLeftoverBelowThresholdIsIgnored() {
        // Only a ~3% strip at the bottom is uncovered — below the area threshold.
        val p = PanelRect(0.0f, 0.0f, 1.0f, 0.97f)
        assertEquals(1, PanelGapFiller.fill(listOf(p)).size)
    }

    @Test
    fun thinMarginFrameIsIgnored() {
        // A centred panel with realistic ~4% margins: every leftover strip is too thin to be a panel.
        val center = PanelRect(0.04f, 0.04f, 0.96f, 0.96f)
        assertEquals(1, PanelGapFiller.fill(listOf(center)).size)
    }

    @Test
    fun fullHeightMarginStripClearsSideThresholdButIsRejectedAsASliver() {
        // Real-world regression: a page whose content clears the right edge at 87% leaves a
        // 13%-wide, full-page-height margin uncovered. 13% alone clears minSideFraction (12%), but
        // an 8:1-elongated sliver spanning the entire page height is unmistakably a trim margin, not
        // a missed panel.
        val panels = listOf(
            PanelRect(0.4162444f, 0.0f, 0.90796894f, 0.32570174f),
            PanelRect(0.0f, 0.0f, 0.46656448f, 0.32570174f),
            PanelRect(0.58781093f, 0.31645924f, 0.8975418f, 0.55915594f),
            PanelRect(0.02660666f, 0.3165883f, 0.65528f, 0.5602106f),
            PanelRect(0.44129044f, 0.56352943f, 0.90509766f, 0.72887135f),
            PanelRect(0.034463376f, 0.5642557f, 0.4762103f, 0.72862226f),
            PanelRect(0.44204548f, 0.73144364f, 0.9060294f, 0.9362229f),
            PanelRect(0.0f, 0.73144364f, 0.4922819f, 0.9362229f),
        )
        val result = PanelGapFiller.fill(panels)
        assertEquals(panels, result, "the right-margin sliver must not be added as a panel")
    }

    /** Solid tone [tone] inside [ink], white (250) elsewhere. */
    private class ToneField(override val width: Int, override val height: Int, val ink: PanelRect, val tone: Int) : LumaField {
        private fun at(x: Int, y: Int): Int {
            val nx = x.toFloat() / width
            val ny = y.toFloat() / height
            return if (nx in ink.left..ink.right && ny in ink.top..ink.bottom) tone else 250
        }
        override fun column(x: Int, y0: Int, y1: Int) = IntArray(y1 - y0) { at(x, y0 + it) }
        override fun row(y: Int, x0: Int, x1: Int) = IntArray(x1 - x0) { at(x0 + it, y) }
    }

    @Test
    fun wideShortUncoveredRegionWithInkIsRecoveredDespiteItsAspectRatio() {
        // Top ~78% is one panel; the bottom ~22% is a full-width panel the model missed. Its aspect
        // (≈4.3:1) would normally be rejected as a margin strip — but it's full of ink, so keep it.
        val top = PanelRect(0.03f, 0.02f, 0.97f, 0.78f)
        val bottomInk = PanelRect(0.0f, 0.80f, 1.0f, 1.0f)
        val luma = ToneField(400, 600, bottomInk, tone = 30) // dark dramatic panel

        val withLuma = PanelGapFiller.fill(listOf(top), luma = luma)
        assertEquals(2, withLuma.size, "the inky bottom strip should be recovered: $withLuma")

        // Same geometry, but the strip is blank paper → still rejected.
        val blank = ToneField(400, 600, bottomInk, tone = 250)
        assertEquals(1, PanelGapFiller.fill(listOf(top), luma = blank).size)
    }

    @Test
    fun missedPanelIsNumberedByThePipeline() {
        // Top panel + a big uncovered bottom: the pipeline should yield at least 2 regions in order.
        val top = PanelRect(0.0f, 0.0f, 1.0f, 0.35f)
        val regions = PanelPipeline.zoomRegions(listOf(top), emptyList(), 1000, 1500, rightToLeft = false)
        assertTrue(regions.size >= 2, "expected the gap to be added; got $regions")
        // Reading order: the top region comes before the filled bottom one.
        assertTrue(regions.first().top <= regions.last().top)
    }
}
