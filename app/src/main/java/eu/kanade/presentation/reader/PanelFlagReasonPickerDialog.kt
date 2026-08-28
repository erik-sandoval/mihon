package eu.kanade.presentation.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.ui.reader.viewer.panel.PanelFlagReason
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Reason picker for flagging the current page's panel detection — reachable both from the
 * reader's page-actions sheet ("Flag for review") and directly from the bottom bar's thumbs-down
 * button (Guided view only), so it's promoted to its own top-level reader dialog state rather
 * than living inside the page-actions sheet specifically.
 */
@Composable
fun PanelFlagReasonPickerDialog(
    onSelect: (PanelFlagReason) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        title = {
            Text(stringResource(MR.strings.panel_flag_reason_title))
        },
        text = {
            Column {
                PanelFlagReasonOption(MR.strings.panel_flag_reason_bad_detection) { onSelect(PanelFlagReason.BAD_DETECTION) }
                PanelFlagReasonOption(MR.strings.panel_flag_reason_wrong_order) { onSelect(PanelFlagReason.WRONG_ORDER) }
                PanelFlagReasonOption(MR.strings.panel_flag_reason_missed_text) { onSelect(PanelFlagReason.MISSED_TEXT) }
                PanelFlagReasonOption(MR.strings.panel_flag_reason_good_example) { onSelect(PanelFlagReason.GOOD_EXAMPLE) }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(MR.strings.action_cancel))
            }
        },
        onDismissRequest = onDismiss,
    )
}

@Composable
private fun PanelFlagReasonOption(
    titleRes: StringResource,
    onClick: () -> Unit,
) {
    TextButton(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        Text(
            text = stringResource(titleRes),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
