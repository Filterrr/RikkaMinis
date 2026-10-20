package com.openminis.app.tools

/**
 * [fix/subagent-report-hygiene] Detects and repairs a DEGENERATE final report.
 *
 * Observed failure: a sub-agent executed five tool calls, gathered real output
 * (dates, disk stats, HTTP codes), then closed with a report consisting of
 * exactly one line:
 *
 *     <parameter name="status">done</parameter>
 *
 * The parent received that line as the run's findings — five tool calls of real
 * evidence discarded, and the header still said SUCCESS. Nothing in the runtime
 * objected: any non-blank text was a report.
 *
 * Why it happens: two instructions in the sub-agent's own prompt pull in
 * different directions. `PLAN_DIRECTIVE` teaches the model to express
 * structured intent as an XML block (`<task-plan>`), while the report contract
 * asks for four plain-text fields (`status:` / `key_findings:` / …). A weak
 * model merges the two conventions and emits the field as an XML tag. The
 * report is then syntactically "content" but semantically empty.
 *
 * This object answers one question — "is this text a real report, or a shell
 * that only LOOKS like one?" — and, when the run did real work, rebuilds a
 * usable report from the evidence the runtime actually holds (tool names,
 * statuses and output tails), so the parent gets facts instead of an empty
 * husk.
 *
 * Pure + JVM-testable: no Android, no registry, no coroutines.
 */
object SubagentReportHygiene {

    /**
     * Tags a model may emit when it mistakes the structured-report field list
     * for a tool-call schema, or when it echoes Anthropic-style tool XML into
     * text. Stripped before judging substance — the tag names are NOT
     * meaningful report content.
     */
    private val SYNTHETIC_TAG = Regex(
        """</?(?:parameter|antml:parameter|status|key_findings|gaps|artifacts|""" +
            """function_calls|antml:function_calls|invoke|antml:invoke|""" +
            """tool_use|tool_calls|tool_result|result|response)\b[^>]*>""",
        RegexOption.IGNORE_CASE,
    )

    /** Self-closing variants of the same tags (`<parameter name="x"/>`). */
    private val SELF_CLOSING_TAG = Regex(
        """<(?:parameter|status|key_findings|gaps|artifacts|function_calls|invoke|""" +
            """tool_use|tool_calls|result|response)\b[^>]*/>""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Threshold applied ONLY to text that actually carries synthetic tags.
     *
     * Deliberately two-part on purpose. Judging by length alone misclassified
     * genuinely terse answers — "HTTP 200 from example.com" (18 meaningful
     * chars) and "外网连通正常，HTTP 200，磁盘使用率 28%" both got flagged as
     * husks by an earlier single-threshold version. Brevity is not the failure
     * mode; tag markup with nothing behind it is.
     *
     * So: a report with NO synthetic tags is never degenerate, however short.
     * A report WITH tags must retain at least this much substance once they
     * are stripped — which `<parameter name="status">done</parameter>` (4
     * meaningful chars left) does not.
     */
    const val MIN_SUBSTANCE_WITH_TAGS = 12

    /** One tool call the run actually executed, for report reconstruction. */
    data class ToolDigest(
        val toolName: String,
        val toolTitle: String,
        val success: Boolean,
        val output: String,
    )

    /** Result of judging a candidate final report. */
    data class Verdict(
        /** True when the text carries no usable findings. */
        val degenerate: Boolean,
        /** The text with synthetic tags stripped (always populated). */
        val stripped: String,
    )

    fun stripSyntheticTags(text: String): String {
        val withoutSelfClosing = SELF_CLOSING_TAG.replace(text, " ")
        return SYNTHETIC_TAG.replace(withoutSelfClosing, " ")
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    /** True when [text] contains at least one synthetic (non-report) tag. */
    fun hasSyntheticTags(text: String): Boolean =
        SYNTHETIC_TAG.containsMatchIn(text) || SELF_CLOSING_TAG.containsMatchIn(text)

    /**
     * Judge [report].
     *
     * Degenerate means: the text uses synthetic tag markup AND, once that
     * markup is removed, carries practically nothing. Text WITHOUT such tags is
     * never degenerate — a brief real answer is still an answer, and replacing
     * it would discard the model's actual conclusion.
     */
    fun judge(report: String): Verdict {
        val stripped = stripSyntheticTags(report)
        if (!hasSyntheticTags(report)) {
            return Verdict(degenerate = false, stripped = stripped)
        }
        val meaningful = stripped.filter { it.isLetterOrDigit() }
        return Verdict(
            degenerate = meaningful.length < MIN_SUBSTANCE_WITH_TAGS,
            stripped = stripped,
        )
    }

    /** True when [report] is an empty husk. */
    fun isDegenerate(report: String): Boolean = judge(report).degenerate

    /**
     * Rebuild a report from the run's executed tool calls.
     *
     * Returns null when there is nothing to rebuild from (a run that genuinely
     * did no work) — the caller keeps its existing "no output" handling and
     * must not pretend evidence exists.
     *
     * The assembled report is explicitly labelled: the parent has to be able to
     * tell a model-written summary from a runtime-assembled digest, because the
     * latter carries raw outputs and no interpretation.
     */
    fun assembleFromTools(
        steps: List<ToolDigest>,
        declaredStatus: String? = null,
        maxOutputCharsPerStep: Int = 400,
    ): String? {
        if (steps.isEmpty()) return null
        val sb = StringBuilder()
        sb.appendLine("status: partial")
        sb.appendLine(
            "[runtime-assembled report — the model's final message carried no usable " +
                "findings, so this digest was built from the tool calls it actually executed. " +
                "It contains raw evidence, not interpretation.]",
        )
        sb.appendLine("key_findings:")
        steps.forEachIndexed { idx, step ->
            val label = step.toolTitle.ifBlank { step.toolName }
            val mark = if (step.success) "ok" else "FAILED"
            sb.appendLine("- [$mark] ${idx + 1}. $label (${step.toolName})")
            val tail = step.output.trim()
            if (tail.isNotEmpty()) {
                val clipped = if (tail.length > maxOutputCharsPerStep) {
                    tail.takeLast(maxOutputCharsPerStep) + " …[clipped]"
                } else tail
                for (line in clipped.lines()) {
                    sb.appendLine("    $line")
                }
            }
        }
        if (declaredStatus != null) {
            sb.appendLine("gaps: the model's own summary was lost; only tool output survives above.")
        } else {
            sb.appendLine("gaps: no model-authored interpretation is available for this run.")
        }
        sb.appendLine("artifacts: none recorded by the runtime digest (see the journal for the full transcript).")
        return sb.toString().trim()
    }
}
