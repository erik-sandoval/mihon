package eu.kanade.tachiyomi.ui.reader.viewer

import eu.kanade.tachiyomi.ui.reader.viewer.panel.PanelRect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PanelStopTargetTest {

    private fun target(
        rect: PanelRect,
        viewWidth: Int,
        viewHeight: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        compact: Boolean,
    ) = panelStopScaleAndCenter(rect, viewWidth, viewHeight, sourceWidth, sourceHeight, compact)

    /**
     * Captured from a real on-device logcat line on a Galaxy Tab S7+ (2800x1752 view): a small panel
     * from a non-upscaled 1067-wide source page. Its fit scale still magnifies the low-res source
     * enough to look soft, so the 2x upscale cap must pull it back - on both device types.
     */
    @Test
    fun smallPanelFromLowResSourceIsCappedAtTwoTimesUpscale() {
        val rect = PanelRect(0.83558047f, 0.0f, 1.0f, 0.34210938f)
        for (compact in listOf(false, true)) {
            val t = target(rect, 2800, 1752, 1067, 1600, compact)
            assertEquals(2.0f, t.scale, 0.0001f, "scale should be clamped to the 2x upscale cap (compact=$compact)")
        }
        // Center is unaffected by the caps - still the panel's midpoint in source pixels.
        val t = target(rect, 2800, 1752, 1067, 1600, compact = false)
        assertEquals(979.28f, t.centerX, 0.5f)
        assertEquals(273.69f, t.centerY, 0.5f)
    }

    /**
     * A landscape panel that previously bound to 100% of the view height (the "takes up the entire
     * viewport" complaint). On a tablet it must now be limited to [MAX_PANEL_FILL_FRACTION] of the
     * height; on a phone it stays full-height (panels are wanted large there). Coordinates captured
     * on-device: a 0.367x0.332 panel from a 2134x3200 source in a 2800x1752 view.
     */
    @Test
    fun landscapeHeightMarginIsTabletOnly() {
        val rect = PanelRect(0.0f, 0.0f, 0.3670412f, 0.33221877f)

        val tablet = target(rect, 2800, 1752, 2134, 3200, compact = false)
        val tabletHeightFraction = tablet.scale * rect.height * 3200 / 1752
        assertEquals(0.85f, tabletHeightFraction, 0.001f, "tablet: height capped at 85%")

        val phone = target(rect, 2800, 1752, 2134, 3200, compact = true)
        val phoneHeightFraction = phone.scale * rect.height * 3200 / 1752
        assertEquals(1.0f, phoneHeightFraction, 0.001f, "phone: unchanged, binds to full height")
    }

    /**
     * Phone landscape width still keeps the original small [LANDSCAPE_MAX_FILL_FRACTION] margin
     * (0.92), not the tablet's 0.85 and not edge-to-edge.
     */
    @Test
    fun phoneLandscapeWidthUsesTheOriginalNinetyTwoPercentCap() {
        val rect = PanelRect(0.0f, 0.0f, 0.9797378f, 0.29528126f)
        val t = target(rect, 2800, 1752, 2134, 3200, compact = true)
        // widthBudget = 2800 * 0.92 = 2576; 2576 / (0.9797378 * 2134) = 1.232 (width-bound).
        assertEquals(1.232f, t.scale, 0.01f)
        assertTrue(t.scale < 2.0f)
    }

    /**
     * The pre-existing portrait tall/narrow height cap ([MAX_TALL_PANEL_HEIGHT_FRACTION]) applies
     * regardless of device type - it predates the tablet/phone split.
     */
    @Test
    fun portraitTallPanelHeightCapIsDeviceIndependent() {
        val rect = PanelRect(0.85f, 0.0f, 1.0f, 0.5f)
        for (compact in listOf(false, true)) {
            val t = target(rect, 1080, 1920, 1067, 1600, compact)
            assertTrue(t.portrait)
            // realAspect = (0.15*1067)/(0.5*1600) = 0.20 -> tall/narrow -> heightBudget = 1920 * 0.6.
            assertEquals(1920f * 0.6f, t.heightBudget, 0.01f, "compact=$compact")
            // heightBudget / (rect.height * sourceHeight) = 1152 / 800 = 1.44, under the 2x cap.
            assertEquals(1.44f, t.scale, 0.001f, "compact=$compact")
        }
    }
}
