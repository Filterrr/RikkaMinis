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
        val internalTypes = listOf(
            "InterruptedRun",
            "SubagentCheckpointFormat.InterruptedRun",
            "SubagentPartialRecovery.Step",
            "SubagentRosterHint.Entry",
            "SubagentDelegation.ConfiguredAgent",
        )
        val offenders = mutableListOf<String>()
        for (file in productionFiles()) {
            val lines = file.readText().lines()
            for ((i, line) in lines.withIndex()) {
                if (!line.contains("fun ")) continue
                if (!Regex("""\bpublic\b""").containsMatchIn(line)) continue
                for (t in internalTypes) {
                    // The signature of THIS declaration: from `fun` to the
                    // opening brace or the end of the line.
                    val sig = line.substringAfter("fun ")
                    if (Regex("""\b""" + Regex.escape(t) + """\b""").containsMatchIn(sig)) {
                        offenders += "${file.name}:${i + 1} public fun exposes internal type $t"
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
