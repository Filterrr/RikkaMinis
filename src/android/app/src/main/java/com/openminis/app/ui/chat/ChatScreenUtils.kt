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

/**
 * Whether a flat chat item should render grayed-out (compacted history).
 * System rows ([FlatChatItem.AssistantInfo], [FlatChatItem.AssistantTyping])
 * are never grayed; every other row looks up its message id (dedupe suffix
 * stripped via [originalMessageId]) in [grayedMap].
 *
 * Extracted from ChatScreen's local `FlatChatItem.isCompacted()` extension so
 * the decision is JVM-testable without composing the screen.
 */
internal fun isCompactedItem(item: FlatChatItem, grayedMap: Map<String, Boolean>): Boolean = when (item) {
    is FlatChatItem.UserBubble -> grayedMap[originalMessageId(item.message.id)] == true
    is FlatChatItem.AssistantHeader -> grayedMap[originalMessageId(item.messageId)] == true
    is FlatChatItem.AssistantText -> grayedMap[originalMessageId(item.messageId)] == true
    is FlatChatItem.AssistantMarkdownBlock -> grayedMap[originalMessageId(item.messageId)] == true
    is FlatChatItem.AssistantThinking -> grayedMap[originalMessageId(item.messageId)] == true
    is FlatChatItem.AssistantToolUse -> grayedMap[originalMessageId(item.messageId)] == true
    is FlatChatItem.AssistantToolRunGroup -> grayedMap[originalMessageId(item.messageId)] == true
    is FlatChatItem.AssistantInfo -> false // system rows never grayed
    is FlatChatItem.AssistantTyping -> false
    is FlatChatItem.AssistantError -> grayedMap[originalMessageId(item.messageId)] == true
    is FlatChatItem.AssistantLegacyContent -> grayedMap[originalMessageId(item.messageId)] == true
}