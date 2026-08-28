package eu.kanade.presentation.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.GridOff
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AdaptiveSheet
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.ActionButton
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun ReaderPageActionsDialog(
    onDismissRequest: () -> Unit,
    onSetAsCover: () -> Unit,
    onShare: (Boolean) -> Unit,
    onSave: () -> Unit,
    isGuidedView: Boolean = false,
    isFullPageOverridden: Boolean = false,
    onToggleFullPageOverride: () -> Unit = {},
    showDebugOrder: Boolean = false,
    onToggleDebugOrder: () -> Unit = {},
    onOpenFlagReasonPicker: () -> Unit = {},
) {
    var showSetCoverDialog by remember { mutableStateOf(false) }

    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        Column {
            Row(
                modifier = Modifier.padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
            ) {
                ActionButton(
                    modifier = Modifier.weight(1f),
                    title = stringResource(MR.strings.set_as_cover),
                    icon = Icons.Outlined.Photo,
                    onClick = { showSetCoverDialog = true },
                )
                ActionButton(
                    modifier = Modifier.weight(1f),
                    title = stringResource(MR.strings.action_copy_to_clipboard),
                    icon = Icons.Outlined.ContentCopy,
                    onClick = {
                        onShare(true)
                        onDismissRequest()
                    },
                )
                ActionButton(
                    modifier = Modifier.weight(1f),
                    title = stringResource(MR.strings.action_share),
                    icon = Icons.Outlined.Share,
                    onClick = {
                        onShare(false)
                        onDismissRequest()
                    },
                )
                ActionButton(
                    modifier = Modifier.weight(1f),
                    title = stringResource(MR.strings.action_save),
                    icon = Icons.Outlined.Save,
                    onClick = {
                        onSave()
                        onDismissRequest()
                    },
                )
            }

            // Guided view only — a global scale-type-style toggle wouldn't make sense for any
            // other reading mode, since these are both panel-by-panel-specific concerns (skip
            // detection for this one page; preview the debug order overlay already in settings).
            if (isGuidedView) {
                Row(
                    modifier = Modifier.padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                ) {
                    ActionButton(
                        modifier = Modifier.weight(1f),
                        title = stringResource(
                            if (isFullPageOverridden) MR.strings.action_show_panels_again else MR.strings.action_show_full_page,
                        ),
                        icon = if (isFullPageOverridden) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen,
                        onClick = {
                            onToggleFullPageOverride()
                            onDismissRequest()
                        },
                    )
                    ActionButton(
                        modifier = Modifier.weight(1f),
                        title = stringResource(
                            if (showDebugOrder) MR.strings.action_hide_panel_order else MR.strings.action_show_panel_order,
                        ),
                        icon = if (showDebugOrder) Icons.Outlined.GridOff else Icons.Outlined.GridOn,
                        onClick = {
                            onToggleDebugOrder()
                            onDismissRequest()
                        },
                    )
                    ActionButton(
                        modifier = Modifier.weight(1f),
                        title = stringResource(MR.strings.action_flag_panel_review),
                        icon = Icons.Outlined.Flag,
                        // No onDismissRequest() here — that's wired to viewModel::closeDialog
                        // (an unconditional null-out), and calling it after opening the picker
                        // would immediately close the dialog state onOpenFlagReasonPicker just
                        // set. Swapping `dialog` to PanelFlagPicker already un-mounts this sheet
                        // on its own, same as any other dialog-to-dialog transition in this screen.
                        onClick = onOpenFlagReasonPicker,
                    )
                }
            }
        }
    }

    if (showSetCoverDialog) {
        SetCoverDialog(
            onConfirm = {
                onSetAsCover()
                showSetCoverDialog = false
            },
            onDismiss = { showSetCoverDialog = false },
        )
    }
}

@Composable
private fun SetCoverDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        text = {
            Text(stringResource(MR.strings.confirm_set_image_as_cover))
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(MR.strings.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(MR.strings.action_cancel))
            }
        },
        onDismissRequest = onDismiss,
    )
}
