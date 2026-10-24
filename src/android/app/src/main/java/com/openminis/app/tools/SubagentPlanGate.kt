package com.openminis.app.tools

/**
 * [fix/subagent-plan-execute] Decides what a TOOL-LESS model turn means once
 * the plan-first flow is in play.
 *
 * The bug this closes: `PLAN_DIRECTIVE` told the model "the plan turn makes
 * NO tool calls — outline only, then STOP. Execution starts next turn", while
 * the runner's loop is driven ENTIRELY by tool calls (`toolCalls.isEmpty()` →
 * `break`). Those two rules are mutually exclusive: a model that follows the
 * directive ends the run instead of starting it, and the parent receives a
 * SUCCESS report containing nothing but a `<task-plan>` outline — no command
 * was ever run, no file ever read. On short single-step tasks a cheap model
 * almost always obeys the directive, so the failure looked like "the sub-agent
 * is lazy" rather than "the runtime stops the run at the first tool-less
 * turn".
 *
 * The fix keeps the plan-first UX (the checklist still renders on the detail
 * page) but makes the plan turn NON-Terminal: when a turn produces no tool
 * calls and the plan has just been captured (or was never captured at all),
 * the loop continues with an execution nudge instead of stopping.
 *
 * Bounded by construction: [Decision.NUDGE_TO_EXECUTE] can be returned at most
 * once per run (`nudgeIssued`), so a model that keeps talking without acting
 * still terminates instead of spinning through its whole turn budget.
 *
 * Pure + JVM-testable: no Android, no registry, no coroutines.
 */
object SubagentPlanGate {

    enum class Decision {
        /**
         * Append an execution nudge and run another turn. The model has
         * planned (or failed to plan) but produced no work yet.
         */
        NUDGE_TO_EXECUTE,

        /** Stop the loop: the model is genuinely done, or plan_first is off. */
        FINISH,
    }

    /**
     * The user-role message appended after a tool-less plan turn. Phrased as a
     * directive, not a suggestion — the failure mode being fixed is a model
     * that treats "plan recorded" as "task complete". It also states the stop
     * condition explicitly (no tool call → the run ends), because that is the
     * mechanic the model was reasoning incorrectly about.
     *
     * [fix/subagent-plan-contract] Delegates to [SubagentPlanContract] — the
     * single source of truth shared with the system prompt directive, so the
     * two cannot state opposite stop conditions.
     */
    const val EXECUTION_NUDGE: String = SubagentPlanContract.EXECUTION_NUDGE

    /**
     * The user-role message used when NO plan was emitted either — the model
     * answered in prose and stopped. Same mechanic, different wording: there
     * is no checklist to point at, so it points at the task instead.
     */
    const val NO_PLAN_NUDGE: String = SubagentPlanContract.NO_PLAN_NUDGE

    /**
     * Decide whether a tool-less turn should end the run.
     *
     * @param planFirst the skill's `plan_first` flag. When off, a tool-less
     *   turn has always meant "done" and keeps meaning that.
     * @param planCaptured a `<task-plan>` has been stored on this run at some
     *   point (including this turn).
     * @param emittedPlanThisTurn this turn carried the extracted plan block.
     * @param nudgeIssued the execution nudge was already used for this run.
     * @param maxTurnsReached continuing would exceed the turn budget, so the
     *   loop would exit immediately anyway — report the run as finished
     *   rather than spending a nudge that cannot be acted on.
     */
    fun decide(
        planFirst: Boolean,
        planCaptured: Boolean,
        emittedPlanThisTurn: Boolean,
        nudgeIssued: Boolean,
        maxTurnsReached: Boolean,
    ): Decision {
        if (!planFirst) return Decision.FINISH
        if (nudgeIssued) return Decision.FINISH
        if (maxTurnsReached) return Decision.FINISH
        // Plan just produced → execution must follow.
        if (emittedPlanThisTurn) return Decision.NUDGE_TO_EXECUTE
        // No plan ever produced → the model skipped planning entirely; nudge
        // once so it acts instead of answering in prose.
        if (!planCaptured) return Decision.NUDGE_TO_EXECUTE
        // A plan exists from an earlier turn and this turn neither updated it
        // nor called a tool: the model is wrapping up. Let it.
        return Decision.FINISH
    }

    /** The nudge text matching [decide]'s reason. */
    fun nudgeText(emittedPlanThisTurn: Boolean): String =
        if (emittedPlanThisTurn) EXECUTION_NUDGE else NO_PLAN_NUDGE
}
