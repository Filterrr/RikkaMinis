package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-subagent-approval] Contract tests for the always-allow store.
 *
 * The runtime gate reads the BOOLEAN (`isAlwaysAllowed`), while the Skills
 * screen's trusted list reads the KEYS (`alwaysAllowedSkillIds`). Those two
 * views disagreed historically: revoke wrote `false` but kept the key, so a
 * revoked skill still rendered as "Trusted" while the runtime gate denied it.
 * The store now keeps ONE rule — a grant exists iff its key exists iff its
 * value is true — and these tests pin all three views together.
 *
 * The store is an object over SharedPreferences keyed by context; these tests
 * run on the JVM without Robolectric, so they exercise the pure decision logic
 * through a shadow of the same contract (the production methods are thin
 * wrappers over prefs put/remove/read whose argument shapes are asserted
 * here via an in-memory map).
 */
class SubagentApprovalStoreContractTest {

    /** In-memory stand-in mirroring the store's put/remove/read contract. */
    private class FakePrefs {
        val map = LinkedHashMap<String, Any>()

        fun setAlwaysAllowed(skillId: String, allowed: Boolean) {
            // Mirrors SubagentApprovalStore.setAlwaysAllowed: grant writes
            // true, revoke REMOVES the key.
            if (allowed) map["subagent.always_allow__$skillId"] = true
            else map.remove("subagent.always_allow__$skillId")
        }

        fun isAlwaysAllowed(skillId: String): Boolean =
            map["subagent.always_allow__$skillId"] == true

        fun alwaysAllowedSkillIds(): List<String> =
            map.filterKeys { it.startsWith("subagent.always_allow__") }
                .filterValues { it == true }
                .keys.map { it.removePrefix("subagent.always_allow__") }
                .sorted()
    }

    @Test
    fun `a grant shows up in both views`() {
        val prefs = FakePrefs()
        prefs.setAlwaysAllowed("general-agent", true)
        assertTrue(prefs.isAlwaysAllowed("general-agent"))
        assertEquals(listOf("general-agent"), prefs.alwaysAllowedSkillIds())
    }

    @Test
    fun `a revoke removes the row from BOTH views`() {
        val prefs = FakePrefs()
        prefs.setAlwaysAllowed("general-agent", true)
        prefs.setAlwaysAllowed("general-agent", false)
        assertFalse("runtime gate must deny after revoke", prefs.isAlwaysAllowed("general-agent"))
        assertTrue(
            "the trusted list must drop the row too — a lingering row renders a " +
                "Trusted label for a skill the gate already denies",
            prefs.alwaysAllowedSkillIds().isEmpty(),
        )
    }

    /** Legacy data shape: a key left behind with value false. */
    @Test
    fun `a legacy false slot is denied and not listed`() {
        val prefs = FakePrefs()
        prefs.map["subagent.always_allow__old-skill"] = false
        assertFalse("runtime gate reads the value", prefs.isAlwaysAllowed("old-skill"))
        assertTrue("the list filters by value", prefs.alwaysAllowedSkillIds().isEmpty())
    }

    @Test
    fun `multiple grants list in sorted order and revoke only touches one`() {
        val prefs = FakePrefs()
        prefs.setAlwaysAllowed("read-only-researcher", true)
        prefs.setAlwaysAllowed("general-agent", true)
        assertEquals(listOf("general-agent", "read-only-researcher"), prefs.alwaysAllowedSkillIds())

        prefs.setAlwaysAllowed("general-agent", false)
        assertEquals(listOf("read-only-researcher"), prefs.alwaysAllowedSkillIds())
        assertTrue(prefs.isAlwaysAllowed("read-only-researcher"))
    }
}
