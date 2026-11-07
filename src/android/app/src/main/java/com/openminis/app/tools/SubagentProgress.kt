package com.openminis.app.tools

import com.openminis.app.tools.SubagentRunRegistry.Run
import com.openminis.app.tools.SubagentRunRegistry.ToolStepStatus

/**
 * [port/upstream-subagent-progress] Mid-run progress notes for a DETACHED
 * sub-agent: the parent learns that a long run is alive and what it is doing,
 * instead of inferring it from silence.
 *
 * Upstream (OpenMinis `progress_report`) offers `none | frequent | moderate`.
 * The mechanism is a PUSH — the agent injects a message into the parent
 * conversation — and that is exactly why it is opt-in here:
 *
 *  - a push costs the parent a full turn, and this codebase deliberately keeps
 *    parent turns expensive (`subagent_status` exists so the parent can PULL
 *    the same facts at the moment it needs them, for free);
 *  - so the default stays `none`, and even when asked for, the reporter is
 *    bounded twice: by the tick interval AND by [MAX_PROGRESS_MESSAGES].
 *
 * Two policies are inherited from upstream because both were learned the hard
 * way there:
 *
 *  1. `frequent` reports **only on change**. A run that spends three minutes
 *     inside one `shell_execute` would otherwise send twelve identical notes —
 *     twelve parent turns saying nothing.
 *  2. `moderate` reports **regardless**, once a minute. It is the "I do not
 *     care what changed, tell me it is alive" setting.
 *
 * Pure: no clock, no coroutines, no Android — the caller supplies `nowMs`, which
 * is what makes the exact cadence unit-testable.
 */
object SubagentProgress {

    enum class Level(val wire: String) {
        NONE("none"),
        FREQUENT("frequent"),
        MODERATE("moderate"),
    }

    /**
     * Hard cap on notes per run.
     *
     * Not a nicety: each note is a parent turn. Twelve `frequent` notes cover
     * three minutes of active change (or far longer if the run is quiet), and
     * a run past that is better served by `subagent_status` than by a stream
     * of pushes. The cap is announced in the last note so the parent knows the
     * silence afterwards is expected.
     */
    const val MAX_PROGRESS_MESSAGES = 12

    const val FREQUENT_INTERVAL_MS = 15_000L
    const val MODERATE_INTERVAL_MS = 60_000L

    /**
     * How often the reporter wakes up to consider a note. Deliberately much
     * shorter than either interval: the reporter does not send at this rate, it
     * only re-reads the run so a change is noticed promptly.
     */
    const val TICK_MS = 5_000L

    /** Last-message preview length inside a note. */
    const val PREVIEW_CHARS = 200

    /** Unknown or blank input degrades to NONE — never to a chatty default. */
    fun parse(raw: String?): Level = when (raw?.trim()?.lowercase()) {
        "frequent" -> Level.FREQUENT
        "moderate" -> Level.MODERATE
        else -> Level.NONE
    }

    fun intervalMs(level: Level): Long = when (level) {
        Level.FREQUENT -> FREQUENT_INTERVAL_MS
        Level.MODERATE -> MODERATE_INTERVAL_MS
        Level.NONE -> Long.MAX_VALUE
    }

    /**
     * What counts as "something changed".
     *
     * The in-flight tool, the turn, and the last message. Deliberately NOT the
     * step count or the elapsed clock: a run sitting inside one long
     * `shell_execute` is unchanged, and reporting a moving clock would defeat
     * rule 1 above.
     */
    fun signature(run: Run): String {
        val active = run.steps.lastOrNull { it.status == ToolStepStatus.RUNNING }
            ?: run.steps.lastOrNull()
        val tail = run.resultText.takeLast(PREVIEW_CHARS).replace(Regex("\\s+"), " ").trim()
        return listOf(
            active?.toolName ?: "",
            active?.toolTitle ?: "",
            run.turn.toString(),
            tail,
        ).joinToString("|")
    }

    /**
     * Whether a note is due.
     *
     * [lastAtMs] is the previous send (or the SPAWN time when nothing has been
     * sent yet). The caller seeds it with the spawn time, so the first note
     * lands one interval AFTER the spawn: a note five seconds in says nothing
     * the spawn result did not already say, and still costs a parent turn.
     * `0` therefore means "no previous send" and satisfies the interval gate
     * immediately — a total function for a case the reporter never produces.
     */
    fun shouldEmit(
        level: Level,
        nowMs: Long,
        lastAtMs: Long,
        signature: String,
        lastSignature: String,
        sentCount: Int = 0,
    ): Boolean {
        if (level == Level.NONE) return false
        if (sentCount >= MAX_PROGRESS_MESSAGES) return false
        if (lastAtMs > 0 && nowMs - lastAtMs < intervalMs(level)) return false
        return when (level) {
            // Change-gated: a run inside one long command sends nothing.
            Level.FREQUENT -> signature != lastSignature
            // Liveness-gated: "tell me it is alive", whatever changed.
            Level.MODERATE -> true
            Level.NONE -> false
        }
    }

    /**
     * The note itself, already fenced.
     *
     * It is delivered as a USER message in the parent conversation, so it goes
     * through the same data boundary as a wake-up: the body can quote whatever
     * the sub-agent read.
     */
    fun prompt(run: Run, nowMs: Long, sentCount: Int = 0): String =
        wrapAsData(body(run, nowMs, sentCount), run.id)

    /** The unfenced body — public so the wording is testable on its own. */
    fun body(run: Run, nowMs: Long, sentCount: Int = 0): String = buildString {
        append("[Background task progress]")
        append("\nrun:   #").append(SubagentStatus.shortId(run.id))
        if (run.title.isNotBlank()) append(" · ").append(run.title.trim())
        if (run.skillName.isNotBlank()) append(" · ").append(run.skillName)
        append("\nstate: ").append(SubagentStatus.stateLabel(run))
        append(" (").append(SubagentStatus.elapsedLabel(SubagentStatus.elapsedMs(run, nowMs))).append(")")
        if (run.turn > 0) {
            append(", turn ").append(run.turn)
            if (run.maxTurns > 0) append("/").append(run.maxTurns)
        }
        SubagentStatus.currentActivity(run)?.let { append("\nnow:   ").append(it) }
        if (run.modelLabel.isNotBlank()) append("\nmodel: ").append(run.modelLabel)
        SubagentStatus.costLabel(run)?.let { append("\ntokens: ").append(it) }
        val text = run.resultText.trim().replace(Regex("\\s+"), " ")
        if (text.isNotEmpty()) {
            append("\nlatest: ")
            append(if (text.length <= PREVIEW_CHARS) text else text.take(PREVIEW_CHARS).trimEnd() + "…")
        }

        // Say what this note is NOT. Without this line a model treats a
        // progress note as a deliverable and starts answering it.
        append("\n\nNo action needed — this is a progress note, not the result. ")
        append("The run is still working and will deliver its own report when it finishes; ")
        append("do not poll it. ")
        val remaining = MAX_PROGRESS_MESSAGES - sentCount
        if (remaining <= 1) {
            append("This is the last progress note for this run (bounded on purpose) — ")
            append("use subagent_status if you need its state later.")
        }
    }
}