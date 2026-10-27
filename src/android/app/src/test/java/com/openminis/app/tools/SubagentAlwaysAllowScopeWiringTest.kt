package com.openminis.app.tools

import com.openminis.app.ProductionSources
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [feat/subagent-roster-routing] Source-grep drift guard for the
 * scope-bound "always allow" grant.
 *
 * The fingerprint is what stops an "always allow" grant from outliving
 * the scope the user actually approved. If the runner computes the
 * fingerprint but does not pass it to the gate, the grant is unbound
 * again and the escalation is back — with the fingerprint code sitting
 * right there looking like protection.
 *
 * The same shape as [SubagentAllowedToolsWiringTest]: pin the call
 * site, and assert the value is not dropped on the way.
 */
class SubagentAlwaysAllowScopeWiringTest {

    private val runnerSource: String by lazy {
        ProductionSources.read("tools/SubagentRunner.kt")
    }

    private val viewModelSource: String by lazy {
        ProductionSources.read("ui/chat/ChatViewModel.kt")
    }

    @Test
    fun `the runner computes the scope fingerprint`() {
        assertTrue(
            "the runner must compute the scope fingerprint for the skill",
            runnerSource.contains("SubagentToolScope.fingerprint("),
        )
    }

    /**
     * The gate must be ASKED with the fingerprint. A gate called without
     * it cannot tell one scope from another, and every grant becomes a
     * grant for whatever the skill happens to allow now.
     */
    @Test
    fun `the gate is consulted with the fingerprint`() {
        val liveGate = Regex(
            """isSkillAlwaysAllowed\(\s*skill\.id\s*,\s*scopeFingerprint\s*\)""",
        )
        assertTrue(
            "the always-allow gate must be asked with the scope fingerprint — " +
                "without it the grant is not bound to the approved scope",
            liveGate.containsMatchIn(runnerSource),
        )
    }

    /**
     * And the grant must be RECORDED with it, otherwise the first
     * approval after a scope change would store an unbound grant and
     * every later spawn would be approved by it.
     */
    @Test
    fun `the grant is recorded with the fingerprint`() {
        val liveGrant = Regex(
            """setSkillAlwaysAllowed\(\s*skill\.id\s*,\s*scopeFingerprint\s*\)""",
        )
        assertTrue(
            "the always-allow grant must be recorded with the scope fingerprint",
            liveGrant.containsMatchIn(runnerSource),
        )
    }

    /**
     * The store is the other end: it must compare the recorded scope
     * with the current one. A store that ignores the fingerprint makes
     * the whole mechanism a no-op.
     */
    @Test
    fun `the store compares the recorded scope`() {
        val store = ProductionSources.read("data/SubagentApprovalStore.kt")
        assertTrue(
            "the store must record the scope alongside the grant",
            store.contains("SCOPE_PREFIX"),
        )
        assertTrue(
            "the store must compare the recorded scope with the current one",
            store.contains("recorded == scopeFingerprint"),
        )
    }

    /**
     * The ViewModel is the adapter between the runner and the store; it
     * must pass the fingerprint through rather than dropping it.
     */
    @Test
    fun `the ViewModel passes the fingerprint through`() {
        // The call SHAPE, not the mere presence of the word: the first
        // version of this guard checked that the ViewModel source merely
        // mentioned `scopeFingerprint`, and the negative verification
        // dropped it from the call while the word stayed elsewhere in
        // the file — so the guard stayed green.
        val liveRead = Regex(
            """isAlwaysAllowed\(\s*\n?\s*context,\s*skillId,\s*scopeFingerprint,\s*\n?\s*\)""",
        )
        assertTrue(
            "the ViewModel must pass the scope fingerprint to the store",
            liveRead.containsMatchIn(viewModelSource),
        )
        val liveWrite = Regex(
            """setAlwaysAllowed\(\s*\n?\s*context,\s*skillId,\s*true,\s*scopeFingerprint,\s*\n?\s*\)""",
        )
        assertTrue(
            "the ViewModel must pass the scope fingerprint when granting",
            liveWrite.containsMatchIn(viewModelSource),
        )
    }
}
