package com.openminis.app.macro

import android.graphics.Path
import android.graphics.Rect
import com.openminis.app.accessibility.MinisAccessibilityService
import com.openminis.app.logging.AppLogger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Replays a macro recording by executing actions via the
 * AccessibilityService.
 *
 * Supports:
 * - Type-based replay: tap by text, desc, id, or coordinates
 * - Sensitive guard (B) integrated
 * - Pre-delay between actions
 * - Loop mode
 * - Cancellation support
 */
class MacroPlayer(
    private val svc: MinisAccessibilityService,
    private val sensitiveGuard: SensitiveGuard = SensitiveGuard(),
) {
    companion object {
        private const val TAG = "MacroPlayer"
        /** Default delay between actions in ms */
        private const val DEFAULT_ACTION_DELAY_MS = 500L
        /** Max loop count to prevent runaway */
        private const val MAX_LOOP = 100
    }

    private val _cancelled = AtomicBoolean(false)

    /** Cancel the current replay. */
    fun cancel() { _cancelled.set(true) }

    /** True if a replay is being cancelled. */
    val isCancelled: Boolean get() = _cancelled.get()

    /** Result of a replay step. */
    sealed class StepResult {
        data class Success(val action: MacroAction, val index: Int) : StepResult()
        data class Failure(val action: MacroAction, val index: Int, val error: String) : StepResult()
        data class Blocked(val action: MacroAction, val index: Int, val reason: String) : StepResult()
        data class Cancelled(val action: MacroAction?, val index: Int) : StepResult()
    }

    /**
     * Replay a macro. Returns the final outcome.
     *
     * @param recording The macro to replay
     * @param loops Number of times to repeat (default 1)
     * @param actionDelayMs Delay between actions in ms
     * @param onStep Callback after each step (for progress reporting)
     */
    fun replay(
        recording: MacroRecording,
        loops: Int = 1,
        actionDelayMs: Long = DEFAULT_ACTION_DELAY_MS,
        onStep: ((StepResult) -> Unit)? = null,
    ): ReplayOutcome {
        _cancelled.set(false)
        val totalLoops = loops.coerceIn(1, MAX_LOOP)
        var totalActions = 0
        var failures = 0
        var blocks = 0

        for (loop in 1..totalLoops) {
            if (_cancelled.get()) {
                return ReplayOutcome(
                    success = false,
                    totalActions = totalActions,
                    failures = failures,
                    blocks = blocks,
                    error = "Cancelled by user at loop $loop",
                )
            }
            for ((i, action) in recording.actions.withIndex()) {
                if (_cancelled.get()) {
                    return ReplayOutcome(
                        success = false,
                        totalActions = totalActions,
                        failures = failures,
                        blocks = blocks,
                        error = "Cancelled by user at action ${i + 1}",
                    )
                }
                totalActions++

                // 1. Sensitive guard check
                val guardCheck = sensitiveGuard.check()
                if (guardCheck.blocked) {
                    blocks++
                    val result = StepResult.Blocked(action, i, guardCheck.reason ?: "Sensitive page detected")
                    onStep?.invoke(result)
                    return ReplayOutcome(
                        success = false,
                        totalActions = totalActions,
                        failures = failures,
                        blocks = blocks,
                        error = "Blocked by sensitive guard: ${guardCheck.reason}",
                    )
                }

                val actionGuard = sensitiveGuard.checkAction(action)
                if (actionGuard.blocked) {
                    blocks++
                    val result = StepResult.Blocked(action, i, actionGuard.reason ?: "Sensitive action detected")
                    onStep?.invoke(result)
                    return ReplayOutcome(
                        success = false,
                        totalActions = totalActions,
                        failures = failures,
                        blocks = blocks,
                        error = "Blocked by sensitive guard: ${actionGuard.reason}",
                    )
                }

                // 2. Pre-delay
                if (action.preDelayMs > 0) {
                    Thread.sleep(action.preDelayMs)
                }

                // 3. Execute action
                val ok = executeAction(action)
                if (!ok) {
                    failures++
                    val result = StepResult.Failure(action, i, "Action failed")
                    onStep?.invoke(result)
                    return ReplayOutcome(
                        success = false,
                        totalActions = totalActions,
                        failures = failures,
                        blocks = blocks,
                        error = "Action ${i + 1} (${action.type}) failed",
                    )
                }

                // 4. Post-action delay
                if (actionDelayMs > 0) {
                    Thread.sleep(actionDelayMs)
                }

                onStep?.invoke(StepResult.Success(action, i))
            }
        }

        return ReplayOutcome(
            success = failures == 0 && blocks == 0,
            totalActions = totalActions,
            failures = failures,
            blocks = blocks,
        )
    }

    private fun executeAction(action: MacroAction): Boolean {
        return try {
            when (action.type) {
                "tap" -> executeTap(action)
                "input" -> executeInput(action)
                "scroll" -> executeScroll(action)
                "swipe" -> executeSwipe(action)
                "key" -> executeKey(action)
                "wait" -> executeWait(action)
                "pinch" -> executePinch(action)
                "screenshot" -> true // screenshot is a no-op during replay
                else -> {
                    AppLogger.warning(TAG, "unknown action type: ${action.type}")
                    false
                }
            }
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "action error: ${action.type} — ${t.message}")
            false
        }
    }

    private fun executeTap(action: MacroAction): Boolean {
        val target = action.target
        if (target == null) return false

        return when (target.strategy) {
            "text" -> tapByText(target.value, exact = true)
            "text-contains" -> tapByText(target.value, exact = false)
            "desc" -> tapByDesc(target.value, exact = true)
            "desc-contains" -> tapByDesc(target.value, exact = false)
            "id" -> tapById(target.value)
            "xy" -> {
                val parts = target.value.split(",")
                if (parts.size == 2) {
                    val x = parts[0].toFloatOrNull() ?: return false
                    val y = parts[1].toFloatOrNull() ?: return false
                    tapXY(x, y)
                } else {
                    // Fallback to stored fallback coordinates
                    val fx = target.fallbackX ?: return false
                    val fy = target.fallbackY ?: return false
                    tapXY(fx.toFloat(), fy.toFloat())
                }
            }
            else -> {
                // Fallback: try text-contains, then coordinates
                if (target.fallbackX != null && target.fallbackY != null) {
                    tapXY(target.fallbackX.toFloat(), target.fallbackY.toFloat())
                } else {
                    false
                }
            }
        }
    }

    private fun tapByText(text: String, exact: Boolean): Boolean {
        for (root in svc.rootNodes()) {
            val matches = mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
            findByText(root, text, exact, 20, 0, matches)
            val node = matches.firstOrNull() ?: continue
            if (node.isClickable) {
                return node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
            }
            // Walk up to find a clickable parent
            return node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
        }
        return false
    }

    private fun tapByDesc(text: String, exact: Boolean): Boolean {
        for (root in svc.rootNodes()) {
            val matches = mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
            findByDesc(root, text, exact, 20, 0, matches)
            val node = matches.firstOrNull() ?: continue
            return node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
        }
        return false
    }

    private fun tapById(resId: String): Boolean {
        for (root in svc.rootNodes()) {
            val matches = mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
            findById(root, resId, 20, 0, matches)
            val node = matches.firstOrNull() ?: continue
            if (node.isClickable) {
                return node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
            }
            // Try center click as fallback
            val rect = Rect().also { node.getBoundsInScreen(it) }
            return tapXY(rect.centerX().toFloat(), rect.centerY().toFloat())
        }
        return false
    }

    private fun tapXY(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        return svc.dispatchSimpleGesture(path, 0L, 100L)
    }

    private fun executeInput(action: MacroAction): Boolean {
        val text = action.text ?: return false
        val target = action.target
        if (target != null) {
            // Find the editable field and set text
            for (root in svc.rootNodes()) {
                val matches = mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
                findEditable(root, 20, 0, matches)
                if (matches.isNotEmpty()) {
                    return svc.setNodeText(matches.first(), text)
                }
            }
        }
        // If no target or no editable field found, try the focused node
        for (root in svc.rootNodes()) {
            val focused = findFocused(root, 20, 0)
            if (focused != null) {
                return svc.setNodeText(focused, text)
            }
        }
        return false
    }

    private fun executeScroll(action: MacroAction): Boolean {
        val direction = action.direction ?: "down"
        val actionId = when (direction.lowercase()) {
            "down", "right" -> android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            "up", "left" -> android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            else -> return false
        }
        for (root in svc.rootNodes()) {
            val scrollable = findFirstScrollable(root, 20, 0)
            if (scrollable != null) {
                return scrollable.performAction(actionId)
            }
        }
        return false
    }

    private fun executeSwipe(action: MacroAction): Boolean {
        val x1 = action.x ?: return false
        val y1 = action.y ?: return false
        val x2 = action.x2 ?: return false
        val y2 = action.y2 ?: return false
        val duration = action.durationMs.coerceAtLeast(100L)
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        return svc.dispatchSimpleGesture(path, 0L, duration)
    }

    private fun executeKey(action: MacroAction): Boolean {
        // Key events are not supported via AccessibilityService directly.
        // For HOME/BACK/RECENTS, we could use GestureDescription or
        // fall back to shell commands. For now, log a warning.
        AppLogger.warning(TAG, "key action not supported: ${action.text}")
        return false
    }

    private fun executeWait(action: MacroAction): Boolean {
        val ms = action.durationMs.coerceIn(100, 30_000L)
        Thread.sleep(ms)
        return true
    }

    private fun executePinch(action: MacroAction): Boolean {
        val cx = action.x ?: return false
        val cy = action.y ?: return false
        val scale = action.text?.toFloatOrNull() ?: 0.5f
        val duration = action.durationMs.coerceAtLeast(200L)
        return svc.dispatchPinch(cx, cy, scale, duration)
    }

    // ── tree helpers ──────────────────────────────────────────────────

    private fun findByText(
        node: android.view.accessibility.AccessibilityNodeInfo?,
        text: String, exact: Boolean,
        maxDepth: Int, depth: Int, out: MutableList<android.view.accessibility.AccessibilityNodeInfo>,
    ) {
        if (node == null || depth > maxDepth) return
        val nodeText = node.text?.toString()
        if (nodeText != null) {
            if (exact && nodeText == text) out.add(node)
            else if (!exact && nodeText.contains(text)) out.add(node)
        }
        // Also check content description
        val desc = node.contentDescription?.toString()
        if (desc != null) {
            if (exact && desc == text) out.add(node)
            else if (!exact && desc.contains(text)) out.add(node)
        }
        for (i in 0 until node.childCount) {
            findByText(node.getChild(i), text, exact, maxDepth, depth + 1, out)
        }
    }

    private fun findByDesc(
        node: android.view.accessibility.AccessibilityNodeInfo?,
        text: String, exact: Boolean,
        maxDepth: Int, depth: Int, out: MutableList<android.view.accessibility.AccessibilityNodeInfo>,
    ) {
        if (node == null || depth > maxDepth) return
        val desc = node.contentDescription?.toString()
        if (desc != null) {
            if (exact && desc == text) out.add(node)
            else if (!exact && desc.contains(text)) out.add(node)
        }
        for (i in 0 until node.childCount) {
            findByDesc(node.getChild(i), text, exact, maxDepth, depth + 1, out)
        }
    }

    private fun findById(
        node: android.view.accessibility.AccessibilityNodeInfo?,
        id: String, maxDepth: Int, depth: Int,
        out: MutableList<android.view.accessibility.AccessibilityNodeInfo>,
    ) {
        if (node == null || depth > maxDepth) return
        if (node.viewIdResourceName == id) out.add(node)
        for (i in 0 until node.childCount) {
            findById(node.getChild(i), id, maxDepth, depth + 1, out)
        }
    }

    private fun findEditable(
        node: android.view.accessibility.AccessibilityNodeInfo?,
        maxDepth: Int, depth: Int,
        out: MutableList<android.view.accessibility.AccessibilityNodeInfo>,
    ) {
        if (node == null || depth > maxDepth) return
        if (node.isEditable) out.add(node)
        for (i in 0 until node.childCount) {
            findEditable(node.getChild(i), maxDepth, depth + 1, out)
        }
    }

    private fun findFocused(
        node: android.view.accessibility.AccessibilityNodeInfo?,
        maxDepth: Int, depth: Int,
    ): android.view.accessibility.AccessibilityNodeInfo? {
        if (node == null || depth > maxDepth) return null
        if (node.isFocused) return node
        for (i in 0 until node.childCount) {
            findFocused(node.getChild(i), maxDepth, depth + 1)?.let { return it }
        }
        return null
    }

    private fun findFirstScrollable(
        node: android.view.accessibility.AccessibilityNodeInfo?,
        maxDepth: Int, depth: Int,
    ): android.view.accessibility.AccessibilityNodeInfo? {
        if (node == null || depth > maxDepth) return null
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            findFirstScrollable(node.getChild(i), maxDepth, depth + 1)?.let { return it }
        }
        return null
    }
}

data class ReplayOutcome(
    val success: Boolean,
    val totalActions: Int,
    val failures: Int,
    val blocks: Int,
    val error: String? = null,
)