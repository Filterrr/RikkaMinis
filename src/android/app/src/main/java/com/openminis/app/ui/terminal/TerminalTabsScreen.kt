package com.openminis.app.ui.terminal

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.sandbox.TerminalSession

/**
 * [T-terminal-tabs] 多标签终端。
 *
 * 每个标签 = 一个独立的 [TerminalSession]（独立 PTY + 独立 proot shell）。
 * 切换标签时 composable 会被重建，所以 [TerminalScreen] 传
 * `manageLifecycle = false`：底层 shell 必须继续活着，由这个容器统一收尾。
 *
 * 上限 [MAX_TABS]：每个标签是一个常驻 proot 进程，而项目对 RSS 很敏感
 * （历史上多会话并发把主进程推到 5.8GB 触发过 SIGABRT）。开满就拒绝新建，
 * 而不是悄悄放任内存增长。
 */
private const val MAX_TABS = 4

private data class TerminalTab(
    val id: String,
    val title: String,
    val session: TerminalSession,
    /** 只有第一个标签吃 initCommand，避免每次切换都重新注入。 */
    val seededInit: Boolean = false,
)

@Composable
fun TerminalTabsScreen(
    context: Context,
    onBack: () -> Unit,
    initCommand: String? = null,
    sessionId: String? = null,
) {
    val tabs = remember { mutableStateListOf<TerminalTab>() }
    var activeId by remember { mutableStateOf<String?>(null) }
    var nextIndex by remember { mutableStateOf(1) }

    // 首个标签：从深链 / 工具 pill 带着 initCommand 进来时就用它。
    LaunchedEffect(Unit) {
        if (tabs.isEmpty()) {
            val tab = TerminalTab(
                id = "tab-${System.nanoTime()}",
                title = "1",
                session = TerminalSession(context.applicationContext),
                seededInit = true,
            )
            tabs += tab
            activeId = tab.id
        }
    }

    // 离开页面：停掉所有标签的 PTY。terminate 不掉的东西会变成孤儿 proot 进程。
    DisposableEffect(Unit) {
        onDispose {
            tabs.forEach { runCatching { it.session.stop() } }
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .height(36.dp)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEach { tab ->
                val active = tab.id == activeId
                Row(
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            if (active) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant,
                        )
                        .clickable { activeId = tab.id }
                        .padding(start = 8.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = tab.title,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (tabs.size > 1) {
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .clickable {
                                    // 关闭：停 PTY，摘掉标签，必要时切换活动标签。
                                    runCatching { tab.session.stop() }
                                    tabs.remove(tab)
                                    if (activeId == tab.id) {
                                        activeId = tabs.lastOrNull()?.id
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close tab",
                                modifier = Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            if (tabs.size < MAX_TABS) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            val title = (nextIndex).toString()
                            val tab = TerminalTab(
                                id = "tab-${System.nanoTime()}",
                                title = title,
                                session = TerminalSession(context.applicationContext),
                            )
                            nextIndex += 1
                            tabs += tab
                            activeId = tab.id
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "New tab",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        val active = tabs.firstOrNull { it.id == activeId } ?: return@Column
        key(active.id) {
            TerminalScreen(
                terminalSession = active.session,
                onBack = onBack,
                initCommand = if (active.seededInit) initCommand else null,
                sessionId = sessionId,
                manageLifecycle = false,
            )
        }
    }
}