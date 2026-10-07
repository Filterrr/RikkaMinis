package com.openminis.app.ui.chat

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import com.openminis.app.ui.theme.ChatColors
import com.openminis.app.ui.theme.ToolAccents
import com.openminis.app.ui.theme.ToolAccentsDark

// [T-android-split-chat] Pure tool-label / duration / timestamp formatting
// helpers have been moved to ChatFormattingUtils.kt for JVM testability.
// This file retains only the Compose-dependent helpers.

// Helper: tool accent color (single source: ToolAccents / ToolAccentsDark).
//
// [ui-polish-A] Theme-aware. Every call site was already inside a composable,
// so reading ChatColors.isDark here makes all seven of them correct without
// touching a single caller. Light mode previously reused the iOS *system*
// colours verbatim; those are tuned for dark backgrounds and small accents on
// white lost their hue — shell green measured 2.22:1 and fileEdit orange
// 2.20:1 against white, both under the 3:1 non-text threshold, so a 14dp icon
// or a 3dp rail in those colours did not resolve. ToolAccentsDark keeps the
// original iOS values (they were already >=4.5:1 on the dark card).
@Composable
@ReadOnlyComposable
internal fun toolAccentColor(toolName: String): Color {
    val a = if (ChatColors.isDark) ToolAccentsDark else ToolAccents
    return when (toolName) {
        "shell_execute" -> a.shell
        "file_read" -> a.fileRead
        "file_write" -> a.fileWrite
        "file_edit" -> a.fileEdit
        "browser_use" -> a.browser
        "read_image" -> a.image
        "memory_write", "memory_get" -> a.memory
        "web_search" -> a.search
        // [T-subagent-ui] Sub-agent runs get a distinct violet accent so they
        // read as "another agent", not another tool. Orchestration tools share
        // the family accent. Both halves of the pair clear 3:1 on their own
        // background, so they stay literal rather than going through the table.
        "spawn_agent", "join_subagents", "wait_any" ->
            if (ChatColors.isDark) Color(0xFF7C5CFF) else Color(0xFF6D4AE0)
        "cancel_subagents" ->
            if (ChatColors.isDark) Color(0xFFE5484D) else Color(0xFFC4202B)
        else -> a.fallback
    }
}

// Helper: tool icon (iOS: distinct SF Symbols per tool type)
internal fun toolIconFor(toolName: String) = when (toolName) {
    "shell_execute" -> Icons.Default.Terminal
    "file_read" -> Icons.Default.Description         // iOS: doc.text
    "file_write" -> Icons.AutoMirrored.Filled.NoteAdd   // iOS: doc.text.fill (filled variant)
    "file_edit" -> Icons.Default.EditNote             // iOS: square.and.pencil
    "browser_use" -> Icons.Default.Language            // iOS: globe
    "read_image" -> Icons.Default.Image                // iOS: photo
    "memory_write", "memory_get" -> Icons.Default.Psychology // iOS: brain.head.profile
    "web_search" -> Icons.Default.Search               // iOS: magnifyingglass
    "spawn_agent", "join_subagents", "wait_any" -> Icons.Default.Radar  // [T-subagent-ui] / [T-subagent-orchestration]
    "cancel_subagents" -> Icons.Default.Error
    else -> Icons.Default.Build
}