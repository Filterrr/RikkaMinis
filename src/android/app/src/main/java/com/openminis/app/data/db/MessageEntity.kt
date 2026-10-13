package com.openminis.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ChatSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index(value = ["session_id", "sort_order"], unique = true)]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    val role: String,
    @ColumnInfo(name = "parts_json") val partsJson: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,        // milliseconds
    @ColumnInfo(name = "token_usage") val tokenUsage: String? = null,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    @ColumnInfo(name = "reasoning_content") val reasoningContent: String? = null,
    // iOS parity fields:
    @ColumnInfo(name = "stream_interrupt_count") val streamInterruptCount: Int = 0,
    @ColumnInfo(name = "updated_at") val updatedAt: Long? = null,        // milliseconds
    // [T-error-persist-android] Terminal error sticker for an assistant turn
    // (mirrors iOS messages.error_info / ChatMessage.error). Null for normal
    // rows; device-local, never synced to iCloud.
    @ColumnInfo(name = "error_info") val errorInfo: String? = null,
    // [T-usage-attribution] Identity of the provider/model that actually
    // produced this turn's token usage (set at persist time, after fallback
    // resolution). Null for legacy rows → allUsageRecords falls back to the
    // session's current model_id.
    @ColumnInfo(name = "usage_model_id") val usageModelId: String? = null,
    @ColumnInfo(name = "usage_entry_id") val usageEntryId: String? = null,
    // ---- [backup-upstream-1.14] per-turn attribution snapshot columns ----
    // Ported from upstream 1.14 (their T-token-attribution-snapshot): the
    // backup/restore record mapper restores these per-message fields. The
    // fork keeps its own usage_model_id/usage_entry_id (v11→12) alongside —
    // both column sets coexist; new columns are additive and nullable.
    // MIGRATION_14_15 also adds these columns to existing installs.
    @ColumnInfo(name = "model_id") val modelId: String? = null,
    @ColumnInfo(name = "model_display_name") val modelDisplayName: String? = null,
    /** `ProviderType` rawValue (e.g. `openAI`), never a display name. */
    @ColumnInfo(name = "provider_type") val providerType: String? = null,
    @ColumnInfo(name = "provider_instance_id") val providerInstanceId: String? = null,
)
