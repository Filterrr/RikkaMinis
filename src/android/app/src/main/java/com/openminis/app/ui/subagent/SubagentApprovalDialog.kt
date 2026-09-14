package com.openminis.app.ui.subagent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.openminis.app.R
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.tools.SpawnApprovalRequest
import com.openminis.app.tools.SpawnDecision

/**
 * [T-subagent-approval] The spawn gate dialog. Renders the HEAD of the
 * per-chat [com.openminis.app.tools.SpawnApprovalQueue]; answering one
 * surfaces the next, so a parallel fan-out of N spawns asks N times rather
 * than presenting one blob — each delegated task is its own decision.
 *
 * Deliberately NOT dismissible by tapping outside or the back button:
 * dismissing without answering is exactly the ambiguity the gate exists to
 * remove. The explicit Deny button is the only "no", and it is also what a
 * timeout resolves to. (MinisAlertDialog allows outside-tap; this dialog's
 * semantics are stronger, which justifies its own component.)
 *
 * Hardcoded English strings match the existing sub-agent UI surfaces
 * ("Completed"/"Queued" in SubagentUiCommon) — the repo's i18n scan only
 * fails ORPHAN R.string references, and upstream localization of this
 * fork's newer UI is not yet a thing.
 */
@Composable
fun SubagentApprovalDialog(
    request: SpawnApprovalRequest?,
    onDecision: (String, SpawnDecision) -> Unit,
) {
    if (request == null) return
    Dialog(
        // Swallow the no-op dismiss request: outside-tap / back cannot answer.
        onDismissRequest = { },
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Allow sub-agent?",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp),
                )
                Spacer(modifier = Modifier.height(12.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // Provenance the decision actually needs: WHO runs, ON
                    // WHAT, WHERE the output goes. The tool loop itself is
                    // invisible to the user otherwise.
                    ApprovalLine(label = "skill", value = request.skillName)
                    if (request.modelLabel.isNotBlank()) {
                        ApprovalLine(label = "model", value = request.modelLabel)
                    }
                    ApprovalLine(
                        label = "mode",
                        value = if (request.detached) "background (detach)" else "foreground (blocks this turn)",
                    )
                    Text(
                        text = "task:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = request.taskPreview,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 10,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "The sub-agent runs its own tool loop (shell, files, browser) " +
                            "without further confirmation until it reports back.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    MinisTextButton(onClick = { onDecision(request.id, SpawnDecision.DENY) }) {
                        Text(stringResource(R.string.cancel))
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    MinisTextButton(onClick = { onDecision(request.id, SpawnDecision.ALWAYS_ALLOW) }) {
                        Text(
                            "Always allow",
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    MinisTextButton(onClick = { onDecision(request.id, SpawnDecision.ALLOW_ONCE) }) {
                        Text(
                            "Allow once",
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ApprovalLine(label: String, value: String) {
    Row {
        Text(
            text = "$label: ",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
