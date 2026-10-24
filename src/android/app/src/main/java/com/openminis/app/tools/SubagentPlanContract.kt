package com.openminis.app.tools

/**
 * [fix/subagent-plan-contract] ONE source of truth for the plan contract.
 *
 * The plan rules used to live in three places, and they contradicted each
 * other:
 *
 *  1. [SubagentSkill.PLAN_DIRECTIVE] (913 bytes, appended to the system
 *     prompt) said "the plan turn makes NO tool calls — outline only, then
 *     stop the turn".
 *  2. [SubagentPlanGate.EXECUTION_NUDGE] / `NO_PLAN_NUDGE` (484 / 366
 *     bytes, injected as a USER message after a tool-less turn) said "a
 *     turn that contains no tool call terminates this run".
 *  3. The skill body (general-agent SKILL.md rule 6) said "writing
 *     `<task-plan>` does not end the task — the run continues and execution
 *     is expected on the very next turn".
 *
 * (1) and (2) are the same rule stated with opposite outcomes: a model that
 * obeys the directive's "no tool calls on the plan turn" breaks the
 * nudge's "no tool call terminates the run" rule. The model has to guess
 * which one the runtime actually enforces.
 *
 * The fix: the plan turn is EXPLICITLY non-terminal, stated once, in the
 * same words, wherever the model will read it. The runtime agrees — a
 * tool-less plan turn is followed by an execution nudge, not a stop.
 *
 * Kept pure and JVM-testable: no Android, no registry, no coroutines.
 */
object SubagentPlanContract {

    /**
     * The directive appended to the sub-agent's system prompt when the skill
     * has `plan_first` on (the default).
     *
     * `val` not `const val`: trimMargin() is a runtime call, which the const
     * initializer rules reject (CI: "Const 'val' initializer should be a
     * constant value").
     */
    val DIRECTIVE: String = """
        |# Task plan (required first)
        |
        |Before doing ANY work, output your plan for this task inside a <task-plan> block:
        |
        |<task-plan>
        |- step one
        |- step two
        |- step three
        |</task-plan>
        |
        |Rules:
        |- 3-7 steps, one line each, verb-first and concrete ("Search X", "Compare A vs B", "Write report to <path>").
        |- The plan turn is the ONE exception to the tool-call rule: make NO tool calls here — outline only, then stop the turn. The run does NOT end here: the runtime keeps the run alive and starts execution on the next turn, so do not treat a finished plan as a finished task.
        |- The list shows the user what you intend to do; deviate when the work demands it, but if the plan changes materially, emit an updated <task-plan> block before continuing.
        |- From the turn AFTER the plan, every turn must actually advance the work with a tool call until the task is complete — a turn with no tool call terminates the run.
    """.trimMargin()

    /**
     * The user-role message appended after a tool-less plan turn.
     *
     * Phrased as a directive, not a suggestion — the failure mode being
     * fixed is a model that treats "plan recorded" as "task complete".
     *
     * The stop condition is stated EXPLICITLY and scoped: it applies from the
     * turn AFTER the plan turn. The plan turn itself is the one turn where
     * "no tool call" is correct, and the directive in the system prompt says
     * so — stating the rule without the scope would reproduce the original
     * contradiction in a new place.
     */
    const val EXECUTION_NUDGE: String =
        "Your plan is recorded and shown to the user as a checklist. Now EXECUTE " +
            "it — call a tool this turn (shell_execute, file_read, file_write, " +
            "browser_use, web_search …). Do NOT restate the plan, do NOT ask for " +
            "confirmation, and do NOT end the turn with an acknowledgement: this " +
            "turn is not the plan turn, so a turn with no tool call terminates " +
            "this run with nothing delivered."

    /**
     * The user-role message used when NO plan was emitted either — the model
     * answered in prose and stopped. Same mechanic, different wording: there
     * is no checklist to point at, so it points at the task instead.
     */
    const val NO_PLAN_NUDGE: String =
        "No <task-plan> block was emitted, and this turn contained no tool call. " +
            "The task is not finished — a prose answer is not a deliverable. " +
            "Call a tool now to make progress on it (shell_execute, file_read, " +
            "file_write, browser_use, web_search …); a turn with no tool call " +
            "terminates this run with nothing delivered."

    /**
     * The marker prepended to the run transcript when the planning turn
     * begins.
     */
    const val PLAN_TURN_NOTICE = "[planning]"

    /**
     * The tag the plan block is wrapped in.
     */
    const val PLAN_TAG = "task-plan"

    /**
     * The nudge text for a given reason. Both variants state the SAME stop
     * condition — only the setup differs.
     */
    fun nudgeText(planEmitted: Boolean): String =
        if (planEmitted) EXECUTION_NUDGE else NO_PLAN_NUDGE
}
