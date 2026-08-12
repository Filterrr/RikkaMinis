package com.openminis.app.debug

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.openminis.app.MinisApp
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.ui.chat.InputAttachment
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

@ExperimentalCoroutinesApi
class ChatMutationMethodsTest {

    private lateinit var mockContext: Context
    private lateinit var mockApp: MinisApp
    private lateinit var mockDao: ChatDao
    private lateinit var mockChatRepository: ChatRepository
    private lateinit var testDispatcher: TestDispatcher

    @BeforeEach
    fun setUp() {
        testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)

        mockContext = mockk(relaxed = true)
        mockApp = mockk(relaxed = true)
        mockDao = mockk(relaxed = true)
        mockChatRepository = mockk(relaxed = true)

        // Make app(context) return our mock
        every { mockContext.applicationContext } returns mockApp
        every { mockApp.chatRepository } returns mockChatRepository
        every { mockChatRepository.dao } returns mockDao

        // Mock FileProvider (for attachments)
        mockkStatic(FileProvider::class)
        every { FileProvider.getUriForFile(any(), any(), any()) } returns Uri.parse("content://mock/")
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    // ─────────────────────────────────────────────────
    // uiPrompt
    // ─────────────────────────────────────────────────
    @Test
    fun `uiPrompt with valid params returns expected JSON`() = runBlocking {
        val params = JSONObject().apply {
            put("prompt", "Hello")
            put("sessionId", "session-1")
        }
        // Mock ViewModelStore / ownerFor / factory
        mockkObject(com.openminis.app.ui.chat.ChatViewModelStore)
        val mockVm = mockk<com.openminis.app.ui.chat.ChatViewModel>(relaxed = true)
        every { com.openminis.app.ui.chat.ChatViewModelStore.activeSessionId } returns null
        every { com.openminis.app.ui.chat.ChatViewModelStore.ownerFor(any()) } returns mockk()
        every {
            mockkStatic(androidx.lifecycle.ViewModelProvider::class)
            androidx.lifecycle.ViewModelProvider(any(), any())[com.openminis.app.ui.chat.ChatViewModel::class.java]
        } returns mockVm
        every { mockVm.isStreaming } returns mockk { every { value } returns true }

        val result = ChatMutationMethods.uiPrompt(mockContext, params)
        assert(result.getBoolean("ok"))
        assert(result.getString("sessionId") == "session-1")
        assert(result.getBoolean("isStreaming"))
    }

    @Test
    fun `uiPrompt missing prompt throws exception`() = runBlocking {
        val params = JSONObject()
        try {
            ChatMutationMethods.uiPrompt(mockContext, params)
            assert(false) { "Should have thrown" }
        } catch (e: RPCException) {
            assert(e.code == -32602)
        }
    }

    @Test
    fun `uiPrompt missing sessionId and no active session throws`() = runBlocking {
        val params = JSONObject().apply { put("prompt", "Hi") }
        mockkObject(com.openminis.app.ui.chat.ChatViewModelStore)
        every { com.openminis.app.ui.chat.ChatViewModelStore.activeSessionId } returns null
        try {
            ChatMutationMethods.uiPrompt(mockContext, params)
            assert(false) { "Should have thrown" }
        } catch (e: RPCException) {
            assert(e.code == -32000)
        }
    }

    // ─────────────────────────────────────────────────
    // prompt
    // ─────────────────────────────────────────────────
    @Test
    fun `prompt with valid params returns expected JSON`() = runBlocking {
        val params = JSONObject().apply {
            put("prompt", "Hello")
            put("sessionId", "session-1")
            put("wait", false)
        }
        // Mock HeadlessChatRunner
        mockkObject(HeadlessChatRunner)
        every { HeadlessChatRunner.ensureSession(any()) } returns "session-1"
        every { HeadlessChatRunner.applyModelOverride(any(), any(), any(), any()) } returns null
        every { HeadlessChatRunner.prompt(any(), any(), any(), any(), any(), any(), any()) } returns
                HeadlessChatRunner.PromptResult(status = "completed", responseText = "Response", timedOut = false)

        // Mock dao
        every { mockDao.loadMessages(any()) } returns listOf(
            mockk {
                every { role } returns "user"
                every { id } returns 42L
            }
        )
        every { mockDao.getSession(any()) } returns mockk {
            every { modelId } returns "model-1"
        }
        // Mock resolveDisplay
        mockkStatic("com.openminis.app.debug.ChatMutationMethodsKt")
        every { ChatMutationMethods.resolveDisplay(any(), any()) } returns "Display Name"

        val result = ChatMutationMethods.prompt(mockContext, params)
        assert(result.getString("sessionId") == "session-1")
        assert(result.getBoolean("isNewSession") == false)
        assert(result.getString("modelName") == "Display Name")
        assert(result.getString("status") == "completed")
        assert(result.getString("responseText") == "Response")
        assert(result.getLong("userMessageId") == 42L)
    }

    @Test
    fun `prompt missing prompt throws`() = runBlocking {
        val params = JSONObject()
        try {
            ChatMutationMethods.prompt(mockContext, params)
            assert(false)
        } catch (e: RPCException) {
            assert(e.code == -32602)
        }
    }

    // ─────────────────────────────────────────────────
    // retry
    // ─────────────────────────────────────────────────
    @Test
    fun `retry with valid params returns expected JSON`() = runBlocking {
        val params = JSONObject().apply {
            put("sessionId", "session-1")
            put("wait", false)
        }
        every { mockDao.getSession(any()) } returns mockk()
        mockkObject(HeadlessChatRunner)
        every { HeadlessChatRunner.applyModelOverride(any(), any(), any(), any()) } returns null
        every { HeadlessChatRunner.retry(any(), any(), any(), any(), any()) } returns
                HeadlessChatRunner.RetryResult(status = "completed", responseText = "Retry", timedOut = false, retriedMessageId = 1L, deletedMessageCount = 3)

        val result = ChatMutationMethods.retry(mockContext, params)
        assert(result.getString("sessionId") == "session-1")
        assert(result.getString("status") == "completed")
        assert(result.getLong("retriedMessageId") == 1L)
        assert(result.getInt("deletedMessageCount") == 3)
    }

    @Test
    fun `retry missing sessionId throws`() = runBlocking {
        try {
            ChatMutationMethods.retry(mockContext, JSONObject())
            assert(false)
        } catch (e: RPCException) {
            assert(e.code == -32602)
        }
    }

    @Test
    fun `retry session not found throws`() = runBlocking {
        val params = JSONObject().apply { put("sessionId", "nonexistent") }
        every { mockDao.getSession(any()) } returns null
        try {
            ChatMutationMethods.retry(mockContext, params)
            assert(false)
        } catch (e: RPCException) {
            assert(e.code == -32602)
        }
    }

    // ─────────────────────────────────────────────────
    // rerunFromToolBlock
    // ─────────────────────────────────────────────────
    @Test
    fun `rerunFromToolBlock with valid params returns expected JSON`() = runBlocking {
        val params = JSONObject().apply {
            put("sessionId", "session-1")
            put("assistantMessageId", "msg-1")
            put("blockId", "block-1")
            put("wait", false)
        }
        every { mockDao.getSession(any()) } returns mockk()
        mockkObject(HeadlessChatRunner)
        every { HeadlessChatRunner.rerunFromToolBlock(any(), any(), any(), any(), any(), any()) } returns
                HeadlessChatRunner.RerunResult(status = "completed", responseText = "Rerun", timedOut = false, deletedMessageCount = 2)

        val result = ChatMutationMethods.rerunFromToolBlock(mockContext, params)
        assert(result.getString("sessionId") == "session-1")
        assert(result.getString("status") == "completed")
        assert(result.getInt("deletedMessageCount") == 2)
    }

    @Test
    fun `rerunFromToolBlock missing assistantMessageId throws`() = runBlocking {
        val params = JSONObject().apply {
            put("sessionId", "session-1")
            put("blockId", "block-1")
        }
        try {
            ChatMutationMethods.rerunFromToolBlock(mockContext, params)
            assert(false)
        } catch (e: RPCException) {
            assert(e.code == -32602)
        }
    }

    // ─────────────────────────────────────────────────
    // status
    // ─────────────────────────────────────────────────
    @Test
    fun `status returns session info`() = runBlocking {
        val params = JSONObject().apply { put("sessionId", "session-1") }
        val mockSession = mockk<Session>() {
            every { title } returns "Test Session"
            every { modelId } returns "model-1"
            every { updatedAt } returns 123456L
        }
        every { mockDao.getSession(any()) } returns mockSession
        every { mockDao.loadMessages(any()) } returns listOf(
            mockk { every { role } returns "assistant" }
        )
        mockkObject(com.openminis.app.service.SessionActivityTracker)
        every { com.openminis.app.service.SessionActivityTracker.isActive(any()) } returns true
        mockkStatic("com.openminis.app.debug.ChatMutationMethodsKt")
        every { ChatMutationMethods.resolveDisplay(any(), any()) } returns "Display Name"

        val result = ChatMutationMethods.status(mockContext, params)
        assert(result.getString("sessionId") == "session-1")
        assert(result.getString("title") == "Test Session")
        assert(result.getString("modelName") == "Display Name")
        assert(result.getBoolean("isRunning"))
        assert(result.getInt("messageCount") == 1)
        assert(result.getString("lastMessageRole") == "assistant")
        assert(result.getLong("updatedAt") == 123456L)
    }

    @Test
    fun `status session not found throws`() = runBlocking {
        every { mockDao.getSession(any()) } returns null
        try {
            ChatMutationMethods.status(mockContext, JSONObject().apply { put("sessionId", "x") })
            assert(false)
        } catch (e: RPCException) {
            assert(e.code == -32602)
        }
    }

    // ─────────────────────────────────────────────────
    // cancel
    // ─────────────────────────────────────────────────
    @Test
    fun `cancel returns expected JSON`() = runBlocking {
        mockkObject(HeadlessChatRunner)
        every { HeadlessChatRunner.cancel(any(), any()) } returns true
        val params = JSONObject().apply { put("sessionId", "session-1") }
        val result = ChatMutationMethods.cancel(mockContext, params)
        assert(result.getString("sessionId") == "session-1")
        assert(result.getBoolean("wasRunning"))
        assert(result.getBoolean("cancelled"))
    }

    @Test
    fun `cancel missing sessionId throws`() = runBlocking {
        try {
            ChatMutationMethods.cancel(mockContext, JSONObject())
            assert(false)
        } catch (e: RPCException) {
            assert(e.code == -32602)
        }
    }

    // ─────────────────────────────────────────────────
    // selectModel
    // ─────────────────────────────────────────────────
    @Test
    fun `selectModel returns expected JSON`() = runBlocking {
        val params = JSONObject().apply {
            put("sessionId", "session-1")
            put("modelEntryId", "entry-1")
        }
        mockkObject(HeadlessChatRunner)
        every { HeadlessChatRunner.selectModel(any(), any(), any()) } returns Pair("Model Name", ThinkingLevel.HIGH)

        val result = ChatMutationMethods.selectModel(mockContext, params)
        assert(result.getString("sessionId") == "session-1")
        assert(result.getString("modelEntryId") == "entry-1")
        assert(result.getString("modelName") == "Model Name")
        assert(result.getString("thinkingLevel") == "high")
    }

    @Test
    fun `selectModel missing sessionId throws`() = runBlocking {
        val params = JSONObject().apply { put("modelEntryId", "entry-1") }
        try {
            ChatMutationMethods.selectModel(mockContext, params)
            assert(false)
        } catch (e: RPCException) {
            assert(e.code == -32602)
        }
    }

    // ─────────────────────────────────────────────────
    // compactBefore
    // ─────────────────────────────────────────────────
    @Test
    fun `compactBefore with valid params returns expected JSON`() = runBlocking {
        val params = JSONObject().apply {
            put("sessionId", "session-1")
            put("messageId", "msg-1")
            put("includesBoundary", false)
            put("waitTimeout", 60)
        }
        every { mockDao.getSession(any()) } returns mockk()
        every { mockDao.listCompactMarkers(any()) } returnsMany listOf(
            emptyList(), // before
            listOf( // after
                mockk {
                    every { id } returns "marker-1"
                    every { version } returns 1
                    every { lastCompactedMessageId } returns "msg-1"
                    every { summary } returns "summary"
                    every { compactedCount } returns 5
                    every { createdAt } returns 100L
                }
            )
        )
        mockkObject(HeadlessChatRunner)
        every { HeadlessChatRunner.compact(any(), any(), any(), any(), any(), any()) } returns
                HeadlessChatRunner.CompactResult(status = "completed", timedOut = false, error = null)

        val result = ChatMutationMethods.compactBefore(mockContext, params)
        assert(result.getString("sessionId") == "session-1")
        assert(result.getString("messageId") == "msg-1")
        assert(result.getBoolean("includesBoundary") == false)
        assert(result.getInt("beforeMarkerCount") == 0)
        assert(result.getInt("afterMarkerCount") == 1)
        assert(result.getBoolean("wrote"))
        assert(result.getString("status") == "completed")
        assert(result.has("latestMarker"))
        JSONObject("latestMarker") // just check it exists
    }

    @Test
    fun `compactBefore missing messageId when includesBoundary false throws`() = runBlocking {
        val params = JSONObject().apply {
            put("sessionId", "session-1")
            put("includesBoundary", false)
        }
        try {
            ChatMutationMethods.compactBefore(mockContext, params)
            assert(false)
        } catch (e: RPCException) {
            assert(e.code == -32602)
        }
    }

    // ─────────────────────────────────────────────────
    // compactMarkersList
    // ─────────────────────────────────────────────────
    @Test
    fun `compactMarkersList returns markers`() = runBlocking {
        val params = JSONObject().apply {
            put("sessionId", "session-1")
            put("includeFullSummary", false)
        }
        every { mockDao.getSession(any()) } returns mockk()
        every { mockDao.listCompactMarkers(any()) } returns listOf(
            mockk {
                every { id } returns "m1"
                every { version } returns 1
                every { lastCompactedMessageId } returns "msg-1"
                every { firstKeptMessageId } returns "msg-2"
                every { summary } returns "A long summary that should be truncated to 120 chars..."
                every { compactedCount } returns 3
                every { createdAt } returns 200L
            }
        )
        val result = ChatMutationMethods.compactMarkersList(mockContext, params)
        assert(result.getString("sessionId") == "session-1")
        assert(result.getInt("count") == 1)
        val markers = result.getJSONArray("markers")
        assert(markers.length() == 1)
        val marker = markers.getJSONObject(0)
        assert(marker.getString("id") == "m1")
        assert(marker.getString("summaryPreview").length <= 120)
    }

    @Test
    fun `compactMarkersList with includeFullSummary includes full summary`() = runBlocking {
        val params = JSONObject().apply {
            put("sessionId", "session-1")
            put("includeFullSummary", true)
        }
        every { mockDao.getSession(any()) } returns mockk()
        every { mockDao.listCompactMarkers(any()) } returns listOf(
            mockk {
                every { id } returns "m1"
                every { version } returns 1
                every { lastCompactedMessageId } returns null
                every { firstKeptMessageId } returns null
                every { summary } returns "full summary text"
                every { compactedCount } returns 0
                every { createdAt } returns 300L
            }
        )
        val result = ChatMutationMethods.compactMarkersList(mockContext, params)
        val marker = result.getJSONArray("markers").getJSONObject(0)
        assert(marker.getString("summary") == "full summary text")
    }

    // ─────────────────────────────────────────────────
    // compactRevert
    // ─────────────────────────────────────────────────
    @Test
    fun `compactRevert reverts successfully`() = runBlocking {
        val params = JSONObject().apply { put("sessionId", "session-1") }
        val markerBefore = mockk<CompactMarker> {
            every { id } returns "marker-old"
            every { createdAt } returns 100L
        }
        every { mockDao.getSession(any()) } returns mockk()
        every { mockDao.listCompactMarkers(any()) } returnsMany listOf(
            listOf(markerBefore), // before
            listOf() // after (polled)
        )
        mockkObject(HeadlessChatRunner)
        every { HeadlessChatRunner.revertCompact(any(), any()) } returns Unit
        coEvery { kotlinx.coroutines.delay(any()) } returns Unit

        val result = ChatMutationMethods.compactRevert(mockContext, params)
        assert(result.getInt("beforeMarkerCount") == 1)
        assert(result.getInt("afterMarkerCount") == 0)
        assert(result.getString("removedMarkerId") == "marker-old")
        assert(result.isNull("newLatestMarkerId"))
    }

    @Test
    fun `compactRevert session not found throws`() = runBlocking {
        every { mockDao.getSession(any()) } returns null
        try {
            ChatMutationMethods.compactRevert(mockContext, JSONObject().apply { put("sessionId", "x") })
            assert(false)
        } catch (e: RPCException) {
            assert(e.code == -32602)
        }
    }

    // ─────────────────────────────────────────────────
    // delete
    // ─────────────────────────────────────────────────
    @Test
    fun `delete with confirm=true deletes session`() = runBlocking {
        val params = JSONObject().apply {
            put("sessionId", "session-1")
            put("confirm", true)
        }
        every { mockDao.getSession(any()) } returns mockk()
        mockkObject(HeadlessChatRunner)
        every { HeadlessChatRunner.cancel(any(), any()) } returns true
        every { HeadlessChatRunner.forget(any()) } returns Unit
        every { mockChatRepository.deleteSession(any()) } returns Unit

        val result = ChatMutationMethods.delete(mockContext, params)
        assert(result.getString("sessionId") == "session-1")
        assert(result.getBoolean("deleted"))
    }

    @Test
    fun `delete without confirm throws`() = runBlocking {
        val params = JSONObject().apply { put("sessionId", "session-1") }
        try {
            ChatMutationMethods.delete(mockContext, params)
            assert(false)
        } catch (e: RPCException) {
            assert(e.code == -32602)
        }
    }

    @Test
    fun `delete session not found throws`() = runBlocking {
        val params = JSONObject().apply {
            put("sessionId", "nonexistent")
            put("confirm", true)
        }
        every { mockDao.getSession(any()) } returns null
        try {
            ChatMutationMethods.delete(mockContext, params)
            assert(false)
        } catch (e: RPCException) {
            assert(e.code == -32602)
        }
    }
}