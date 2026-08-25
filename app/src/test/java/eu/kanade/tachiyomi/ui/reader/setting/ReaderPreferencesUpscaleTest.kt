package eu.kanade.tachiyomi.ui.reader.setting

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore

class ReaderPreferencesUpscaleTest {

    @Test
    fun `upscaler preferences have expected defaults`() {
        val preferenceStore = InMemoryPreferenceStore()
        val prefs = ReaderPreferences(preferenceStore)

        assertFalse(prefs.waifu2xEnabled().get())
        assertEquals(2, prefs.waifu2xNoiseLevel().get())

        assertFalse(prefs.anime4kEnabled().get())
        assertEquals(0, prefs.anime4kMode().get())

        assertFalse(prefs.realCuganEnabled().get())
        assertEquals(0, prefs.realCuganNoiseLevel().get())
        assertEquals(2, prefs.realCuganScale().get())
        assertEquals(0, prefs.realCuganModel().get())
        assertEquals(0, prefs.realEsrganStyle().get())
        assertEquals(3, prefs.realCuganPreloadSize().get())
        assertFalse(prefs.realCuganProEnabled().get())
        assertEquals(0, prefs.realCuganPerformanceMode().get())
        assertEquals(128, prefs.realCuganTileSize().get())
        assertEquals(0, prefs.realCuganPrecision().get())
        assertEquals(1, prefs.realCuganProcessingBackend().get())
        assertFalse(prefs.realCuganFp16Arithmetic().get())
        assertEquals(1600, prefs.realCuganMaxSizeWidth().get())
        assertEquals(1600, prefs.realCuganMaxSizeHeight().get())
        assertEquals(0, prefs.realCuganSkipMaxSizeWidth().get())
        assertEquals(0, prefs.realCuganSkipMaxSizeHeight().get())
        assertFalse(prefs.realCuganShowStatus().get())
    }

    @Test
    fun `upscaler preference set is reflected by the same live handle`() {
        val preferenceStore = InMemoryPreferenceStore()
        val prefs = ReaderPreferences(preferenceStore)

        // InMemoryPreferenceStore builds its backing map once from the preferences
        // it's constructed with, so a *new* getX() call on the store doesn't observe
        // a write made through a previously-returned Preference handle. Real get/set
        // round-tripping is exercised against the same handle here, matching how a
        // single ReaderPreferences consumer actually holds and reuses these.
        val enabled = prefs.waifu2xEnabled()
        assertFalse(enabled.get())
        enabled.set(true)
        assertEquals(true, enabled.get())

        val noiseLevel = prefs.realCuganNoiseLevel()
        noiseLevel.set(3)
        assertEquals(3, noiseLevel.get())
    }
}
