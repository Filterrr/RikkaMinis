package com.openminis.app.tools

/**
 * [feat/subagent-result-envelope] ONE renderer for a sub-agent's result.
 *
 * The problem this closes
 * ----------------------
 * A sub-agent's result reached the parent through FOUR different
 * renderers, each with its own wording, its own field order and its own
 * budget:
 *
 *  - inline spawn     — [SubagentResult.toPromptText]   (tool result, NO cap)
 *  - detached wake    — [buildWakePrompt]                 (user message, 4k)
 *  - join_subagents   — the runner's join renderer        (tool result, 6k each)
 *  - wait_any         — the runner's winner renderer      (tool result, 6k)
 *
 * The inline path was the outlier twice over: it had NO budget at all
 * (a run's stored text can be 24k, and the inline path spliced all of
 * it in), and it carried no model / token line, so the parent could
 * not see what the delegation cost.
 *
 * The fix is not "four renderers, tweaked". It is ONE envelope, rendered
 * once, with the per-path differences expressed as DATA (a budget and a
 * framing line) rather than as four copies of the same logic.
 *
 * The envelope's shape
 * -------------------
 * Four layers, always in this order:
 *
 *  1. ENVELOPE   — run id, skill, verdict, turns, tools, model, tokens.
 *                  Never truncated. The parent must always be able to
 *                  tell WHAT this is and whether to trust it.
 *  2. CONTRACT   — summary / findings / gaps / artifacts, parsed.
 *                  This is the actionable part and it comes BEFORE the
 *                  body, so a truncation of the body cannot eat it.
 *  3. BODY       — the report text. This is the only layer that may be
 *                  cut, and a cut is announced with its recovery route.
 *  4. POINTERS   — journal path, artifact paths.
 *
 * A parent that reads only the envelope and the summary knows what
 * happened, what was found, and where the evidence is — without reading
 * the body at all.
 */
object SubagentResultEnvelope {

    /** One run's facts, as the renderer needs them. */
    data class Facts(
        val runId: String,
        val skillName: String,
        /** The verdict about the WORK — see [SubagentRunAssessment.Outcome]. */
        val outcome: SubagentRunAssessment.Outcome,
        /** Where the report text came from — a model account or a rebuild. */
        val reportSource: SubagentRunAssessment.ReportSource,
        /** Tool calls that actually executed. */
        val executedToolCalls: Int,
        /** Turns executed / turn budget. */
        val turns: Int,
        val maxTurns: Int,
        /** The model that ran, empty when unknown. */
        val modelLabel: String = "",
        /** Provider-reported token counts; -1 means never reported. */
        val tokensIn: Int = -1,
        val tokensOut: Int = -1,
        /** The report body. */
        val report: String = "",
        /** The parsed contract, when the report is a model-authored one. */
        val parsed: SubagentReportContract.ParsedReport? = null,
        /** Deliverables, from the runtime's write log. */
        val artifacts: List<String> = emptyList(),
        /** The durable journal — the recovery anchor. */
        val journalPath: String? = null,
        /** The error, for a failed run. */
        val error: String? = null,
        /** The model's own declared status, when it declared one. */
        val declaredStatus: String? = null,
        /**
         * The RUNTIME status of the run ("TIMED_OUT" / "CANCELLED" / …).
         *
         * A different fact from the verdict: the verdict is what the
         * runtime concluded about the work, this is how the run ENDED. A
         * run can time out AND have delivered partial notes, and the
         * parent needs to know both.
         */
        val runtimeStatus: String? = null,
    )

    /**
     * How a path frames the envelope.
     *
     * The framing differs (a wake-up is an EVENT, an inline result is a
     * RESULT), but the CONTENT does not: the same envelope, the same
     * fields, the same order. A path that wants a different framing
     * gets a different first line — it does not get a different report.
     */
    enum class Framing {
        /** A blocking spawn: the parent called spawn and waited. */
        INLINE,

        /**
         * A first-turn readout — `run_until=first_turn`. The run is NOT
         * finished; it is a peek. The framing says so, because reading a
         * readout as a finished task is the exact confusion the
         * STOPPED_EARLY verdict exists to prevent.
         */
        EARLY_READOUT,

        /** A background run finished and pushed its result. */
        WAKE,

        /** An explicit "give me the results" call. */
        JOIN,

        /** A race — one member won. */
        WAIT_ANY,
    }

    /**
     * The per-path budget for the BODY layer.
     *
     * The inline path used to have NO budget at all. It has one now, and
     * it is the SAME number as the join path's — a spawn is at least as
     * explicit a request as a join, and two constants with the same
     * value are two constants that will drift apart.
     *
     * The wake path keeps its smaller 4k — it is unsolicited, and it
     * interrupts whatever the parent was doing.
     *
     * The value itself lives in
     * [SubagentParentDelivery.JOIN_REPORT_MAX_CHARS] so there is exactly
     * ONE number in the codebase for "how much report text rides into a
     * parent turn".
     */
    const val INLINE_BODY_MAX_CHARS = JOIN_REPORT_MAX_CHARS

    /**
     * Render the envelope.
     *
     * @param facts the run's facts.
     * @param framing how the path frames it.
     * @param bodyMaxChars the BODY budget. The envelope and the contract
     *   are NEVER cut — only the body is, and the cut is announced.
     */
    fun render(
        facts: Facts,
        framing: Framing,
        bodyMaxChars: Int = INLINE_BODY_MAX_CHARS,
    ): String = buildString {
        append(headerLine(facts, framing))
        append("\n").append(SubagentRunAssessment.headline(facts.outcome, facts.executedToolCalls, facts.reportSource))
        append("\n").append(costLine(facts))
        append("\n").append(turnLine(facts))

        // The declared-status annotation — only for a model-authored
        // report, and only when it says something (the runtime's own
        // digest writes its own status line; forwarding that as the
        // model's self-assessment would misattribute it).
        if (SubagentRunAssessment.isModelAuthored(facts.reportSource)) {
            declaredStatusAnnotation(facts.declaredStatus)?.let { append("\n").append(it) }
        }

        // ── layer 2: the contract ──
        append("\n").append(summaryLine(facts.parsed))
        append("\n").append(gapsLine(facts.parsed))
        append("\n").append(artifactsLine(facts.artifacts))

        // ── layer 3: the body ──
        if (facts.report.isNotBlank()) {
            // [feat/subagent-result-envelope] A body that is NOT the
            // model's own complete account must say so. A parent that
            // reads a partial body as a complete one will treat a
            // half-finished run's conclusions as final — the exact
            // mistake the PARTIAL / DEGRADED verdicts exist to prevent.
            val partialBody = !SubagentRunAssessment.isModelAuthored(facts.reportSource) ||
                facts.outcome == SubagentRunAssessment.Outcome.PARTIAL ||
                facts.outcome == SubagentRunAssessment.Outcome.DECLARED_FAILED ||
                facts.outcome == SubagentRunAssessment.Outcome.TIMED_OUT ||
                facts.outcome == SubagentRunAssessment.Outcome.FAILED ||
                facts.outcome == SubagentRunAssessment.Outcome.UNKNOWN ||
                facts.outcome == SubagentRunAssessment.Outcome.STOPPED_EARLY ||
                facts.outcome == SubagentRunAssessment.Outcome.NO_OUTPUT ||
                facts.outcome == SubagentRunAssessment.Outcome.UNSTARTED
            if (partialBody) {
                append("\nPartial output (not a complete account):")
            }
            append("\n---\n")
            append(boundBody(facts.report, facts.journalPath, bodyMaxChars))
        } else {
            append("\n(no text output produced)")
        }

        // ── layer 4: the pointers ──
        facts.journalPath?.let {
            append("\nFull report: ").append(it)
            append(" (file_read it for the complete transcript and every tool output)")
        }
        facts.error?.let {
            append("\nError: ").append(it)
        }
    }

    /** The first line: what this is, and which run it is. */
    private fun headerLine(facts: Facts, framing: Framing): String = when (framing) {
        Framing.INLINE ->
            "Sub-agent '${facts.skillName}' ran ${facts.turns} turn(s)."
        Framing.EARLY_READOUT ->
            "Sub-agent '${facts.skillName}' first-turn readout (1/${facts.maxTurns} turns):"
        Framing.WAKE ->
            "[Sub-agent '${facts.skillName}' finished in the background" +
                (facts.runtimeStatus?.let { " — status $it" } ?: "") + "]" +
                // [feat/subagent-result-envelope] The run id is NOT
                // decoration: the parent's next move is usually
                // join_subagents(run_ids=...) or cancel_subagents, and
                // without the id it cannot name the run it is talking
                // about. The old wake renderer printed it; the envelope
                // must not lose it.
                "\nrun_id: ${facts.runId}"
        Framing.JOIN ->
            "=== run ${facts.runId} (${facts.skillName}) " +
                SubagentRunAssessment.summaryStatus(
                    runtimeStatus = facts.runtimeStatus,
                    verdict = facts.outcome,
                    hasReport = facts.report.isNotBlank(),
                ) + " ==="
        Framing.WAIT_ANY ->
            "wait_any: first completion -> run ${facts.runId} (${facts.skillName}) " +
                SubagentRunAssessment.summaryStatus(
                    runtimeStatus = null,
                    verdict = facts.outcome,
                    hasReport = facts.report.isNotBlank(),
                )
    }

    /**
     * The cost line.
     *
     * A provider that never reports usage leaves the totals at -1. That
     * must render as "unknown", never as 0 — a zero cost claim is a
     * measurement the runtime does not have.
     */
    private fun costLine(facts: Facts): String {
        val parts = mutableListOf<String>()
        if (facts.modelLabel.isNotBlank()) parts += "model: ${facts.modelLabel}"
        if (facts.tokensIn >= 0 || facts.tokensOut >= 0) {
            parts += "tokens: in=${facts.tokensIn.coerceAtLeast(0)} out=${facts.tokensOut.coerceAtLeast(0)}"
        }
        return if (parts.isEmpty()) "cost: not reported by the provider" else "cost: " + parts.joinToString(", ")
    }

    /** The turn line. */
    private fun turnLine(facts: Facts): String {
        val turns = if (facts.maxTurns > 0) "${facts.turns}/${facts.maxTurns}" else "${facts.turns}"
        return "turns: $turns"
    }

    /**
     * The summary line — the one part that answers the parent's question.
     *
     * A DERIVED summary (the runtime fell back to the first finding) is
     * labelled as such. The parent must be able to tell the model's own
     * bottom line from the runtime's guess at one.
     */
    private fun summaryLine(parsed: SubagentReportContract.ParsedReport?): String {
        if (parsed == null) return "summary: not reported"
        val summary = parsed.summary ?: return "summary: not reported"
        return if (parsed.summaryIsModelAuthored) {
            "summary: $summary"
        } else {
            "summary: $summary (derived from the first key finding — the sub-agent wrote no summary)"
        }
    }

    /** The gaps line — what the run did NOT establish. */
    private fun gapsLine(parsed: SubagentReportContract.ParsedReport?): String {
        val gaps = parsed?.gaps?.takeIf { it.isNotBlank() } ?: return "gaps: not reported"
        return "gaps: $gaps"
    }

    /** The artifacts line. */
    private fun artifactsLine(artifacts: List<String>): String {
        if (artifacts.isEmpty()) return "artifacts: none"
        return "artifacts: " + artifacts.joinToString(", ")
    }

    /**
     * The model's declared status, when it says something the verdict does
     * not already say.
     */
    private fun declaredStatusAnnotation(declaredStatus: String?): String? = when (declaredStatus) {
        "partial" ->
            "The sub-agent's report declares status: partial — it did NOT complete the task " +
                "as specified. Check its gaps and the journaled artifacts before relying on this."
        "failed" ->
            "The sub-agent's report declares status: failed — treat this result as an error " +
                "report, not a deliverable."
        else -> null
    }

    /**
     * The body, bounded.
     *
     * The cut is announced WITH ITS RECOVERY ROUTE — a parent that
     * cannot find the rest would treat a partial body as the whole
     * finding, which is the exact failure the report-hygiene workstream
     * exists to prevent.
     *
     * [feat/subagent-result-envelope] Public within the module so the
     * delivery helpers delegate here instead of carrying their own copy
     * of "how do we cut" — one implementation, four callers.
     */
    internal fun boundBody(body: String, journalPath: String?, maxChars: Int): String {
        if (body.length <= maxChars) return body
        val recovery = if (!journalPath.isNullOrBlank()) {
            "full text: file_read $journalPath"
        } else {
            "the run was not journaled — re-spawn with a narrower scope to get the rest"
        }
        return body.take(maxChars) + "\n...[body truncated at $maxChars chars — $recovery]"
    }
}
