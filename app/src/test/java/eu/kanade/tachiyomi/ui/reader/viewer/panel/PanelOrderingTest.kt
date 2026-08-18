package eu.kanade.tachiyomi.ui.reader.viewer.panel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PanelOrderingTest {

    private val topLeft = PanelRect(0.0f, 0.0f, 0.45f, 0.4f)
    private val topRight = PanelRect(0.55f, 0.0f, 1.0f, 0.4f)
    private val bottomLeft = PanelRect(0.0f, 0.5f, 0.45f, 1.0f)
    private val bottomRight = PanelRect(0.55f, 0.5f, 1.0f, 1.0f)

    @Test
    fun ordersRowsTopDownThenLeftToRight() {
        val shuffled = listOf(bottomRight, topRight, bottomLeft, topLeft)
        val ordered = PanelOrdering.order(shuffled)
        assertEquals(listOf(topLeft, topRight, bottomLeft, bottomRight), ordered)
    }

    @Test
    fun rightToLeftReversesWithinRowsOnly() {
        val shuffled = listOf(bottomRight, topRight, bottomLeft, topLeft)
        val ordered = PanelOrdering.order(shuffled, rightToLeft = true)
        assertEquals(listOf(topRight, topLeft, bottomRight, bottomLeft), ordered)
    }

    @Test
    fun slightlyMisalignedPanelsStillShareARow() {
        // Vertical ranges overlap well over half of the shorter panel's height.
        val left = PanelRect(0.0f, 0.10f, 0.45f, 0.45f)
        val right = PanelRect(0.55f, 0.05f, 1.0f, 0.40f)
        val ordered = PanelOrdering.order(listOf(right, left))
        assertEquals(listOf(left, right), ordered)
    }

    @Test
    fun barelyOverlappingPanelsFormSeparateRows() {
        // Overlap is far below half of the shorter panel's height → two rows, top first.
        val upper = PanelRect(0.5f, 0.0f, 1.0f, 0.32f)
        val lower = PanelRect(0.0f, 0.3f, 0.45f, 0.7f)
        val ordered = PanelOrdering.order(listOf(lower, upper))
        assertEquals(listOf(upper, lower), ordered)
    }

    @Test
    fun staggeredBottomRowUnderAnOverlappingPanelReadsLeftToRight() {
        // A huge panel overlaps everything (no clean cut), with a bottom row whose two panels are
        // vertically staggered. The bottom row must read left→right, not "higher-first".
        val huge = PanelRect(0.0f, 0.01f, 1.0f, 0.84f)
        val bottomRightHigher = PanelRect(0.48f, 0.77f, 0.89f, 0.98f) // starts higher
        val bottomLeftLower = PanelRect(0.0f, 0.84f, 0.48f, 1.0f) // starts lower, but is on the left
        val ordered = PanelOrdering.order(listOf(bottomRightHigher, huge, bottomLeftLower))
        assertEquals(listOf(huge, bottomLeftLower, bottomRightHigher), ordered)
    }

    @Test
    fun emptyAndSingleListsPassThrough() {
        assertEquals(emptyList<PanelRect>(), PanelOrdering.order(emptyList()))
        assertEquals(listOf(topLeft), PanelOrdering.order(listOf(topLeft)))
    }

    @Test
    fun spreadFinishesTheRightPageBeforeTheLeftPageOnManga() {
        // Regression: a right-page top-row panel and an unrelated left-page top panel can end up
        // with overlapping Y-ranges purely by coincidence (different pages, different artists'
        // layouts). Without spread-awareness, the general row-based cut treats them as one row and
        // interleaves the pages — right page top row, then the left page's top panel, THEN back to
        // the right page's own bottom panel. isSpread=true must keep each page's panels together.
        val rightTop1 = PanelRect(0.85f, 0.0f, 0.98f, 0.3f) // rightmost
        val rightTop2 = PanelRect(0.65f, 0.0f, 0.85f, 0.3f)
        val rightTop3 = PanelRect(0.55f, 0.0f, 0.65f, 0.3f)
        val rightBottom = PanelRect(0.5f, 0.3f, 1.0f, 1.0f)
        val leftTop = PanelRect(0.05f, 0.0f, 0.45f, 0.28f) // aligns in Y with the right-page top row
        val leftBottom = PanelRect(0.05f, 0.3f, 0.45f, 1.0f)

        val shuffled = listOf(leftBottom, rightBottom, leftTop, rightTop2, rightTop1, rightTop3)
        val ordered = PanelOrdering.order(shuffled, rightToLeft = true, isSpread = true)

        assertEquals(
            listOf(rightTop1, rightTop2, rightTop3, rightBottom, leftTop, leftBottom),
            ordered,
        )
    }

    @Test
    fun spreadFinishesTheLeftPageBeforeTheRightPageOnLtr() {
        val leftTop = PanelRect(0.0f, 0.0f, 0.2f, 0.3f)
        val leftBottom = PanelRect(0.0f, 0.3f, 0.45f, 1.0f)
        val rightTop = PanelRect(0.55f, 0.0f, 0.95f, 0.28f)
        val rightBottom = PanelRect(0.5f, 0.3f, 1.0f, 1.0f)

        val ordered = PanelOrdering.order(
            listOf(rightBottom, leftBottom, rightTop, leftTop),
            rightToLeft = false,
            isSpread = true,
        )

        assertEquals(listOf(leftTop, leftBottom, rightTop, rightBottom), ordered)
    }

    @Test
    fun spreadWithEverythingOnOneHalfFallsBackToTheNormalCut() {
        // A false-positive "spread" (wide image, but every detected panel happens to sit on one
        // half — e.g. the other half is a blank/textless page) must not collapse to nothing: the
        // left/right split is skipped and the general cut runs on the full list instead.
        val upperRight = PanelRect(0.55f, 0.0f, 1.0f, 0.4f)
        val lowerRight = PanelRect(0.55f, 0.5f, 1.0f, 1.0f)
        val ordered = PanelOrdering.order(listOf(lowerRight, upperRight), isSpread = true)
        assertEquals(listOf(upperRight, lowerRight), ordered)
    }

    @Test
    fun spreadWithUnevenPageWidthsFindsTheRealSeamNotTheMidpoint() {
        // Real coordinates captured from an actual manga spread (One Piece ch.950 p.6), verified
        // against the correct reading order by hand. The right page occupies only the ~25% of the
        // image on the right — nowhere near the image's 50% midpoint — so a bottom-row panel from
        // the (much wider) left page pokes past x=0.5 and would be wrongly grouped with the right
        // page's column under a fixed-midpoint split, landing it several stops too early.
        val rightA = PanelRect(0.74602914f, 0.0026333786f, 0.9993285f, 0.2612385f)
        val rightB = PanelRect(0.7465892f, 0.29417488f, 0.9993948f, 0.529441f)
        val rightC = PanelRect(0.74649024f, 0.5621845f, 0.99953985f, 0.75447744f)
        val rightD = PanelRect(0.7467271f, 0.78508025f, 1.0f, 0.9971414f)
        val leftBottomRight = PanelRect(0.5197791f, 0.66752493f, 0.70967954f, 0.9976027f) // pokes past x=0.5
        val leftTopHuge = PanelRect(0.0021447837f, 0.002055191f, 0.7112302f, 0.63770956f)
        val leftBottomMid = PanelRect(0.36590105f, 0.66541916f, 0.4772229f, 0.99686694f)
        val leftBottomMid2 = PanelRect(0.23992243f, 0.6665898f, 0.35810277f, 0.99659365f)
        val leftBottomLeft = PanelRect(1.1920929E-7f, 0.66795236f, 0.23020627f, 0.99622935f)

        val shuffled = listOf(
            leftBottomRight, rightD, leftBottomLeft, rightB, leftTopHuge, rightA, leftBottomMid2, rightC, leftBottomMid,
        )
        val ordered = PanelOrdering.order(shuffled, rightToLeft = true, isSpread = true)

        assertEquals(
            listOf(rightA, rightB, rightC, rightD, leftTopHuge, leftBottomRight, leftBottomMid, leftBottomMid2, leftBottomLeft),
            ordered,
        )
    }

    @Test
    fun spreadPanelPokingPastTheTruePageBoundaryStaysWithItsOwnPage() {
        // Same root cause as above, from a different spread (One Piece ch.951 p.13): a right-page
        // panel (width ~0.24) sits entirely past x=0.5 while the left page's dominant top panel
        // spans most of the image width and one of its own row-mates pokes to x=0.729 — well past
        // the naive midpoint. The true seam is the actual gap between the two pages' content, not 0.5.
        val rightTop = PanelRect(0.76543844f, 0.002778343f, 0.9999614f, 0.45556042f)
        val rightMid = PanelRect(0.76557213f, 0.4889498f, 0.99920255f, 0.6818085f)
        val rightBottom = PanelRect(0.7911114f, 0.7115339f, 0.9995846f, 0.99766755f)
        val leftBottomRight = PanelRect(0.52091724f, 0.674393f, 0.72929645f, 0.9968116f) // pokes past x=0.5
        val leftTopHuge = PanelRect(0.0f, 0.004455725f, 0.72205716f, 0.6380228f)
        val leftBottomMid = PanelRect(0.28125978f, 0.6753949f, 0.4758771f, 0.9967237f)
        val leftBottomLeft = PanelRect(0.036744297f, 0.67470723f, 0.27247894f, 0.99759775f)

        val shuffled = listOf(leftBottomRight, rightBottom, leftBottomLeft, rightMid, leftTopHuge, rightTop, leftBottomMid)
        val ordered = PanelOrdering.order(shuffled, rightToLeft = true, isSpread = true)

        assertEquals(
            listOf(rightTop, rightMid, rightBottom, leftTopHuge, leftBottomRight, leftBottomMid, leftBottomLeft),
            ordered,
        )
    }
}
