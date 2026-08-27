package eu.kanade.tachiyomi.ui.reader.viewer.panel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PanelTest {

    @Test
    fun `flattenToStops uses subStops when present, otherwise the panel bounds, then reveals the full page`() {
        val simple = Panel(bounds = PanelRect(0f, 0f, 0.5f, 1f))
        val wide = Panel(
            bounds = PanelRect(0.5f, 0f, 1f, 1f),
            subStops = listOf(
                PanelRect(0.5f, 0f, 0.7f, 1f),
                PanelRect(0.5f, 0f, 1f, 1f),
            ),
        )

        val stops = listOf(simple, wide).flattenToStops()

        assertEquals(
            listOf(
                PanelRect(0f, 0f, 0.5f, 1f),
                PanelRect(0.5f, 0f, 0.7f, 1f),
                PanelRect(0.5f, 0f, 1f, 1f),
                PanelRect.FULL_PAGE,
            ),
            stops,
        )
    }

    @Test
    fun `flattenToStops does not duplicate the full-page reveal when the only panel already is the full page`() {
        // The detector's fallback case (nothing confidently detected, decode failure, timeout)
        // is exactly one Panel(PanelRect.FULL_PAGE) with no substops. Appending a second,
        // identical full-page stop would make the reader tap once for a no-op.
        val fallback = Panel(bounds = PanelRect.FULL_PAGE)

        val stops = listOf(fallback).flattenToStops()

        assertEquals(listOf(PanelRect.FULL_PAGE), stops)
    }

    @Test
    fun `flattenToStops adds a full-page intro before the first panel when enabled`() {
        val panels = listOf(Panel(bounds = PanelRect(0f, 0f, 0.5f, 1f)))

        val stops = panels.flattenToStops(showIntro = true, showOutro = false)

        assertEquals(listOf(PanelRect.FULL_PAGE, PanelRect(0f, 0f, 0.5f, 1f)), stops)
    }

    @Test
    fun `flattenToStops brackets both ends when intro and outro are both enabled`() {
        val panels = listOf(Panel(bounds = PanelRect(0f, 0f, 0.5f, 1f)))

        val stops = panels.flattenToStops(showIntro = true, showOutro = true)

        assertEquals(
            listOf(PanelRect.FULL_PAGE, PanelRect(0f, 0f, 0.5f, 1f), PanelRect.FULL_PAGE),
            stops,
        )
    }

    @Test
    fun `flattenToStops has no full-page reveal at all when both are disabled`() {
        val panels = listOf(Panel(bounds = PanelRect(0f, 0f, 0.5f, 1f)))

        val stops = panels.flattenToStops(showIntro = false, showOutro = false)

        assertEquals(listOf(PanelRect(0f, 0f, 0.5f, 1f)), stops)
    }

    @Test
    fun `flattenToStops with intro still collapses to a single stop for the no-panels fallback`() {
        val fallback = Panel(bounds = PanelRect.FULL_PAGE)

        val stops = listOf(fallback).flattenToStops(showIntro = true, showOutro = true)

        assertEquals(listOf(PanelRect.FULL_PAGE), stops)
    }

    @Test
    fun `PanelRect width and height are computed from bounds`() {
        val rect = PanelRect(left = 0.2f, top = 0.1f, right = 0.8f, bottom = 0.6f)

        assertEquals(0.6f, rect.width, 0.0001f)
        assertEquals(0.5f, rect.height, 0.0001f)
    }

    @Test
    fun `panelIndexForFlatStop maps a flat index back to its owning panel, or null for an intro-outro bracket`() {
        val panels = listOf(
            Panel(bounds = PanelRect(0f, 0f, 0.5f, 1f)), // 1 stop
            Panel(
                bounds = PanelRect(0.5f, 0f, 1f, 1f),
                subStops = listOf(PanelRect(0.5f, 0f, 0.7f, 1f), PanelRect(0.5f, 0f, 1f, 1f)),
            ), // 2 stops
        )
        // Flattened with intro+outro: [FULL_PAGE, panel0, panel1-sub0, panel1-sub1, FULL_PAGE]

        assertEquals(null, panels.panelIndexForFlatStop(0, showIntro = true, showOutro = true)) // intro bracket
        assertEquals(0, panels.panelIndexForFlatStop(1, showIntro = true, showOutro = true))
        assertEquals(1, panels.panelIndexForFlatStop(2, showIntro = true, showOutro = true))
        assertEquals(1, panels.panelIndexForFlatStop(3, showIntro = true, showOutro = true))
        assertEquals(null, panels.panelIndexForFlatStop(4, showIntro = true, showOutro = true)) // outro bracket
    }

    @Test
    fun `resumeIndexAfterReshape resumes at the panel's first new stop when its stop count grew`() {
        val oldPanels = listOf(Panel(bounds = PanelRect(0f, 0f, 1f, 0.15f))) // 1 stop, plain view
        val newPanels = listOf(
            Panel(
                bounds = PanelRect(0f, 0f, 1f, 0.15f),
                subStops = listOf(
                    PanelRect(0.05f, 0.05f, 0.25f, 0.15f),
                    PanelRect(0.7f, 0.05f, 0.9f, 0.15f),
                    PanelRect(0f, 0f, 1f, 0.15f),
                ),
            ), // grew to 3 stops
        )

        val resumeIndex = newPanels.resumeIndexAfterReshape(
            oldFlatIndex = 0,
            oldPanels = oldPanels,
            oldShowIntro = false,
            oldShowOutro = false,
            newShowIntro = false,
            newShowOutro = false,
        )

        assertEquals(0, resumeIndex) // first of the panel's new stops (bubble1), not the trailing full-panel reveal
    }

    @Test
    fun `resumeIndexAfterReshape resumes at the panel's single stop when its stop count shrank`() {
        val oldPanels = listOf(
            Panel(
                bounds = PanelRect(0f, 0f, 1f, 0.15f),
                subStops = listOf(
                    PanelRect(0.05f, 0.05f, 0.25f, 0.15f),
                    PanelRect(0.7f, 0.05f, 0.9f, 0.15f),
                    PanelRect(0f, 0f, 1f, 0.15f),
                ),
            ),
        )
        val newPanels = listOf(Panel(bounds = PanelRect(0f, 0f, 1f, 0.15f))) // shrank to 1 stop

        val resumeIndex = newPanels.resumeIndexAfterReshape(
            oldFlatIndex = 1, // was on the second bubble
            oldPanels = oldPanels,
            oldShowIntro = false,
            oldShowOutro = false,
            newShowIntro = false,
            newShowOutro = false,
        )

        assertEquals(0, resumeIndex) // the panel's only remaining stop
    }

    @Test
    fun `resumeIndexAfterReshape resumes at the same bubble when the panel's stop count is unchanged`() {
        val bubble1 = PanelRect(0.05f, 0.05f, 0.25f, 0.15f)
        val bubble2 = PanelRect(0.7f, 0.05f, 0.9f, 0.15f)
        val bounds = PanelRect(0f, 0f, 1f, 0.15f)
        val oldPanels = listOf(Panel(bounds = bounds, subStops = listOf(bubble1, bubble2, bounds)))
        // Same bubbles, same order — as they would be across a rotation, since bubble rects are
        // orientation-agnostic cached data.
        val newPanels = listOf(Panel(bounds = bounds, subStops = listOf(bubble1, bubble2, bounds)))

        val resumeIndex = newPanels.resumeIndexAfterReshape(
            oldFlatIndex = 1, // was on bubble2
            oldPanels = oldPanels,
            oldShowIntro = false,
            oldShowOutro = false,
            newShowIntro = false,
            newShowOutro = false,
        )

        assertEquals(1, resumeIndex) // still bubble2, not reset to bubble1
    }

    @Test
    fun `resumeIndexAfterReshape does not crash on the no-panels-detected fallback with intro enabled`() {
        // The detector's fallback (nothing confidently detected, decode failure, timeout) is
        // exactly one Panel(PanelRect.FULL_PAGE) with no substops. flattenToStops collapses this
        // to a single stop with no intro/outro brackets regardless of showIntro/showOutro — a
        // reshape trigger (toggling a preference, or rotating) on such a page must not assume a
        // bracket exists just because showIntro is true here. In practice old and new panel lists
        // are identical in this scenario, since the sub-stop generator never expands a
        // full-page panel.
        val fallbackPanels = listOf(Panel(bounds = PanelRect.FULL_PAGE))

        val resumeIndex = fallbackPanels.resumeIndexAfterReshape(
            oldFlatIndex = 0,
            oldPanels = fallbackPanels,
            oldShowIntro = true,
            oldShowOutro = true,
            newShowIntro = true,
            newShowOutro = true,
        )

        assertEquals(0, resumeIndex)
    }

    @Test
    fun `resumeIndexAfterReshape resumes at the new list's last stop when the old position was the outro bracket`() {
        // A page with a real panel, both intro and outro enabled: [FULL_PAGE, panel, FULL_PAGE].
        // Bounds is deliberately NOT PanelRect.FULL_PAGE (0,0,1,1) — that exact rect would
        // structurally equal the no-panels-detected fallback sentinel and trip the unrelated
        // isSingleFullPageFallback special case instead of the real bracket path this test means
        // to exercise. Toggling a preference (or rotating) while on the trailing outro reveal must
        // not bounce the reader back to the very start of the page — only the intro bracket should
        // resume at 0; the outro should resume at the new list's own last valid index.
        val oldPanels = listOf(Panel(bounds = PanelRect(0f, 0f, 1f, 0.9f)))
        val newPanels = listOf(
            Panel(
                bounds = PanelRect(0f, 0f, 1f, 0.9f),
                subStops = listOf(PanelRect(0.05f, 0.05f, 0.25f, 0.15f), PanelRect(0f, 0f, 1f, 0.9f)),
            ),
        )
        // Old flattened: [FULL_PAGE(intro), panel.bounds, FULL_PAGE(outro)] — oldFlatIndex 2 is the outro.

        val resumeIndex = newPanels.resumeIndexAfterReshape(
            oldFlatIndex = 2,
            oldPanels = oldPanels,
            oldShowIntro = true,
            oldShowOutro = true,
            newShowIntro = true,
            newShowOutro = true,
        )

        // New flattened: [FULL_PAGE(intro), bubble, panel.bounds, FULL_PAGE(outro)] — last index is 3.
        assertEquals(3, resumeIndex)
    }

    @Test
    fun `resumeIndexAfterReshape still resumes at 0 when the old position was the intro bracket`() {
        // Same non-FULL_PAGE bounds caveat as the outro test above.
        val oldPanels = listOf(Panel(bounds = PanelRect(0f, 0f, 1f, 0.9f)))
        val newPanels = listOf(
            Panel(
                bounds = PanelRect(0f, 0f, 1f, 0.9f),
                subStops = listOf(PanelRect(0.05f, 0.05f, 0.25f, 0.15f), PanelRect(0f, 0f, 1f, 0.9f)),
            ),
        )

        val resumeIndex = newPanels.resumeIndexAfterReshape(
            oldFlatIndex = 0,
            oldPanels = oldPanels,
            oldShowIntro = true,
            oldShowOutro = true,
            newShowIntro = true,
            newShowOutro = true,
        )

        assertEquals(0, resumeIndex)
    }
}
