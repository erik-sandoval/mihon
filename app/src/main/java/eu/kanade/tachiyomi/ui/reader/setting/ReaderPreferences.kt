package eu.kanade.tachiyomi.ui.reader.setting

import android.os.Build
import androidx.compose.ui.graphics.BlendMode
import dev.icerock.moko.resources.StringResource
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum
import tachiyomi.core.common.preference.getEnumSet
import tachiyomi.i18n.MR

@Inject
@SingleIn(AppScope::class)
class ReaderPreferences(
    private val preferenceStore: PreferenceStore,
) {

    // region General

    val pageTransitions: Preference<Boolean> = preferenceStore.getBoolean("pref_enable_transitions_key", true)

    val flashOnPageChange: Preference<Boolean> = preferenceStore.getBoolean("pref_reader_flash", false)

    val flashDurationMillis: Preference<Int> = preferenceStore.getInt("pref_reader_flash_duration", MILLI_CONVERSION)

    val flashPageInterval: Preference<Int> = preferenceStore.getInt("pref_reader_flash_interval", 1)

    val flashColor: Preference<FlashColor> = preferenceStore.getEnum("pref_reader_flash_mode", FlashColor.BLACK)

    val doubleTapAnimSpeed: Preference<Int> = preferenceStore.getInt("pref_double_tap_anim_speed", 500)

    val showPageNumber: Preference<Boolean> = preferenceStore.getBoolean("pref_show_page_number_key", true)

    val verticalNavigator: Preference<Set<ReadingMode>> = preferenceStore.getEnumSet(
        "pref_vertical_navigator",
        emptySet(),
    )

    val verticalNavigatorOnLeft: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_vertical_navigator_on_left",
        false,
    )

    val verticalNavigatorHeight: Preference<Int> = preferenceStore.getInt(
        "pref_vertical_navigator_height",
        65,
    )

    val showReadingMode: Preference<Boolean> = preferenceStore.getBoolean("pref_show_reading_mode", true)

    val fullscreen: Preference<Boolean> = preferenceStore.getBoolean("fullscreen", true)

    val drawUnderCutout: Preference<Boolean> = preferenceStore.getBoolean("cutout_short", true)

    val keepScreenOn: Preference<Boolean> = preferenceStore.getBoolean("pref_keep_screen_on_key", false)

    val defaultReadingMode: Preference<Int> = preferenceStore.getInt(
        "pref_default_reading_mode_key",
        ReadingMode.RIGHT_TO_LEFT.flagValue,
    )

    /**
     * Inverts the fixed right-to-left default for panel-by-panel navigation order, used when a
     * series has no [PanelByPanelDirection] override of its own (see
     * [eu.kanade.domain.manga.model.panelByPanelDirection]). Right-to-left (false) is the fixed
     * default to match typical manga; this only needs to be set when left-to-right is wanted
     * instead.
     */
    val panelByPanelLeftToRight: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_panel_by_panel_left_to_right",
        false,
    )

    /** Whether a chapter's first page starts with a full-page reveal before stepping into its first panel. */
    val panelByPanelShowFullPageIntro: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_panel_by_panel_show_full_page_intro",
        true,
    )

    /** Whether a page ends with a full-page reveal after its last panel, before turning the page. */
    val panelByPanelShowFullPageOutro: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_panel_by_panel_show_full_page_outro",
        true,
    )

    /**
     * Off by default: expands an oversized panel into its detected speech bubbles before the full
     * panel, instead of leaving it scaled to fit both axes. See SpeechBubblePanelSubStopGenerator.
     *
     * A `val` like every sibling here, deliberately — `Preference.collectAsState()` memoizes with
     * `remember(this) { changes() }` and the preference class has no `equals()`, so identity is the
     * key. A function handing back a fresh instance per call would break that at every Compose call
     * site, restarting the flow collection on each recomposition.
     */
    val panelByPanelBubbleStopsEnabled: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_panel_by_panel_bubble_stops_enabled",
        false,
    )

    /** Opacity of the scrim dimming everything outside the current panel, 0 (transparent) to 100 (opaque black). */
    val panelByPanelOverlayOpacity: Preference<Int> = preferenceStore.getInt(
        "pref_panel_by_panel_overlay_opacity",
        65,
    )

    /** Debug aid: outlines every detected panel with its reading-order number. */
    val panelByPanelShowDebugOrder: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_panel_by_panel_show_debug_order",
        false,
    )

    /**
     * Inverts the fixed swipe-right-is-forward convention (see [PagerViewer][eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerViewer]'s
     * `panelSwipeListener`) so swipe-left steps forward instead. Independent of
     * [panelByPanelLeftToRight]/[PanelByPanelDirection] — this flips the physical gesture itself,
     * not the reading-order direction those control.
     */
    val panelByPanelSwipeInverted: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_panel_by_panel_swipe_inverted",
        false,
    )

    val defaultOrientationType: Preference<Int> = preferenceStore.getInt(
        "pref_default_orientation_type_key",
        ReaderOrientation.FREE.flagValue,
    )

    val webtoonDoubleTapZoomEnabled: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_enable_double_tap_zoom_webtoon",
        true,
    )

    val imageScaleType: Preference<Int> = preferenceStore.getInt("pref_image_scale_type_key", 1)

    val zoomStart: Preference<Int> = preferenceStore.getInt("pref_zoom_start_key", 1)

    val readerTheme: Preference<Int> = preferenceStore.getInt("pref_reader_theme_key", 1)

    val alwaysShowChapterTransition: Preference<Boolean> = preferenceStore.getBoolean(
        "always_show_chapter_transition",
        true,
    )

    val cropBorders: Preference<Boolean> = preferenceStore.getBoolean("crop_borders", false)

    val navigateToPan: Preference<Boolean> = preferenceStore.getBoolean("navigate_pan", true)

    val landscapeZoom: Preference<Boolean> = preferenceStore.getBoolean("landscape_zoom", true)

    val cropBordersWebtoon: Preference<Boolean> = preferenceStore.getBoolean("crop_borders_webtoon", false)

    val webtoonSidePadding: Preference<Int> = preferenceStore.getInt("webtoon_side_padding", WEBTOON_PADDING_MIN)

    val readerHideThreshold: Preference<ReaderHideThreshold> = preferenceStore.getEnum(
        "reader_hide_threshold",
        ReaderHideThreshold.LOW,
    )

    val folderPerManga: Preference<Boolean> = preferenceStore.getBoolean("create_folder_per_manga", false)

    val skipRead: Preference<Boolean> = preferenceStore.getBoolean("skip_read", false)

    val skipFiltered: Preference<Boolean> = preferenceStore.getBoolean("skip_filtered", true)

    val skipDupe: Preference<Boolean> = preferenceStore.getBoolean("skip_dupe", false)

    val webtoonDisableZoomOut: Preference<Boolean> = preferenceStore.getBoolean("webtoon_disable_zoom_out", false)

    // endregion

    // region Split two-page spread

    val dualPageSplitPaged: Preference<Boolean> = preferenceStore.getBoolean("pref_dual_page_split", false)

    val dualPageInvertPaged: Preference<Boolean> = preferenceStore.getBoolean("pref_dual_page_invert", false)

    val dualPageSplitWebtoon: Preference<Boolean> = preferenceStore.getBoolean("pref_dual_page_split_webtoon", false)

    val dualPageInvertWebtoon: Preference<Boolean> = preferenceStore.getBoolean("pref_dual_page_invert_webtoon", false)

    val dualPageRotateToFit: Preference<Boolean> = preferenceStore.getBoolean("pref_dual_page_rotate", false)

    val dualPageRotateToFitInvert: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_dual_page_rotate_invert",
        false,
    )

    val dualPageRotateToFitWebtoon: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_dual_page_rotate_webtoon",
        false,
    )

    val dualPageRotateToFitInvertWebtoon: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_dual_page_rotate_invert_webtoon",
        false,
    )

    val dualPageView: Preference<DualPageView> = preferenceStore.getEnum(
        "pref_dual_page_view",
        DualPageView.NEVER,
    )

    // endregion

    // region Color filter

    val customBrightness: Preference<Boolean> = preferenceStore.getBoolean("pref_custom_brightness_key", false)

    val customBrightnessValue: Preference<Int> = preferenceStore.getInt("custom_brightness_value", 0)

    val colorFilter: Preference<Boolean> = preferenceStore.getBoolean("pref_color_filter_key", false)

    val colorFilterValue: Preference<Int> = preferenceStore.getInt("color_filter_value", 0)

    val colorFilterMode: Preference<Int> = preferenceStore.getInt("color_filter_mode", 0)

    val grayscale: Preference<Boolean> = preferenceStore.getBoolean("pref_grayscale", false)

    val invertedColors: Preference<Boolean> = preferenceStore.getBoolean("pref_inverted_colors", false)

    // endregion

    // region Controls

    val readWithLongTap: Preference<Boolean> = preferenceStore.getBoolean("reader_long_tap", true)

    val readWithVolumeKeys: Preference<Boolean> = preferenceStore.getBoolean("reader_volume_keys", false)

    val readWithVolumeKeysInverted: Preference<Boolean> = preferenceStore.getBoolean(
        "reader_volume_keys_inverted",
        false,
    )

    val navigationModePager: Preference<Int> = preferenceStore.getInt("reader_navigation_mode_pager", 0)

    val navigationModeWebtoon: Preference<Int> = preferenceStore.getInt("reader_navigation_mode_webtoon", 0)

    val pagerNavInverted: Preference<TappingInvertMode> = preferenceStore.getEnum(
        "reader_tapping_inverted",
        TappingInvertMode.NONE,
    )

    val webtoonNavInverted: Preference<TappingInvertMode> = preferenceStore.getEnum(
        "reader_tapping_inverted_webtoon",
        TappingInvertMode.NONE,
    )

    val showNavigationOverlayNewUser: Preference<Boolean> = preferenceStore.getBoolean(
        "reader_navigation_overlay_new_user",
        true,
    )

    val showNavigationOverlayOnStart: Preference<Boolean> = preferenceStore.getBoolean(
        "reader_navigation_overlay_on_start",
        false,
    )

    // endregion

    // region Upscaling

    fun waifu2xEnabled() = preferenceStore.getBoolean("pref_waifu2x_enabled", false)

    fun waifu2xNoiseLevel() = preferenceStore.getInt("pref_waifu2x_noise_level", 2)

    fun anime4kEnabled() = preferenceStore.getBoolean("pref_anime4k_enabled", false)

    fun anime4kMode() = preferenceStore.getInt("pref_anime4k_mode", 0) // 0: Fast, 1: High, 2: Ultra

    fun realCuganEnabled() = preferenceStore.getBoolean("pref_realcugan_enabled", false)

    // 0: No Denoise, 1: Denoise 1x, 2: Denoise 2x, 3: Denoise 3x, 4: Conservative
    fun realCuganNoiseLevel() = preferenceStore.getInt("pref_realcugan_noise_level", 0)

    fun realCuganScale() = preferenceStore.getInt("pref_realcugan_scale", 2) // 2x, 3x, 4x

    fun realCuganModel() = preferenceStore.getInt("pref_realcugan_model", 0)

    fun realEsrganStyle() = preferenceStore.getInt("pref_realesrgan_style", 0) // 0: Anime, 1: Photo

    fun realCuganPreloadSize() = preferenceStore.getInt("pref_realcugan_preload_size", 3)

    fun realCuganProEnabled() = preferenceStore.getBoolean("pref_realcugan_pro_enabled", false)

    // 0: 90%, 1: 50%, 2: 30%
    fun realCuganPerformanceMode() = preferenceStore.getInt("pref_realcugan_performance_mode", 0)

    fun realCuganTileSize() = preferenceStore.getInt("pref_realcugan_tile_size", 128)

    // 0: FP16, 1: FP32, 2: INT8, 3: BF16
    fun realCuganPrecision() = preferenceStore.getInt("pref_realcugan_precision", 0)

    // 0: Vulkan, 1: Qualcomm NPU
    fun realCuganProcessingBackend() = preferenceStore.getInt("pref_realcugan_processing_backend", 1)

    fun realCuganFp16Arithmetic() = preferenceStore.getBoolean("pref_realcugan_fp16_arithmetic", false)

    fun realCuganMaxSizeWidth() = preferenceStore.getInt("pref_realcugan_max_size_width", 1600)

    fun realCuganMaxSizeHeight() = preferenceStore.getInt("pref_realcugan_max_size_height", 1600)

    fun realCuganSkipMaxSizeWidth() = preferenceStore.getInt("pref_realcugan_skip_max_size_width", 0)

    fun realCuganSkipMaxSizeHeight() = preferenceStore.getInt("pref_realcugan_skip_max_size_height", 0)

    /** Max on-disk size, in MB, of [eu.kanade.tachiyomi.util.waifu2x.ImageEnhancementCache] before [eu.kanade.tachiyomi.util.waifu2x.ImageEnhancementCache.checkAndTrim] starts evicting the oldest entries. 0 = unlimited. */
    fun aiImageCacheMaxSizeMb() = preferenceStore.getInt("pref_ai_image_cache_max_size_mb", 3072)

    /** Whether a chapter's enhanced-image cache entries are deleted as soon as that chapter is marked read. */
    fun clearAiImageCacheOnChapterRead() = preferenceStore.getBoolean("pref_clear_ai_image_cache_on_chapter_read", false)

    fun realCuganShowStatus() = preferenceStore.getBoolean("pref_realcugan_show_status", false)

    // endregion

    // region WebGpu

    val transitionAnimation: Preference<TransitionAnimation> =
        preferenceStore.getEnum("webgpu_transition_animation", TransitionAnimation.DEFAULT)

    val cutoutMode: Preference<CutoutMode> = preferenceStore.getEnum("webgpu_cutout_mode", CutoutMode.AVOID)

    // endregion

    enum class FlashColor {
        BLACK,
        WHITE,
        WHITE_BLACK,
    }

    enum class TappingInvertMode(
        val titleRes: StringResource,
        val shouldInvertHorizontal: Boolean = false,
        val shouldInvertVertical: Boolean = false,
    ) {
        NONE(MR.strings.tapping_inverted_none),
        HORIZONTAL(
            MR.strings.tapping_inverted_horizontal,
            shouldInvertHorizontal = true,
        ),
        VERTICAL(
            MR.strings.tapping_inverted_vertical,
            shouldInvertVertical = true,
        ),
        BOTH(MR.strings.tapping_inverted_both, shouldInvertHorizontal = true, shouldInvertVertical = true),
    }

    enum class ReaderHideThreshold(val threshold: Int) {
        HIGHEST(5),
        HIGH(13),
        LOW(31),
        LOWEST(47),
    }

    enum class TransitionAnimation(val titleRes: StringResource) {
        DEFAULT(MR.strings.transition_animation_default),
        FLIP_LEFT(MR.strings.transition_animation_flip_left),
        FLIP_RIGHT(
            MR.strings.transition_animation_flip_right,
        ),
        STACK_LEFT(MR.strings.transition_animation_stack_left),
        STACK_RIGHT(MR.strings.transition_animation_stack_right),
        STACK_UP(
            MR.strings.transition_animation_stack_up,
        ),
        STACK_DOWN(MR.strings.transition_animation_stack_down),
        SPHERE(MR.strings.transition_animation_sphere),
        CUBE_INSIDE(
            MR.strings.transition_animation_cube_inside,
        ),
        CUBE_OUTSIDE(MR.strings.transition_animation_cube_outside),
        FADE(MR.strings.transition_animation_fade),
        FADE_WHITE(
            MR.strings.transition_animation_fade_white,
        ),
        NONE(MR.strings.transition_animation_none),
    }

    enum class CutoutMode(val titleRes: StringResource) {
        IGNORE(MR.strings.cutout_mode_ignore),
        AVOID(MR.strings.cutout_mode_avoid),
        SHIFT(MR.strings.cutout_mode_shift),
    }

    enum class DualPageView(val titleRes: StringResource) {
        NEVER(MR.strings.dual_page_view_never),
        ALWAYS(MR.strings.dual_page_view_always),
        WIDE(MR.strings.dual_page_view_wide),
    }

    companion object {
        const val WEBTOON_PADDING_MIN = 0
        const val WEBTOON_PADDING_MAX = 25

        const val MILLI_CONVERSION = 100

        val TapZones = listOf(
            MR.strings.label_default,
            MR.strings.l_nav,
            MR.strings.kindlish_nav,
            MR.strings.edge_nav,
            MR.strings.right_and_left_nav,
            MR.strings.disabled_nav,
        )

        val ImageScaleType = listOf(
            MR.strings.scale_type_fit_screen,
            MR.strings.scale_type_stretch,
            MR.strings.scale_type_fit_width,
            MR.strings.scale_type_fit_height,
            MR.strings.scale_type_original_size,
            MR.strings.scale_type_smart_fit,
        )

        val ZoomStart = listOf(
            MR.strings.zoom_start_automatic,
            MR.strings.zoom_start_left,
            MR.strings.zoom_start_right,
            MR.strings.zoom_start_center,
        )

        val ColorFilterMode = buildList {
            addAll(
                listOf(
                    MR.strings.label_default to BlendMode.SrcOver,
                    MR.strings.filter_mode_multiply to BlendMode.Modulate,
                    MR.strings.filter_mode_screen to BlendMode.Screen,
                ),
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                addAll(
                    listOf(
                        MR.strings.filter_mode_overlay to BlendMode.Overlay,
                        MR.strings.filter_mode_lighten to BlendMode.Lighten,
                        MR.strings.filter_mode_darken to BlendMode.Darken,
                    ),
                )
            }
        }
    }
}
