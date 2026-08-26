package eu.kanade.tachiyomi.ui.reader.setting

import dev.icerock.moko.resources.StringResource
import tachiyomi.i18n.MR

/**
 * Per-series override for panel-by-panel reading order. [ReadingMode.PANEL_BY_PANEL] has no
 * direction of its own (unlike [ReadingMode.LEFT_TO_RIGHT]/[ReadingMode.RIGHT_TO_LEFT]), so this
 * is tracked as its own manga flag, mirroring how [ReadingMode] itself resolves: [DEFAULT] falls
 * back to the app-wide [ReaderPreferences.panelByPanelLeftToRight] preference, while
 * [LEFT_TO_RIGHT]/[RIGHT_TO_LEFT] pin a specific series regardless of that global setting.
 */
enum class PanelByPanelDirection(val stringRes: StringResource, val flagValue: Int) {
    DEFAULT(MR.strings.label_default, 0x00000000),
    LEFT_TO_RIGHT(MR.strings.panel_by_panel_direction_left_to_right, 0x00000040),
    RIGHT_TO_LEFT(MR.strings.panel_by_panel_direction_right_to_left, 0x00000080),
    ;

    companion object {
        const val MASK = 0x000000C0

        fun fromPreference(preference: Int?): PanelByPanelDirection = entries.find { it.flagValue == preference } ?: DEFAULT
    }
}
