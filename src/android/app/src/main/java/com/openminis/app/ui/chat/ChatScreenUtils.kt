package com.openminis.app.ui.chat

/**
 * Pure utility functions extracted from [ChatScreen] for JVM testability.
 *
 * [ChatScreen] itself is a 6000-line @Composable function with Android
 * dependencies. These functions are pure logic extracted so they can be
 * tested without any Android or Compose runtime.
 */

/**
 * Strip dedupe suffix from a flat-chat-item message id.
 * `"msgId#2"` → `"msgId"`, `"msgId"` → `"msgId"`.
 */
internal fun originalMessageId(id: String): String =
    id.substringBefore('#')