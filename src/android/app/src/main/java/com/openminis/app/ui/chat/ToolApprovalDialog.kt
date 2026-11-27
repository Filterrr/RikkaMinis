package com.openminis.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.tools.ToolApprovalDecision
import com.openminis.app.tools.ToolApprovalRequest

/**
 * [T-tool-approval] 危险命令审批弹窗。
 *
 * 和 ask_user 的区别：这个必须给出"不可逆"的警告，并把**命令原文**摆出来
 * ——用户判断的是"这条命令要不要跑"，不是"这个模型可不可信"。所以命令用
 * 等宽字体、单独一块背景，且不截断成一行摘要。
 *
 * 三个选项的语义必须一眼分清：
 *  - 允许一次 —— 只这一次；
 *  - 本会话允许 —— 只放行**这一条规则**（不是整个 shell）；
 *  - 拒绝 —— 工具不执行，模型收到明确拒绝。
 */
@Composable
internal fun ToolApprovalDialog(
    request: ToolApprovalRequest,
    onDecision: (ToolApprovalDecision) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onDecision(ToolApprovalDecision.DENY) },
        title = { Text(stringResource(R.string.tool_approval_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = request.ruleLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = stringResource(R.string.tool_approval_body),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = request.commandPreview,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(8.dp),
                        )
                        .padding(10.dp),
                )
                Text(
                    text = request.toolName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onDecision(ToolApprovalDecision.ALLOW_ONCE) }) {
                Text(stringResource(R.string.tool_approval_allow_once))
            }
        },
        dismissButton = {
            Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                TextButton(onClick = { onDecision(ToolApprovalDecision.ALLOW_SESSION) }) {
                    Text(stringResource(R.string.tool_approval_allow_session))
                }
                TextButton(onClick = { onDecision(ToolApprovalDecision.DENY) }) {
                    Text(
                        text = stringResource(R.string.tool_approval_deny),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
    )
}