package com.openminis.app.tools

/**
 * [feat/subagent-roster-routing] Source-text helpers for the guards.
 *
 * The guards read the Kotlin sources textually, because the files they
 * check (the runner, the UI screens) cannot be compiled by the local
 * JVM script — they need Android, Compose and lifecycle.
 *
 * ## Why the comment stripper exists
 *
 * It was written THREE times, once per guard, and each copy had to be
 * fixed after the guard reported a dozen files whose only mention of an
 * API was in prose. A comment is not a USE: requiring an import, an
 * opt-in, or an error message for something written in a KDoc is wrong.
 *
 * One implementation, used by all the guards — so the next guard does
 * not have to learn the same lesson.
 */
internal object SubagentSourceText {

    /**
     * Strip line and block comments, preserving line structure so that
     * line numbers stay meaningful.
     *
     * A string literal is preserved as-is (a double-slash inside a
     * string is not a comment), and a triple-quoted Kotlin string is
     * treated as a string too — otherwise a raw string containing a
     * comment terminator would end the comment early and the rest of the
     * file would be misread.
     */
    fun stripComments(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        var inBlock = false
        while (i < text.length) {
            if (inBlock) {
                when {
                    text.startsWith("*/", i) -> { inBlock = false; i += 2 }
                    text[i] == '\n' -> { out.append('\n'); i++ }
                    else -> i++
                }
                continue
            }
            // A raw string (""" ... """) can contain anything, including
            // comment markers.
            if (text.startsWith("\"\"\"", i)) {
                val end = text.indexOf("\"\"\"", i + 3)
                val stop = if (end < 0) text.length else end + 3
                out.append(text, i, stop)
                i = stop
                continue
            }
            if (text.startsWith("/*", i)) { inBlock = true; i += 2; continue }
            if (text.startsWith("//", i)) {
                while (i < text.length && text[i] != '\n') i++
                continue
            }
            if (text[i] == '"') {
                out.append(text[i]); i++
                while (i < text.length && text[i] != '"' && text[i] != '\n') {
                    if (text[i] == '\\' && i + 1 < text.length) { out.append(text[i]); i++ }
                    out.append(text[i]); i++
                }
                if (i < text.length) { out.append(text[i]); i++ }
                continue
            }
            out.append(text[i]); i++
        }
        return out.toString()
    }

    /**
     * The source with the comments removed and the import lines dropped.
     *
     * An IMPORT alone does not trigger a compile error — only a USE
     * does — so a file that imports an API it never calls must not be
     * reported.
     */
    fun codeOnly(text: String): String =
        stripComments(text)
            .lines()
            .filterNot { it.trimStart().startsWith("import ") }
            .joinToString("\n")
}
