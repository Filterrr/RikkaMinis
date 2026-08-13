package com.openminis.app.ui.chat

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.ui.graphics.Color

// [T-android-split-chat] Pure tool-label / duration / timestamp formatting
// helpers have been moved to ChatFormattingUtils.kt for JVM testability.
// This file retains only the Compose-dependent helpers.

// Helper: tool accent color
internal fun toolAccentColor(toolName: String): Color = when (toolName) {
    "shell_execute" -> Color(0xFF34C759)
    "file_read" -> Color(0xFF32ADE6)
    "file_write" -> Color(0xFF007AFF)
    "file_edit" -> Color(0xFFFF9500)
    "browser_use" -> Color(0xFF007AFF)
    "read_image" -> Color(0xFFAF52DE)
    "memory_write", "memory_get" -> Color(0xFFFF2D55)
    "web_search" -> Color(0xFF32ADE6)    // iOS: .cyan for search
    else -> Color(0xFF8E8E93)
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
    else -> Icons.Default.Build
}