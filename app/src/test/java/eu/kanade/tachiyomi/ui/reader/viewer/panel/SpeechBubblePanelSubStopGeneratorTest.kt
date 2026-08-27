package eu.kanade.tachiyomi.ui.reader.viewer.panel

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpeechBubblePanelSubStopGeneratorTest {

    @Test
    fun `never generates sub-stops for the full-page fallback, regardless of bubbles or view size`() = runTest {
        val bubble = PanelRect(0.1f, 0.1f, 0.2f, 0.2f)
        val fallback = Panel(bounds = PanelRect.FULL_PAGE, bubbles = listOf(bubble))

        // A view shape that would otherwise clearly trigger expansion (very tall, narrow panel
        // vs. a normal portrait viewport) — must still return empty for FULL_PAGE.
        val stops = SpeechBubblePanelSubStopGenerator.generate(
            fallback, PanelDirection.LTR, viewWidth = 1080, viewHeight = 2400,
        ) { null }

        assertTrue(stops.isEmpty())
    }

    @Test
    fun `a panel with no bubbles is never expanded even if it doesn't fit well`() = runTest {
        // Extremely tall, narrow panel relative to the viewport, but no dialogue to preserve context for.
        val panel = Panel(bounds = PanelRect(0.4f, 0f, 0.6f, 1f), bubbles = emptyList())

        val stops = SpeechBubblePanelSubStopGenerator.generate(
            panel, PanelDirection.LTR, viewWidth = 1080, viewHeight = 2400,
        ) { null }

        assertTrue(stops.isEmpty())
    }

    @Test
    fun `a panel that fits the current view well is never expanded even with bubbles`() = runTest {
        // A roughly square panel comfortably fits either orientation.
        val panel = Panel(
            bounds = PanelRect(0f, 0f, 1f, 1f),
            bubbles = listOf(PanelRect(0.1f, 0.1f, 0.3f, 0.2f)),
        )

        val stops = SpeechBubblePanelSubStopGenerator.generate(
            panel, PanelDirection.LTR, viewWidth = 1080, viewHeight = 1200,
        ) { null }

        assertTrue(stops.isEmpty())
    }

    @Test
    fun `an oversized panel with bubbles expands to its bubbles in order, then the full panel`() = runTest {
        val bubble1 = PanelRect(0.05f, 0.05f, 0.25f, 0.15f)
        val bubble2 = PanelRect(0.7f, 0.8f, 0.9f, 0.9f)
        // Very wide, short panel — poor fit for a tall portrait viewport.
        val panel = Panel(
            bounds = PanelRect(0f, 0f, 1f, 0.15f),
            bubbles = listOf(bubble1, bubble2),
        )

        val stops = SpeechBubblePanelSubStopGenerator.generate(
            panel, PanelDirection.LTR, viewWidth = 1080, viewHeight = 2400,
        ) { null }

        assertEquals(listOf(bubble1, bubble2, panel.bounds), stops)
    }

    @Test
    fun `fit decision depends on the page's real pixel aspect ratio, not just the raw viewport`() = runTest {
        // A panel at normalized aspect 0.5 (width 0.5, height 1.0) on a portrait phone
        // (1080x2400, aspect 0.45) — comparing the panel directly against the raw viewport says
        // "fits" (0.5 / 0.45 ~= 1.11, inside [0.4, 2.5]). But the source page here is an unusually
        // wide scan (sWidth=4000, sHeight=1600, aspect 2.5) — the panel's real *rendered* aspect
        // is what panelStopTarget() actually scales against, and PagerPageHolder.
        // aspectCorrectedViewport() folds the page's own aspect into the viewport it hands the
        // generator: effectiveViewportAspect = viewportAspect / pageAspect = 0.45 / 2.5 = 0.18,
        // giving effWidth=1080*1600=1_728_000, effHeight=2400*4000=9_600_000. Against *that*
        // viewport the same panel is a poor fit (0.5 / 0.18 ~= 2.78, outside [0.4, 2.5]) and must
        // expand — demonstrating the two checks genuinely disagree, not just differ by a rounding
        // error, when the fix isn't applied.
        val bubble = PanelRect(0.05f, 0.05f, 0.2f, 0.15f)
        val panel = Panel(bounds = PanelRect(0f, 0f, 0.5f, 1f), bubbles = listOf(bubble))

        val rawStops = SpeechBubblePanelSubStopGenerator.generate(
            panel, PanelDirection.LTR, viewWidth = 1080, viewHeight = 2400,
        ) { null }
        val realAspectAwareStops = SpeechBubblePanelSubStopGenerator.generate(
            panel, PanelDirection.LTR, viewWidth = 1_728_000, viewHeight = 9_600_000,
        ) { null }

        assertTrue(rawStops.isEmpty(), "raw normalized viewport should call this a fit")
        assertEquals(
            listOf(bubble, panel.bounds),
            realAspectAwareStops,
            "the same panel, corrected for the page's real pixel aspect ratio, should not fit and must expand",
        )
    }
}
