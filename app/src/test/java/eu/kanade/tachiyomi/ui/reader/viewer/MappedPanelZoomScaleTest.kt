package eu.kanade.tachiyomi.ui.reader.viewer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MappedPanelZoomScaleTest {

    /**
     * Carrying a mid-panel zoom across the raw -> enhanced image swap: the user was 3x past the
     * previous view's base framing (scale 6.0, minScale 2.0), and the new (upscaled) view's own
     * panel-base scale is 1.85. The remap must preserve the 3x factor: 1.85 * 3 = 5.55.
     */
    @Test
    fun carriedPanelZoomIsRemappedRelativeToTheNewBaseScale() {
        val mapped = mappedPanelZoomScale(oldScale = 6.0f, oldMinScale = 2.0f, newBaseScale = 1.85f, newMaxScale = 5.55f)
        assertEquals(5.55f, mapped, 0.0001f)
    }

    /**
     * Regression: a freshly-created SCALE_TYPE_CUSTOM view that nothing has assigned a minScale to
     * yet reports minScale == NaN. Feeding that straight into `minScale * zoomFactor` (the old code)
     * produced a NaN scale, which `setScaleAndCenter` then baked permanently into the swapped-in
     * view - the page rendered black and the panel spotlight overlay / navigation stayed visibly
     * stuck until the page was torn down. Confirmed on-device via
     * `animateProcessedSwap.onReady ... new(...,minScale=NaN)` followed by `post(scale=NaN)`.
     */
    @Test
    fun poisonedNaNOldMinScaleCollapsesToTheNewBaseFramingInsteadOfNaN() {
        val mapped = mappedPanelZoomScale(oldScale = 6.0f, oldMinScale = Float.NaN, newBaseScale = 1.85f, newMaxScale = 5.55f)
        assertFalse(mapped.isNaN(), "must never propagate NaN into the swapped-in view's scale")
        assertEquals(1.85f, mapped, 0.0001f, "a non-finite zoom factor falls back to the panel's base framing")
    }

    /** A zero incoming minScale (division by zero -> Infinity, also non-finite) falls back the same way. */
    @Test
    fun zeroOldMinScaleAlsoFallsBackToBaseFraming() {
        val mapped = mappedPanelZoomScale(oldScale = 6.0f, oldMinScale = 0.0f, newBaseScale = 1.85f, newMaxScale = 5.55f)
        assertTrue(mapped.isFinite())
        assertEquals(1.85f, mapped, 0.0001f)
    }

    /** The result is always clamped into [newBaseScale, newMaxScale] - never below base, never past the cap. */
    @Test
    fun mappedZoomStaysWithinBaseAndMaxScale() {
        assertEquals(1.85f, mappedPanelZoomScale(3.0f, 3.0f, 1.85f, 5.55f), 0.0001f, "1x factor -> base")
        assertEquals(5.55f, mappedPanelZoomScale(100f, 2.0f, 1.85f, 5.55f), 0.0001f, "huge factor -> clamped to max")
    }
}
