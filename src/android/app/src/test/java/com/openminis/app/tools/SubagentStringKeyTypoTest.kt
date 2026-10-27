package com.openminis.app.tools

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [feat/subagent-roster-routing] A string key that is REFERENCED but
 * misspelled compiles fine and blows up at runtime — `R.string.foo` is a
 * synthetic int, so the compiler cannot tell that no such resource
 * exists. The i18n scan gate does catch a missing key, but it reports the
 * key as "orphan" without suggesting the likely intended one, and a key
 * that differs from a real one by a couple of characters is exactly the
 * case a human reads past.
 *
 * This guard names the likely intended key, so the fix is one rename
 * rather than a hunt.
 *
 * The spelling pairs checked are the ones that actually bit during this
 * work: "roster"/"rooster" (the roster editor), and the common
 * transpositions a resource author makes.
 */
class SubagentStringKeyTypoTest {

    private val spellingPairs = listOf(
        "rooster" to "roster",
        "subagent" to "sub_agent",
        "sub_agent" to "subagent",
        "descripton" to "description",
        "instrctions" to "instructions",
    )

    @Test
    fun `a referenced string key that is only a typo away from a real one is reported`() {
        val root = com.openminis.app.ProductionSources.mainRoot() ?: return
        val stringsFile = findStringsFile(root) ?: return
        val defined = definedKeys(stringsFile)

        val offenders = mutableListOf<String>()
        for (file in root.walkTopDown().filter { it.isFile && it.extension == "kt" }) {
            val text = file.readText()
            for (m in Regex("R\\.string\\.([a-z0-9_]+)").findAll(text)) {
                val key = m.groupValues[1]
                if (key in defined) continue
                val candidate = spellingPairs.firstOrNull { (wrong, right) ->
                    key.contains(wrong) && !key.contains(right) &&
                        defined.any { it.contains(right) && it.replace(right, wrong) == key }
                } ?: continue
                offenders += "${file.name}: R.string.$key — did you mean R.string.${candidate.second}?"
            }
        }
        assertTrue(
            "string keys that look like a typo of a defined key:\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    private fun findStringsFile(root: File): File? {
        var dir: File? = root
        while (dir != null) {
            val candidate = File(dir, "src/android/app/src/main/res/values/strings.xml")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        return null
    }

    private fun definedKeys(file: File): Set<String> =
        Regex("""name="([a-z0-9_]+)"""").findAll(file.readText())
            .map { it.groupValues[1] }
            .toSet()
}
