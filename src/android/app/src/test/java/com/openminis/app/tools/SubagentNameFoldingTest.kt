package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [feat/subagent-roster-routing] The name-folding rule must be ONE rule.
 *
 * It used to exist three times with two different behaviours:
 *  - the data layer folded diacritics, whitespace and case;
 *  - the tools resolver folded whitespace and case only;
 *  - the roster-hint merge folded whitespace and case only.
 *
 * So a roster entry named "Résumé-agent" was LISTED by the hint layer
 * under one key and MATCHED by the resolver under another — the same
 * delegation could work or fail depending on which layer answered. A
 * name that comes back from a MODEL may not reproduce accents exactly,
 * and the stored side is user-typed with stray whitespace; folding all
 * three is what makes the two agree.
 */
class SubagentNameFoldingTest {

    @Test
    fun `diacritics are folded`() {
        assertEquals("resume-agent", SubagentDelegation.normalize("Résumé-agent"))
        assertEquals("resume-agent", SubagentDelegation.normalize("RÉSUMÉ-AGENT"))
        assertEquals("resume-agent", SubagentDelegation.normalize("  Résumé-agent  "))
    }

    @Test
    fun `the data layer and the tools layer fold identically`() {
        // The data layer's `SubAgentRoster.nameKey` cannot be compiled by
        // the local JVM script (the file depends on Android's R and a
        // serialization module), so the two are compared by SOURCE here:
        // the data layer must delegate to this normaliser rather than
        // keep its own copy of the rule.
        val root = com.openminis.app.ProductionSources.mainRoot() ?: return
        val dataFile = File(root, "data/model/SubAgentDefinition.kt")
        if (!dataFile.isFile) return
        val text = dataFile.readText()
        assertTrue(
            "the data layer must delegate to the shared normaliser, not keep " +
                "its own copy of the folding rule",
            text.contains("SubagentNameKey.normalize"),
        )
        assertTrue(
            "the data layer must not keep a second implementation of the rule",
            !text.contains("Normalizer.normalize"),
        )
        // The shared normaliser is the ONLY place the rule is written.
        val shared = File(root, "tools/SubagentNameKey.kt")
        if (shared.isFile) {
            val sharedText = shared.readText()
            assertTrue(
                "the shared normaliser must actually fold diacritics",
                sharedText.contains("Normalizer.normalize"),
            )
        }
    }

    /**
     * The end-to-end consequence: a configured entry whose name carries an
     * accent must still be REACHABLE by a parent that emits it without.
     */
    @Test
    fun `a configured entry with an accent is reachable without it`() {
        val d = SubagentDelegation.resolve(
            requested = "resume-agent",
            skillRoster = listOf(SubagentRosterHint.Entry("general-agent", "general")),
            configured = listOf(
                SubagentDelegation.ConfiguredAgent(
                    name = "Résumé-agent",
                    description = "use for CVs",
                ),
            ),
            skillExists = { it == "general-agent" },
        )
        assertNotNull("the accent-folded name must resolve", d)
        assertTrue("it must resolve to the CONFIGURED entry", d!!.fromRoster)
    }

    /**
     * The skill LOOKUP has to fold the same way. It used to match on the
     * exact name, the id, or `ignoreCase` — and `ignoreCase` does NOT fold
     * diacritics. So a skill named "Résumé-agent" was listed under the
     * folded key and could not be found by the name the roster showed.
     */
    @Test
    fun `the skill lookup folds the same way as the roster`() {
        val root = com.openminis.app.ProductionSources.mainRoot() ?: return
        val cvm = File(root, "ui/chat/ChatViewModel.kt")
        if (!cvm.isFile) return
        val text = SubagentSourceText.stripComments(cvm.readText())
        val lookup = Regex("""override fun findSkill[\s\S]{0,600}?\n {16}\}""")
            .find(text)?.value
        assertTrue("the skill lookup was not found", lookup != null)
        assertTrue(
            "the skill lookup must use the shared folding rule, not just " +
                "ignoreCase (which does not fold diacritics)",
            lookup!!.contains("SubagentNameKey.normalize"),
        )
    }

    @Test
    fun `the roster hint resolves with the same folding`() {
        val entries = listOf(SubagentRosterHint.Entry("Résumé-agent", "use for CVs"))
        assertNotNull(
            SubagentRosterHint.resolve("resume-agent", entries),
        )
        assertNull(
            SubagentRosterHint.resolve("something-else", entries),
        )
    }

    /**
     * The empty-name rule: the data layer's `SubAgentRoster.resolve`
     * resolved an empty name to the built-in, and the tools layer did
     * not. An unnamed spawn means "use the default agent".
     */
    @Test
    fun `an empty name resolves to the built-in entry`() {
        val d = SubagentDelegation.resolve(
            requested = "",
            skillRoster = listOf(SubagentRosterHint.Entry("general-agent", "general")),
            configured = listOf(
                SubagentDelegation.ConfiguredAgent(
                    name = "general-agent",
                    description = "the default",
                    instructions = "do the general work",
                ),
                SubagentDelegation.ConfiguredAgent(
                    name = "other",
                    description = "something else",
                ),
            ),
            skillExists = { it == "general-agent" },
        )
        assertNotNull(d)
        assertEquals("general-agent", d!!.skillName)
        assertTrue("the built-in's mission is carried", d.extraInstructions.contains("general work"))
    }

    @Test
    fun `an empty name with no configured roster falls back to the general runtime`() {
        val d = SubagentDelegation.resolve(
            requested = null,
            skillRoster = listOf(SubagentRosterHint.Entry("general-agent", "general")),
            configured = emptyList(),
            skillExists = { it == "general-agent" },
        )
        assertNotNull(d)
        assertEquals("general-agent", d!!.skillName)
    }
}
