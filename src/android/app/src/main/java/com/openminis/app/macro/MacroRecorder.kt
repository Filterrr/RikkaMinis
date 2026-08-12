package com.openminis.app.macro

import android.view.accessibility.AccessibilityNodeInfo
import com.openminis.app.accessibility.MinisAccessibilityService
import com.openminis.app.logging.AppLogger
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Records a macro by watching accessibility events and capturing UI state.
 *
 * Usage:
 * ```
 * val recorder = MacroRecorder(accessibilityService, "record-settings-dark")
 * recorder.start()
 * // ... user performs actions ...
 * val recording = recorder.stop()
 * ```
 *
 * Each user action is captured as a [MacroAction] with a locator strategy
 * (text-based first, coordinate fallback) and a human-readable description.
 */
class MacroRecorder(
    private val svc: MinisAccessibilityService,
    private val macroName: String,
    private val description: String? = null,
) {
    companion object {
        private const val TAG = "MacroRecorder"
        /** Minimum time between consecutive events to be considered separate actions */
        private const val EVENT_DEBOUNCE_MS = 300L
    }

    private val actions = mutableListOf<MacroAction>()
    private val listener: (MinisAccessibilityService.RecordedEvent) -> Unit = { onEvent(it) }
    private var lastEventTime = 0L
    private var isActive = false
    private val capturedPackage = mutableSetOf<String>()

    /** Start recording. Returns true if service is available. */
    fun start(): Boolean {
        if (isActive) return false
        if (MinisAccessibilityService.getInstance() == null) return false
        isActive = true
        lastEventTime = System.currentTimeMillis()
        svc.addEventListener(listener)
        AppLogger.info(TAG, "started recording '$macroName'")
        return true
    }

    /** Stop recording and return the captured [MacroRecording]. */
    fun stop(): MacroRecording? {
        if (!isActive) return null
        isActive = false
        svc.removeEventListener(listener)
        val recording = MacroRecording(
            name = macroName,
            createdAt = System.currentTimeMillis(),
            actions = actions.toList(),
            description = description,
            packageName = capturedPackage.firstOrNull(),
        )
        AppLogger.info(TAG, "stopped recording '$macroName': ${actions.size} actions")
        return recording
    }

    /** True while recording is active. */
    val active: Boolean get() = isActive

    /** Current number of captured actions. */
    val actionCount: Int get() = actions.size

    // ── event handling ──────────────────────────────────────────────────

    private fun onEvent(event: MinisAccessibilityService.RecordedEvent) {
        val now = System.currentTimeMillis()
        if (now - lastEventTime < EVENT_DEBOUNCE_MS) return
        lastEventTime = now

        event.packageName?.let { capturedPackage.add(it) }

        val action = when {
            event.type.contains("CLICKED", ignoreCase = true) ||
            event.type.contains("LONG_CLICKED", ignoreCase = true) ||
            event.type.contains("PRESSED", ignoreCase = true) -> {
                captureTapAction(event)
            }
            event.type.contains("TEXT_CHANGED", ignoreCase = true) ||
            event.type.contains("TEXT_TRAVERSED_AT", ignoreCase = true) -> {
                captureInputAction(event)
            }
            event.type.contains("SCROLLED", ignoreCase = true) -> {
                captureScrollAction(event)
            }
            else -> null
        }
        if (action != null) {
            actions.add(action)
            AppLogger.info(TAG, "captured action ${actions.size}: ${action.type} ${action.description ?: ""}")
        }
    }

    private fun captureTapAction(event: MinisAccessibilityService.RecordedEvent): MacroAction {
        val target = resolveTarget(event)
        return MacroAction(
            type = "tap",
            target = target,
            description = "Tap ${event.text ?: event.className ?: "unknown"}",
        )
    }

    private fun captureInputAction(event: MinisAccessibilityService.RecordedEvent): MacroAction {
        val target = resolveTarget(event)
        return MacroAction(
            type = "input",
            target = target,
            text = event.text ?: "",
            description = "Input text into ${event.className ?: "field"}",
        )
    }

    private fun captureScrollAction(event: MinisAccessibilityService.RecordedEvent): MacroAction {
        val target = resolveTarget(event)
        return MacroAction(
            type = "scroll",
            target = target,
            description = "Scroll ${event.className ?: "container"}",
        )
    }

    /**
     * Resolve the best locator for the event target.
     * Strategy: text > text-contains > desc > id > xy
     */
    private fun resolveTarget(event: MinisAccessibilityService.RecordedEvent): MacroTarget? {
        // 1. Try to find the exact clicked node in the current UI tree
        val node = findClickedNode(event)
        if (node != null) {
            val text = node.text?.toString()
            val desc = node.contentDescription?.toString()
            val resId = node.viewIdResourceName
            val rect = android.graphics.Rect().also { node.getBoundsInScreen(it) }

            // Strategy: text match (exact) > desc match > text-contains > id > coordinates
            val strategy: String
            val value: String
            when {
                !text.isNullOrBlank() && text.length <= 100 -> {
                    strategy = "text"
                    value = text
                }
                !desc.isNullOrBlank() && desc.length <= 100 -> {
                    strategy = "desc"
                    value = desc
                }
                !text.isNullOrBlank() -> {
                    strategy = "text-contains"
                    value = text.take(80)
                }
                !resId.isNullOrBlank() -> {
                    strategy = "id"
                    value = resId
                }
                else -> {
                    strategy = "xy"
                    value = "${rect.centerX()},${rect.centerY()}"
                }
            }
            return MacroTarget(
                strategy = strategy,
                value = value,
                fallbackX = rect.centerX(),
                fallbackY = rect.centerY(),
            )
        }
        // 2. Fallback: use event text if available
        val text = event.text
        if (!text.isNullOrBlank()) {
            return MacroTarget(
                strategy = "text-contains",
                value = text.take(80),
            )
        }
        return null
    }

    /**
     * Try to find the node that was just interacted with, by matching
     * the event text against the current UI tree.
     */
    private fun findClickedNode(event: MinisAccessibilityService.RecordedEvent): AccessibilityNodeInfo? {
        val text = event.text
        if (text.isNullOrBlank()) return null
        val matches = mutableListOf<AccessibilityNodeInfo>()
        for (root in svc.rootNodes()) {
            findByTextOrDesc(root, text, contains = true, 20, 0, matches)
            if (matches.isNotEmpty()) break
        }
        return matches.firstOrNull()
    }

    private fun findByTextOrDesc(
        node: AccessibilityNodeInfo?,
        text: String,
        contains: Boolean,
        maxDepth: Int,
        depth: Int,
        out: MutableList<AccessibilityNodeInfo>,
    ) {
        if (node == null || depth > maxDepth) return
        val nodeText = node.text?.toString()
        val nodeDesc = node.contentDescription?.toString()
        val match = if (contains) {
            (nodeText?.contains(text) == true) || (nodeDesc?.contains(text) == true)
        } else {
            nodeText == text || nodeDesc == text
        }
        if (match) out.add(node)
        for (i in 0 until node.childCount) {
            findByTextOrDesc(node.getChild(i), text, contains, maxDepth, depth + 1, out)
        }
    }
}