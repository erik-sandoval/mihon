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
}
