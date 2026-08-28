package eu.kanade.presentation.reader.appbars

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun ReaderBottomBar(
    readingMode: ReadingMode,
    onClickReadingMode: () -> Unit,
    orientation: ReaderOrientation,
    onClickOrientation: () -> Unit,
    cropEnabled: Boolean,
    onClickCropBorder: () -> Unit,
    onClickSettings: () -> Unit,
    modifier: Modifier = Modifier,
    isPanelByPanel: Boolean = false,
    onClickPageGrid: () -> Unit = {},
    isPageMarkedGood: Boolean = false,
    onClickMarkGood: () -> Unit = {},
    onClickFlagBad: () -> Unit = {},
    upscalingEnabled: Boolean = false,
    onClickToggleUpscaling: () -> Unit = {},
) {
    Row(
        modifier = modifier
            .pointerInput(Unit) {},
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClickReadingMode) {
            Icon(
                painter = painterResource(readingMode.iconRes),
                contentDescription = stringResource(MR.strings.viewer),
            )
        }

        IconButton(onClick = onClickOrientation) {
            Icon(
                imageVector = orientation.icon,
                contentDescription = stringResource(MR.strings.rotation_type),
            )
        }

        if (isPanelByPanel) {
            IconButton(onClick = onClickPageGrid) {
                Icon(
                    painter = painterResource(R.drawable.ic_grid_view_24dp),
                    contentDescription = stringResource(MR.strings.action_page_grid),
                )
            }
            IconButton(onClick = onClickMarkGood) {
                Icon(
                    imageVector = if (isPageMarkedGood) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp,
                    contentDescription = stringResource(
                        if (isPageMarkedGood) MR.strings.action_unflag_panel_good else MR.strings.action_flag_panel_good,
                    ),
                )
            }
            // Unlike thumbs-up, this never fills/toggles — a bad flag is an append-only report
            // (any number of reasons, any number of times), not a single piece of page state, so
            // there's no one "is this page flagged bad" boolean to reflect. It just opens the
            // reason picker directly, same destination as the page-actions sheet's "Flag for
            // review" button, without the extra long-press hop.
            IconButton(onClick = onClickFlagBad) {
                Icon(
                    imageVector = Icons.Outlined.ThumbDown,
                    contentDescription = stringResource(MR.strings.action_flag_panel_review),
                )
            }
        } else {
            IconButton(onClick = onClickCropBorder) {
                Icon(
                    painter = painterResource(if (cropEnabled) R.drawable.ic_crop_24dp else R.drawable.ic_crop_off_24dp),
                    contentDescription = stringResource(MR.strings.pref_crop_borders),
                )
            }
        }

        IconButton(onClick = onClickToggleUpscaling) {
            Icon(
                painter = painterResource(if (upscalingEnabled) R.drawable.ic_upscale_24dp else R.drawable.ic_upscale_off_24dp),
                contentDescription = stringResource(MR.strings.reader_image_enhancement),
            )
        }

        IconButton(onClick = onClickSettings) {
            Icon(
                imageVector = Icons.Outlined.Settings,
                contentDescription = stringResource(MR.strings.action_settings),
            )
        }
    }
}
