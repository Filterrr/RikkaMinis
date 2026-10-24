package com.openminis.app.tools

/**
 * [fix/subagent-artifact-path-truth] Makes the RUNTIME the authority on an
 * artifact's path, and repairs the model's self-reported paths against it.
 *
 * ## The defect
 *
 * A sub-agent writes a deliverable and then describes it in its report. The
 * description is prose: the model can misspell the directory it was given,
 * drop a level, use a relative path, or simply invent a plausible-looking
 * one. The runtime, meanwhile, knows the TRUTH — the `path` argument of every
 * successful `file_write` / `file_edit` call.
 *
 * Observed (2026-10-24, real run `subagent-3`): the file was really written
 * to
 *
 *     /var/minis/workspace/subagents/subagent-3/parallel_file_read_report.md
 *
 * and the model's report claimed
 *
 *     /var/minis/workspace/sub/subagents/subagent-3/parallel_file_read_report.md
 *
 * The parent received BOTH: the runtime's `Artifacts written:` line and the
 * model's `artifacts:` line. Nothing told it which one to trust, and the
 * model's line is the one a reader (human or model) looks at first. A
 * parent that followed it would `file_read` a path that does not exist —
 * and the run looked broken even though it was complete.
 *
 * The runtime's own artifact list had a second, quieter defect: it stored
 * the `path` argument verbatim, so a relative path stayed relative and
 * `./x` / `a//b` were never normalised. Those strings are not resolvable
 * anchors either.
 *
 * ## The rule
 *
 * The runtime's write log is the authority for what the run produced. The
 * model's prose is the authority for what it MEANT to say. The repair keeps
 * both:
 *
 * - a claimed path that matches a recorded one (exactly, or by file name)
 *   is rewritten to the recorded one, keeping the model's description;
 * - a claimed path the runtime never recorded is KEPT — it may have been
 *   produced by `shell_execute`, which the runtime does not log — but it is
 *   explicitly marked as unverified, so the parent is not misled into
 *   treating it as a confirmed deliverable;
 * - a recorded path the model forgot to mention is ADDED, so the contract's
 *   fourth field is never silently incomplete.
 *
 * Nothing is deleted. A repair that removes information is a different kind
 * of defect.
 *
 * ## Why this is an object and not a patch
 *
 * The repair happens where three facts meet: the model's text, the runtime's
 * write log, and the run's own artifacts directory. Each of those is
 * available at a different call site, and the earlier defects in this area
 * were all "the call site passed the wrong fact". So the reconciliation is
 * one pure function of three inputs — testable, and impossible to invoke
 * with the wrong arguments in a different order.
 *
 * Pure + JVM-testable: no Android, no registry, no clock, no filesystem.
 */
object SubagentArtifactTruth {

    /**
     * The outcome of reconciling a report against the runtime's write log.
     *
     * @param report the report with the paths repaired.
     * @param authoritativePaths the canonical paths the runtime recorded.
     * @param correctedPaths pairs of (claimed, authoritative) where the
     *   model's spelling was repaired.
     * @param unverifiedPaths paths the model cited that the runtime did NOT
     *   record. Kept in the report, explicitly marked as unverified.
     */
    data class Reconciliation(
        val report: String,
        val authoritativePaths: List<String>,
        val correctedPaths: List<Pair<String, String>>,
        val unverifiedPaths: List<String>,
    ) {
        /** True when the report claimed a path that was not the real one. */
        val hadDrift: Boolean get() = correctedPaths.isNotEmpty() || unverifiedPaths.isNotEmpty()
    }

    /** Root of the session sandbox — the only path space the runtime can reason about. */
    private const val MINIS_ROOT = "/var/minis/"

    /**
     * Absolute paths inside the session sandbox. Only these are candidates
     * for repair: a URL (`https://example.com/a.txt`) or a host path
     * (`/tmp/x`) is not the runtime's business and must survive untouched.
     *
     * The character class stops at whitespace and at the punctuation that
     * typically ends a sentence, so a path in prose is captured without
     * dragging the following comma or full stop along.
     */
    private val ABS_MINIS_PATH = Regex(
        """/var/minis/[^\s"'`，。、；：)）\],]+""",
    )

    /**
     * A RELATIVE path with a directory part (`notes/report.md`) — a relative
     * path with no directory part (`report.md`) is just a file name, and is
     * matched separately against the recorded file names.
     *
     * The lookbehind keeps the pattern out of a URL: in
     * `https://example.com/a.txt` the candidate `example.com/a.txt` is
     * preceded by `/`, which the lookbehind rejects.
     */
    private val RELATIVE_PATH = Regex(
        """(?<![/\w.\-])[\w.\-]+(?:/[\w.\-]+)+(?:\.\w+)?""",
    )

    /** A bare file name (`report.md`) — resolved against the artifacts dir. */
    private val BARE_FILENAME = Regex(
        """(?<![/\w.\-])[\w\-]+\.\w+""",
    )

    /** The `artifacts:` section header of the report contract. */
    private val SECTION_HEADER = Regex(
        """^([ \t]*(?:[-*]\s+)?)(artifacts)(\s*[:：])(.*)$""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * A new TOP-LEVEL contract field — ends an `artifacts:` section.
     * A list bullet (`- …`) is not a top-level field, even when the bullet's
     * text contains a colon.
     *
     * Note: this is matched with `containsMatchIn`, NOT `matches` —
     * `matches` demands a FULL-string match, so a pattern without a trailing
     * `.*` would never fire and the section would swallow the rest of the
     * report. (That bug cost one debugging round here.)
     */
    private val NEW_TOP_LEVEL_FIELD = Regex("""^(?![-*+>|\s])[^:：]*[:：]""")

    /** Punctuation that may trail a path in prose and must not be part of it. */
    private const val TRAILING_PUNCT = ".,;:。，；："

    /** Marker for a path the runtime never recorded. */
    private const val UNVERIFIED_MARK = "(unverified — not in the runtime's write log)"

    /**
     * Canonical form of [rawPath] as seen from [artifactsDir].
     *
     * - relative paths are resolved against the run's artifacts directory;
     * - `.` and `..` segments are folded;
     * - duplicate slashes collapse;
     * - a trailing slash is dropped (a directory is not an artifact).
     *
     * An absolute path keeps its own root — it is already resolved — but
     * still gets its segments folded.
     */
    fun canonicalize(rawPath: String, artifactsDir: String): String {
        val trimmed = rawPath.trim().trim('`', '"', '\'')
        if (trimmed.isEmpty()) return ""
        val absolute = when {
            trimmed.startsWith("/") -> trimmed
            artifactsDir.isBlank() -> trimmed
            else -> artifactsDir.trimEnd('/') + "/" + trimmed
        }
        return normalizeSegments(absolute)
    }

    /** Fold `.` / `..` / duplicate slashes; keep the leading slash for absolute paths. */
    private fun normalizeSegments(path: String): String {
        val isAbsolute = path.startsWith("/")
        val out = ArrayList<String>()
        for (segment in path.split('/')) {
            when {
                segment.isEmpty() || segment == "." -> Unit
                segment == ".." -> when {
                    out.isNotEmpty() && out.last() != ".." -> out.removeAt(out.size - 1)
                    !isAbsolute -> out.add("..")
                    else -> Unit
                }
                else -> out.add(segment)
            }
        }
        val joined = out.joinToString("/")
        return if (isAbsolute) "/$joined" else joined
    }

    /**
     * Reconcile [report] against the runtime's write log.
     *
     * @param report the model's report text.
     * @param recordedPaths the paths the runtime recorded for successful
     *   `file_write` / `file_edit` calls (raw, as the tool was called).
     * @param artifactsDir this run's artifacts directory — the base for
     *   resolving relative paths.
     */
    fun reconcile(
        report: String,
        recordedPaths: List<String>,
        artifactsDir: String,
    ): Reconciliation {
        val authoritative = recordedPaths
            .map { canonicalize(it, artifactsDir) }
            .filter { it.isNotEmpty() }
            .distinct()

        if (report.isBlank() && authoritative.isEmpty()) {
            return Reconciliation(report, authoritative, emptyList(), emptyList())
        }

        val corrected = mutableListOf<Pair<String, String>>()

        // ── 1. repair every absolute sandbox path mentioned in the text ──
        var text = ABS_MINIS_PATH.replace(report) { match ->
            val raw = match.groupValues[0]
            val trailing = raw.takeLastWhile { it in TRAILING_PUNCT }
            val core = raw.dropLast(trailing.length)
            if (core.isEmpty()) return@replace match.value
            val canonical = canonicalize(core, artifactsDir)
            when {
                canonical in authoritative -> {
                    if (canonical != core) corrected += core to canonical
                    canonical + trailing
                }
                else -> {
                    val byName = authoritative.filter { basename(it) == basename(core) }
                    if (byName.size == 1) {
                        corrected += core to byName.first()
                        byName.first() + trailing
                    } else {
                        match.value
                    }
                }
            }
        }

        // ── 2. rebuild the `artifacts:` section from the authoritative list ──
        val section = rebuildArtifactsSection(text, authoritative, artifactsDir)
        text = section.text
        val unverified = section.unverified

        // ── 3. say out loud which cited paths the runtime did not record ──
        if (unverified.isNotEmpty()) {
            val note = "⚠️ artifact path(s) cited in this report were NOT recorded by the runtime's " +
                "file-write log (they may have been produced by shell_execute, or may not exist): " +
                unverified.joinToString(", ")
            text = text.trimEnd() + "\n" + note
        }

        return Reconciliation(
            report = text,
            authoritativePaths = authoritative,
            correctedPaths = corrected.distinct(),
            unverifiedPaths = unverified.distinct(),
        )
    }

    /** The rebuilt section plus the paths it could not verify. */
    private data class SectionResult(
        val text: String,
        val unverified: List<String>,
    )

    /**
     * Rebuild the report's `artifacts:` section so that the runtime's
     * recorded paths lead, the model's own descriptions survive, and any
     * path the runtime never recorded is kept but explicitly marked.
     *
     * When the report has no `artifacts:` section at all, one is appended —
     * the contract has four fields and a run with real artifacts must not
     * silently omit the fourth.
     */
    private fun rebuildArtifactsSection(
        text: String,
        authoritative: List<String>,
        artifactsDir: String,
    ): SectionResult {
        val lines = text.lines()
        val headerIndex = lines.indexOfFirst { SECTION_HEADER.matches(it.trimEnd()) }
        if (headerIndex < 0) {
            if (authoritative.isEmpty()) return SectionResult(text, emptyList())
            val appended = buildString {
                append(text.trimEnd())
                appendLine()
                appendLine()
                appendLine("artifacts:")
                authoritative.forEach { appendLine("- $it") }
            }
            return SectionResult(appended, emptyList())
        }

        val header = SECTION_HEADER.find(lines[headerIndex].trimEnd())!!
        val headerPrefix = header.groupValues[1].trimEnd()
        val inlineRemainder = header.groupValues[4].trim()

        // The section body runs until the next top-level contract field.
        var bodyEnd = headerIndex + 1
        while (bodyEnd < lines.size) {
            val line = lines[bodyEnd]
            if (line.isBlank()) { bodyEnd++; continue }
            if (NEW_TOP_LEVEL_FIELD.containsMatchIn(line) || line.trimStart().startsWith("#")) break
            bodyEnd++
        }

        val originalBody = buildList {
            if (inlineRemainder.isNotEmpty()) add(inlineRemainder)
            addAll(lines.subList(headerIndex + 1, bodyEnd))
        }

        // ── 2a. repair each body line in place, keeping its description ──
        val unverified = mutableListOf<String>()
        val mentioned = mutableSetOf<String>()
        val repairedLines = originalBody.map { line ->
            var worked = false
            val repaired = replaceSandboxPaths(line, authoritative, artifactsDir) { claim ->
                worked = true
                val canonical = canonicalize(claim, artifactsDir)
                val match = authoritative.firstOrNull {
                    it == canonical || basename(it) == basename(canonical)
                }
                when {
                    match != null -> {
                        mentioned += match
                        match
                    }
                    else -> {
                        unverified += canonical
                        null
                    }
                }
            }
            if (worked) repaired else line
        }

        // ── 2b. add the recorded paths the model never mentioned ──
        val missing = authoritative.filter { it !in mentioned }
        val rebuilt = buildString {
            append(headerPrefix)
            append("artifacts:")
            missing.forEach { path ->
                appendLine()
                append("- $path")
            }
            repairedLines.forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty()) return@forEach
                appendLine()
                append(if (trimmed.startsWith("-") || trimmed.startsWith("*")) {
                    trimmed
                } else {
                    "- $trimmed"
                })
            }
        }

        val out = ArrayList<String>(lines.size)
        out.addAll(lines.subList(0, headerIndex))
        out.add(rebuilt)
        out.addAll(lines.subList(bodyEnd, lines.size))
        // Folding the section can leave blank lines behind; keep it tidy.
        val joined = out.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trimEnd()
        return SectionResult(joined, unverified.distinct())
    }

    /**
     * Replace the sandbox paths in [line] using [resolve].
     *
     * @param resolve returns the replacement path, or null to leave the claim
     *   untouched (it is then reported as unverified by the caller).
     */
    private inline fun replaceSandboxPaths(
        line: String,
        authoritative: List<String>,
        artifactsDir: String,
        crossinline resolve: (String) -> String?,
    ): String {
        var result = line
        // Absolute sandbox paths first.
        result = ABS_MINIS_PATH.replace(result) { match ->
            val raw = match.groupValues[0]
            val trailing = raw.takeLastWhile { it in TRAILING_PUNCT }
            val core = raw.dropLast(trailing.length)
            if (core.isEmpty()) return@replace match.value
            resolve(core)?.let { it + trailing } ?: match.value
        }
        // Then relative paths with a directory part.
        result = RELATIVE_PATH.replace(result) { match ->
            val raw = match.groupValues[0]
            resolve(raw)?.let { it } ?: match.value
        }
        // Then a bare file name — resolved against the artifacts directory.
        result = BARE_FILENAME.replace(result) { match ->
            val raw = match.groupValues[0]
            val byName = authoritative.filter { basename(it) == raw }
            if (byName.size == 1) resolve(raw)?.let { it } ?: match.value else match.value
        }
        return result
    }

    /** File name of a path (`""` for a path that ends in a slash). */
    private fun basename(path: String): String = path.trimEnd('/').substringAfterLast('/')
}
