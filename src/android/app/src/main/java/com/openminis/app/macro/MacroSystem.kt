package com.openminis.app.macro

import android.content.Context
import com.openminis.app.accessibility.MinisAccessibilityService
import com.openminis.app.logging.AppLogger

/**
 * Facade that ties MacroRecorder, MacroPlayer, MacroStorage, and
 * SensitiveGuard together.
 *
 * One instance per AccessibilityOffloadHandler (lazy, app-scoped).
 */
class MacroSystem(context: Context) {

    companion object {
        private const val TAG = "MacroSystem"
    }

    val storage: MacroStorage = MacroStorage(context)
    val guard: SensitiveGuard = SensitiveGuard()

    private var activeRecorder: MacroRecorder? = null

    /** Start recording a macro. Returns false if already recording. */
    fun startRecording(name: String, description: String?, svc: MinisAccessibilityService): Boolean {
        if (activeRecorder != null) return false
        val recorder = MacroRecorder(svc, name, description)
        if (!recorder.start()) return false
        activeRecorder = recorder
        return true
    }

    /** Stop recording and return the recording (null if not recording). */
    fun stopRecording(): MacroRecording? {
        val recorder = activeRecorder ?: return null
        activeRecorder = null
        return recorder.stop()
    }

    /** True while recording is active. */
    val isRecording: Boolean get() = activeRecorder != null

    /** Replay a macro. */
    fun replay(
        recording: MacroRecording,
        svc: MinisAccessibilityService,
        loops: Int = 1,
        actionDelayMs: Long = 500L,
        useGuard: Boolean = true,
    ): ReplayOutcome {
        val player = if (useGuard) MacroPlayer(svc, guard) else MacroPlayer(svc)
        val outcome = player.replay(recording, loops, actionDelayMs)
        if (!outcome.success) {
            AppLogger.warning(TAG, "replay failed: ${outcome.error ?: "unknown"}")
        }
        return outcome
    }
}