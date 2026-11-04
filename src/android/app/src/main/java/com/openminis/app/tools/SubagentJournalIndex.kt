package com.openminis.app.tools

/**
 * [feat/subagent-journal-index] The journal's machine-readable index.
 *
 * WHY
 * ---
 * A journal is the RECOVERY route: when the envelope's body was
 * truncated, the parent is told to `file_read` this file. But the
 * journal was a raw dump — a measured 283 KB / 5058 lines for one
 * 40-turn research run — so the parent paid tens of thousands of
 * tokens to open it and then had to FIND the conclusion inside it by
 * eye.
 *
 * [fix/subagent-journal-navigability] The first version of this index named
 * the sections but not WHERE they were (“## Report holds the conclusions”),
 * so the parent still had to open the whole file to act on that advice. An
 * index without offsets is a table of contents without page numbers.
 *
 * The index now reports, per section, a LINE RANGE and a byte size, plus a
 * judgement about whether that section is worth reading:
 *
 *     - sections (line ranges, read the range you need):
 *         report:     L46-L58   (1.8 KB)  <- read this first: the conclusions
 *         transcript: L59-L276  (190 KB)  <- evidence only, skip by default
 *
 * WHY THE BLOCK IS FIXED LENGTH
 * -----------------------------
 * The line numbers are about the file CONTAINING this index, so the index
 * changes the numbers it reports. That is only stable if the index itself is
 * a fixed height — which it is: exactly one line per section, and the set of
 * sections is closed. A variable-length index would report offsets that shift
 * every time a finding was added, and the parent would read the wrong lines.
 *
 * WHY IT IS ITS OWN OBJECT
 * -------------------------
 * [SubagentRunJournal] depends on `PRootKernel` — an Android class —
 * so it cannot be compiled or unit-tested on the JVM. The index does
 * not need any of that: it is a function of the run's own fields.
 *
 * That is the same lesson this repository keeps relearning: a pure
 * concern hidden inside an Android-dependent file is a pure concern
 * nobody can test. Extracting it makes the index's wording a local
 * test's business instead of CI's.
 */
object SubagentJournalIndex {

    /** The sections the journal renders, in document order. */
    val SECTIONS = listOf("Task", "Report", "Transcript", "Steps")

    /**
     * [fix/subagent-journal-navigability] The marker a section body carries
     * when its content did not all fit.
     *
     * It is a shared CONSTANT because two places must agree on it: the journal
     * writes it, and [assemble] scans for it to mark the section `SHORTENED` in
     * the index. Two literals with the same value are two literals that drift.
     */
    const val ELISION_MARKER = "… SHORTENED:"

    /**
     * One rendered section: its heading line number (1-based) and its byte
     * size, as measured in the finished document.
     */
    data class SectionSpan(
        val name: String,
        /** 1-based line of the `## <name>` heading, or -1 when absent. */
        val startLine: Int,
        /** 1-based line of the LAST line belonging to this section. */
        val endLine: Int,
        val bytes: Int,
        /** True when the section was shortened to fit its budget. */
        val elided: Boolean = false,
    ) {
        val isEmpty: Boolean get() = startLine < 0

        /** Lines a reader would pass to `file_read(offset=…, lines=…)`. */
        val lineCount: Int get() = if (isEmpty) 0 else endLine - startLine + 1
    }

    /**
     * Render the index.
     *
     * @param run the run — its id, skill, turn budget, steps and segments.
     * @param terminal the terminal status word.
     * @param resultText the delivered report text; parsed for the
     *   declared artifacts, the findings count and the gaps.
     * @param turns the turns executed.
     * @param artifacts the runtime's write log.
     * @param spans where each section ended up in the finished document, or
     *   null when the caller has not laid the document out yet (the
     *   pre-layout probe pass). The shape of the block is IDENTICAL either
     *   way — only the numbers change — which is what keeps them stable.
     */
    fun render(
        run: SubagentRunRegistry.Run,
        terminal: String,
        resultText: String,
        turns: Int,
        artifacts: List<String>,
        spans: List<SectionSpan>? = null,
    ): String = buildString {
        val ok = run.steps.count { it.status == SubagentRunRegistry.ToolStepStatus.SUCCESS }
        val failed = run.steps.count { it.status == SubagentRunRegistry.ToolStepStatus.FAILED }
        val running = run.steps.count { it.status == SubagentRunRegistry.ToolStepStatus.RUNNING }
        val parsed = if (resultText.isNotBlank()) {
            SubagentReportContract.parse(resultText)
        } else {
            null
        }

        appendLine("## Index")
        appendLine()
        appendLine("- skill: ${run.skillId}")
        appendLine("- turns: $turns/${run.maxTurns}")
        appendLine("- terminal status: $terminal")
        appendLine(
            buildString {
                append("- tool calls: ${run.steps.size}")
                when {
                    run.steps.isEmpty() -> append(" (no tool calls ran)")
                    failed > 0 && running > 0 -> append(" ($ok ok / $failed failed / $running running)")
                    failed > 0 -> append(" ($ok ok / $failed failed)")
                    running > 0 -> append(" ($ok ok / $running running)")
                    else -> append(" ($ok ok)")
                }
            },
        )
        val declared = parsed?.artifacts.orEmpty()
        val allArtifacts = (artifacts + declared).distinct()
        appendLine(
            if (allArtifacts.isEmpty()) "- artifacts: none"
            else "- artifacts: ${allArtifacts.size}\n" +
                allArtifacts.joinToString("\n") { "  - $it" },
        )
        appendLine("- key findings: ${parsed?.findings?.size ?: 0}")
        appendLine("- gaps: ${if (parsed?.gaps.isNullOrBlank()) "none declared" else "declared"}")
        appendLine(
            "- report: " + when {
                resultText.isBlank() -> "none (no text output produced)"
                else -> "${resultText.length} chars"
            },
        )
        // [fix/subagent-journal-navigability] One line per section, always, so
        // the block's height does not depend on the numbers it reports.
        appendLine("- sections (line ranges — read the range you need):")
        val byName = spans?.associateBy { it.name }.orEmpty()
        for (name in SECTIONS) {
            val span = byName[name]
            appendLine(sectionLine(name, span))
        }
    }

    /**
     * One section's index line.
     *
     * The advice column is the point: a parent that reads
     * "transcript: L59-L276 (190 KB)" and nothing else has no idea whether
     * that is cheap or expensive. Saying "skip by default" is worth more than
     * the number next to it.
     */
    private fun sectionLine(name: String, span: SectionSpan?): String {
        val addr = when {
            span == null || span.isEmpty -> "L—       "
            else -> "L${span.startLine}-L${span.endLine}"
        }
        val size = if (span == null || span.isEmpty) "—" else humanSize(span.bytes)
        val elided = if (span?.elided == true) ", SHORTENED to fit" else ""
        val advice = when (name) {
            "Task" -> "the assignment"
            "Report" -> "<- read this first: the conclusions"
            "Transcript" -> "<- evidence only, skip by default"
            "Steps" -> "flat tool index"
            else -> ""
        }
        // Fixed-width columns keep the block the same height AND aligned.
        return "    ${name.padEnd(11)} ${addr.padEnd(14)} ${size.padEnd(9)}$elided  $advice"
    }

    /** Bytes as a short human string — the reader needs scale, not precision. */
    fun humanSize(bytes: Int): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${(bytes / 1024)} KB".let {
            if (bytes < 10 * 1024) "${(bytes * 10 / 1024) / 10.0} KB" else it
        }
        else -> "${bytes / (1024 * 1024)} MB"
    }

    /**
     * [fix/subagent-journal-navigability] How many LINES a block occupies when
     * written out as `append(block)` followed by `appendLine()`.
     *
     * The accounting has to match the writer exactly, which is why this is one
     * function used by both sides. A block that does not end in a newline
     * leaves a partial last line; the `appendLine()` that follows it closes
     * that line rather than starting a new one. Counting the naive
     * `split('\n').size` got this wrong by one for every section whose body
     * was not newline-terminated — and the error accumulated down the file.
     */
    fun lineCount(rendered: String): Int {
        if (rendered.isEmpty()) return 0
        val lines = rendered.count { it == '\n' }
        // A trailing newline already ends the last line; anything else leaves a
        // partial line that the caller's next `appendLine()` will close.
        return if (rendered.endsWith('\n')) lines else lines + 1
    }

    /**
     * [fix/subagent-journal-navigability] The line span a section occupies in
     * the finished document, given the body it will be written with.
     *
     * `## Name` + newline + body + a separating blank line = this many lines.
     * The heading and the body are counted separately because the body may or
     * may not end in a newline.
     */
    fun sectionLineSpan(body: String): Int = 1 + lineCount(body) + 1

    /**
     * [fix/subagent-journal-navigability] Assemble the finished document, with
     * the index's line numbers filled in.
     *
     * WHY THIS LIVES HERE, NOT IN [SubagentRunJournal]
     * ------------------------------------------------
     * The numbering is the whole point of the change and the easiest thing to
     * get wrong, and `SubagentRunJournal` imports `PRootKernel` — an Android
     * class — so it cannot be compiled on the JVM and its output cannot be
     * asserted locally. A layout algorithm inside an untestable file is a
     * layout algorithm nobody can verify.
     *
     * So the journal supplies CONTENT (already redacted) and this function owns
     * the LAYOUT.
     *
     * MEASURED, NOT COMPUTED
     * ----------------------
     * The first version of this function did the line arithmetic in advance
     * (`header + index + separator + span…`) and was wrong twice over:
     *
     *  - `appendLine()` after a body that already ends in "\n" does NOT open a
     *    blank line — it terminates the body's own last line;
     *  - therefore whether a section consumes 1 blank separator or 0 depends on
     *    the body's final byte, and the error compounds section by section.
     *
     * The offsets are now OBSERVED: the document is built once and the heading
     * line numbers are read off the builder as each heading is appended. That
     * removes the whole class of off-by-one rather than fixing one instance of
     * it.
     *
     * THE ONE REMAINING DEPENDENCY
     * ----------------------------
     * The index reports lines *in this file*, and the index is part of it — so
     * its own height has to be known before the sections can be placed. That is
     * a fixed point, not a loop, because the index is FIXED HEIGHT (one line per
     * section from a closed set): render it once to measure, lay out, render it
     * again with the real spans.
     *
     * @param header the document header.
     * @param sections the ordered sections, name to MARKDOWN BODY (no heading).
     * @param renderIndex renders the index block for a given set of spans.
     */
    fun assemble(
        header: String,
        sections: List<Pair<String, String>>,
        renderIndex: (List<SectionSpan>) -> String,
    ): String {
        val probe = renderIndex(emptyList())

        // ── pass 1: build the document, recording where each heading lands ──
        val sb = StringBuilder()
        sb.append(header)
        sb.append(probe)
        sb.append('\n') // the blank line separating the index from the content

        val starts = mutableListOf<Int>()
        val bodies = mutableListOf<String>()
        for ((name, body) in sections) {
            // 1-based line number of the heading: newlines before it, plus one.
            starts += sb.count { it == '\n' } + 1
            bodies += body
            sb.append("## ").append(name).append('\n')
            sb.append(body)
            // A body that did not end in a newline gets one; a body that did
            // keeps its own, so sections are always separated by exactly one
            // blank line regardless of how the caller wrote the body.
            if (!body.endsWith('\n')) sb.append('\n')
        }

        // ── pass 2: the real spans, from what pass 1 actually produced ──
        val text = sb.toString()
        val lineStartOffsets = mutableListOf(0)
        text.forEachIndexed { i, c -> if (c == '\n') lineStartOffsets += i + 1 }
        val totalLines = lineStartOffsets.size - if (text.endsWith('\n')) 1 else 0

        val spans = sections.indices.map { i ->
            val start = starts[i]
            // The section runs until the next heading, or the end of the file.
            val endExclusive = if (i + 1 < starts.size) starts[i + 1] else totalLines + 1
            val end = (endExclusive - 2).coerceAtLeast(start) // minus the blank separator
            val bytes = run {
                val startOff = lineStartOffsets[start - 1]
                val endOff = if (endExclusive - 1 < lineStartOffsets.size) {
                    lineStartOffsets[endExclusive - 1]
                } else text.length
                (endOff - startOff).coerceAtLeast(0)
            }
            SubagentJournalIndex.SectionSpan(
                name = sections[i].first,
                startLine = start,
                endLine = end,
                bytes = bytes,
                elided = bodies[i].contains(SubagentJournalIndex.ELISION_MARKER),
            )
        }

        // ── pass 3: swap in the index that knows the offsets ──
        val finalIndex = renderIndex(spans)
        return buildString {
            append(header)
            append(finalIndex)
            append('\n')
            for ((name, body) in sections) {
                append("## ").append(name).append('\n')
                append(body)
                if (!body.endsWith('\n')) append('\n')
            }
        }
    }
}
