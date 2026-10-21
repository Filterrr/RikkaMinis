package com.openminis.app.tools

/**
 * [fix/subagent-report-gate-final-message] Decides which text the report
 * gate judges, and exposes the reasoning so it is testable end-to-end.
 *
 * THE BUG THIS CLOSES
 *
 * The hygiene gate's rule is "carries synthetic tags AND keeps almost no
 * substance once they are stripped" (a 12-meaningful-character floor). It
 * was fed the run's ACCUMULATED text — every turn's narration — so any
 * earlier narration pushed a closing husk over that floor and the gate
 * never fired. The parent then received the bare tag as the findings,
 * which is the exact failure the gate exists to prevent.
 *
 * The interaction was not hypothetical: PR #66 made the plan turn
 * non-terminal, and a plan turn ALWAYS carries prose, so the two fixes
 * cancelled each other out for precisely the runs they were written for.
 *
 * Pure + JVM-testable: no Android, no registry, no coroutines.
 */
object SubagentReportGate {

    /**
     * The text the gate should judge.
     *
     * @param finalMessage the model's most recent message — the thing the
     *   report contract actually describes. Blank when the model stopped on
     *   a tool call and produced no closing text.
     * @param accumulatedText the run's whole narration, used as the fallback
     *   when the model said nothing at the end (so the gate still sees the
     *   run's own words rather than nothing).
     * @return the final message when it is non-blank, else the accumulated
     *   text.
     */
    fun textToJudge(finalMessage: String, accumulatedText: String): String {
        val final = finalMessage.trim()
        return if (final.isNotBlank()) final else accumulatedText
    }

    /**
     * Judge the run's report.
     *
     * @param finalMessage the model's most recent message.
     * @param accumulatedText the run's whole narration (the fallback).
     */
    fun judge(finalMessage: String, accumulatedText: String): SubagentReportHygiene.Verdict =
        SubagentReportHygiene.judge(textToJudge(finalMessage, accumulatedText))
}
