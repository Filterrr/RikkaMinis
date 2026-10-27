package com.openminis.app.tools

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [feat/subagent-roster-routing] A Compose MATERIAL EXPERIMENTAL API used
 * without the opt-in is a COMPILE ERROR here, not a warning.
 *
 * The build enables `allWarningsAsErrors`, so "This material API is
 * experimental and is likely to change" fails the build. The local JVM
 * script cannot compile the UI packages (they need Compose and
 * lifecycle), so the CI is the only place this shows up — and the CI
 * caught it, after the feature was already written and merged locally.
 *
 * The check is textual: a file that uses a known experimental API must
 * either annotate the composable with `@OptIn(ExperimentalMaterial3Api::class)`
 * or annotate the file with `@file:OptIn(...)`.
 */
class SubagentExperimentalApiGuardTest {

    /**
     * The experimental APIs this subsystem's UI uses, and the opt-in marker
     * each needs.
     */
    private val experimentalApis = mapOf(
        "ExposedDropdownMenuBox" to "ExperimentalMaterial3Api",
        "ExposedDropdownMenuDefaults" to "ExperimentalMaterial3Api",
        "TopAppBar" to "ExperimentalMaterial3Api",
        "PullToRefreshBox" to "ExperimentalMaterial3Api",
    )

    /**
     * Strip line and block comments, preserving line structure.
     */

    /**
     * Is the use at [useIndex] inside the scope of the opt-in at
     * [optInIndex]?
     *
     * An opt-in covers the declaration that follows it. A use further
     * down, in a SIBLING declaration, is NOT covered by it — that
     * declaration has to opt in for itself.
     *
     * The rule used here: the opt-in covers the use when there is no
     * intervening `fun`/`class`/`object` declaration at the SAME
     * indentation level. A nested lambda (setContent { ... }) is not a
     * new declaration, so it stays inside the scope — which is exactly
     * how the Kotlin rule behaves.
     */


    @Test
    fun `a file using an experimental material API opts in`() {
        val root = com.openminis.app.ProductionSources.mainRoot() ?: return
        val offenders = mutableListOf<String>()
        for (file in root.walkTopDown().filter { it.isFile && it.extension == "kt" }) {
            // COMMENTS are stripped: a mention of an API in a comment is
            // not a USE of it. (The first version of this guard flagged a
            // dozen files whose only "TopAppBar" was in a prose comment.)
            val text = SubagentSourceText.stripComments(file.readText())
            // An IMPORT alone does not trigger the error — only a USE
            // does. The import lines are removed before the search, or a
            // file that imports an API it never calls (there are several)
            // would be flagged.
            val body = text
                .lines()
                .filterNot { it.trimStart().startsWith("import ") }
                .joinToString("\n")
            val uses = experimentalApis.keys.filter { api -> body.contains(api) }
            if (uses.isEmpty()) continue
            val needs = experimentalApis.filterKeys { it in uses }.values.toSet()
            // A file-level opt-in covers everything.
            val fileOptsIn = text.contains("@file:OptIn(") &&
                needs.all { text.contains("$it::class") }
            if (fileOptsIn) continue

            // Otherwise the opt-in must appear BEFORE the use. Matching the
            // annotation to a specific composable is too brittle: the
            // opt-in is often on an OUTER function and the use sits in a
            // nested lambda (setContent { ... }), which a "annotation
            // immediately precedes fun" regex cannot see.
            //
            // "before" is the rule that matters anyway — an annotation
            // after the use cannot possibly cover it.
            // EVERY use must be covered.
            //
            // The unit is the DECLARATION: a top-level `fun`/`class`
            // starts a scope, and the `@OptIn` that precedes it (or a
            // `@file:OptIn`) covers everything inside it — including
            // nested lambdas, which is exactly how the Kotlin rule
            // behaves.
            //
            // This is finer than "the file opts in somewhere" (which
            // missed a file with two composables where only one opted in)
            // and coarser than "the annotation immediately precedes the
            // use" (which missed an opt-in on an outer function whose use
            // sits in a nested setContent lambda).
            val uncovered = mutableListOf<String>()
            var currentScopeApis = mutableSetOf<String>()
            var fileApis = mutableSetOf<String>()
            // The opt-in waiting for the NEXT declaration.
            var pendingScopeApis = mutableSetOf<String>()
            var sawAnnotation = false
            // The indentation of the opt-in that is currently in force.
            var optInIndent = -1
            val lines = body.split('\n')
            for ((lineNo, line) in lines.withIndex()) {
                val trimmed = line.trim()
                val indent = line.takeWhile { it == ' ' }.length
                val optInMatch = Regex("""@OptIn\(([^)]*)\)""").find(line)
                // A generic function is `fun <T> Name(` — the type
                // parameter sits between `fun` and the name, so the
                // declaration pattern must allow it. (Without this, every
                // generic composable looked like a new declaration and
                // reset the scope.)
                //
                // The declaration need NOT be top-level: a composable
                // inside an `object` is indented, and its `@OptIn` is too.
                // The scope boundary is a declaration at the SAME level as
                // the annotation (or shallower).
                val isDeclaration = Regex(
                    """^(?:public\s+|internal\s+|private\s+|open\s+|override\s+|suspend\s+)*(?:fun\s*<[^>]*>\s*|fun\s+|class\s+|object\s)""",
                ).containsMatchIn(trimmed)
                val isScopeBoundary = isDeclaration && optInIndent >= 0 && indent <= optInIndent
                if (optInMatch != null) {
                    // An opt-in that immediately precedes a declaration
                    // BELONGS to that declaration — it annotates it. The
                    // scope starts AT the declaration, not at the
                    // annotation, so the declaration line must not reset
                    // it.
                    pendingScopeApis = optInMatch.groupValues[1]
                        .split(',')
                        .map { it.trim().substringBefore("::class") }
                        .filter { it.isNotEmpty() }
                        .toMutableSet()
                    sawAnnotation = true
                    optInIndent = indent
                } else if (isScopeBoundary) {
                    // A new top-level declaration. If an opt-in was
                    // waiting for it, that is the one it belongs to;
                    // otherwise it starts with only the file-level scope.
                    currentScopeApis = if (sawAnnotation) {
                        pendingScopeApis.toMutableSet()
                    } else {
                        fileApis.toMutableSet()
                    }
                    pendingScopeApis = mutableSetOf()
                    sawAnnotation = false
                }
                for ((api, requiredMarker) in experimentalApis) {
                    if (!Regex("""\b""" + Regex.escape(api) + """\b""").containsMatchIn(trimmed)) continue
                    // The scope holds OPT-IN MARKERS (ExperimentalMaterial3Api),
                    // not the API names. Comparing the API name against the
                    // marker set never matched, so every use looked
                    // uncovered.
                    if (requiredMarker in currentScopeApis) continue
                    uncovered += "${file.name}:${lineNo + 1} $api (needs $requiredMarker)"
                }
            }
            if (uncovered.isNotEmpty()) {
                offenders += uncovered.joinToString("\n   ")
            }
        }
        assertTrue(
            "experimental material APIs used without the opt-in:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }
}
