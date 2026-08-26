package eu.kanade.presentation.reader.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.graphics.alpha
import androidx.core.graphics.blue
import androidx.core.graphics.green
import androidx.core.graphics.red
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences.Companion.ColorFilterMode
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsViewModel
import eu.kanade.tachiyomi.util.waifu2x.Waifu2x
import tachiyomi.core.common.preference.getAndSet
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.HeadingItem
import tachiyomi.presentation.core.components.SettingsChipRow
import tachiyomi.presentation.core.components.SettingsItemsPaddings
import tachiyomi.presentation.core.components.SliderItem
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

@Composable
internal fun ColumnScope.ColorFilterPage(viewModel: ReaderSettingsViewModel) {
    val customBrightness by viewModel.preferences.customBrightness.collectAsState()
    CheckboxItem(
        label = stringResource(MR.strings.pref_custom_brightness),
        pref = viewModel.preferences.customBrightness,
    )

    /*
     * Sets the brightness of the screen. Range is [-75, 100].
     * From -75 to -1 a semi-transparent black view is shown at the top with the minimum brightness.
     * From 1 to 100 it sets that value as brightness.
     * 0 sets system brightness and hides the overlay.
     */
    if (customBrightness) {
        val customBrightnessValue by viewModel.preferences.customBrightnessValue.collectAsState()
        SliderItem(
            value = customBrightnessValue,
            valueRange = -75..100,
            steps = 0,
            label = stringResource(MR.strings.pref_custom_brightness),
            onChange = { viewModel.preferences.customBrightnessValue.set(it) },
            pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
    }

    val colorFilter by viewModel.preferences.colorFilter.collectAsState()
    CheckboxItem(
        label = stringResource(MR.strings.pref_custom_color_filter),
        pref = viewModel.preferences.colorFilter,
    )
    if (colorFilter) {
        val colorFilterValue by viewModel.preferences.colorFilterValue.collectAsState()
        SliderItem(
            value = colorFilterValue.red,
            valueRange = 0..255,
            steps = 0,
            label = stringResource(MR.strings.color_filter_r_value),
            onChange = { newRValue ->
                viewModel.preferences.colorFilterValue.getAndSet {
                    getColorValue(it, newRValue, RED_MASK, 16)
                }
            },
            pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
        SliderItem(
            value = colorFilterValue.green,
            valueRange = 0..255,
            steps = 0,
            label = stringResource(MR.strings.color_filter_g_value),
            onChange = { newGValue ->
                viewModel.preferences.colorFilterValue.getAndSet {
                    getColorValue(it, newGValue, GREEN_MASK, 8)
                }
            },
            pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
        SliderItem(
            value = colorFilterValue.blue,
            valueRange = 0..255,
            steps = 0,
            label = stringResource(MR.strings.color_filter_b_value),
            onChange = { newBValue ->
                viewModel.preferences.colorFilterValue.getAndSet {
                    getColorValue(it, newBValue, BLUE_MASK, 0)
                }
            },
            pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
        SliderItem(
            value = colorFilterValue.alpha,
            valueRange = 0..255,
            steps = 0,
            label = stringResource(MR.strings.color_filter_a_value),
            onChange = { newAValue ->
                viewModel.preferences.colorFilterValue.getAndSet {
                    getColorValue(it, newAValue, ALPHA_MASK, 24)
                }
            },
            pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )

        val colorFilterMode by viewModel.preferences.colorFilterMode.collectAsState()
        SettingsChipRow(MR.strings.pref_color_filter_mode) {
            ColorFilterMode.mapIndexed { index, it ->
                FilterChip(
                    selected = colorFilterMode == index,
                    onClick = { viewModel.preferences.colorFilterMode.set(index) },
                    label = { Text(stringResource(it.first)) },
                )
            }
        }
    }

    CheckboxItem(
        label = stringResource(MR.strings.pref_grayscale),
        pref = viewModel.preferences.grayscale,
    )
    CheckboxItem(
        label = stringResource(MR.strings.pref_inverted_colors),
        pref = viewModel.preferences.invertedColors,
    )

    // region AI image upscaling (Real-CUGAN / Real-ESRGAN / Waifu2x / W2xEX). Vulkan-only in this
    // build -- Qualcomm NPU processing backend support is not wired up here.
    val realCuganEnabled by viewModel.preferences.realCuganEnabled().collectAsState()
    CheckboxItem(
        label = stringResource(MR.strings.reader_image_enhancement),
        pref = viewModel.preferences.realCuganEnabled(),
    )
    if (realCuganEnabled) {
        val realCuganModel by viewModel.preferences.realCuganModel().collectAsState()
        val realEsrganStyle by viewModel.preferences.realEsrganStyle().collectAsState()
        val realCuganNoiseLevel by viewModel.preferences.realCuganNoiseLevel().collectAsState()
        val realCuganScale by viewModel.preferences.realCuganScale().collectAsState()

        LaunchedEffect(realCuganModel, realCuganNoiseLevel) {
            if (realCuganModel == 1 && realCuganNoiseLevel !in setOf(0, 3, 4)) {
                viewModel.preferences.realCuganNoiseLevel().set(3)
            }
        }

        // This build only ships the Vulkan processing backend (no Qualcomm NPU/QNN runtime is
        // bundled), so pin the preference to Vulkan and present it as a fixed, informational chip
        // rather than a real choice.
        LaunchedEffect(Unit) {
            viewModel.preferences.realCuganProcessingBackend().set(Waifu2x.PROCESSING_BACKEND_VULKAN)
        }
        SettingsChipRow(MR.strings.reader_processing_backend) {
            FilterChip(
                selected = true,
                onClick = {},
                label = { Text(stringResource(MR.strings.reader_backend_vulkan)) },
            )
        }

        SettingsChipRow(MR.strings.reader_model) {
            // Model IDs 6 (W2xEX Universal Fast), 8 (W2xEX Omni Mini V2),
            // Waifu2x.MODEL_W2XEX_PHOTO_SMALL (W2xEX Photo Small), and 16 (AnimeJaNai v2
            // UltraCompact) are intentionally excluded here: none of them ship bundled model
            // weights under app/src/main/assets/ (see Waifu2x.kt's w2xExModelFor/directSourceFor),
            // so selecting them would fall through to downloadDirectModels() — a runtime download
            // of unverified, unpinned native .bin/.param weights straight into ncnn's native model
            // parser, with no checksum/signature verification and no user consent. That
            // download-path code is left in place (matches this port's pattern of leaving inert
            // fork code rather than surgically removing it), just made unreachable from the UI.
            val models = listOf(
                0 to "Real-CUGAN SE",
                1 to "Real-CUGAN Pro",
                Waifu2x.MODEL_REAL_ESRGAN_ANIME to "Real-ESRGAN",
                3 to "Real-CUGAN Nose",
                4 to "Waifu2x",
                5 to "Waifu2x (Fast)",
                18 to "sudo UltraCompact",
                Waifu2x.MODEL_SPAN_NOMOSUNI_PHOTO to "SPAN NomosUni Photo",
            )
            models.map { (modelId, name) ->
                FilterChip(
                    selected = realCuganModel == modelId,
                    onClick = { viewModel.preferences.realCuganModel().set(modelId) },
                    label = { Text(name) },
                )
            }
        }

        if (realCuganModel == Waifu2x.MODEL_REAL_ESRGAN_ANIME) {
            SettingsChipRow(MR.strings.reader_model_style) {
                listOf(
                    Waifu2x.REAL_ESRGAN_STYLE_ANIME to "Anime",
                    Waifu2x.REAL_ESRGAN_STYLE_PHOTO to "Photo",
                ).map { (style, name) ->
                    FilterChip(
                        selected = realEsrganStyle == style,
                        onClick = { viewModel.preferences.realEsrganStyle().set(style) },
                        label = { Text(name) },
                    )
                }
            }
        }

        if (realCuganModel == 0 || realCuganModel == 1 || realCuganModel == 4 || realCuganModel == 5) {
            val levels = if (realCuganModel == 1) { // Pro only has no-denoise, denoise3x, conservative
                listOf(
                    0 to stringResource(MR.strings.reader_none),
                    3 to "3x",
                    4 to stringResource(MR.strings.reader_conservative),
                )
            } else if (realCuganModel == 4) { // Waifu2x
                listOf(0 to "1x", 1 to "2x", 2 to "3x")
            } else if (realCuganModel == 5) { // Waifu2x Fast (UpConv7)
                listOf(0 to stringResource(MR.strings.reader_none), 1 to "1x", 2 to "2x", 3 to "3x")
            } else { // SE
                listOf(
                    0 to stringResource(MR.strings.reader_none),
                    1 to "1x",
                    2 to "2x",
                    3 to "3x",
                    4 to stringResource(MR.strings.reader_conservative),
                )
            }

            SettingsChipRow(MR.strings.reader_denoise_level) {
                levels.map { (index, name) ->
                    FilterChip(
                        selected = realCuganNoiseLevel == index,
                        onClick = { viewModel.preferences.realCuganNoiseLevel().set(index) },
                        label = { Text(name) },
                    )
                }
            }
        }

        val fixedW2xExScale = Waifu2x.w2xExScaleFor(realCuganModel)
        when {
            realCuganModel == 3 || realCuganModel == 4 || realCuganModel == 5 ||
                (
                    realCuganModel == Waifu2x.MODEL_REAL_ESRGAN_ANIME &&
                        realEsrganStyle == Waifu2x.REAL_ESRGAN_STYLE_PHOTO
                    ) ||
                fixedW2xExScale == 2 -> {
                SettingsChipRow(MR.strings.reader_scale_factor) {
                    FilterChip(
                        selected = true,
                        onClick = {},
                        label = { Text(stringResource(MR.strings.reader_scale_fixed_2x)) },
                    )
                }
            }
            fixedW2xExScale == 4 -> {
                SettingsChipRow(MR.strings.reader_scale_factor) {
                    FilterChip(
                        selected = true,
                        onClick = {},
                        label = { Text("4x") },
                    )
                }
            }
            realCuganModel == 1 -> { // Pro only supports 2x, 3x
                SettingsChipRow(MR.strings.reader_scale_factor) {
                    listOf(2, 3).map { scale ->
                        FilterChip(
                            selected = realCuganScale == scale,
                            onClick = { viewModel.preferences.realCuganScale().set(scale) },
                            label = { Text("${scale}x") },
                        )
                    }
                }
            }
            else -> {
                SettingsChipRow(MR.strings.reader_scale_factor) {
                    listOf(2, 3, 4).map { scale ->
                        FilterChip(
                            selected = realCuganScale == scale,
                            onClick = { viewModel.preferences.realCuganScale().set(scale) },
                            label = { Text("${scale}x") },
                        )
                    }
                }
            }
        }

        SettingsChipRow(MR.strings.reader_preload_pages) {
            val realCuganPreloadSize by viewModel.preferences.realCuganPreloadSize().collectAsState()
            listOf(1, 2, 3, 5, 8).map { size ->
                FilterChip(
                    selected = realCuganPreloadSize == size,
                    onClick = { viewModel.preferences.realCuganPreloadSize().set(size) },
                    label = { Text(stringResource(MR.strings.reader_preload_pages_value, size)) },
                )
            }
        }

        SettingsChipRow(MR.strings.reader_gpu_performance_mode) {
            val performanceMode by viewModel.preferences.realCuganPerformanceMode().collectAsState()
            listOf(
                0 to stringResource(MR.strings.reader_gpu_performance_high),
                1 to stringResource(MR.strings.reader_gpu_performance_balanced),
                2 to stringResource(MR.strings.reader_gpu_performance_power_saving),
            ).map { (value, name) ->
                FilterChip(
                    selected = performanceMode == value,
                    onClick = { viewModel.preferences.realCuganPerformanceMode().set(value) },
                    label = { Text(name) },
                )
            }
        }

        SettingsChipRow(MR.strings.reader_tile_size) {
            val tileSize by viewModel.preferences.realCuganTileSize().collectAsState()
            listOf(64, 96, 128, 192, 256).map { value ->
                FilterChip(
                    selected = tileSize == value,
                    onClick = { viewModel.preferences.realCuganTileSize().set(value) },
                    label = { Text(value.toString()) },
                )
            }
        }

        val precision by viewModel.preferences.realCuganPrecision().collectAsState()
        SettingsChipRow(MR.strings.reader_precision) {
            listOf(
                0 to stringResource(MR.strings.reader_precision_fp16),
                1 to stringResource(MR.strings.reader_precision_fp32),
                2 to stringResource(MR.strings.reader_precision_int8),
                3 to stringResource(MR.strings.reader_precision_bf16),
            ).map { (value, name) ->
                FilterChip(
                    selected = precision == value,
                    onClick = { viewModel.preferences.realCuganPrecision().set(value) },
                    label = { Text(name) },
                )
            }
        }

        if (precision == 0) {
            CheckboxItem(
                label = stringResource(MR.strings.reader_fp16_arithmetic),
                pref = viewModel.preferences.realCuganFp16Arithmetic(),
            )
        }

        val processMaxWidth by viewModel.preferences.realCuganMaxSizeWidth().collectAsState()
        val processMaxHeight by viewModel.preferences.realCuganMaxSizeHeight().collectAsState()
        ResolutionLimitFields(
            heading = stringResource(MR.strings.reader_processing_resolution),
            width = processMaxWidth,
            height = processMaxHeight,
            onWidthChange = { viewModel.preferences.realCuganMaxSizeWidth().set(it) },
            onHeightChange = { viewModel.preferences.realCuganMaxSizeHeight().set(it) },
        )

        val skipMaxWidth by viewModel.preferences.realCuganSkipMaxSizeWidth().collectAsState()
        val skipMaxHeight by viewModel.preferences.realCuganSkipMaxSizeHeight().collectAsState()
        ResolutionLimitFields(
            heading = stringResource(MR.strings.reader_max_resolution),
            width = skipMaxWidth,
            height = skipMaxHeight,
            onWidthChange = { viewModel.preferences.realCuganSkipMaxSizeWidth().set(it) },
            onHeightChange = { viewModel.preferences.realCuganSkipMaxSizeHeight().set(it) },
        )

        CheckboxItem(
            label = stringResource(MR.strings.reader_show_processing_status),
            pref = viewModel.preferences.realCuganShowStatus(),
        )
    }
    // endregion
}

@Composable
private fun ResolutionLimitFields(
    heading: String,
    width: Int,
    height: Int,
    onWidthChange: (Int) -> Unit,
    onHeightChange: (Int) -> Unit,
) {
    Column {
        HeadingItem(heading)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SettingsItemsPaddings.Horizontal, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ResolutionNumberField(
                modifier = Modifier.weight(1f),
                value = width,
                onValueChange = onWidthChange,
                label = stringResource(MR.strings.reader_resolution_width),
            )
            ResolutionNumberField(
                modifier = Modifier.weight(1f),
                value = height,
                onValueChange = onHeightChange,
                label = stringResource(MR.strings.reader_resolution_height),
            )
        }
    }
}

@Composable
private fun ResolutionNumberField(
    value: Int,
    onValueChange: (Int) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    var text by remember { mutableStateOf(value.toResolutionText()) }

    LaunchedEffect(value) {
        val normalized = value.toResolutionText()
        if (text != normalized && (text.toIntOrNull() ?: 0) != value) {
            text = normalized
        }
    }

    OutlinedTextField(
        modifier = modifier,
        value = text,
        onValueChange = { raw ->
            val filtered = raw.filter(Char::isDigit)
            text = filtered
            onValueChange(filtered.toIntOrNull() ?: 0)
        },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
    )
}

private fun Int.toResolutionText(): String = if (this == 0) "" else toString()

private fun getColorValue(currentColor: Int, color: Int, mask: Long, bitShift: Int): Int {
    return (color shl bitShift) or (currentColor and mask.inv().toInt())
}
private const val ALPHA_MASK: Long = 0xFF000000
private const val RED_MASK: Long = 0x00FF0000
private const val GREEN_MASK: Long = 0x0000FF00
private const val BLUE_MASK: Long = 0x000000FF
