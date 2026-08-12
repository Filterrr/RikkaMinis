package com.openminis.app.macro

import org.json.JSONArray
import org.json.JSONObject

/**
 * A single step in a macro recording — one user (or agent) action.
 *
 * Serialised as JSON for storage / export / replay.
 */
data class MacroAction(
    /** Action type: "tap", "input", "scroll", "swipe", "key", "wait", "pinch", "screenshot" */
    val type: String,
    /** Target element (for tap / input / scroll targeting a specific UI element) */
    val target: MacroTarget? = null,
    /** Text payload (input text, key event name, text to wait for) */
    val text: String? = null,
    /** Scroll / swipe direction */
    val direction: String? = null,
    /** Coordinates (swipe, pinch, xy tap) */
    val x: Float? = null,
    val y: Float? = null,
    val x2: Float? = null,
    val y2: Float? = null,
    /** Duration in ms (gestures, wait) */
    val durationMs: Long = 0,
    /** Delay BEFORE this action in ms (default 0) */
    val preDelayMs: Long = 0,
    /** Human-readable description of this step */
    val description: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("type", type)
        target?.let { put("target", it.toJson()) }
        text?.let { put("text", it) }
        direction?.let { put("direction", it) }
        x?.let { put("x", it.toDouble()) }
        y?.let { put("y", it.toDouble()) }
        x2?.let { put("x2", it.toDouble()) }
        y2?.let { put("y2", it.toDouble()) }
        put("durationMs", durationMs)
        put("preDelayMs", preDelayMs)
        description?.let { put("description", it) }
    }

    companion object {
        fun fromJson(obj: JSONObject): MacroAction = MacroAction(
            type = obj.getString("type"),
            target = obj.optJSONObject("target")?.let { MacroTarget.fromJson(it) },
            text = obj.optString("text", null),
            direction = obj.optString("direction", null),
            x = if (obj.has("x")) obj.getDouble("x").toFloat() else null,
            y = if (obj.has("y")) obj.getDouble("y").toFloat() else null,
            x2 = if (obj.has("x2")) obj.getDouble("x2").toFloat() else null,
            y2 = if (obj.has("y2")) obj.getDouble("y2").toFloat() else null,
            durationMs = obj.optLong("durationMs", 0L),
            preDelayMs = obj.optLong("preDelayMs", 0L),
            description = obj.optString("description", null),
        )
    }
}

/**
 * Describes how to locate a UI element for replay.
 *
 * Strategy precedence: text > text-contains > desc > id > xy
 */
data class MacroTarget(
    /** Locator strategy: "text", "text-contains", "desc", "desc-contains", "id", "xy" */
    val strategy: String,
    /** The locator value (text, resource-id, or "x,y" for xy) */
    val value: String,
    /** Fallback coordinates (x,y) when a11y text matching fails */
    val fallbackX: Int? = null,
    val fallbackY: Int? = null,
    /** The original a11y nodeId that was captured (for reference, may be stale on replay) */
    val originalNodeId: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("strategy", strategy)
        put("value", value)
        fallbackX?.let { put("fallbackX", it) }
        fallbackY?.let { put("fallbackY", it) }
        originalNodeId?.let { put("originalNodeId", it) }
    }

    companion object {
        fun fromJson(obj: JSONObject): MacroTarget = MacroTarget(
            strategy = obj.getString("strategy"),
            value = obj.getString("value"),
            fallbackX = if (obj.has("fallbackX")) obj.getInt("fallbackX") else null,
            fallbackY = if (obj.has("fallbackY")) obj.getInt("fallbackY") else null,
            originalNodeId = obj.optString("originalNodeId", null),
        )
    }
}

/**
 * A complete macro recording — a named sequence of actions.
 */
data class MacroRecording(
    val name: String,
    val createdAt: Long,
    val actions: List<MacroAction>,
    val description: String? = null,
    /** The app/package this macro was recorded in */
    val packageName: String? = null,
    /** Number of times this macro has been replayed */
    val replayCount: Int = 0,
    /** Last replay timestamp */
    val lastReplayedAt: Long? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("createdAt", createdAt)
        put("actions", JSONArray().apply { for (a in actions) put(a.toJson()) })
        description?.let { put("description", it) }
        packageName?.let { put("packageName", it) }
        put("replayCount", replayCount)
        lastReplayedAt?.let { put("lastReplayedAt", it) }
    }

    fun withIncrementedReplay(): MacroRecording = copy(
        replayCount = replayCount + 1,
        lastReplayedAt = System.currentTimeMillis(),
    )

    companion object {
        fun fromJson(obj: JSONObject): MacroRecording {
            val actionsArr = obj.getJSONArray("actions")
            val actions = (0 until actionsArr.length()).map { MacroAction.fromJson(actionsArr.getJSONObject(it)) }
            return MacroRecording(
                name = obj.getString("name"),
                createdAt = obj.getLong("createdAt"),
                actions = actions,
                description = obj.optString("description", null),
                packageName = obj.optString("packageName", null),
                replayCount = obj.optInt("replayCount", 0),
                lastReplayedAt = if (obj.has("lastReplayedAt")) obj.getLong("lastReplayedAt") else null,
            )
        }
    }
}