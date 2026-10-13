package com.openminis.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(
    tableName = "sessions",
    indices = [
        // [backup-upstream-1.14] both indices ported from upstream 1.14 — the
        // entity MUST match what MIGRATION_13_14 / MIGRATION_14_15 create or
        // Room's open-time validation aborts startup.
        androidx.room.Index(value = ["folder_id"], name = "index_sessions_folder_id"),
        androidx.room.Index(value = ["updated_at"], name = "index_sessions_updated_at"),
    ],
)
data class ChatSessionEntity(
    @PrimaryKey val id: String,
    val title: String? = null,
    @ColumnInfo(name = "model_id") val modelId: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,        // milliseconds
    @ColumnInfo(name = "updated_at") val updatedAt: Long,        // milliseconds
    val category: String? = null,
    @ColumnInfo(name = "last_message") val lastMessage: String? = null,
    @ColumnInfo(name = "model_binding") val modelBinding: String? = null,
    // iOS parity fields:
    @ColumnInfo(name = "source") val source: String? = null,             // e.g. "shortcut", "share"
    @ColumnInfo(name = "memory_enabled") val memoryEnabled: Int = 1,     // 1=on, 0=off
    @ColumnInfo(name = "pinned_at") val pinnedAt: Long? = null,          // milliseconds, null=not pinned
    @ColumnInfo(name = "edit_count") val editCount: Int = 0,             // message edit counter
    // T239: per-session thinking-mode override. null = unset (use the
    // current model/group default — i.e. existing pre-T239 behaviour, which
    // is OFF on Android today). Non-null is one of ThinkingLevel.name
    // ("OFF"/"LOW"/"MEDIUM"/"HIGH"/"XHIGH") and represents an explicit user
    // choice that survives cold-start.
    @ColumnInfo(name = "thinking_override") val thinkingOverride: String? = null,
    // ---- [backup-upstream-1.14] ported from upstream 1.14 ----------------
    /** Session group (folders table) this session is filed under. Null = none. */
    @ColumnInfo(name = "folder_id") val folderId: String? = null,
    /** Parent session of a delegated child run (T-p1-delegate-task). */
    @ColumnInfo(name = "parent_session_id") val parentSessionId: String? = null,
    /** Tool-use id of the spawn call that created this child session. */
    @ColumnInfo(name = "parent_tool_use_id") val parentToolUseId: String? = null,
) {
    val isChild: Boolean get() = parentSessionId != null
}
