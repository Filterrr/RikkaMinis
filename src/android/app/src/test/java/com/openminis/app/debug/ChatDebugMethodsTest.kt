package com.openminis.app.debug

import android.content.Context
import com.openminis.app.MinisApp
import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.service.SessionActivityTracker
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*

class ChatDebugMethodsTest {

    private val context: Context = mock()
    private val minisApp: MinisApp = mock()
    private val chatRepository: ChatRepository = mock()
    private val providerRepository: ProviderRepository = mock()
    private val dao: com.openminis.app.data.db.ChatDao = mock()

    private fun setupMocks() {
        whenever(context.applicationContext).thenReturn(minisApp)
        whenever(minisApp.chatRepository).thenReturn(chatRepository)
        whenever(minisApp.providerRepository).thenReturn(providerRepository)
        whenever(chatRepository.dao).thenReturn(dao)
    }

    @Test
    fun `sessionsList with default params`() = runBlocking {
        setupMocks()
        val session = createSessionEntity()
        whenever(dao.listSessions()).thenReturn(listOf(session))
        whenever(dao.lastMessageParts(session.id)).thenReturn(null)
        whenever(providerRepository.config).thenReturn(mockProviderConfig())

        val params = JSONObject()
        val result = ChatDebugMethods.sessionsList(context, params)

        assertEquals(0, result.getInt("count"))
        assertEquals(0, result.getJSONArray("sessions").length())
    }

    @Test
    fun `sessionsList with includeEmpty and limit`() = runBlocking {
        setupMocks()
        val session1 = createSessionEntity(id = "s1", title = "Title1")
        val session2 = createSessionEntity(id = "s2", title = "Title2")
        whenever(dao.listSessions()).thenReturn(listOf(session1, session2))
        whenever(dao.lastMessageParts(session1.id)).thenReturn(null)
        whenever(dao.lastMessageParts(session2.id)).thenReturn(null)
        whenever(providerRepository.config).thenReturn(mockProviderConfig())

        val params = JSONObject().put("includeEmpty", true).put("limit", 1)
        val result = ChatDebugMethods.sessionsList(context, params)

        assertEquals(1, result.getInt("count"))
        val arr = result.getJSONArray("sessions")
        assertEquals(1, arr.length())
        assertEquals("s1", arr.getJSONObject(0).getString("id"))
    }

    @Test
    fun `sessionsList with limit exceeded`() = runBlocking {
        setupMocks()
        val sessions = (1..3).map { createSessionEntity(id = "s$it", title = "Title$it") }
        whenever(dao.listSessions()).thenReturn(sessions)
        sessions.forEach { whenever(dao.lastMessageParts(it.id)).thenReturn(null) }
        whenever(providerRepository.config).thenReturn(mockProviderConfig())

        val params = JSONObject().put("includeEmpty", true).put("limit", 2)
        val result = ChatDebugMethods.sessionsList(context, params)

        assertEquals(2, result.getInt("count"))
        assertEquals(2, result.getJSONArray("sessions").length())
    }

    @Test
    fun `sessionsList when MinisApp not initialized throws`() = runBlocking {
        whenever(context.applicationContext).thenReturn(null)
        val params = JSONObject()
        assertThrows<RPCException> {
            runBlocking { ChatDebugMethods.sessionsList(context, params) }
        }
    }

    @Test
    fun `sessionsGet with valid session`() = runBlocking {
        setupMocks()
        val session = createSessionEntity(id = "s1", title = "Title")
        whenever(dao.getSession("s1")).thenReturn(session)
        whenever(dao.loadMessages("s1")).thenReturn(emptyList())
        whenever(providerRepository.config).thenReturn(mockProviderConfig())

        val params = JSONObject().put("sessionId", "s1")
        val result = ChatDebugMethods.sessionsGet(context, params)

        assertEquals("s1", result.getString("id"))
        assertEquals("Title", result.getString("title"))
        assertEquals(0, result.getInt("messageCount"))
    }

    @Test
    fun `sessionsGet with missing sessionId throws`() = runBlocking {
        setupMocks()
        val params = JSONObject()
        assertThrows<RPCException> {
            runBlocking { ChatDebugMethods.sessionsGet(context, params) }
        }
    }

    @Test
    fun `sessionsGet with non-existent session throws`() = runBlocking {
        setupMocks()
        whenever(dao.getSession("missing")).thenReturn(null)
        val params = JSONObject().put("sessionId", "missing")
        assertThrows<RPCException> {
            runBlocking { ChatDebugMethods.sessionsGet(context, params) }
        }
    }

    @Test
    fun `messagesList with default params`() = runBlocking {
        setupMocks()
        val session = createSessionEntity(id = "s1")
        whenever(dao.getSession("s1")).thenReturn(session)
        val msg1 = createMessageEntity(id = "m1", role = "user", partsJson = """[{"type":"text","value":"hello"}]""")
        whenever(dao.loadMessages("s1")).thenReturn(listOf(msg1))

        val params = JSONObject().put("sessionId", "s1")
        val result = ChatDebugMethods.messagesList(context, params)

        assertEquals("s1", result.getString("sessionId"))
        assertEquals(1, result.getInt("totalCount"))
        assertEquals(1, result.getInt("count"))
        val arr = result.getJSONArray("messages")
        assertEquals("m1", arr.getJSONObject(0).getString("id"))
    }

    @Test
    fun `messagesList with roles filter`() = runBlocking {
        setupMocks()
        val session = createSessionEntity(id = "s1")
        whenever(dao.getSession("s1")).thenReturn(session)
        val msg1 = createMessageEntity(id = "m1", role = "user", partsJson = """[{"type":"text","value":"hello"}]""")
        val msg2 = createMessageEntity(id = "m2", role = "assistant", partsJson = """[{"type":"text","value":"hi"}]""")
        whenever(dao.loadMessages("s1")).thenReturn(listOf(msg1, msg2))

        val params = JSONObject().put("sessionId", "s1").put("roles", JSONArray().put("user"))
        val result = ChatDebugMethods.messagesList(context, params)

        assertEquals(1, result.getInt("totalCount"))
        assertEquals(1, result.getInt("count"))
        assertEquals("m1", result.getJSONArray("messages").getJSONObject(0).getString("id"))
    }

    @Test
    fun `messagesList with offset and limit`() = runBlocking {
        setupMocks()
        val session = createSessionEntity(id = "s1")
        whenever(dao.getSession("s1")).thenReturn(session)
        val msgs = (1..5).map { createMessageEntity(id = "m$it", role = "user", partsJson = """[{"type":"text","value":"msg$it"}]""") }
        whenever(dao.loadMessages("s1")).thenReturn(msgs)

        val params = JSONObject().put("sessionId", "s1").put("offset", 1).put("limit", 2)
        val result = ChatDebugMethods.messagesList(context, params)

        assertEquals(5, result.getInt("totalCount"))
        assertEquals(2, result.getInt("count"))
        val arr = result.getJSONArray("messages")
        assertEquals("m2", arr.getJSONObject(0).getString("id"))
        assertEquals("m3", arr.getJSONObject(1).getString("id"))
    }

    @Test
    fun `messagesList with includeTools false`() = runBlocking {
        setupMocks()
        val session = createSessionEntity(id = "s1")
        whenever(dao.getSession("s1")).thenReturn(session)
        val msg = createMessageEntity(
            id = "m1",
            role = "assistant",
            partsJson = """[{"type":"text","value":"hello"},{"type":"tool_call","value":"tool1"}]"""
        )
        whenever(dao.loadMessages("s1")).thenReturn(listOf(msg))

        val params = JSONObject().put("sessionId", "s1").put("includeTools", false)
        val result = ChatDebugMethods.messagesList(context, params)

        val message = result.getJSONArray("messages").getJSONObject(0)
        assertFalse(message.has("toolCalls"))
        assertEquals("hello", message.getString("content"))
    }

    @Test
    fun `messagesList with includeTools true`() = runBlocking {
        setupMocks()
        val session = createSessionEntity(id = "s1")
        whenever(dao.getSession("s1")).thenReturn(session)
        val msg = createMessageEntity(
            id = "m1",
            role = "assistant",
            partsJson = """[{"type":"text","value":"hello"},{"type":"tool_call","value":"tool1"}]"""
        )
        whenever(dao.loadMessages("s1")).thenReturn(listOf(msg))

        val params = JSONObject().put("sessionId", "s1").put("includeTools", true)
        val result = ChatDebugMethods.messagesList(context, params)

        val message = result.getJSONArray("messages").getJSONObject(0)
        assertTrue(message.has("toolCalls"))
        assertEquals(1, message.getJSONArray("toolCalls").length())
    }

    @Test
    fun `messagesList with includeReasoning`() = runBlocking {
        setupMocks()
        val session = createSessionEntity(id = "s1")
        whenever(dao.getSession("s1")).thenReturn(session)
        val msg = createMessageEntity(
            id = "m1",
            role = "assistant",
            partsJson = """[{"type":"text","value":"hello"}]""",
            reasoningContent = "reasoning"
        )
        whenever(dao.loadMessages("s1")).thenReturn(listOf(msg))

        val params = JSONObject().put("sessionId", "s1").put("includeReasoning", true)
        val result = ChatDebugMethods.messagesList(context, params)

        val message = result.getJSONArray("messages").getJSONObject(0)
        assertEquals("reasoning", message.getString("reasoningContent"))
    }

    @Test
    fun `messagesList with missing sessionId throws`() = runBlocking {
        setupMocks()
        val params = JSONObject()
        assertThrows<RPCException> {
            runBlocking { ChatDebugMethods.messagesList(context, params) }
        }
    }

    @Test
    fun `messagesList with non-existent session throws`() = runBlocking {
        setupMocks()
        whenever(dao.getSession("missing")).thenReturn(null)
        val params = JSONObject().put("sessionId", "missing")
        assertThrows<RPCException> {
            runBlocking { ChatDebugMethods.messagesList(context, params) }
        }
    }

    @Test
    fun `sessionsUsage with default params`() = runBlocking {
        setupMocks()
        val session = createSessionEntity(id = "s1")
        whenever(dao.getSession("s1")).thenReturn(session)
        val msg = createMessageEntity(
            id = "m1",
            role = "assistant",
            tokenUsage = """{"inputTokens":10,"outputTokens":20,"cacheCreationTokens":5,"cacheReadTokens":15,"reasoningTokens":3}"""
        )
        whenever(dao.loadMessages("s1")).thenReturn(listOf(msg))

        val params = JSONObject().put("sessionId", "s1")
        val result = ChatDebugMethods.sessionsUsage(context, params)

        assertEquals("s1", result.getString("sessionId"))
        val totals = result.getJSONObject("totals")
        assertEquals(10L, totals.getLong("inputTokens"))
        assertEquals(20L, totals.getLong("outputTokens"))
        assertEquals(5L, totals.getLong("cacheCreationTokens"))
        assertEquals(15L, totals.getLong("cacheReadTokens"))
        assertEquals(3L, totals.getLong("reasoningTokens"))
        assertEquals(1, totals.getInt("turnCount"))
        assertFalse(result.has("turns"))
    }

    @Test
    fun `sessionsUsage with perTurn true`() = runBlocking {
        setupMocks()
        val session = createSessionEntity(id = "s1")
        whenever(dao.getSession("s1")).thenReturn(session)
        val msg = createMessageEntity(
            id = "m1",
            role = "assistant",
            tokenUsage = """{"inputTokens":10,"outputTokens":20}"""
        )
        whenever(dao.loadMessages("s1")).thenReturn(listOf(msg))

        val params = JSONObject().put("sessionId", "s1").put("perTurn", true)
        val result = ChatDebugMethods.sessionsUsage(context, params)

        assertTrue(result.has("turns"))
        val turns = result.getJSONArray("turns")
        assertEquals(1, turns.length())
        assertEquals("m1", turns.getJSONObject(0).getString("messageId"))
    }

    @Test
    fun `sessionsUsage with missing sessionId throws`() = runBlocking {
        setupMocks()
        val params = JSONObject()
        assertThrows<RPCException> {
            runBlocking { ChatDebugMethods.sessionsUsage(context, params) }
        }
    }

    @Test
    fun `sessionsUsage with non-existent session throws`() = runBlocking {
        setupMocks()
        whenever(dao.getSession("missing")).thenReturn(null)
        val params = JSONObject().put("sessionId", "missing")
        assertThrows<RPCException> {
            runBlocking { ChatDebugMethods.sessionsUsage(context, params) }
        }
    }

    @Test
    fun `modelsList with default params`() {
        setupMocks()
        val config = mockProviderConfig()
        whenever(providerRepository.config).thenReturn(config)

        val params = JSONObject()
        val result = ChatDebugMethods.modelsList(context, params)

        assertEquals(0, result.getInt("entryCount"))
        assertEquals(0, result.getInt("groupCount"))
        assertTrue(result.isNull("defaultGroupId"))
    }

    @Test
    fun `modelsList with entries and groups`() {
        setupMocks()
        val config = mock<com.openminis.app.data.model.ProviderConfig>()
        val instance = mock<com.openminis.app.data.model.ProviderInstance>()
        whenever(instance.id).thenReturn("inst1")
        whenever(instance.label).thenReturn("Instance1")
        whenever(instance.providerType).thenReturn(com.openminis.app.data.model.ProviderType.OPENAI)
        whenever(instance.isEnabled).thenReturn(true)

        val model = mock<com.openminis.app.data.model.ModelDefinition>()
        whenever(model.id).thenReturn("model1")
        whenever(model.displayName).thenReturn("Model1")
        whenever(model.inputModalities).thenReturn(listOf("image", "video"))
        whenever(model.supportsReasoning).thenReturn(true)
        whenever(model.contextWindow).thenReturn(4096)
        whenever(model.maxOutputTokens).thenReturn(2048)

        val entry = mock<com.openminis.app.data.model.ModelEntry>()
        whenever(entry.id).thenReturn("entry1")
        whenever(entry.modelId).thenReturn("model1")
        whenever(entry.providerInstanceId).thenReturn("inst1")
        whenever(entry.isHidden).thenReturn(false)
        whenever(entry.baseModel).thenReturn(model)
        whenever(entry.model).thenReturn(model)

        val group = mock<com.openminis.app.data.model.ModelGroup>()
        whenever(group.id).thenReturn("group1")
        whenever(group.name).thenReturn("Group1")
        whenever(group.strategy).thenReturn(com.openminis.app.data.model.GroupStrategy.ROUND_ROBIN)
        whenever(group.memberEntryIds).thenReturn(listOf("entry1"))

        whenever(config.instances).thenReturn(listOf(instance))
        whenever(config.modelEntries).thenReturn(listOf(entry))
        whenever(config.modelGroups).thenReturn(listOf(group))
        whenever(config.defaultPrimaryGroupId).thenReturn("group1")
        whenever(providerRepository.config).thenReturn(config)

        val params = JSONObject()
        val result = ChatDebugMethods.modelsList(context, params)

        assertEquals(1, result.getInt("entryCount"))
        assertEquals(1, result.getInt("groupCount"))
        assertEquals("group1", result.getString("defaultGroupId"))

        val entries = result.getJSONArray("entries")
        val entryJson = entries.getJSONObject(0)
        assertEquals("entry1", entryJson.getString("id"))
        assertEquals("model1", entryJson.getString("modelId"))
        assertEquals("Model1", entryJson.getString("modelName"))
        assertEquals("inst1", entryJson.getString("providerInstanceId"))
        assertEquals("Instance1", entryJson.getString("providerInstanceName"))
        assertEquals("OPENAI", entryJson.getString("providerType"))
        assertTrue(entryJson.getBoolean("supportsReasoning"))
        assertTrue(entryJson.getBoolean("supportsImages"))
        assertTrue(entryJson.getBoolean("supportsVideo"))
        assertFalse(entryJson.getBoolean("supportsAudio"))
        assertFalse(entryJson.getBoolean("supportsPDF"))
        assertEquals(4096, entryJson.getInt("contextWindow"))
        assertEquals(2048, entryJson.getInt("maxOutputTokens"))
        assertFalse(entryJson.getBoolean("hidden"))

        val groups = result.getJSONArray("groups")
        val groupJson = groups.getJSONObject(0)
        assertEquals("group1", groupJson.getString("id"))
        assertEquals("Group1", groupJson.getString("name"))
        assertEquals("ROUND_ROBIN", groupJson.getString("strategy"))
        assertTrue(groupJson.getBoolean("isDefault"))
        assertEquals(1, groupJson.getJSONArray("memberEntryIds").length())
    }

    @Test
    fun `modelsList with includeHidden and includeDisabled`() {
        setupMocks()
        val