package com.openminis.app.tools

import com.openminis.app.ProductionSources
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [feat/subagent-roster-routing] A source-grep guard for the CROSS-PACKAGE
 * references this change introduced.
 *
 * Why a grep guard and not a compile: the files that use the new helper
 * objects — `ui/chat/ChatViewModel.kt`, `ui/subagent/SubagentUiCommon.kt` —
 * pull in Compose, lifecycle and the whole provider stack, so they cannot be
 * compiled on the plain JVM (and the local harness deliberately does not
 * try). The CI build IS the real compiler check, but it runs minutes later
 * and only after a push — a missing import is a one-line fix that should
 * not cost a CI round trip.
 *
 * So this guard asserts the SHAPE of the fix: every file that names one of
 * the new `tools` helpers must import it. A missing import is exactly what
 * the first CI run caught (`Unresolved reference 'SubagentRosterHint'`), and
 * the same class of mistake recurs whenever a helper is added.
 */
class SubagentCrossPackageImportTest {

    /** The helpers this change moved into the pure `tools` package. */
    private val helpers = listOf(
        "SubagentRosterHint",
        "SubagentRunFormat",
        "SubagentDelegation",
        "SubagentPartialRecovery",
        "SubagentContextTokens",
        "SubagentCheckpointFormat",
        // [feat/subagent-roster-routing] The CI build caught a missing
        // import for EACH of these three in a different file — the same
        // defect class three times. The guard's helper list was too
        // narrow: a `data.repository` or `ui.settings` file that used a
        // `tools` helper was never checked.
        //
        // The rule is now general: ANY file in ANY package that BARELY
        // uses one of these types must import it (unless the file is in
        // the same package as the type).
        "SubagentFrontmatter",
        "SubagentSkill",
        "SubagentToolScope",
        "SubagentTaskFingerprint",
        "SubagentCostLedger",
    )

    /** Files that may legitimately use the helpers without an import. */
    private val samePackage = setOf(
        "SubagentRosterHint.kt",
        "SubagentRunFormat.kt",
        "SubagentDelegation.kt",
        "SubagentPartialRecovery.kt",
        "SubagentContextTokens.kt",
        "SubagentCheckpointFormat.kt",
    )

    private fun productionFiles(): List<java.io.File> {
        val root = ProductionSources.mainRoot()
            ?: error("production source root not found from ${File("").absolutePath}")
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
    }

    @Test
    fun `a public function does not expose an internal type`() {
        // Kotlin: "public type exposes its internal type argument" — a public
        // function whose signature mentions an internal type is a compile
        // error. The CI build caught exactly this when the checkpoint's
        // `InterruptedRun` typealias (internal) was exposed by a public
        // function.
        //
        // The check is textual, not a compiler: it looks for a `public`
        // declaration on one line and an `internal` type name in its
        // signature. It is deliberately narrow — only the types this
        // subsystem actually owns — so it cannot fire on unrelated code.
        // The internal types are DISCOVERED from the sources, not listed.
        //
        // A hand-kept list was the blind spot: the CI build caught a
        // public function exposing `SubagentCostLedger` — a type added
        // AFTER the guard was written, so the guard did not know it. A
        // guard that must be updated when the code changes is a guard that
        // fails exactly when it is needed.
        // The internal types are DISCOVERED from the sources, not listed.
        //
        // A hand-kept list was the blind spot: the CI build caught a
        // public function exposing `SubagentCostLedger` — a type added
        // AFTER the guard was written, so the guard did not know it. A
        // guard that must be updated when the code changes is a guard that
        // fails exactly when it is needed.
        //
        // The discovery is scoped to the SUBSYSTEM's own files. Scanning
        // the whole codebase surfaced unrelated pre-existing patterns
        // (`OffloadArgs`, `FlatChatItem`) whose visibility rules differ
        // (they are exposed through different modules), so the guard would
        // fire on code this change does not own.
        val internalTypes = mutableListOf<String>()
        for (file in productionFiles()) {
            if (file.parentFile?.name != "tools") continue
            val text = file.readText()
            // `internal class X` / `internal object X` / `internal data class X`
            for (m in Regex("""\binternal\s+(?:data\s+|sealed\s+|abstract\s+|value\s+)*(?:class|object|interface|typealias)\s+([A-Za-z_][A-Za-z0-9_]*)""").findAll(text)) {
                internalTypes += m.groupValues[1]
            }
        }
        val offenders = mutableListOf<String>()
        for (file in productionFiles()) {
            val lines = file.readText().lines()
            // The ENCLOSING declaration's visibility matters: a member of
            // an `internal object` IS internal, whatever the member itself
            // says. Kotlin computes the effective visibility, and the
            // compiler error follows THAT. So the guard tracks the nearest
            // enclosing `internal`/`private` declaration.
            var enclosingIsRestricted = false
            var enclosingIndent = -1
            for ((i, line) in lines.withIndex()) {
                val indent = line.takeWhile { it == ' ' }.length
                if (line.trim().isEmpty() || line.trimStart().startsWith("//") ||
                    line.trimStart().startsWith("*") || line.trimStart().startsWith("/*")
                ) {
                    continue
                }
                if (enclosingIndent >= 0 && indent <= enclosingIndent) {
                    enclosingIsRestricted = false
                    enclosingIndent = -1
                }
                if (Regex("""\b(internal|private)\s+(?:data\s+|sealed\s+|abstract\s+|value\s+)*(?:class|object|interface)\s+[A-Za-z_]""").containsMatchIn(line)) {
                    enclosingIsRestricted = true
                    enclosingIndent = indent
                }
                if (!line.contains("fun ")) continue
                // [feat/subagent-roster-routing] The visibility rule is
                // about the DECLARED visibility, not the `public` keyword:
                // a member of an interface without a modifier IS public,
                // and the source never says "public". So the check must
                // fire on any `fun` whose signature mentions an internal
                // type UNLESS the declaration is explicitly `internal` or
                // `private`.
                //
                // The first version of this guard looked for the literal
                // word "public" and therefore caught NOTHING in an
                // interface — the exact case that the CI build then
                // failed on. The negative verification caught it.
                val isExplicitlyRestricted = Regex("""\b(internal|private)\s+fun\b""")
                    .containsMatchIn(line)
                if (isExplicitlyRestricted) continue
                // Inside an `internal`/`private` declaration, the member's
                // effective visibility is already restricted — no error.
                if (enclosingIsRestricted) continue
                for (t in internalTypes) {
                    // The signature of THIS declaration: from `fun` to the
                    // opening brace or the end of the line.
                    // The SIGNATURE is what matters — not the body. A
                    // one-line `fun f(x: T) = Other.internalThing(x)`
                    // exposes nothing: the internal type appears in the
                    // body, and Kotlin only forbids it in the signature.
                    val sig = line.substringAfter("fun ")
                        .substringBefore(" = ")
                        .substringBefore(" {")
                    // A QUALIFIED mention (`Foo.Bar`) is not the same as
                    // exposing `Bar` — `SubagentCheckpointFormat.InterruptedRun`
                    // names the internal object's member, and Kotlin allows
                    // that where the object itself is internal. Only a
                    // BARE mention counts.
                    val bare = Regex("""(?<![.\w])""" + Regex.escape(t) + """\b""").containsMatchIn(sig)
                    if (bare) {
                        offenders += "${file.name}:${i + 1} fun exposes internal type $t"
                    }
                }
            }
        }
        assertTrue(
            "a public function must not expose an internal type:\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun `a cross-package use of a new helper has an import`() {
        val offenders = mutableListOf<String>()
        for (file in productionFiles()) {
            if (file.name in samePackage) continue
            val text = file.readText()
            // A file in the `tools` package needs no import for a `tools`
            // helper — that is the same package, not a cross-package use.
            val inToolsPackage = file.parentFile?.name == "tools"
            for (helper in helpers) {
                // Count only BARE uses: `Helper.` — a fully-qualified
                // `com.openminis.app.tools.Helper.` needs no import, and a
                // mention inside a comment or a KDoc link does not count.
                val bareUse = Regex("""(?<![\w.])""" + helper + """\s*\.""").containsMatchIn(text)
                if (!bareUse) continue
                val importsIt = Regex("""^import\s+com\.openminis\.app\.tools\.$helper\s*$""", RegexOption.MULTILINE)
                    .containsMatchIn(text)
                if (inToolsPackage || importsIt) continue
                offenders += "${file.name} uses $helper without importing it"
            }
        }
        assertTrue(
            "every cross-package use of a new helper needs an import:\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    /**
     * A `public` declaration must not expose an `internal` type — Kotlin
     * rejects it ("public function exposes its internal return type
     * argument"). The first CI run caught exactly this on the checkpoint
     * typealias. Pin the rule for the new helpers so the next extraction
     * does not have to learn it from a failed build.
     */
    @Test
    fun `no public declaration exposes an internal helper type`() {
        val offenders = mutableListOf<String>()
        for (file in productionFiles()) {
            if (file.name !in samePackage) continue
            val text = file.readText()
            val internalTypes = Regex("""internal\s+(?:object|class|data class)\s+(\w+)""")
                .findAll(text)
                .map { it.groupValues[1] }
                .toSet()
            if (internalTypes.isEmpty()) continue
            for (type in internalTypes) {
                // A public function whose signature mentions the internal type.
                val publicLeak = Regex(
                    """^\s*(?:public\s+)?fun\s+\w+\([^)]*\b$type\b[^)]*\)\s*:\s*[^\n]*\b$type\b""",
                    RegexOption.MULTILINE,
                ).containsMatchIn(text)
                val publicReturn = Regex(
                    """^\s*(?:public\s+)?fun\s+\w+\([^)]*\)\s*:\s*[^\n]*\b$type\b""",
                    RegexOption.MULTILINE,
                ).containsMatchIn(text)
                if (publicLeak || publicReturn) {
                    offenders += "${file.name} exposes internal type $type in a public declaration"
                }
            }
        }
        assertTrue(
            "a public declaration must not expose an internal type:\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }
}
