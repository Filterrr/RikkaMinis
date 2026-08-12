package com.openminis.app.ui.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performLongClick
import com.openminis.app.R
import com.openminis.app.config.ChatActionSpec
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.ui.sessions.DatePeriod
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.jupiter.api.Test
import java.util.UUID

class ChatHistoryDrawerTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val mockChatRepository = mockk<ChatRepository>(relaxed = true)
    private val sessionsFlow = MutableStateFlow(emptyList<ChatSessionEntity>())
    private val messageCountsFlow = MutableStateFlow(emptyMap<String, Int>())

    private fun setupMocks() {
        every { mockChatRepository.observeSessions() } returns sessionsFlow
        every { mockChatRepository.observeMessageCountsPerSession() } returns messageCountsFlow
    }

    @Test
    fun testChatHistoryDrawer_rendersAppName() {
        setupMocks()
        composeTestRule.setContent {
            ChatHistoryDrawer(
                chatRepository = mockChatRepository,
                currentSessionId = "test-session",
                onSessionClick = {},
                onNewChat = {}
            )
        }
        composeTestRule.onNodeWithText("Mini", substring = true).assertIsDisplayed()
    }

    @Test
    fun testChatHistoryDrawer_rendersEmptyState() {
        setupMocks()
        composeTestRule.setContent {
            ChatHistoryDrawer(
                chatRepository = mockChatRepository,
                currentSessionId = "test-session",
                onSessionClick = {},
                onNewChat = {}
            )
        }
        composeTestRule.onNodeWithText("No sessions", substring = true).assertIsDisplayed()
    }

    @Test
    fun testChatHistoryDrawer_rendersSessions() {
        setupMocks()
        val session = ChatSessionEntity(
            id = "session-1",
            title = "Test Session",
            updatedAt = System.currentTimeMillis(),
            category = "general"
        )
        sessionsFlow.value = listOf(session)
        messageCountsFlow.value = mapOf("session-1" to 1)

        composeTestRule.setContent {
            ChatHistoryDrawer(
                chatRepository = mockChatRepository,
                currentSessionId = "other-session",
                onSessionClick = {},
                onNewChat = {}
            )
        }
        composeTestRule.onNodeWithText("Test Session").assertIsDisplayed()
    }

    @Test
    fun testChatHistoryDrawer_sessionClick() {
        setupMocks()
        var clickedSessionId: String? = null
        val session = ChatSessionEntity(
            id = "session-1",
            title = "Clickable Session",
            updatedAt = System.currentTimeMillis(),
            category = "general"
        )
        sessionsFlow.value = listOf(session)
        messageCountsFlow.value = mapOf("session-1" to 1)

        composeTestRule.setContent {
            ChatHistoryDrawer(
                chatRepository = mockChatRepository,
                currentSessionId = "other-session",
                onSessionClick = { clickedSessionId = it },
                onNewChat = {}
            )
        }
        composeTestRule.onNodeWithText("Clickable Session").performClick()
        assert(clickedSessionId == "session-1")
    }

    @Test
    fun testChatHistoryDrawer_draftRendered() {
        setupMocks()
        composeTestRule.setContent {
            ChatHistoryDrawer(
                chatRepository = mockChatRepository,
                currentSessionId = "test-session",
                draft = com.openminis.app.data.ComposerDraftStore.DraftSnapshot(
                    text = "Draft text content",
                    model = "test-model",
                    inputTokens = 10,
                    outputTokens = 20
                ),
                onOpenDraft = {},
                onDiscardDraft = {},
                onSessionClick = {},
                onNewChat = {}
            )
        }
        composeTestRule.onNodeWithText("Draft text content").assertIsDisplayed()
        composeTestRule.onNodeWithText("Draft", substring = true).assertIsDisplayed()
    }

    @Test
    fun testChatHistoryDrawer_draftClick() {
        setupMocks()
        var draftOpened = false
        composeTestRule.setContent {
            ChatHistoryDrawer(
                chatRepository = mockChatRepository,
                currentSessionId = "test-session",
                draft = com.openminis.app.data.ComposerDraftStore.DraftSnapshot(
                    text = "Draft text",
                    model = "test-model",
                    inputTokens = 10,
                    outputTokens = 20
                ),
                onOpenDraft = { draftOpened = true },
                onDiscardDraft = {},
                onSessionClick = {},
                onNewChat = {}
            )
        }
        composeTestRule.onNodeWithText("Draft text").performClick()
        assert(draftOpened)
    }

    @Test
    fun testChatHistoryDrawer_draftLongClickShowsDialog() {
        setupMocks()
        composeTestRule.setContent {
            ChatHistoryDrawer(
                chatRepository = mockChatRepository,
                currentSessionId = "test-session",
                draft = com.openminis.app.data.ComposerDraftStore.DraftSnapshot(
                    text = "Draft text",
                    model = "test-model",
                    inputTokens = 10,
                    outputTokens = 20
                ),
                onOpenDraft = {},
                onDiscardDraft = {},
                onSessionClick = {},
                onNewChat = {}
            )
        }
        composeTestRule.onNodeWithText("Draft text").performLongClick()
        composeTestRule.onNodeWithText("Delete", substring = true).assertIsDisplayed()
    }

    @Test
    fun testChatHistoryDrawer_footerActions() {
        setupMocks()
        val actionSpec = ChatActionSpec(
            key = "test-action",
            titleRes = R.string.app_name,
            icon = androidx.compose.material.icons.Icons.Default.Settings
        )
        var actionTriggered = false
        composeTestRule.setContent {
            ChatHistoryDrawer(
                chatRepository = mockChatRepository,
                currentSessionId = "test-session",
                onSessionClick = {},
                onNewChat = {},
                footerActions = listOf(actionSpec),
                onAction = { actionTriggered = true }
            )
        }
        composeTestRule.onNodeWithTag("test-action", useUnmergedTree = true).performClick()
        assert(actionTriggered)
    }

    @Test
    fun testChatHistoryDrawer_newChatButton() {
        setupMocks()
        var newChatClicked = false
        composeTestRule.setContent {
            ChatHistoryDrawer(
                chatRepository = mockChatRepository,
                currentSessionId = "test-session",
                onSessionClick = {},
                onNewChat = { newChatClicked = true }
            )
        }
        composeTestRule.onNodeWithText("New chat", substring = true).performClick()
        assert(newChatClicked)
    }

    @Test
    fun testChatHistoryDrawer_pinSession() {
        setupMocks()
        var pinnedSessionId: String? = null
        val session = ChatSessionEntity(
            id = "session-1",
            title = "Pin Test",
            updatedAt = System.currentTimeMillis(),
            category = "general",
            pinnedAt = null
        )
        sessionsFlow.value = listOf(session)
        messageCountsFlow.value = mapOf("session-1" to 1)

        composeTestRule.setContent {
            ChatHistoryDrawer(
                chatRepository = mockChatRepository,
                currentSessionId = "other-session",
                onSessionClick = {},
                onNewChat = {},
                onPinSession = { pinnedSessionId = it }
            )
        }
        composeTestRule.onNodeWithTag("Pin", substring = true, useUnmergedTree = true).performClick()
        assert(pinnedSessionId == "session-1")
    }

    @Test
    fun testDrawerSectionHeader_Pinned() {
        composeTestRule.setContent {
            DrawerSectionHeader(period = DatePeriod.PINNED)
        }
        composeTestRule.onNodeWithText("Pinned", substring = true).assertIsDisplayed()
    }

    @Test
    fun testDrawerSectionHeader_Today() {
        composeTestRule.setContent {
            DrawerSectionHeader(period = DatePeriod.TODAY)
        }
        composeTestRule.onNodeWithText("Today", substring = true).assertIsDisplayed()
    }

    @Test
    fun testDrawerSectionHeader_Yesterday() {
        composeTestRule.setContent {
            DrawerSectionHeader(period = DatePeriod.YESTERDAY)
        }
        composeTestRule.onNodeWithText("Yesterday", substring = true).assertIsDisplayed()
    }

    @Test
    fun testDrawerSectionHeader_ThisWeek() {
        composeTestRule.setContent {
            DrawerSectionHeader(period = DatePeriod.THIS_WEEK)
        }
        composeTestRule.onNodeWithText("This week", substring = true).assertIsDisplayed()
    }

    @Test
    fun testDrawerSectionHeader_ThisMonth() {
        composeTestRule.setContent {
            DrawerSectionHeader(period = DatePeriod.THIS_MONTH)
        }
        composeTestRule.onNodeWithText("This month", substring = true).assertIsDisplayed()
    }

    @Test
    fun testDrawerSectionHeader_Earlier() {
        composeTestRule.setContent {
            DrawerSectionHeader(period = DatePeriod.EARLIER)
        }
        composeTestRule.onNodeWithText("Earlier", substring = true).assertIsDisplayed()
    }

    @Test
    fun testDrawerSessionRow_rendersTitle() {
        val session = ChatSessionEntity(
            id = "test-id",
            title = "Session Title",
            updatedAt = System.currentTimeMillis(),
            category = "general"
        )
        composeTestRule.setContent {
            DrawerSessionRow(
                session = session,
                selected = false,
                onClick = {},
                onLongClick = {},
                isPinned = false,
                onTogglePin = {}
            )
        }
        composeTestRule.onNodeWithText("Session Title").assertIsDisplayed()
    }

    @Test
    fun testDrawerSessionRow_rendersLastMessage() {
        val session = ChatSessionEntity(
            id = "test-id",
            title = "Test",
            lastMessage = "Last message preview",
            updatedAt = System.currentTimeMillis(),
            category = "general"
        )
        composeTestRule.setContent {
            DrawerSessionRow(
                session = session,
                selected = false,
                onClick = {},
                onLongClick = {},
                isPinned = false,
                onTogglePin = {}
            )
        }
        composeTestRule.onNodeWithText("Last message preview").assertIsDisplayed()
    }

    @Test
    fun testDrawerSessionRow_click() {
        var clicked = false
        val session = ChatSessionEntity(
            id = "test-id",
            title = "Clickable",
            updatedAt = System.currentTimeMillis(),
            category = "general"
        )
        composeTestRule.setContent {
            DrawerSessionRow(
                session = session,
                selected = false,
                onClick = { clicked = true },
                onLongClick = {},
                isPinned = false,
                onTogglePin = {}
            )
        }
        composeTestRule.onNodeWithText("Clickable").performClick()
        assert(clicked)
    }

    @Test
    fun testDrawerSessionRow_longClick() {
        var longClicked = false
        val session = ChatSessionEntity(
            id = "test-id",
            title = "Long Click",
            updatedAt = System.currentTimeMillis(),
            category = "general"
        )
        composeTestRule.setContent {
            DrawerSessionRow(
                session = session,
                selected = false,
                onClick = {},
                onLongClick = { longClicked = true },
                isPinned = false,
                onTogglePin = {}
            )
        }
        composeTestRule.onNodeWithText("Long Click").performLongClick()
        assert(longClicked)
    }

    @Test
    fun testDrawerSessionRow_pinIcon() {
        val session = ChatSessionEntity(
            id = "test-id",
            title = "Pin Test",
            updatedAt = System.currentTimeMillis(),
            category = "general"
        )
        var pinToggled = false
        composeTestRule.setContent {
            DrawerSessionRow(
                session = session,
                selected = false,
                onClick = {},
                onLongClick = {},
                isPinned = true,
                onTogglePin = { pinToggled = true }
            )
        }
        composeTestRule.onNodeWithTag("Unpin", substring = true, useUnmergedTree = true).performClick()
        assert(pinToggled)
    }
}