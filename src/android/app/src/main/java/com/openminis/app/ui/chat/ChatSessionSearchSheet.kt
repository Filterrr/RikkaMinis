package com.openminis.app.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.repository.MessageSearchMatch
import com.openminis.app.ui.components.MinisTextButton
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * [T-session-search] 会话内全文搜索面板。
 *
 * 和顶栏的「历史输入」面板（[InputHistorySheet]）刻意分成两个东西：
 *  - 历史输入只过滤 **已加载进内存** 的消息，长会话里早期的轮次早就被
 *    compaction / 分页甩掉了，搜不到；
 *  - 这个面板走 `ChatRepository.searchMessages`，直接打持久化消息表，
 *    所以哪怕这条会话有几千轮，也能把三个月前那句话翻出来。
 *
 * 点击命中条目后回调 [onJump]（ChatScreen 把它写进 pendingFocusId），
 * 复用现有的「跳到某条消息并短暂高亮」原语——和 fork 血缘标签走同一条路。
 *
 * 搜索是防抖的（默认 300ms），空查询不查库；结果上限由调用方传的
 * [search] 实现决定（VM 里默认 50 条），超出的历史命中不渲染，避免一次
 * 几千条把 Compose 列表打爆。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatSessionSearchSheet(
    onDismiss: () -> Unit,
    onJump: (messageId: String) -> Unit,
    search: suspend (query: String) -> List<MessageSearchMatch>,
    debounceMs: Long = 300L,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<MessageSearchMatch>>(emptyList()) }
    var searchedQuery by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    // Guards against an out-of-order response: the coroutine for "ab" must not
    // overwrite the results of the later "abc". LaunchedEffect already cancels
    // the previous body on every query change, but the DB call is not
    // cancellable mid-flight, so the token is checked after it returns.
    var requestToken by remember { mutableStateOf(0) }

    LaunchedEffect(query) {
        val q = query.trim()
        if (q.isEmpty()) {
            results = emptyList()
            searchedQuery = ""
            loading = false
            return@LaunchedEffect
        }
        loading = true
        delay(debounceMs)
        val token = ++requestToken
        val found = runCatching { search(q) }.getOrElse { emptyList() }
        if (token != requestToken) return@LaunchedEffect
        results = found
        searchedQuery = q
        loading = false
    }

    val timeFormat = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    val trimmed = query.trim()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.session_search_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (trimmed.isNotEmpty() && !loading) {
                    Text(
                        stringResource(R.string.session_search_result_count, results.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.size(8.dp))
                }
                MinisTextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.input_history_done))
                }
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.session_search_hint)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                },
                trailingIcon = {
                    when {
                        loading -> CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                        query.isNotEmpty() -> IconButton(onClick = { query = "" }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                singleLine = true,
                shape = RoundedCornerShape(20.dp),
                textStyle = MaterialTheme.typography.bodySmall,
            )

            Spacer(Modifier.height(8.dp))

            when {
                trimmed.isEmpty() -> CenteredHint(stringResource(R.string.session_search_empty))
                results.isEmpty() && !loading -> CenteredHint(
                    stringResource(R.string.session_search_no_result, trimmed),
                )
                else -> LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    items(items = results, key = { it.messageId }) { hit ->
                        SearchHitRow(
                            hit = hit,
                            stamp = runCatching { timeFormat.format(Date(hit.createdAt)) }.getOrDefault(""),
                            onClick = {
                                onJump(hit.messageId)
                                onDismiss()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CenteredHint(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.6f),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
    }
}

@Composable
private fun SearchHitRow(
    hit: MessageSearchMatch,
    stamp: String,
    onClick: () -> Unit,
) {
    val isUser = hit.role == "user"
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isUser) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(
                        if (isUser) R.string.session_search_role_user
                        else R.string.session_search_role_assistant,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = stamp,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = hit.snippet.trim().ifBlank { "[...]" },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}