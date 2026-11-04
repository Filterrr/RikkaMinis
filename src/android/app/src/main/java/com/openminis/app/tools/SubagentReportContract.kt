package com.openminis.app.tools

/**
 * [feat/subagent-report-summary] The report contract, PARSED.
 *
 * ## The defect this closes
 *
 * The sub-agent's report contract is plain text with FIVE fields:
 * `summary` / `status` / `key_findings` / `gaps` / `artifacts`. The
 * runtime parsed exactly ONE of them — `status`, and only from the last
 * ten lines ([SubagentSkill.parseReportStatus]). The other three were
 * never consumed as structure: they were prose the parent had to
 * re-parse, every time, for every run.
 *
 * That works fine while the parent reads the whole report. It stops
 * working the moment the report does not fit:
 *
 * - the delivery paths truncate ([SubagentResultEnvelope.boundBody]
 *   keeps the HEAD, so the tail — `gaps` and `artifacts`, the two most
 *   actionable fields — is what gets cut);
 * - a join of N runs stacks N full reports into one parent turn;
 * - the journal is a 283KB markdown dump that the parent is told to
 *   `file_read` and re-parse by hand.
 *
 * Measured 2026-10-28 on a real 40-turn run: the delivered report was
 * the single line "I'll start by exploring the repository structure and
 * locating the target files." — the model had done 76 tool calls, and
 * every one of them was reachable only through a 5058-line journal.
 *
 * ## The rule
 *
 * A report has FIVE fields now. The new one is `summary` — and it is
 * the one that answers the parent's actual question.
 *
 * `key_findings` is a list of conclusions. `summary` is what the parent
 * asked for, in three to five lines, BEFORE the findings. It is the
 * field that survives truncation, because the runtime puts it first.
 *
 * Parsing is deliberately forgiving, and the degradation ladder is
 * explicit:
 *
 *  1. an explicit `summary:` field → used as-is;
 *  2. no `summary:` field, but a `key_findings:` section → the first
 *     finding line is used as the summary, and the fact that it was
 *     DERIVED is recorded (`derivedFrom = KEY_FINDINGS`);
 *  3. neither → the summary is absent, and [ParsedReport.summaryAvailable]
 *     is false. The runtime says so out loud rather than inventing one.
 *
 * Step 3 is the important one: a missing summary must be VISIBLE. A
 * runtime that quietly substitutes the whole report for a missing
 * summary is back to square one.
 *
 * Pure + JVM-testable: no Android, no registry, no filesystem.
 */
object SubagentReportContract {

    /**
     * The contract's field labels. `summary` is new; the other four are
     * unchanged from the original contract.
     */
    const val FIELD_SUMMARY = "summary"
    const val FIELD_STATUS = "status"
    const val FIELD_KEY_FINDINGS = "key_findings"
    const val FIELD_GAPS = "gaps"
    const val FIELD_ARTIFACTS = "artifacts"

    /**
     * Where a summary came from. The parent needs to know whether it is
     * reading something the MODEL wrote for this purpose, or something
     * the runtime salvaged from a different field.
     */
    enum class SummarySource {
        /** The model wrote an explicit `summary:` field. */
        MODEL,

        /**
         * The model wrote no summary; the runtime took the first line of
         * `key_findings`. A useful approximation, but not the same thing.
         */
        KEY_FINDINGS,

        /** Nothing usable. [ParsedReport.summary] is null. */
        NONE,
    }

    /**
     * A report, parsed.
     *
     * @param summary the summary line(s), or null when the report carried
     *   none and none could be derived.
     * @param summarySource where the summary came from.
     * @param status the declared status ("done" / "partial" / "failed"),
     *   or null when the report declared none.
     * @param findings the `key_findings:` lines, in order.
     * @param gaps the `gaps:` text, verbatim (it is a sentence, not a list).
     * @param artifacts the `artifacts:` lines, in order.
     */
    data class ParsedReport(
        val summary: String?,
        val summarySource: SummarySource,
        /**
         * The DECLARED status, in the contract's own vocabulary:
         * "done" / "partial" / "failed", or null when the report declared
         * none.
         *
         * This is NOT the same thing as
         * [SubagentSkill.parseReportStatus], and the difference is
         * deliberate:
         *
         * - `parseReportStatus` answers "does the model want the parent
         *   to know this is unfinished?" — it returns "partial" /
         *   "failed" and null for "done" (a done report needs no
         *   annotation). It also scans the report TAIL, because the
         *   contract pins `status` to the tail.
         * - this field answers "what did the model declare?" — including
         *   "done", which is a real answer the parent may want.
         *
         * Both are derived from the SAME field; they differ in the
         * question they answer, not in where they look. A report that
         * declares "partial" yields "partial" from both.
         */
        val status: String?,
        val findings: List<String>,
        val gaps: String?,
        val artifacts: List<String>,
        /**
         * The report text that is NOT part of any contract field — the
         * prose the sub-agent wrote around its findings.
         *
         * Why this exists: the envelope renders the contract fields AS
         * structure (summary / findings / gaps / artifacts) and then
         * renders the body. If the body were the whole report, every
         * finding would appear TWICE — once as a structured line and
         * once inside the prose — and the parent would pay for the
         * duplicate in every token budget.
         *
         * The prose is what the structured fields cannot carry: the
         * reasoning, the caveats, the "why this matters". Dropping it
         * would lose the sub-agent's actual account; keeping it in the
         * body would duplicate the contract.
         *
         * Empty when the report was nothing but contract fields.
         */
        val prose: String = "",
    ) {
        /** True when the report carried a usable summary. */
        val summaryAvailable: Boolean get() = summary != null

        /** True when the summary is the model's own, not a salvage. */
        val summaryIsModelAuthored: Boolean
            get() = summarySource == SummarySource.MODEL
    }

    /**
     * A contract field header — `status:`, `key_findings:`, `summary:` …
     *
     * A field is TOP-LEVEL: it starts at the beginning of the line, with
     * no indentation and no list bullet. A bullet belongs to the
     * PREVIOUS field's body — otherwise a `key_findings:` entry that
     * happens to contain a colon ("- the api: returns 404") would
     * silently become a field, and the body of the real field would be
     * cut in half.
     *
     * This is the same rule [SubagentArtifactTruth] uses to decide where
     * the `artifacts:` section ends, and the two must agree: the report
     * contract's fields and the artifact section's header are the same
     * grammar.
     *
     * The label may contain letters of any script (the contract is
     * written in English, but the parent may be Chinese), and the colon
     * may be full width (：) — the existing
     * [SubagentSkill.parseReportStatus] accepts both, and the two
     * parsers must not disagree about what a field is.
     */
    private val FIELD_HEADER = Regex(
        """^([^\s\-*+>|][^:：]*?)([ \t]*[:：])(.*)$""",
    )

    /**
     * Parse [report] into its contract fields.
     *
     * Tolerant by design: a report that uses `结果状态:` instead of
     * `status:` is still a report the parent can use, and the runtime
     * should not throw a finding away over a label.
     */
    fun parse(report: String): ParsedReport {
        if (report.isBlank()) {
            return ParsedReport(
                summary = null,
                summarySource = SummarySource.NONE,
                status = null,
                findings = emptyList(),
                gaps = null,
                artifacts = emptyList(),
            )
        }

        // Field label → the body lines that follow it, until the next
        // top-level field.
        val summaryLines = mutableListOf<String>()
        val findingLines = mutableListOf<String>()
        val artifactLines = mutableListOf<String>()
        val gapLines = mutableListOf<String>()

        val proseLines = mutableListOf<String>()

        var current: String? = null
        var declaredStatus: String? = null
        for (raw in report.lines()) {
            val line = raw.trimEnd()
            // [fix/subagent-contract-prose-loss] A BLANK line ends a SCALAR
            // field's body.
            //
            // `summary` and `gaps` are prose fields: the contract writes them
            // over 3-5 lines and the next field follows directly. Without this
            // rule the field swallowed every following line until the next
            // header — so `gaps: none` followed by a blank line and a closing
            // paragraph became a 500-char "gaps" and the paragraph was lost
            // from both the gaps and the prose.
            //
            // LIST fields are exempt on purpose: a bullet list may be written
            // with blank lines between items, and ending the section there
            // would silently drop the second half of the findings.
            if (line.isBlank() && (current == FIELD_SUMMARY || current == FIELD_GAPS)) {
                current = null
                continue
            }
            val header = FIELD_HEADER.matchEntire(line)
            val label = header?.groupValues?.get(1)?.trim()?.lowercase()?.replace(" ", "_")
            val canonical = label?.let { LABEL_ALIASES[it] ?: it }
            val isHeader = header != null && canonical != null &&
                canonical in CANONICAL_LABELS
            if (isHeader) {
                // A header's INLINE value takes the same validation as a body
                // line. These two paths grew apart: the body checked
                // `looksLikePath` / `normalizeStatus`, the inline value went
                // straight into the field. So `artifacts: none` became the
                // deliverable "none" and `status: mostly finished` became a
                // null status with the words deleted — the exact defects the
                // body path had already been fixed for.
                val inline = header!!.groupValues[3].trim()
                current = canonical
                when (canonical) {
                    FIELD_SUMMARY -> if (inline.isNotBlank()) summaryLines += inline
                    FIELD_KEY_FINDINGS -> if (inline.isNotBlank()) findingLines += inline
                    FIELD_GAPS -> if (inline.isNotBlank()) gapLines += inline
                    FIELD_ARTIFACTS -> if (inline.isNotBlank()) {
                        val entry = stripBullet(inline)
                        if (looksLikePath(entry)) artifactLines += entry else proseLines += entry
                    }
                    // The status is read here, from the TAIL-most status
                    // line — the same "most recent self-assessment wins"
                    // rule the existing parser uses. See below for why
                    // this object keeps its own read.
                    FIELD_STATUS -> if (inline.isNotBlank()) {
                        val normalized = normalizeStatus(inline)
                        if (normalized != null) {
                            declaredStatus = normalized
                        } else {
                            // An unrecognised token is still text the model
                            // wrote; prose is the only honest place for it.
                            proseLines += inline
                        }
                    }
                    else -> Unit
                }
                continue
            }
            when (current) {
                FIELD_SUMMARY -> summaryLines += line.trim()
                FIELD_KEY_FINDINGS -> {
                    val raw = line.trim()
                    // [feat/subagent-truncation-envelope] The findings
                    // section is a LIST — the contract writes it as
                    // bullets. A line that is not a bullet is prose that
                    // followed the list, and it belongs to the prose
                    // (the reasoning), not to the findings.
                    if (isBullet(raw)) {
                        findingLines += stripBullet(raw)
                    } else {
                        proseLines += raw
                    }
                }
                FIELD_ARTIFACTS -> {
                    val entry = stripBullet(line.trim())
                    // [feat/subagent-truncation-envelope] Only a PATH is an
                    // artifact. Without this check, any line that follows
                    // an `artifacts:` header is taken as a deliverable —
                    // including a paragraph of prose, which would then be
                    // rendered as a comma-separated list of "paths" and
                    // would blow the envelope's budget.
                    //
                    // A line that is NOT a path is prose that happened to
                    // follow the header — it goes to the prose, not to
                    // the artifacts.
                    if (looksLikePath(entry)) artifactLines += entry else proseLines += line.trim()
                }
                FIELD_GAPS -> gapLines += line.trim()
                // A `status:` header with the value on the NEXT line —
                // the contract allows it, and the old tail-scanning
                // parser found it.
                //
                // [fix/subagent-contract-prose-loss] Everything ELSE after a
                // `status:` is PROSE. This branch used to be
                // `else -> Unit`, so every line between `status:` and the next
                // field header was silently dropped — not into the prose, not
                // into any field, nowhere.
                //
                // That is the contract's own documented layout: SKILL.md says
                // `summary` → `status` → the evidence, and a model that
                // explains its status ("done, but the second provider is
                // still unverified") had that explanation deleted from every
                // delivery surface. Measured on a realistic report: a whole
                // paragraph vanished and `prose` came back empty, so the
                // parent saw a verdict with no reasoning.
                FIELD_STATUS -> {
                    if (declaredStatus == null && line.isNotBlank()) {
                        val normalized = normalizeStatus(line.trim())
                        if (normalized != null) {
                            declaredStatus = normalized
                        } else {
                            // [fix/subagent-contract-prose-loss] An unrecognised
                            // token is still text the model wrote. Dropping it
                            // would lose the sentence; the contract has no
                            // place for "a status I did not understand", and
                            // prose is exactly that place.
                            proseLines += line.trim()
                        }
                    } else if (line.isNotBlank()) {
                        proseLines += line.trim()
                    }
                }
                else -> Unit
            }
            // Text BEFORE the first contract field is prose too — a
            // sub-agent that writes an intro before `summary:` put its
            // account there, and dropping it would lose the only
            // narrative the run produced.
            if (current == null && line.isNotBlank()) proseLines += line.trim()
        }

        val findings = findingLines.filter { it.isNotBlank() }
        val artifacts = artifactLines.filter { it.isNotBlank() }
        val gaps = gapLines.joinToString("\n").trim().ifBlank { null }
        val summary = summaryLines.joinToString(" ").trim().ifBlank { null }

        // The DECLARED status: "done" / "partial" / "failed" / null.
        //
        // The existing [SubagentSkill.parseReportStatus] answers a
        // DIFFERENT question — "does the model want the parent warned?"
        // — and returns null for "done" on purpose. This field must
        // report "done", because "the model declared it was done" is a
        // real answer that the parent may want, and because a status
        // that can only ever be partial/failed cannot distinguish a
        // clean run from a run that simply said nothing.
        //
        // The two readers share the SAME vocabulary (the existing
        // parser's token sets) and the SAME "most recent wins" rule, so
        // they cannot drift apart on what a token MEANS.
        val status = declaredStatus
        val prose = proseLines.joinToString("\n").trim()

        if (summary != null) {
            return ParsedReport(
                summary = summary,
                summarySource = SummarySource.MODEL,
                status = status,
                findings = findings,
                gaps = gaps,
                artifacts = artifacts,
                prose = prose,
            )
        }
        // Degradation step 2: salvage the first finding as the summary.
        // Recorded as KEY_FINDINGS so the parent knows it was not written
        // for this purpose.
        val derived = findings.firstOrNull()
        if (derived != null) {
            return ParsedReport(
                summary = derived,
                summarySource = SummarySource.KEY_FINDINGS,
                status = status,
                findings = findings,
                gaps = gaps,
                artifacts = artifacts,
                prose = prose,
            )
        }
        // Step 3: nothing. Absence is reported, never papered over.
        return ParsedReport(
            summary = null,
            summarySource = SummarySource.NONE,
            status = status,
            findings = findings,
            gaps = gaps,
            artifacts = artifacts,
            prose = prose,
        )
    }

    /**
     * The contract's canonical field labels.
     */
    private val CANONICAL_LABELS = setOf(
        FIELD_SUMMARY,
        FIELD_STATUS,
        FIELD_KEY_FINDINGS,
        FIELD_GAPS,
        FIELD_ARTIFACTS,
    )

    /**
     * Normalize a declared status token to the contract's vocabulary:
     * "done" / "partial" / "failed", or null when unrecognised.
     *
     * The token sets are the EXISTING ones
     * ([SubagentSkill.REPORT_FAILED_STATUS_TOKENS] and
     * [SubagentSkill.REPORT_PARTIAL_STATUS_TOKENS]) — the two readers of
     * this field must not disagree about what a token means. "done" is
     * added here because the existing reader deliberately treats it as
     * "no annotation", while this reader reports it as a declaration.
     */
    private fun normalizeStatus(raw: String): String? {
        val token = raw.substringBefore('(').substringBefore('（').trim().lowercase()
        return when {
            token in SubagentSkill.REPORT_FAILED_STATUS_TOKENS -> "failed"
            token in SubagentSkill.REPORT_PARTIAL_STATUS_TOKENS -> "partial"
            token == "done" || token == "完成" -> "done"
            else -> null
        }
    }

    /**
     * Does this line look like a filesystem path?
     *
     * [feat/subagent-truncation-envelope] The `artifacts:` section lists
     * paths. A line that does not look like one is prose that happened to
     * follow the header — and taking it as a path produced an envelope
     * that listed a paragraph as a deliverable.
     *
     * [fix/subagent-artifact-path-truth] "Contains a dot" is not a path test.
     * It accepted `No artifacts were produced.` (the last word, `produced.`,
     * carries the dot) and `see the report above.` — so a model that answered
     * the field in a sentence handed the parent invented deliverables. The
     * test is now about SHAPE:
     *
     *  - an absolute or relative path has a separator and no whitespace;
     *  - a bare filename has a 1-8 alphanumeric extension and does not end in
     *    sentence punctuation.
     *
     * A sentence can contain a separator (`/var/minis/... was written`), so
     * the whitespace check comes FIRST — it is what separates a path from
     * prose about a path.
     */
    private fun looksLikePath(line: String): Boolean {
        val text = line.trim().trim('`', '"', '\'').trim()
        if (text.isBlank()) return false
        // A path never contains whitespace. A sentence about a path usually
        // does, so this alone rejects most prose.
        if (text.any { it.isWhitespace() }) return false
        if (text.any { it == '。' || it == '，' || it == '、' }) return false
        if (text.endsWith('.')) return false
        if (NO_ARTIFACT_TOKENS.contains(text.lowercase())) return false
        // An absolute / relative path: contains a separator AND at least one
        // non-separator character. A bare "/" is not a deliverable; a
        // directory ("…/subagent-1/") is.
        if (text.contains('/')) return text.any { it != '/' }
        // A bare filename: needs a plausible extension, and the name part must
        // exist ("md" alone is not a file, ".env" is).
        val name = text.substringBefore(' ')
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return false
        val ext = name.substring(dot + 1)
        return ext.length in 1..8 && ext.all { it.isLetterOrDigit() }
    }

    /**
     * [fix/subagent-artifact-path-truth] Words a model writes INSTEAD of a
     * path. "none" is already handled by the shape test (no separator, no
     * extension) — this list catches the ones that look path-shaped.
     */
    private val NO_ARTIFACT_TOKENS = setOf(
        "none", "n/a", "na", "null", "-", "—", "无", "没有",
        "none.", "n/a.", "no", "nothing",
    )

    /**
     * Strip a leading list bullet from a body line.
     *
     * The contract's list fields are written as bullets ("- conclusion
     * (anchor)"), and the bullet is PRESENTATION — the finding is the
     * text after it. Keeping the bullet would put "- " in front of every
     * line the parent reads, including the derived summary.
     */
    private fun isBullet(line: String): Boolean =
        line.startsWith("- ") || line.startsWith("* ") || line.startsWith("+ ")

    private fun stripBullet(line: String): String = when {
        line.startsWith("- ") || line.startsWith("* ") -> line.substring(2).trim()
        line.startsWith("+ ") -> line.substring(2).trim()
        else -> line
    }

    /**
     * Labels the runtime accepts as the same field. The contract is
     * written in English, but the parent may be Chinese, and the
     * existing status parser already accepts the Chinese forms — the
     * two parsers must not disagree about what a field is.
     */
    private val LABEL_ALIASES = mapOf(
        "摘要" to FIELD_SUMMARY,
        "总结" to FIELD_SUMMARY,
        "结论摘要" to FIELD_SUMMARY,
        "结果状态" to FIELD_STATUS,
        "状态" to FIELD_STATUS,
        "关键发现" to FIELD_KEY_FINDINGS,
        "要点" to FIELD_KEY_FINDINGS,
        "未找到" to FIELD_GAPS,
        "缺口" to FIELD_GAPS,
        "遗留" to FIELD_GAPS,
        "产物" to FIELD_ARTIFACTS,
        "产出" to FIELD_ARTIFACTS,
    )
}
