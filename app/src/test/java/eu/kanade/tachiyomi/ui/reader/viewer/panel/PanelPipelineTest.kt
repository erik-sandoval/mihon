package eu.kanade.tachiyomi.ui.reader.viewer.panel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PanelPipelineTest {

    @Test
    fun hugePanelNextToNarrowSiblingDoesNotSwallowIt() {
        // Regression from a real on-device page: BASE_MARGIN scales with a panel's own size, so a
        // panel spanning most of the page got a margin bigger than its narrow neighbor's entire
        // width, engulfing most of it. Capping each side's margin at half the shared gap keeps both
        // sides honest regardless of the size mismatch between them.
        // Width kept under PanelPlanner's fullWidthFraction (0.85) so the huge panel isn't itself
        // divided — this test is isolating the padding/capping behavior, not the planner's.
        val huge = PanelRect(0.0f, 0.0f, 0.80f, 1.0f)
        val narrow = PanelRect(0.82f, 0.0f, 1.0f, 1.0f)
        val regions = PanelPipeline.zoomRegions(listOf(huge, narrow), emptyList(), 1000, 1000, false)
        val hugeRegion = regions.first { it.width > 0.5f }
        val narrowRegion = regions.first { it.width < 0.5f }
        assertTrue(
            hugeRegion.right <= narrowRegion.left + 1e-4f,
            "huge panel's margin should not cross into the narrow one: $hugeRegion vs $narrowRegion",
        )
    }

    @Test
    fun outermostPanelExtendsToTheUnclaimedPageEdge() {
        // Regression from a real on-device page: a narrow panel's own margin (5.7% of its own
        // ~8%-wide size) doesn't reach the page edge, leaving a slice of real art outside every
        // panel's bounds — content that would never be shown during panel-by-panel navigation
        // since nothing else claims it either. The narrow panel is the leftmost thing in its row
        // (nothing else shares its row further left), so it should absorb that margin instead of
        // leaving it an orphaned, unreachable strip.
        val narrow = PanelRect(0.02f, 0.0f, 0.10f, 0.4f)
        val wide = PanelRect(0.20f, 0.0f, 1.0f, 0.4f)
        val bottom = PanelRect(0.0f, 0.5f, 1.0f, 1.0f)
        val regions = PanelPipeline.zoomRegions(listOf(narrow, wide, bottom), emptyList(), 1000, 1000, false)
        val leftmost = regions.first { it.left < 0.15f && it.bottom < 0.5f }
        assertEquals(0f, leftmost.left, "nothing else covers the strip left of the narrow panel")
    }

    @Test
    fun largeBlankMarginIsNotFullyClaimedByExtension() {
        // A big empty border shouldn't drag a panel all the way to the page edge chasing it — only a
        // bounded amount gets absorbed, on the assumption a very wide gap is more likely decorative
        // whitespace than art the model missed.
        val panel = PanelRect(0.35f, 0.0f, 1.0f, 0.5f)
        val other = PanelRect(0.0f, 0.6f, 1.0f, 1.0f)
        val regions = PanelPipeline.zoomRegions(listOf(panel, other), emptyList(), 1000, 1000, false)
        val panelRegion = regions.first { it.left > 0.1f }
        assertTrue(panelRegion.left > 0.15f, "should not extend all the way to the page edge: $panelRegion")
    }

    @Test
    fun panelWithAnotherPanelToItsLeftDoesNotExtendToTheEdge() {
        // The flip side of the above: when another panel genuinely already covers the region closer
        // to the edge, extending would incorrectly claim that panel's own territory.
        val left = PanelRect(0.0f, 0.0f, 0.30f, 0.4f)
        val right = PanelRect(0.40f, 0.0f, 1.0f, 0.4f)
        val bottom = PanelRect(0.0f, 0.5f, 1.0f, 1.0f)
        val regions = PanelPipeline.zoomRegions(listOf(left, right, bottom), emptyList(), 1000, 1000, false)
        val rightRegion = regions.first { it.left > 0.15f && it.bottom < 0.5f }
        assertTrue(rightRegion.left > 0.30f, "should stay short of the left panel's own territory: $rightRegion")
    }

    @Test
    fun pageWithNoDetectedPanelsIsShownWhole() {
        // When the model returns nothing, there's no real panel geometry to zoom into — show the
        // whole page as a single stop rather than guessing at a geometric split.
        val regions = PanelPipeline.zoomRegions(emptyList(), emptyList(), 1000, 1500, rightToLeft = false)
        assertEquals(listOf(PanelRect.FULL_PAGE), regions)
    }

    @Test
    fun mangaPageWithOnlyOneWeakDetectionIsShownWhole() {
        // A single region covering most of the page (e.g. a background too noisy for the model to
        // resolve the real panel boundaries) isn't real panel geometry either — same as the
        // no-detection case, fall back to the whole page rather than a geometric split.
        val weakDetection = listOf(PanelRect(0.07f, 0.0f, 1.0f, 0.86f))
        val regions = PanelPipeline.zoomRegions(weakDetection, emptyList(), 1000, 1500, rightToLeft = true)
        assertEquals(listOf(PanelRect.FULL_PAGE), regions)
    }

    @Test
    fun mangaPageWithTwoConfidentPanelsIsNotDivided() {
        // The actual complaint this profile exists to fix: two real, separately-detected panels
        // must each stay exactly one stop, not get split further.
        val panels = listOf(PanelRect(0.0f, 0.0f, 1.0f, 0.45f), PanelRect(0.0f, 0.5f, 1.0f, 1.0f))
        val regions = PanelPipeline.zoomRegions(panels, emptyList(), 1000, 1500, rightToLeft = true)
        assertEquals(2, regions.size)
    }

    @Test
    fun ltrAndRtlUseTheSameMergeDivideProfile() {
        // Panel-by-panel navigation should feel identical regardless of reading direction: a
        // full-width, broad panel that the old grid-friendly LTR profile would have quartered
        // must stay exactly one stop, same as it already does for RTL.
        val panels = listOf(PanelRect(0.0f, 0.0f, 1.0f, 0.6f), PanelRect(0.0f, 0.65f, 1.0f, 1.0f))
        val ltrRegions = PanelPipeline.zoomRegions(panels, emptyList(), 1000, 1500, rightToLeft = false)
        val rtlRegions = PanelPipeline.zoomRegions(panels, emptyList(), 1000, 1500, rightToLeft = true)
        assertEquals(2, ltrRegions.size)
        assertEquals(rtlRegions.size, ltrRegions.size)
    }

    @Test
    fun panelWithNoOverflowingBubbleOnlyGetsTheBaseMargin() {
        // Both panels are deliberately >10% area so neither is a merge candidate, keeping this a
        // pure padding test rather than exercising the planner's merge/divide behavior too. `other`
        // shares the panel's row and sits to its right, so extendToPageEdges doesn't pull that edge
        // all the way to the page boundary — isolating pad()'s own base-margin growth to measure.
        val panel = PanelRect(0.10f, 0.10f, 0.45f, 0.45f)
        val bubble = PanelRect(0.20f, 0.20f, 0.30f, 0.30f) // fully inside, nowhere near an edge
        val other = PanelRect(0.50f, 0.10f, 0.90f, 0.45f)
        val region = PanelPipeline.zoomRegions(listOf(panel, other), listOf(bubble), 1000, 1000, false)
            .first { it.left < 0.15f }
        // Base margin only: 5.7% of the panel's own 0.35-wide size ≈ 0.02, well short of the bubble.
        assertTrue(region.right in 0.465f..0.475f, "expected only the base margin, got $region")
    }

    @Test
    fun panelWithAnOverflowingBubbleGrowsToContainIt() {
        val panel = PanelRect(0.30f, 0.30f, 0.65f, 0.65f)
        // Centred inside the panel (so it's attributed to it) but bleeding past its left edge.
        val bubble = PanelRect(0.22f, 0.40f, 0.42f, 0.55f)
        val other = PanelRect(0.02f, 0.70f, 0.37f, 1.0f)
        val region = PanelPipeline.zoomRegions(listOf(panel, other), listOf(bubble), 1000, 1000, false)
            .first { it.left < 0.2f }
        assertTrue(region.left < bubble.left, "expected the panel to grow past the bubble's left edge: $region")
    }

    @Test
    fun bubbleStraddlingGutterBetweenTwoPanelsIsShownWholeByBoth() {
        // Regression from a real on-device page (Official Bleach ch.17 p16): a "COME IN FOR A
        // MINUTE, OKAY?" bubble sits in the narrow gutter between two panels, its centre landing
        // in neither panel's raw box (cx=0.4582, between the left panel's raw right edge 0.4498
        // and the right panel's raw left edge 0.4610). Before the fix, neither panel claimed it,
        // so the shared cut line ran straight through the bubble instead of either panel showing
        // it whole.
        val rightPanel = PanelRect(0.46095777f, 0.093161315f, 0.9081674f, 0.30152285f)
        val leftPanel = PanelRect(0.07759259f, 0.09368136f, 0.44976175f, 0.30146083f)
        val bubble = PanelRect(0.41516086f, 0.1693387f, 0.5012333f, 0.25637332f)
        val regions = PanelPipeline.zoomRegions(
            listOf(rightPanel, leftPanel), listOf(bubble), 1000, 1000, rightToLeft = true,
        )
        val right = regions.first { it.left > 0.3f }
        val left = regions.first { it.left < 0.3f }
        assertTrue(right.left <= bubble.left, "right panel should grow to include the full bubble: $right")
        assertTrue(left.right >= bubble.right, "left panel should grow to include the full bubble: $left")
    }

    @Test
    fun interiorGapBetweenTwoAdjacentPanelsIsClosedNotOrphaned() {
        // Regression from a real on-device page (Official One Piece ch.944 p15): two panels'
        // raw detections sit 7% of the page apart. Each one's own margin only grows inward by its
        // BASE_MARGIN-derived proportional amount (smaller than half the raw gap here, so the
        // "split the gutter evenly" cap in cappedMargin never actually kicks in), leaving a ~2.1%
        // strip in the middle that neither panel's padded box reaches — real page content that
        // was never shown by any panel-by-panel stop at all.
        val left = PanelRect(0.0f, 0.7642173f, 0.44449624f, 0.9990628f)
        val right = PanelRect(0.51489437f, 0.76588583f, 0.9351317f, 1.0f)
        val regions = PanelPipeline.zoomRegions(listOf(left, right), emptyList(), 1000, 1000, rightToLeft = true)
        val leftRegion = regions.first { it.left < 0.3f }
        val rightRegion = regions.first { it.left > 0.3f }
        assertTrue(
            leftRegion.right >= rightRegion.left - 1e-4f,
            "no unclaimed strip should remain between the two panels: left=$leftRegion right=$rightRegion",
        )
    }

    @Test
    fun closeInteriorGapsDoesNotReachPastAnAlreadyNearbyNeighborToADistantOne() {
        // Regression from a real on-device page (Official Vinland Saga ch.1 p68): the top panel's
        // raw bottom (~0.355) is already just past the top edge of its true immediate neighbors
        // (~0.31-0.33) — a few percent of overlap from normal ML jitter, not a real gap. The old
        // closeInteriorGaps only considered neighbors starting at or after the panel's OWN padded
        // bottom, so both true neighbors got filtered out (their tops were already before that
        // bottom) and the search fell through to a much farther panel (top ~0.693), extending the
        // top panel's crop all the way down to ~0.527 — swallowing the entire middle-tier panel's
        // territory. The fix picks the nearest neighbor by position first, then only extends into a
        // genuine remaining gap (or clamps to the neighbor if there isn't one).
        val topPanel = PanelRect(0.0021610695f, 9.467304E-4f, 0.896554f, 0.35508305f)
        val rightSpear = PanelRect(0.55014193f, 0.31393322f, 0.8924058f, 1.0f)
        val leftMiddle = PanelRect(0.0f, 0.33309758f, 0.5806764f, 0.6980338f)
        val bottomLeft = PanelRect(0.108317524f, 0.69298774f, 0.6014403f, 0.93332916f)
        val bubbles = listOf(
            PanelRect(0.4264938f, 0.8147194f, 0.59177035f, 0.9448942f),
            PanelRect(0.093413554f, 0.716229f, 0.19401278f, 0.771475f),
            PanelRect(0.10597799f, 0.8932628f, 0.19600494f, 0.93414724f),
            PanelRect(0.69368064f, 0.09151158f, 0.85812813f, 0.16459227f),
        )

        val regions = PanelPipeline.zoomRegions(
            listOf(rightSpear, topPanel, leftMiddle, bottomLeft),
            bubbles,
            569,
            800,
            rightToLeft = true,
        )
        val topRegion = regions.first { it.top < 0.1f }

        assertTrue(
            topRegion.bottom < 0.45f,
            "top panel's crop should stay near its true immediate neighbors (~0.31-0.33), " +
                "not reach past them to the distant bottom-left panel (top ~0.693): $topRegion",
        )
    }

    @Test
    fun pipelineMatchesOrderingThenPlanning() {
        val shuffled = listOf(
            PanelRect(0.55f, 0.5f, 1.0f, 1.0f),
            PanelRect(0.0f, 0.0f, 0.45f, 0.4f),
            PanelRect(0.0f, 0.5f, 0.45f, 1.0f),
            PanelRect(0.55f, 0.0f, 1.0f, 0.4f),
        )
        val bubbles = listOf(PanelRect(0.1f, 0.1f, 0.2f, 0.2f))

        val viaPipeline = PanelPipeline.zoomRegions(shuffled, bubbles, 1000, 1500, rightToLeft = false)
        val manual = PanelPlanner.plan(
            PanelOrdering.order(shuffled, rightToLeft = false),
            bubbles, 1000, 1500, rightToLeft = false, config = PanelPlanner.Config.MANGA,
        )
        // The pipeline is ordering → planning → a small outward pad (breathing room for overflowing
        // text). So it must keep the same count and order, and each region must CONTAIN its planned
        // counterpart (padding only grows, clamped to the page) — never shrink or reorder.
        assertEquals(manual.size, viaPipeline.size, "pipeline should preserve the planned region count")
        for (i in manual.indices) {
            val m = manual[i]
            val v = viaPipeline[i]
            assertTrue(
                v.left <= m.left + 1e-4f && v.top <= m.top + 1e-4f &&
                    v.right >= m.right - 1e-4f && v.bottom >= m.bottom - 1e-4f,
                "pipeline region $i should be a padded superset of the planned region: $v vs $m",
            )
        }
    }
}
