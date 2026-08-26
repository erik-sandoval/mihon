package eu.kanade.tachiyomi.ui.reader.setting

/**
 * Per-series override for the AI upscaling (Real-CUGAN/Real-ESRGAN/Waifu2x/W2xEX) *content*
 * knobs — which model, denoise level, scale factor, and style. Deliberately does **not** include
 * whether upscaling is on at all; see [UpscaleEnabledOverride] for why that's a separate,
 * independent override rather than a field bundled in here. Device-performance knobs (tile size,
 * precision, processing backend, preload size, max/skip resolution, etc.) stay app-wide only;
 * they're about the phone/GPU running the model, not about this particular manga, so they aren't
 * part of this override either.
 *
 * Packed into the same [eu.kanade.domain.manga.model.Manga.viewerFlags] `Long` used for reading
 * mode/orientation/panel-by-panel direction (see CLAUDE.md's "Per-manga viewer flag bit-packing"
 * table) rather than a new DB column, reusing the existing [eu.kanade.domain.manga.interactor.SetMangaViewerFlags]
 * plumbing (including backup/restore, which already covers `viewerFlags`). A null instance means
 * "no override" — every field then falls back to the matching app-wide [ReaderPreferences]
 * preference (`realCuganModel`/`realCuganNoiseLevel`/`realCuganScale`/`realEsrganStyle`).
 *
 * Bit layout (bits 8-23 of viewerFlags; bit 9 is unused after the [UpscaleEnabledOverride] split
 * below and deliberately left free rather than reclaimed, to keep this diff minimal):
 * - bit 8 (0x100): has-override flag. 0 means every other bit here is meaningless and this
 *   whole override is absent — the getter never even decodes the rest.
 * - bits 10-14 (0x7C00): model (0-31; highest observed model id is 19)
 * - bits 15-18 (0x78000): noiseLevel (0-15; highest observed level is 4)
 * - bits 19-21 (0x380000): scale (0-7; observed values are 2-4)
 * - bits 22-23 (0xC00000): style (0-3; observed values are 0-1, only meaningful for the
 *   Real-ESRGAN Anime model)
 */
data class MangaUpscaleSettings(
    val model: Int,
    val noiseLevel: Int,
    val scale: Int,
    val style: Int,
) {
    companion object {
        private const val HAS_OVERRIDE_BIT = 0x100L

        private const val MODEL_SHIFT = 10
        private const val MODEL_BITS = 0x1FL
        private const val NOISE_LEVEL_SHIFT = 15
        private const val NOISE_LEVEL_BITS = 0xFL
        private const val SCALE_SHIFT = 19
        private const val SCALE_BITS = 0x7L
        private const val STYLE_SHIFT = 22
        private const val STYLE_BITS = 0x3L

        /** Every bit this override ever writes to — used to clear the field before re-setting it. */
        const val MASK = HAS_OVERRIDE_BIT or
            (MODEL_BITS shl MODEL_SHIFT) or
            (NOISE_LEVEL_BITS shl NOISE_LEVEL_SHIFT) or
            (SCALE_BITS shl SCALE_SHIFT) or
            (STYLE_BITS shl STYLE_SHIFT)

        /** Decodes an override from [viewerFlags], or null when this series has none. */
        fun fromFlags(viewerFlags: Long): MangaUpscaleSettings? {
            if (viewerFlags and HAS_OVERRIDE_BIT == 0L) return null
            return MangaUpscaleSettings(
                model = ((viewerFlags shr MODEL_SHIFT) and MODEL_BITS).toInt(),
                noiseLevel = ((viewerFlags shr NOISE_LEVEL_SHIFT) and NOISE_LEVEL_BITS).toInt(),
                scale = ((viewerFlags shr SCALE_SHIFT) and SCALE_BITS).toInt(),
                style = ((viewerFlags shr STYLE_SHIFT) and STYLE_BITS).toInt(),
            )
        }

        /** Encodes [settings] into the bits this override owns, or clears them when null. */
        fun toFlags(settings: MangaUpscaleSettings?): Long {
            settings ?: return 0L
            return HAS_OVERRIDE_BIT or
                ((settings.model.toLong() and MODEL_BITS) shl MODEL_SHIFT) or
                ((settings.noiseLevel.toLong() and NOISE_LEVEL_BITS) shl NOISE_LEVEL_SHIFT) or
                ((settings.scale.toLong() and SCALE_BITS) shl SCALE_SHIFT) or
                ((settings.style.toLong() and STYLE_BITS) shl STYLE_SHIFT)
        }
    }
}

/**
 * Per-series override for whether AI upscaling is on at all — deliberately independent of
 * [MangaUpscaleSettings] (model/denoise/scale/style), not a field bundled into it. Confirmed bug
 * with the earlier bundled design: turning upscaling off for a series auto-created a
 * [MangaUpscaleSettings] override (since editing *any* knob does that) and ticked "Custom settings
 * for this series" as a side effect; un-ticking that checkbox afterward — meant as "forget my
 * model/denoise/scale customization" — cleared the *enabled* bit right along with it, silently
 * turning upscaling back on. Splitting it out means toggling "Image upscaling" on/off never
 * touches the content override, and clearing the content override never touches enabled/disabled,
 * exactly mirroring how [ReadingMode]/[ReaderOrientation]/[PanelByPanelDirection] are each their
 * own independent per-series flag rather than bundled together.
 *
 * A plain on/off UI control (unlike [PanelByPanelDirection]'s explicit LEFT_TO_RIGHT/RIGHT_TO_LEFT
 * chips) can't directly express three states, so [DEFAULT] is never written by the "Image
 * upscaling" checkbox itself — every tap pins an explicit [ENABLED] or [DISABLED] for that series,
 * the same way picking a reading-mode chip always pins that series regardless of the app-wide
 * default. [DEFAULT] only reads back as the *initial* state for a series nobody has touched yet.
 */
enum class UpscaleEnabledOverride(val flagValue: Long) {
    DEFAULT(0x0L),
    ENABLED(0x1000000L),
    DISABLED(0x2000000L),
    ;

    companion object {
        const val MASK = 0x3000000L

        fun fromFlags(viewerFlags: Long): UpscaleEnabledOverride =
            entries.find { it.flagValue == viewerFlags and MASK } ?: DEFAULT
    }
}

/** Resolves this override against [globalEnabled] (the app-wide `realCuganEnabled` preference). */
fun UpscaleEnabledOverride.resolve(globalEnabled: Boolean): Boolean = when (this) {
    UpscaleEnabledOverride.DEFAULT -> globalEnabled
    UpscaleEnabledOverride.ENABLED -> true
    UpscaleEnabledOverride.DISABLED -> false
}
