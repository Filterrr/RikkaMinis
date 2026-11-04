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
 *  - inline spawn     - [SubagentResult.toPromptText]   (tool result, NO cap)
 *  - detached wake    - [buildWakePrompt]                 (user message, 4k)
 *  - join_subagents   - the runner's join renderer        (tool result, 6k each)
 *  - wait_any         - the runner's winner renderer      (tool result, 6k)
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
 *  1. ENVELOPE   - run id, skill, verdict, turns, tools, model, tokens.
 *                  Never truncated. The parent must always be able to
 *                  tell WHAT this is and whether to trust it.
 *  2. CONTRACT   - summary / findings / gaps / artifacts, parsed.
 *                  This is the actionable part and it comes BEFORE the
 *                  body, so a truncation of the body cannot eat it.
 *  3. BODY       - the report text. This is the only layer that may be
 *                  cut, and a cut is announced with its recovery route.
 *  4. POINTERS   - journal path, artifact paths.
 *
 * A parent that reads only the envelope and the summary knows what
 * happened, what was found, and where the evidence is - without reading
 * the body at all.
 */
object SubagentResultEnvelope {

    /** One run's facts, as the renderer needs them. */
    data class Facts(
        val runId: String,
        val skillName: String,
        /** The verdict about the WORK - see [SubagentRunAssessment.Outcome]. */
        val outcome: SubagentRunAssessment.Outcome,
        /** Where the report text came from - a model account or a rebuild. */
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
        /** The durable journal - the recovery anchor. */
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
     * gets a different first line - it does not get a different report.
     */
    enum class Framing {
        /** A blocking spawn: the parent called spawn and waited. */
        INLINE,

        /**
         * A first-turn readout - `run_until=first_turn`. The run is NOT
         * finished; it is a peek. The framing says so, because reading a
         * readout as a finished task is the exact confusion the
         * STOPPED_EARLY verdict exists to prevent.
         */
        EARLY_READOUT,

        /** A background run finished and pushed its result. */
        WAKE,

        /** An explicit "give me the results" call. */
        JOIN,

        /** A race - one member won. */
        WAIT_ANY,
    }

    /**
     * The per-path budget for the BODY layer.
     *
     * The inline path used to have NO budget at all. It has one now, and
     * it is the SAME number as the join path's - a spawn is at least as
     * explicit a request as a join, and two constants with the same
     * value are two constants that will drift apart.
     *
     * The wake path keeps its smaller 4k - it is unsolicited, and it
     * interrupts whatever the parent was doing.
     *
     * The value itself lives in
     * [SubagentParentDelivery.JOIN_REPORT_MAX_CHARS] so there is exactly
     * ONE number in the codebase for "how much report text rides into a
     * parent turn".
     */
    const val INLINE_BODY_MAX_CHARS = JOIN_REPORT_MAX_CHARS

    /**
     * [fix/subagent-contract-budget] Per-field budgets for the CONTRACT layer.
     *
     * The contract layer is deliberately outside the BODY budget: a truncation
     * of the body must never be able to eat the summary, the findings or the
     * gaps. That rule was implemented as "no bound at all", which meant one
     * runaway field could spend the parent's whole turn:
     *
     *  - a `summary:` that swallowed a 20 KB paragraph rendered a 20 KB
     *    summary (measured), and a wake-up whose declared budget is 4 KB
     *    arrived at 20 493 chars;
     *  - a 30 KB `gaps:` produced a 30 474-char wake-up;
     *  - 400 findings rendered 38 743 chars under a 6 000-char budget.
     *
     * The budget these fields share is the point of the BODY budget: the
     * parent turn. Each field is bounded on its own and the cut is announced
     * in place, so a field that WAS cut is visible as cut rather than
     * silently shortened (silence there would be the same lie the truncation
     * marker exists to prevent).
     */
    const val MAX_SUMMARY_CHARS = 1200
    const val MAX_GAPS_CHARS = 1200
    const val MAX_FINDING_CHARS = 400
    const val MAX_FINDINGS_CHARS = 2400
    const val MAX_FINDINGS_SHOWN = 40

    /**
     * Render the envelope.
     *
     * @param facts the run's facts.
     * @param framing how the path frames it.
     * @param bodyMaxChars the BODY budget. The envelope and the contract
     *   are NEVER cut - only the body is, and the cut is announced.
     */
    fun render(
        facts: Facts,
        framing: Framing,
        bodyMaxChars: Int = INLINE_BODY_MAX_CHARS,
    ): String = buildString {
        append(headerLine(facts, framing))
        append("\n").append(
            headlineFor(facts),
        )
        append("\n").append(costLine(facts))
        append("\n").append(turnLine(facts))

        // The declared-status annotation - only for a model-authored
        // report, and only when it says something (the runtime's own
        // digest writes its own status line; forwarding that as the
        // model's self-assessment would misattribute it).
        if (SubagentRunAssessment.isModelAuthored(facts.reportSource)) {
            declaredStatusAnnotation(facts.declaredStatus)?.let { append("\n").append(it) }
        }

        // ── layer 2: the contract ──
        append("\n").append(summaryLine(facts.parsed))
        append("\n").append(findingsLine(facts.parsed))
        append("\n").append(gapsLine(facts.parsed))
        append("\n").append(artifactsLine(facts.artifacts, facts.parsed?.artifacts.orEmpty()))

        // ── layer 3: the body ──
        //
        // [feat/subagent-truncation-envelope] The body is the report's
        // PROSE - the text that is not part of any contract field. The
        // contract fields are already rendered above, as structure;
        // rendering the whole report again as the body would show every
        // finding TWICE and spend the parent's budget on the duplicate.
        //
        // When there is no parsed contract (a runtime-assembled digest, a
        // crash with no text), the body IS the report - there is nothing
        // else to show.
        //
        // When the contract layer captured NOTHING (a report whose fields
        // the parser did not recognise), the body IS the report - there
        // is nothing above it to duplicate. When the contract layer DID
        // capture something, the body is the PROSE only: falling back to
        // the whole report there would render every finding twice, once
        // as structure and once inside the body.
        val contractCapturedSomething = facts.parsed != null &&
            (facts.parsed.summary != null ||
                facts.parsed.findings.isNotEmpty() ||
                facts.parsed.gaps != null ||
                facts.parsed.artifacts.isNotEmpty())
        val body = if (contractCapturedSomething) {
            facts.parsed?.prose.orEmpty()
        } else {
            facts.report
        }
        if (body.isNotBlank()) {
            // [feat/subagent-result-envelope] A body that is NOT the
            // model's own complete account must say so. A parent that
            // reads a partial body as a complete one will treat a
            // half-finished run's conclusions as final - the exact
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
            append("\n\n---\n")
            append(boundBody(body, facts.journalPath, bodyMaxChars))
        } else if (contractCapturedSomething) {
            // The whole report WAS the contract - there is no prose left
            // to show. Say so, and say where the full account lives.
            //
            // Silence here would be a lie of omission: the parent sees a
            // short envelope and cannot tell "the sub-agent wrote three
            // lines" from "the sub-agent wrote 400 lines and the
            // contract captured all of it".
            append("\n\n---\n(the whole report is the contract above - no additional prose)")
            if (facts.report.length > bodyMaxChars) {
                append("\n...[body truncated: 0 of ${facts.report.length} chars shown " +
                    "(${facts.report.length} chars dropped) - the summary, findings, gaps and " +
                    "artifacts above are NOT cut; full text: file_read " +
                    (facts.journalPath ?: "the run was not journaled") + "]")
            }
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

    /**
     * [fix/subagent-cancel-headline] The verdict line, reconciled with how the
     * run actually ENDED.
     *
     * `SubagentRunAssessment.headline` renders the WORK verdict, and a
     * cancelled run's work verdict is FAILED (it did not finish). Left at
     * that, a run the USER stopped was announced as
     *
     *     [failed] 12 tool calls executed before the run errored.
     *
     * — which asserts a crash that never happened. The header line above it
     * said CANCELLED, so the message contradicted itself two lines apart.
     *
     * The runtime status is the fact that settles it: a cancelled run is
     * announced as cancelled, and its text as partial, never as an error.
     */
    private fun headlineFor(facts: Facts): String = when (facts.runtimeStatus) {
        "CANCELLED" ->
            "[cancelled] the run was stopped before it finished; " +
                if (facts.executedToolCalls > 0) {
                    "${facts.executedToolCalls} tool call(s) had executed. " +
                        "What follows is partial output, not an error report."
                } else {
                    "no tool call had executed yet."
                }
        else -> SubagentRunAssessment.headline(
            facts.outcome, facts.executedToolCalls, facts.reportSource,
        )
    }

    /** The first line: what this is, and which run it is. */
    private fun headerLine(facts: Facts, framing: Framing): String = when (framing) {
        Framing.INLINE ->
            // [fix/subagent-envelope-facts] The run id rides in the header when
            // it is known. The parent's next move after reading any result is
            // often join_subagents(run_ids=…) or cancel_subagents, and the
            // inline path used to print the SKILL id in that slot — naming a
            // run that does not exist, and making two runs of one skill
            // indistinguishable.
            "Sub-agent '${facts.skillName}'" +
                (facts.runId.takeIf { it.isNotBlank() }?.let { " (run $it)" } ?: "") +
                " ran ${facts.turns} turn(s)."
        Framing.EARLY_READOUT ->
            "Sub-agent '${facts.skillName}' first-turn readout (1/${facts.maxTurns} turns):"
        Framing.WAKE ->
            "[Sub-agent '${facts.skillName}' finished in the background" +
                (facts.runtimeStatus?.let { " - status $it" } ?: "") + "]" +
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
                    // [fix/subagent-envelope-facts] This was a hardcoded
                    // `null`, so CANCELLED could never be rendered on the
                    // race path even though the facts carried it. Both
                    // detached framings now read the same field.
                    runtimeStatus = facts.runtimeStatus,
                    verdict = facts.outcome,
                    hasReport = facts.report.isNotBlank(),
                )
    }

    /**
     * The cost line.
     *
     * A provider that never reports usage leaves the totals at -1. That
     * must render as "unknown", never as 0 - a zero cost claim is a
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
     * The summary line - the one part that answers the parent's question.
     *
     * A DERIVED summary (the runtime fell back to the first finding) is
     * labelled as such. The parent must be able to tell the model's own
     * bottom line from the runtime's guess at one.
     *
     * [fix/subagent-contract-budget] Bounded. The contract layer is NOT part
     * of the BODY budget (a truncation must never eat the conclusions), but
     * that made every scalar field UNBOUNDED: a report whose `summary:` ran
     * into a 20 KB paragraph produced a 20 KB "summary" and a wake-up that
     * blew its 4 KB budget by 5×. The fields are bounded individually and the
     * cut is announced — silently shortening a conclusion would be worse than
     * the size problem it solves.
     */
    private fun summaryLine(parsed: SubagentReportContract.ParsedReport?): String {
        if (parsed == null) return "summary: not reported"
        val summary = parsed.summary ?: return "summary: not reported"
        val body = boundField("summary", summary, MAX_SUMMARY_CHARS)
        return if (parsed.summaryIsModelAuthored) {
            "summary: $body"
        } else {
            "summary: $body (derived from the first key finding - the sub-agent wrote no summary)"
        }
    }

    /**
     * The findings line - the conclusions, with their anchors.
     *
     * [feat/subagent-truncation-envelope] The envelope's documentation
     * promised `findings` from the start, and the code never rendered
     * it - the parsed findings were parsed, stored, and then DROPPED at
     * the moment they were supposed to reach the parent. The findings
     * are the sub-agent's conclusions with their evidence anchors; a
     * parent that gets the summary and the gaps but not the findings
     * gets the bottom line without the proof.
     *
     * The findings are the actionable part of the contract, and a
     * truncation of the BODY must never be able to eat them. They are
     * bounded independently ([MAX_FINDINGS_CHARS] / [MAX_FINDINGS_SHOWN])
     * with an explicit "… and N more (see the journal)" tail, so a report
     * with 400 findings cannot spend the whole parent turn.
     */
    private fun findingsLine(parsed: SubagentReportContract.ParsedReport?): String {
        val findings = parsed?.findings?.filter { it.isNotBlank() }.orEmpty()
        if (findings.isEmpty()) return "findings: none reported"
        val shown = findings.take(MAX_FINDINGS_SHOWN)
        val sb = StringBuilder("findings:")
        var used = 0
        for (f in shown) {
            if (used >= MAX_FINDINGS_CHARS) break
            val line = boundField("finding", f, MAX_FINDING_CHARS)
            sb.append("\n- ").append(line)
            used += line.length
        }
        val rendered = findings.size.coerceAtMost(shown.size)
        // Count what was actually rendered, not what was attempted: a
        // finding that hit MAX_FINDING_CHARS still counts as shown.
        val remaining = findings.size - rendered
        if (remaining > 0) {
            sb.append("\n- … and ").append(remaining)
                .append(" more finding(s) — the whole list is in the journal")
        }
        return sb.toString()
    }

    /** The gaps line - what the run did NOT establish. Bounded, see [MAX_GAPS_CHARS]. */
    private fun gapsLine(parsed: SubagentReportContract.ParsedReport?): String {
        val gaps = parsed?.gaps?.takeIf { it.isNotBlank() } ?: return "gaps: not reported"
        return "gaps: " + boundField("gaps", gaps, MAX_GAPS_CHARS)
    }

    /**
     * [fix/subagent-contract-budget] Bound one contract field, announcing the
     * cut.
     *
     * @param label the field name, for the announcement.
     * @param text the field's text.
     * @param maxChars the field's budget.
     */
    private fun boundField(label: String, text: String, maxChars: Int): String {
        val flat = text.replace(Regex("\\s*\\n\\s*"), " ").trim()
        if (flat.length <= maxChars) return flat
        val dropped = flat.length - maxChars
        return flat.take(maxChars) +
            " [… $label truncated: $maxChars of ${flat.length} chars shown, " +
            "$dropped dropped — see the journal]"
    }

    /**
     * The artifacts line.
     *
     * [feat/subagent-truncation-envelope] It carries the UNION of two
     * sources:
     *
     * - the runtime's WRITE LOG (the paths the runtime vouched for), and
     * - the model's DECLARED paths, which the reconciliation has already
     *   checked against disk.
     *
     * The runtime log alone is not enough: a file written through
     * `shell_execute` never enters it, and the reconciliation accepted
     * that path precisely because it exists on disk. The declared paths
     * alone are not enough: the model's prose drifts, and a path it
     * invented would be listed as a deliverable.
     *
     * The union keeps both, deduplicated, in that order.
     */
    private fun artifactsLine(
        runtimeArtifacts: List<String>,
        declaredArtifacts: List<String>,
    ): String {
        val all = (runtimeArtifacts + declaredArtifacts).distinct()
        if (all.isEmpty()) return "artifacts: none"
        return "artifacts: " + all.joinToString(", ")
    }

    /**
     * The model's declared status, when it says something the verdict does
     * not already say.
     */
    private fun declaredStatusAnnotation(declaredStatus: String?): String? = when (declaredStatus) {
        "partial" ->
            "The sub-agent's report declares status: partial - it did NOT complete the task " +
                "as specified. Check its gaps and the journaled artifacts before relying on this."
        "failed" ->
            "The sub-agent's report declares status: failed - treat this result as an error " +
                "report, not a deliverable."
        else -> null
    }

    /**
     * The body, bounded.
     *
     * The cut is announced WITH ITS RECOVERY ROUTE - a parent that
     * cannot find the rest would treat a partial body as the whole
     * finding, which is the exact failure the report-hygiene workstream
     * exists to prevent.
     *
     * [feat/subagent-result-envelope] Public within the module so the
     * delivery helpers delegate here instead of carrying their own copy
     * of "how do we cut" - one implementation, four callers.
     */
    internal fun boundBody(body: String, journalPath: String?, maxChars: Int): String {
        if (body.length <= maxChars) return body
        val recovery = if (!journalPath.isNullOrBlank()) {
            "full text: file_read $journalPath"
        } else {
            "the run was not journaled - re-spawn with a narrower scope to get the rest"
        }
        // [feat/subagent-truncation-envelope] The marker says WHAT was
        // cut and HOW MUCH, not just "truncated at N chars".
        //
        // "truncated at 6000 chars" told the parent a NUMBER and nothing
        // else - it did not say how much was dropped (so the parent could
        // not judge whether the remainder mattered), and it did not say
        // WHAT was dropped (the prose? the findings?). The findings are
        // NOT in this layer - they are in the contract layer above, and
        // they are never cut. A parent that knew that could read a cut
        // body as "the reasoning was cut, the conclusions were not".
        val dropped = body.length - maxChars
        return body.take(maxChars) +
            "\n...[body truncated: $maxChars of ${body.length} chars shown " +
            "($dropped chars dropped) - the summary, findings, gaps and artifacts " +
            "above are NOT cut; $recovery]"
    }
}
