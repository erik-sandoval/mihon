package eu.kanade.tachiyomi.ui.reader.viewer.panel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ContentAwarePanelExpanderTest {

    /**
     * A [LumaField] painted from simple filled rectangles. Anything not covered by a rect is white
     * (255). Each rect can be a flat tone or a "busy" fill that alternates light/dark every pixel
     * (so it reads as line art, not a gutter).
     */
    private class TestField(override val width: Int, override val height: Int) : LumaField {
        private val px = IntArray(width * height) { 255 }
        fun fill(l: Int, t: Int, r: Int, b: Int, tone: Int) {
            for (y in t until b) for (x in l until r) px[y * width + x] = tone
        }
        /** Fill with a per-pixel checker so it scores as content (high edge density), not a gutter. */
        fun fillBusy(l: Int, t: Int, r: Int, b: Int) {
            for (y in t until b) for (x in l until r) px[y * width + x] = if ((x + y) % 2 == 0) 20 else 235
        }
        override fun column(x: Int, y0: Int, y1: Int) = IntArray(y1 - y0) { px[(y0 + it) * width + x] }
        override fun row(y: Int, x0: Int, x1: Int) = IntArray(x1 - x0) { px[y * width + (x0 + it)] }
    }

    private fun rect(l: Float, t: Float, r: Float, b: Float) = PanelRect(l, t, r, b)

    @Test
    fun bottomEdgeCuttingThroughArtGrowsDownToTheGutter() {
        // Real art fills y=40..120; the detected box stops at y=90, slicing it. A white gutter runs
        // y=120..135, then the next panel's art. Bottom should snap to ~y=120 (0.60).
        val field = TestField(200, 200).apply {
            fillBusy(40, 40, 160, 120)
            fillBusy(40, 135, 160, 195)
        }
        val panel = rect(0.2f, 0.2f, 0.8f, 0.45f) // bottom at y=90, mid-art

        val e = ContentAwarePanelExpander.expand(listOf(panel), field).single()

        assertTrue(e.bottom in 0.58f..0.64f, "bottom should reach the gutter (~0.60): $e")
        assertTrue(kotlin.math.abs(e.top - panel.top) < 0.03f, "top should not move: $e")
    }

    @Test
    fun panelAlreadyFramedByWhiteGuttersIsUnchanged() {
        // 200x200 page, one panel of busy art at 20%..80% with clean white all around it.
        val field = TestField(200, 200).apply { fillBusy(40, 40, 160, 160) }
        val panel = rect(0.2f, 0.2f, 0.8f, 0.8f)

        val result = ContentAwarePanelExpander.expand(listOf(panel), field)

        assertEquals(1, result.size)
        val e = result.single()
        assertTrue(kotlin.math.abs(e.left - panel.left) < 0.02f, "left moved: $e")
        assertTrue(kotlin.math.abs(e.top - panel.top) < 0.02f, "top moved: $e")
        assertTrue(kotlin.math.abs(e.right - panel.right) < 0.02f, "right moved: $e")
        assertTrue(kotlin.math.abs(e.bottom - panel.bottom) < 0.02f, "bottom moved: $e")
    }

    @Test
    fun borderlessArtWithNoGutterIsLeftAlone() {
        // Busy art fills the entire page — no gutter anywhere. A tight detected box must not balloon.
        val field = TestField(200, 200).apply { fillBusy(0, 0, 200, 200) }
        val panel = rect(0.3f, 0.3f, 0.7f, 0.7f)

        val e = ContentAwarePanelExpander.expand(listOf(panel), field).single()

        assertTrue(kotlin.math.abs(e.left - 0.3f) < 0.02f && kotlin.math.abs(e.right - 0.7f) < 0.02f, "x moved: $e")
        assertTrue(kotlin.math.abs(e.top - 0.3f) < 0.02f && kotlin.math.abs(e.bottom - 0.7f) < 0.02f, "y moved: $e")
    }

    @Test
    fun stopsAtASolidBlackGutterNotJustWhite() {
        // One Piece style: panels separated by a solid black bar, not white paper.
        val field = TestField(200, 200).apply {
            fillBusy(20, 20, 180, 90)
            fill(20, 90, 180, 105, tone = 8) // black gutter
            fillBusy(20, 105, 180, 180)
        }
        val panel = rect(0.1f, 0.1f, 0.9f, 0.35f) // bottom at y=70, cutting the top panel's art

        val e = ContentAwarePanelExpander.expand(listOf(panel), field).single()

        assertTrue(e.bottom in 0.42f..0.48f, "bottom should stop at the black gutter (~0.45): $e")
    }

    @Test
    fun doesNotCrossIntoAnOverlappingNeighbour() {
        // Two side-by-side panels with a thin gutter between them, whose boxes overlap slightly
        // (normal ML jitter). The left panel's right edge must stop at the gutter, not scan on.
        val field = TestField(200, 200).apply {
            fillBusy(10, 40, 100, 160)
            fillBusy(106, 40, 195, 160)
        }
        val left = rect(0.05f, 0.2f, 0.52f, 0.8f)
        val right = rect(0.48f, 0.2f, 0.97f, 0.8f)

        val result = ContentAwarePanelExpander.expand(listOf(left, right), field)

        assertEquals(2, result.size, "a real gutter must keep the two panels separate: $result")
        assertTrue(result.first().right <= 0.56f, "left panel's right edge ran past the gutter: ${result.first()}")
    }

    @Test
    fun midGreyScreentoneIsNotMistakenForAGutter() {
        // A flat mid-grey screentone band between two busy regions must NOT stop the scan (it's a
        // background tone, not a between-panel gutter) — so with no real white/black gutter the edge
        // stays put rather than snapping onto the screentone.
        val field = TestField(200, 200).apply {
            fillBusy(20, 20, 180, 100)
            fill(20, 100, 180, 150, tone = 150) // mid-grey screentone, wide
            fillBusy(20, 150, 180, 190)
        }
        val panel = rect(0.1f, 0.1f, 0.9f, 0.35f) // bottom at y=70, cutting the top art

        val e = ContentAwarePanelExpander.expand(listOf(panel), field).single()

        // If screentone were taken as a gutter the bottom would snap to ~0.49 (y~98); it must not.
        assertTrue(e.bottom < 0.44f, "bottom snapped onto screentone as if it were a gutter: $e")
    }

    @Test
    fun argbLumaFieldConvertsPixelsToRec601LumaAndSlicesRowsAndColumns() {
        // 3x2 ARGB image: row 0 = pure red, green, blue; row 1 = black, white, mid-grey.
        val argb = intArrayOf(
            0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(),
            0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF808080.toInt(),
        )
        val field = ArgbLumaField(argb, width = 3, height = 2)

        // Rec.601-ish integer luma: R*77 + G*150 + B*29 >> 8
        assertEquals(intArrayOf(76, 149, 28).toList(), field.row(0, 0, 3).toList())
        assertEquals(intArrayOf(0, 255, 128).toList(), field.row(1, 0, 3).toList())
        assertEquals(intArrayOf(149, 255).toList(), field.column(1, 0, 2).toList())
    }

    @Test
    fun fullWidthPanelAbsorbsAFullWidthCaptionBarBelowIt() {
        // panel art y=20..80, a 3px gutter, a caption bar (busy, same width) y=83..100, then white.
        // The bar is the model's class-1 blind spot — the panel should grow through it.
        val field = TestField(200, 200).apply {
            fillBusy(20, 20, 180, 80)
            fillBusy(20, 83, 180, 100)
        }
        val panel = rect(0.10f, 0.10f, 0.90f, 0.40f) // bottom at y=80

        val e = ContentAwarePanelExpander.expand(listOf(panel), field).single()

        assertTrue(e.bottom in 0.49f..0.55f, "bottom should absorb the caption bar (~0.50): $e")
    }

    @Test
    fun aPanelDoesNotAbsorbACaptionBarWiderThanItself() {
        // The caption bar spans the whole page; the panel is only the left third. The bar belongs
        // to a wider panel, not this one — this panel must stop at its own gutter.
        val field = TestField(200, 200).apply {
            fillBusy(20, 20, 78, 80)
            fillBusy(20, 83, 180, 100) // full-width caption
        }
        val panel = rect(0.10f, 0.10f, 0.39f, 0.40f) // narrow, bottom at y=80

        val e = ContentAwarePanelExpander.expand(listOf(panel), field).single()

        assertTrue(e.bottom in 0.38f..0.44f, "narrow panel should not absorb the wide caption: $e")
    }

    @Test
    fun aNearDuplicateOverDetectionIsMergedIntoOnePanel() {
        // The model boxed one panel twice with a big offset — the boxes overlap heavily, continuous
        // art (no gutter) across the overlap. NMS's IoU/containment thresholds let both through.
        val field = TestField(200, 200).apply { fillBusy(20, 20, 180, 170) }
        val a = rect(0.10f, 0.10f, 0.90f, 0.60f)
        val b = rect(0.12f, 0.20f, 0.88f, 0.70f)

        val result = ContentAwarePanelExpander.expand(listOf(a, b), field)

        assertEquals(1, result.size, "the duplicate should merge: $result")
        val m = result.single()
        assertTrue(m.top <= 0.12f && m.bottom >= 0.68f, "merged box should span both: $m")
    }

    @Test
    fun aThinStripClippedOffAPanelsEdgeAcrossAHairlineGapIsMergedBack() {
        // The model boxed a busy edge strip (a curtain) as its own panel, 1px of gap from the main
        // box, with continuous art through the gap.
        val field = TestField(200, 200).apply { fillBusy(20, 40, 190, 160) }
        val main = rect(0.10f, 0.20f, 0.86f, 0.80f)
        val strip = rect(0.87f, 0.21f, 0.95f, 0.79f)

        val result = ContentAwarePanelExpander.expand(listOf(main, strip), field)

        assertEquals(1, result.size, "the clipped strip should merge back: $result")
    }

    @Test
    fun twoFullAdjacentPanelsWithAHairlineGapAndNoStripAreNotMerged() {
        // Same hairline gap, but both boxes are full panels (neither is a thin strip) — keep them.
        val field = TestField(200, 200).apply { fillBusy(20, 40, 180, 160) }
        val a = rect(0.10f, 0.20f, 0.49f, 0.80f)
        val b = rect(0.51f, 0.20f, 0.90f, 0.80f)

        assertEquals(2, ContentAwarePanelExpander.expand(listOf(a, b), field).size)
    }

    @Test
    fun fragmentsAreNotMergedWhenTheUnionWouldSwallowAThirdPanel() {
        // A busy diagonal-layout page: no white gutters anywhere, but there IS panel structure.
        // Merging the two outer boxes would engulf the panel sitting between them, so don't.
        val field = TestField(200, 200).apply { fillBusy(0, 0, 200, 200) }
        val a = rect(0.05f, 0.10f, 0.70f, 0.90f)
        val b = rect(0.10f, 0.15f, 0.75f, 0.88f) // near-duplicate of a
        val inset = rect(0.30f, 0.40f, 0.50f, 0.60f) // a real panel inside their union

        val result = ContentAwarePanelExpander.expand(listOf(a, b, inset), field)

        assertEquals(3, result.size, "merging the duplicates would swallow the inset: $result")
    }

    @Test
    fun twoBoxesWithARealGutterBetweenThemStaySeparate() {
        // Same shapes, but a white gutter row runs through the overlap — a real panel boundary.
        val field = TestField(200, 200).apply {
            fillBusy(20, 20, 180, 98)
            fillBusy(20, 104, 180, 170)
        }
        val upper = rect(0.10f, 0.10f, 0.90f, 0.52f)
        val lower = rect(0.12f, 0.48f, 0.88f, 0.85f)

        val result = ContentAwarePanelExpander.expand(listOf(upper, lower), field)

        assertEquals(2, result.size, "a real gutter must keep them separate: $result")
    }

    @Test
    fun emptyAndSingleInputsPassThrough() {
        val field = TestField(100, 100)
        assertEquals(emptyList<PanelRect>(), ContentAwarePanelExpander.expand(emptyList(), field))
        val one = rect(0.2f, 0.2f, 0.8f, 0.8f)
        assertEquals(listOf(one), ContentAwarePanelExpander.expand(listOf(one), field))
    }
}
