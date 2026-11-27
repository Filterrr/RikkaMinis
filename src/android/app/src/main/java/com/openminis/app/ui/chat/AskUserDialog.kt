package com.openminis.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.tools.AskUserRequest

/**
 * [T-ask-user] 挂起式提问的对话框。
 *
 * 和审批弹窗（SubagentApprovalDialog）刻意区分开：那个是"允不允许"，按钮是
 * 允许/拒绝；这个是"你想要哪个"，选项就是模型给的那几条。混在一起会让用户
 * 分不清自己点的是权限还是内容。
 *
 * 关掉对话框（点外部/返回键）不等同于"取消这一轮"——它投递一个"没回答"，
 * agent 拿到 no answer 后按自己的 best judgment 继续，或者直接收尾再问。
 * 否则用户随手一划就把一整轮工作干掉了。
 */
@Composable
internal fun AskUserDialog(
    request: AskUserRequest,
    onAnswer: (selectedIndex: Int, text: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var freeText by remember(request.id) { mutableStateOf("") }
    val canSend = freeText.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ask_user_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = request.question,
                    style = MaterialTheme.typography.bodyMedium,
                )
                request.options.forEachIndexed { index, option ->
                    TextButton(
                        onClick = { onAnswer(index, null) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = option,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                if (request.allowFreeText) {
                    OutlinedTextField(
                        value = freeText,
                        onValueChange = { freeText = it },
                        label = { Text(stringResource(R.string.ask_user_free_text_label)) },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                    )
                }
            }
        },
        confirmButton = {
            if (request.allowFreeText) {
                TextButton(
                    onClick = { onAnswer(-1, freeText.trim()) },
                    enabled = canSend,
                ) {
                    Text(stringResource(R.string.ask_user_send))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.ask_user_skip))
            }
        },
    )
}