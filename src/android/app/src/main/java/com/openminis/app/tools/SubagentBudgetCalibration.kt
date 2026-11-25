package com.openminis.app.tools

import com.openminis.app.tools.SubagentRunRegistry.Run

/**
 * [feat/subagent-budget-hardening] Learn how long a turn actually takes here.
 *
 * `SECONDS_PER_TURN_ESTIMATE = 15` was a guess calibrated once, on one relay,
 * and every skill and model has been paying for it since. Meanwhile the
 * registry already records, for every finished run, its turn count and its
 * wall-clock duration — the exact ratio the budget conversion needs.
 *
 * This is deliberately a MEDIAN of finished runs, not a mean: one run that
 * spent ten minutes stuck in a retry loop must not move the estimate for the
 * next twenty. It is also deliberately per (skill, model) first, per skill
 * second, and global only as a last resort — a deep-researcher on a reasoning
 * model does not turn at the same pace as a file-mover on a cheap one.
 *
 * Pure + JVM-testable: callers hand over snapshots, this decides nothing
 * about the registry itself.
 */
internal object SubagentBudgetCalibration {

    /** Below this many usable runs, the default estimate is more honest. */
    const val MIN_SAMPLES = 3

    /** Only the most recent runs count — old models/relays are a different world. */
    const val MAX_SAMPLES = 20

    /** One finished run, reduced to the numbers this needs. */
    data class Sample(
        val skillId: String,
        val modelLabel: String,
        val turns: Int,
        val durationMs: Long,
    )

    data class Estimate(
        /** Median wall clock per model turn, clamped to the sane band. */
        val secondsPerTurn: Int,
        /** Median total duration, in minutes. */
        val medianMinutes: Int,
        /** How many runs the estimate is based on. */
        val samples: Int,
        /** True when only the same skill (not the same model) matched. */
        val skillOnly: Boolean,
    )

    /** Usable = it actually turned and actually took time. */
    private fun usable(run: Run): Sample? {
        if (run.isActive) return null
        if (run.turn <= 0 || run.durationMs <= 0L) return null
        return Sample(
            skillId = run.skillId,
            modelLabel = run.modelLabel,
            turns = run.turn,
            durationMs = run.durationMs,
        )
    }

    private fun median(values: List<Long>): Long {
        if (values.isEmpty()) return 0L
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2 else sorted[mid]
    }

    /**
     * Estimate for one spawn. Selection order:
     *   1. same skill AND same model label;
     *   2. same skill, any model;
     *   3. everything (only when no skill-specific history exists).
     * Returns null below [MIN_SAMPLES] — a guess dressed as evidence is worse
     * than the published default.
     */
    fun estimate(runs: List<Run>, skillId: String, modelLabel: String): Estimate? {
        val samples = runs.mapNotNull { usable(it) }
        if (samples.isEmpty()) return null
        val sameModel = samples.filter { it.skillId == skillId && it.modelLabel == modelLabel }
        val sameSkill = samples.filter { it.skillId == skillId }
        val (chosen, skillOnly) = when {
            sameModel.size >= MIN_SAMPLES -> sameModel to false
            sameSkill.size >= MIN_SAMPLES -> sameSkill to true
            samples.size >= MIN_SAMPLES -> samples to true
            else -> return null
        }
        val window = chosen.take(MAX_SAMPLES)
        val secondsPerTurn = median(
            window.map { (it.durationMs / 1000L / it.turns.coerceAtLeast(1)) },
        ).coerceIn(
            SubagentSkill.MIN_SECONDS_PER_TURN.toLong(),
            SubagentSkill.MAX_SECONDS_PER_TURN.toLong(),
        ).toInt()
        val medianMinutes = median(window.map { it.durationMs / 60_000L })
            .coerceIn(1L, SubagentSkill.MAX_MINUTES.toLong())
            .toInt()
        return Estimate(
            secondsPerTurn = secondsPerTurn,
            medianMinutes = medianMinutes,
            samples = window.size,
            skillOnly = skillOnly,
        )
    }

    /**
     * [feat/subagent-budget-hardening] A compact line for the spawn_agent
     * schema covering up to [limitSkills] skills that have enough history.
     * Returns "" when nothing qualifies — the schema must not invent a
     * number.
     */
    fun schemaHint(runs: List<Run>, limitSkills: Int = 3): String {
        val groups = runs
            .filter { !it.isActive && it.turn > 0 && it.durationMs > 0L }
            .groupBy { it.skillId }
            .toList()
            .sortedByDescending { (_, list) -> list.size }
            .take(limitSkills)
        val parts = groups.mapNotNull { (skillId, list) ->
            val name = list.firstOrNull()?.skillName?.takeIf { it.isNotBlank() } ?: skillId
            val estimate = estimate(runs, skillId, modelLabel = "") ?: return@mapNotNull null
            "$name: median ${estimate.medianMinutes} min over ${estimate.samples} run(s)"
        }
        if (parts.isEmpty()) return ""
        return "Observed here — " + parts.joinToString("; ") +
            ". Use as a starting point, not a ceiling."
    }

    /**
     * One line for the spawn_agent schema: "recent general-agent runs here:
     * median 6 min over 7 run(s)." Empty when there is not enough history —
     * the schema must not invent an estimate.
     */
    fun describe(runs: List<Run>, skillId: String, modelLabel: String): String {
        val estimate = estimate(runs, skillId, modelLabel) ?: return ""
        val scope = if (estimate.skillOnly) "this skill" else "this skill on this model"
        return "Recent $scope runs here: median ${estimate.medianMinutes} min over " +
            "${estimate.samples} run(s) — use that as a starting point, not a ceiling."
    }
}