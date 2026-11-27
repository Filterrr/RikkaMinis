package com.openminis.app.tools

import org.json.JSONArray
import org.json.JSONObject

/**
 * [feat/subagent-resume-durability] The on-disk half of a continuation.
 *
 * The registry is memory-only and retains the last 20 runs, so before this
 * a `resume_run_id` died with the process — exactly the case a continuation
 * is most useful in (a long run that stopped overnight, an app that was
 * killed mid-task). Everything a continuation needs is small and already
 * known at terminal time, so it is written next to the run's artifacts as
 * `<artifacts dir>/resume.json`:
 *
 *     runId, skillId, skillName, status, error, turns, maxTurns,
 *     report (bounded), artifacts (bounded)
 *
 * Why not "parse the journal": the journal is a Markdown document optimised
 * for a HUMAN recovering a report (line map, transcript, steps). Reading
 * identity fields back out of prose is exactly the kind of parsing that
 * breaks when the layout changes; this file is written by the runtime, for
 * the runtime.
 *
 * Bounds are the same ones the hand-off uses — a continuation message rides
 * in a USER turn, so it must not grow with the previous run's verbosity.
 *
 * Pure + JVM-testable (org.json only); the file I/O lives in [SubagentRunner].
 */
internal object SubagentResumeArchive {

    /** File name inside the run's artifacts directory. */
    const val FILE_NAME = "resume.json"

    /** Schema version — bump only on an incompatible field change. */
    const val VERSION = 1

    const val MAX_REPORT_CHARS = SubagentResume.MAX_REPORT_CHARS
    const val MAX_ARTIFACTS = 50

    /**
     * [feat/subagent-budget-hardening] How many archives to keep on disk.
     * Every run writes one; without a bound the workspace grows forever.
     * Only the archive file is pruned — artifacts and directories stay.
     */
    const val MAX_ARCHIVES = 50

    /** Everything a continuation needs from the previous attempt. */
    data class Snapshot(
        val runId: String,
        val skillId: String,
        val skillName: String,
        /** Terminal status name, e.g. "TIMED_OUT". */
        val status: String,
        val error: String,
        val turns: Int,
        val maxTurns: Int,
        val report: String,
        val artifacts: List<String>,
        // [T-subagent-audit] 这四项让"重启之后还能回答那次跑的是什么"成为
        // 可能。旧档案没有这些键，解析时落到默认值——增量字段，不升版本。
        val agentKind: String = "skill",
        val toolsHash: String = "",
        val profileVersion: Int = 0,
        val roleSummary: String = "",
    )

    fun toJson(snapshot: Snapshot): String {
        val report = snapshot.report.take(MAX_REPORT_CHARS)
        val artifacts = snapshot.artifacts
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(MAX_ARTIFACTS)
        return JSONObject()
            .put("v", VERSION)
            .put("runId", snapshot.runId)
            .put("skillId", snapshot.skillId)
            .put("skillName", snapshot.skillName)
            .put("status", snapshot.status)
            .put("error", snapshot.error)
            .put("turns", snapshot.turns)
            .put("maxTurns", snapshot.maxTurns)
            .put("report", report)
            .put("artifacts", JSONArray(artifacts))
            .put("agentKind", snapshot.agentKind)
            .put("toolsHash", snapshot.toolsHash)
            .put("profileVersion", snapshot.profileVersion)
            .put("roleSummary", snapshot.roleSummary)
            .toString()
    }

    /**
     * Parse an archive written by [toJson]. Returns null for anything that
     * cannot carry a continuation (bad JSON, wrong/missing version, empty
     * run id) — a corrupt archive must look ABSENT, not like a run with no
     * evidence, so the caller falls through to its normal "not a run of this
     * chat" error.
     */
    fun parse(text: String): Snapshot? {
        val obj = try {
            JSONObject(text)
        } catch (_: Exception) {
            return null
        }
        if (obj.optInt("v", 0) != VERSION) return null
        val runId = obj.optString("runId", "").trim()
        if (runId.isEmpty()) return null
        val artifacts = obj.optJSONArray("artifacts")?.let { arr ->
            (0 until arr.length()).mapNotNull { i -> arr.optString(i, "").takeIf { it.isNotBlank() } }
        }.orEmpty()
        return Snapshot(
            runId = runId,
            skillId = obj.optString("skillId", ""),
            skillName = obj.optString("skillName", "").ifBlank { obj.optString("skillId", "") },
            status = obj.optString("status", "").ifBlank { "INTERRUPTED" },
            error = obj.optString("error", ""),
            turns = obj.optInt("turns", 0).coerceAtLeast(0),
            maxTurns = obj.optInt("maxTurns", 0).coerceAtLeast(0),
            report = obj.optString("report", "").take(MAX_REPORT_CHARS),
            artifacts = artifacts,
            agentKind = obj.optString("agentKind", "skill").ifBlank { "skill" },
            toolsHash = obj.optString("toolsHash", ""),
            profileVersion = obj.optInt("profileVersion", 0),
            roleSummary = obj.optString("roleSummary", ""),
        )
    }
}