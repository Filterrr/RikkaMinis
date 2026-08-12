package com.openminis.app.config.audit

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.jupiter.api.Test
import org.junit.Rule

class ConfigAuditEntryTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testActorLabel_rendersCorrectly() {
        composeTestRule.setContent {
            ConfigAuditActorLabel(actor = ConfigAuditActor.USER)
        }
        composeTestRule.onNodeWithText("user").assertExists()
    }

    @Test
    fun testActorLabel_rendersWithDefaultParameter() {
        composeTestRule.setContent {
            ConfigAuditActorLabel(actor = ConfigAuditActor.AGENT)
        }
        composeTestRule.onNodeWithText("agent").assertExists()
    }

    @Test
    fun testActorLabel_clickEvent() {
        var clicked = false
        composeTestRule.setContent {
            ConfigAuditActorLabel(
                actor = ConfigAuditActor.AGENT_REVERT,
                onClick = { clicked = true }
            )
        }
        composeTestRule.onNodeWithText("agent-revert").performClick()
        assert(clicked)
    }

    @Test
    fun testStatusLabel_rendersCorrectly() {
        composeTestRule.setContent {
            ConfigAuditStatusLabel(status = ConfigAuditStatus.APPLIED)
        }
        composeTestRule.onNodeWithText("applied").assertExists()
    }

    @Test
    fun testStatusLabel_rendersWithDefaultParameter() {
        composeTestRule.setContent {
            ConfigAuditStatusLabel(status = ConfigAuditStatus.REJECTED)
        }
        composeTestRule.onNodeWithText("rejected").assertExists()
    }

    @Test
    fun testStatusLabel_clickEvent() {
        var clicked = false
        composeTestRule.setContent {
            ConfigAuditStatusLabel(
                status = ConfigAuditStatus.TIMEOUT,
                onClick = { clicked = true }
            )
        }
        composeTestRule.onNodeWithText("timeout").performClick()
        assert(clicked)
    }

    @Test
    fun testAuditEntryItem_rendersWithAllFields() {
        val entry = ConfigAuditEntry(
            id = "1",
            at = 1000L,
            actor = ConfigAuditActor.USER,
            sessionId = "session123",
            scope = "scope1",
            key = "key1",
            oldValueJSON = "\"old\"",
            newValueJSON = "\"new\"",
            confirmedAt = 2000L,
            status = ConfigAuditStatus.APPLIED,
            revertOf = null,
            caption = "Test caption"
        )
        composeTestRule.setContent {
            ConfigAuditEntryItem(entry = entry)
        }
        composeTestRule.onNodeWithText("scope1").assertExists()
        composeTestRule.onNodeWithText("key1").assertExists()
        composeTestRule.onNodeWithText("Test caption").assertExists()
    }

    @Test
    fun testAuditEntryItem_rendersWithNullCaption() {
        val entry = ConfigAuditEntry(
            id = "2",
            at = 1000L,
            actor = ConfigAuditActor.AGENT,
            sessionId = null,
            scope = "scope2",
            key = "key2",
            oldValueJSON = "\"old\"",
            newValueJSON = "\"new\"",
            confirmedAt = null,
            status = ConfigAuditStatus.REVERTED,
            revertOf = "1",
            caption = null
        )
        composeTestRule.setContent {
            ConfigAuditEntryItem(entry = entry)
        }
        composeTestRule.onNodeWithText("scope2").assertExists()
        composeTestRule.onNodeWithText("key2").assertExists()
    }

    @Test
    fun testAuditEntryItem_clickEvent() {
        var clicked = false
        val entry = ConfigAuditEntry(
            id = "3",
            at = 1000L,
            actor = ConfigAuditActor.USER_REVERT,
            sessionId = null,
            scope = "scope3",
            key = "key3",
            oldValueJSON = "\"old\"",
            newValueJSON = "\"new\"",
            confirmedAt = null,
            status = ConfigAuditStatus.REJECTED,
            revertOf = null,
            caption = "Clickable"
        )
        composeTestRule.setContent {
            ConfigAuditEntryItem(entry = entry, onClick = { clicked = true })
        }
        composeTestRule.onNodeWithText("Clickable").performClick()
        assert(clicked)
    }

    @Test
    fun testActorLabel_withNullSession() {
        composeTestRule.setContent {
            ConfigAuditActorLabel(actor = ConfigAuditActor.AGENT)
        }
        composeTestRule.onNodeWithText("agent").assertIsDisplayed()
    }

    @Test
    fun testStatusLabel_withTimeoutStatus() {
        composeTestRule.setContent {
            ConfigAuditStatusLabel(status = ConfigAuditStatus.TIMEOUT)
        }
        composeTestRule.onNodeWithText("timeout").assertIsDisplayed()
    }

    @Test
    fun testAuditEntryItem_withRevertOf() {
        val entry = ConfigAuditEntry(
            id = "4",
            at = 1000L,
            actor = ConfigAuditActor.AGENT_REVERT,
            sessionId = null,
            scope = "scope4",
            key = "key4",
            oldValueJSON = "\"old\"",
            newValueJSON = "\"new\"",
            confirmedAt = 3000L,
            status = ConfigAuditStatus.REVERTED,
            revertOf = "original-1",
            caption = "Reverted entry"
        )
        composeTestRule.setContent {
            ConfigAuditEntryItem(entry = entry)
        }
        composeTestRule.onNodeWithText("scope4").assertExists()
        composeTestRule.onNodeWithText("Reverted entry").assertExists()
    }
}