package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import com.openminis.app.data.model.ThinkingLevel
import org.junit.Test

/**
 * [T-agent-profiles] 内置 profile 与「能力 ↔ 工具」双向映射的契约测试。
 *
 * 重点守两件事：
 *  1. `toolsFor` 与 `capabilityOf` 必须互为反函数——错一边，工具就会从所有
 *     profile 里静默消失（或反过来，绕过了 capability 的语义）；
 *  2. profile 的权限只能被"收窄"，不能被调用参数放大。
 */
class AgentProfileTest {

    @Test
    fun `every capability maps to tools that belong to it`() {
        for (capability in AgentCapability.entries) {
            val tools = AgentCapabilities.toolsOf(capability)
            assertTrue("capability $capability has no tools", tools.isNotEmpty())
            for (tool in tools) {
                assertEquals(
                    "tool '$tool' is listed under $capability but capabilityOf says otherwise",
                    capability,
                    AgentCapabilities.capabilityOf(tool),
                )
            }
        }
    }

    @Test
    fun `every tool in the catalogue belongs to exactly one capability`() {
        val allTools = AgentCapability.entries.flatMap { AgentCapabilities.toolsOf(it) }
        assertEquals(allTools.size, allTools.toSet().size)
    }

    @Test
    fun `general is the default and holds the broad set`() {
        val general = AgentProfiles.default()
        assertEquals("general", general.id)
        assertTrue(AgentCapability.FILE_READ in general.capabilities)
        assertTrue(AgentCapability.FILE_WRITE in general.capabilities)
        assertTrue(AgentCapability.SHELL in general.capabilities)
    }

    @Test
    fun `researcher and reviewer are read only`() {
        for (profile in listOf(AgentProfiles.RESEARCHER, AgentProfiles.REVIEWER)) {
            assertTrue(
                "${profile.id} must not hold FILE_WRITE",
                AgentCapability.FILE_WRITE !in profile.capabilities,
            )
            val config = AgentProfiles.toConfig(profile)
            assertTrue("${profile.id} config must be read-only", config.readOnly)
            assertNull(config.allowedTools?.firstOrNull { it == "shell_execute" })
        }
    }

    @Test
    fun `profile config never grants a tool outside its capabilities`() {
        for (profile in AgentProfiles.ALL) {
            val allowed = AgentProfiles.toConfig(profile).allowedTools ?: continue
            val expected = AgentCapabilities.toolsFor(profile.capabilities)
            assertEquals("${profile.id} allowedTools drifted from its capabilities", expected, allowed)
        }
    }

    @Test
    fun `profile skill ids live in their own namespace`() {
        for (profile in AgentProfiles.ALL) {
            val info = AgentProfiles.toSkillInfo(profile)
            assertEquals("profile:${profile.id}", info.id)
            assertTrue(info.body.isNotBlank())
        }
    }

    @Test
    fun `general allows a parallel batch`() {
        // Concurrency keys are skill ids, and every default spawn is
        // "profile:general" — maxParallel=1 would queue a batch that used to
        // run in parallel under distinct skill ids.
        assertTrue(AgentProfiles.GENERAL.maxParallel >= 2)
    }

    @Test
    fun `reasoning is spent only where it pays`() {
        assertEquals(ThinkingLevel.MEDIUM, AgentProfiles.REVIEWER.thinking)
        assertEquals(ThinkingLevel.MEDIUM, AgentProfiles.CODER.thinking)
        // Search/read work does not need multi-step reasoning, and reasoning
        // tokens are billed per turn.
        assertEquals(ThinkingLevel.OFF, AgentProfiles.RESEARCHER.thinking)
    }

    @Test
    fun `lookup accepts id and display name`() {
        assertNotNull(AgentProfiles.find("coder"))
        assertNotNull(AgentProfiles.find("Coder"))
        assertNotNull(AgentProfiles.find("  RESEARCHER  "))
        assertNull(AgentProfiles.find("does-not-exist"))
        assertNull(AgentProfiles.find(""))
    }
}