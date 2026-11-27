package com.openminis.app.ui.chat

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R

/**
 * [T-clipboard-suggest] 剪贴板 → 输入框的一键插入。
 *
 * 只在 App 处于前台时监听（Android 10+ 本来也只允许前台读剪贴板），并且
 * **不落盘**：内容只活在这个 composable 的 state 里，关掉 App 就没了。
 *
 * 触发条件刻意保守：4 字符以上、4KB 以下、跟当前输入框不同、用户刚点过
 * "×" 的那一段不再提示。否则每复制一个单词都弹一个条，比没有还烦。
 *
 * 不自动发送——只填进输入框，发送永远是用户按下去的那个动作。
 */
@Composable
internal fun ClipboardSuggestionChip(
    context: Context,
    currentInput: String,
    onInsert: (String) -> Unit,
) {
    // 监听器是在 DisposableEffect 里注册一次的长生命周期 lambda，
    // 直接捕获 currentInput 会永远读到注册那一刻的值。
    val latestInput by rememberUpdatedState(currentInput)
    var suggestion by remember { mutableStateOf<String?>(null) }
    var dismissed by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val listener = ClipboardManager.OnPrimaryClipChangedListener {
            val clip = cm?.primaryClip ?: return@OnPrimaryClipChangedListener
            if (clip.itemCount == 0) return@OnPrimaryClipChangedListener
            val text = runCatching {
                clip.getItemAt(0)?.coerceToText(context)?.toString()?.trim().orEmpty()
            }.getOrDefault("")
            if (text.length in MIN_LENGTH..MAX_LENGTH &&
                text != latestInput.trim() &&
                text != dismissed
            ) {
                suggestion = text
            }
        }
        cm?.addPrimaryClipChangedListener(listener)
        onDispose { cm?.removePrimaryClipChangedListener(listener) }
    }

    val pending = suggestion ?: return

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 10.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
        ) {
            Text(
                text = stringResource(R.string.clipboard_suggest_label),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                text = pending.replace('\n', ' '),
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = {
                onInsert(pending)
                suggestion = null
            }) {
                Text(stringResource(R.string.clipboard_suggest_insert), fontSize = 12.sp)
            }
            IconButton(
                onClick = {
                    dismissed = pending
                    suggestion = null
                },
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.clipboard_suggest_dismiss),
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

private const val MIN_LENGTH = 4
private const val MAX_LENGTH = 4000