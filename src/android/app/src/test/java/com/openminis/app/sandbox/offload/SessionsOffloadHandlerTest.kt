package com.openminis.app.sandbox.offload

import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.MessageMeta
import com.openminis.app.data.repository.SessionMeta
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import org.json.JSONObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension

@ExtendWith(MockitoExtension::class)
class SessionsOffloadHandlerTest {

    @Mock
    private lateinit var repo: ChatRepository

    private val handler = SessionsOffloadHandler(repo)

    private val baseArgs = listOf("minis-sessions-cli")

    // ---------- helper to build a request ----------
    private fun req(vararg args: String) = NativeOffloadRequest(baseArgs + args.toList())

    // ---------- helper to mock SessionMeta ----------
    private fun mockSessionMeta(
        id: String = "sess1",
        startedAt: Long = 1000L,
        lastActive: Long = 2000L,
        messageCount: Int = 5,
        title: String? = "Test Title",
        preview: String? = "Hello world",
        source: String? = "user"
    ): SessionMeta {
        val m = org.mockito.Mockito.mock(SessionMeta::class.java)
        `when`(m.id).thenReturn(id)
        `when`(m.startedAt).thenReturn(startedAt)
        `when`(m.lastActive).thenReturn(lastActive)
        `when`(m.messageCount).thenReturn(messageCount)
        `when`(m.title).thenReturn(title)
        `when`(m.preview).thenReturn(preview)
        `when`(m.source).thenReturn(source)
        return m
    }

    // ---------- helper to mock MessageMeta ----------
    private fun mockMessageMeta(
        sessionId: String = "sess1",
        messageId: String = "msg1",
        role: String = "user",
        createdAt: Long = 1500L,
        snippet: String = "some snippet"
    ): MessageMeta {
        val m = org.mockito.Mockito.mock(MessageMeta::class.java)
        `when`(m.sessionId).thenReturn(sessionId)
        `when`(m.messageId).thenReturn(messageId)
        `when`(m.role).thenReturn(role)
        `when`(m.createdAt).thenReturn(createdAt)
        `when`(m.snippet).thenReturn(snippet)
        return m
    }

    // ---------- helper to mock ChatRepository.MessageRow ----------
    private fun mockMessageRow(
        messageId: String = "msg1",
        role: String = "user",
        createdAt: Long = 1500L,
        text: String = "Hello",
        truncated: Boolean = false
    ): ChatRepository.MessageRow {
        val m = org.mockito.Mockito.mock(ChatRepository.MessageRow::class.java)
        `when`(m.messageId).thenReturn(messageId)
        `when`(m.role).thenReturn(role)
        `when`(m.createdAt).thenReturn(createdAt)
        `when`(m.text).thenReturn(text)
        `when`(m.truncated).thenReturn(truncated)
        return m
    }

    // ==================== Tests ====================

    @Test
    fun `handle --help returns help text`() {
        val result: NativeOffloadResult = handler.handle(req("--help"))
        assert(result.exitCode == 0)
        assert(result.output.contains("minis-sessions-cli - Query historical chat sessions and messages"))
    }

    @Test
    fun `handle -h returns help text`() {
        val result = handler.handle(req("-h"))
        assert(result.exitCode == 0)
        assert(result.output.contains("USAGE:"))
    }

    @Test
    fun `handle unknown command returns error`() {
        val result = handler.handle(req("unknown"))
        assert(result.exitCode == 2)
        assert(result.output.contains("Unknown command 'unknown'"))
    }

    @Test
    fun `handle list with no arguments`() {
        val meta = listOf(mockSessionMeta())
        `when`(runBlocking { repo.querySessionsMeta(null, null, 50, null, null) }).thenReturn(meta)

        val result = handler.handle(req("list"))
        assert(result.exitCode == 0)
        val json = JSONObject(result.output)
        assert(json.getJSONObject("data").getInt("count") == 1)
        assert(json.getJSONObject("data").getJSONArray("sessions").length() == 1)
    }

    @Test
    fun `handle list with --limit`() {
        val meta = listOf(mockSessionMeta())
        `when`(runBlocking { repo.querySessionsMeta(null, null, 10, null, null) }).thenReturn(meta)

        val result = handler.handle(req("list", "--limit", "10"))
        assert(result.exitCode == 0)
        val count = JSONObject(result.output).getJSONObject("data").getInt("count")
        assert(count == 1)
    }

    @Test
    fun `handle list with --ids`() {
        val meta = listOf(mockSessionMeta(id = "abc"))
        `when`(runBlocking { repo.querySessionsMeta(listOf("abc"), null, 50, null, null) }).thenReturn(meta)

        val result = handler.handle(req("list", "--ids", "abc"))
        assert(result.exitCode == 0)
        val sessions = JSONObject(result.output).getJSONObject("data").getJSONArray("sessions")
        assert(sessions.length() == 1)
        assert(sessions.getJSONObject(0).getString("session_id") == "abc")
    }

    @Test
    fun `handle list with --keywords`() {
        val meta = listOf(mockSessionMeta())
        `when`(runBlocking { repo.querySessionsMeta(null, listOf("foo", "bar"), 50, null, null) }).thenReturn(meta)

        val result = handler.handle(req("list", "--keywords", "foo bar"))
        assert(result.exitCode == 0)
        assert(JSONObject(result.output).getJSONObject("data").getInt("count") == 1)
    }

    @Test
    fun `handle list with --start and --end`() {
        val meta = listOf(mockSessionMeta())
        // start = 2025-01-01, end = 2025-01-02 23:59:59.999
        // We mock the repo call; actual date parsing is tested indirectly
        `when`(runBlocking { repo.querySessionsMeta(null, null, 50, 1735689600000L, 1735862399999L) }).thenReturn(meta)

        val result = handler.handle(req("list", "--start", "2025-01-01", "--end", "2025-01-02"))
        assert(result.exitCode == 0)
    }

    @Test
    fun `handle search without --keywords returns error`() {
        val result = handler.handle(req("search"))
        assert(result.exitCode == 2)
        assert(result.output.contains("--keywords is required for search"))
    }

    @Test
    fun `handle search with --keywords`() {
        val matches = listOf(mockMessageMeta())
        `when`(runBlocking { repo.searchMessages(null, listOf("API", "error"), 50, null, null) }).thenReturn(matches)

        val result = handler.handle(req("search", "--keywords", "API error"))
        assert(result.exitCode == 0)
        val data = JSONObject(result.output).getJSONObject("data")
        assert(data.getInt("count") == 1)
        assert(data.getJSONArray("messages").length() == 1)
    }

    @Test
    fun `handle search with --ids`() {
        val matches = listOf(mockMessageMeta(sessionId = "sess123"))
        `when`(runBlocking { repo.searchMessages(listOf("sess123"), listOf("hello"), 50, null, null) }).thenReturn(matches)

        val result = handler.handle(req("search", "--keywords", "hello", "--ids", "sess123"))
        assert(result.exitCode == 0)
    }

    @Test
    fun `handle messages without --id returns error`() {
        val result = handler.handle(req("messages"))
        assert(result.exitCode == 2)
        assert(result.output.contains("--id <session_id> is required"))
    }

    @Test
    fun `handle messages with --id`() {
        val rows = listOf(mockMessageRow())
        `when`(runBlocking { repo.loadMessagePage("sess1", 0, 50, 600) }).thenReturn(rows)
        `when`(runBlocking { repo.messageCount("sess1") }).thenReturn(1)

        val result = handler.handle(req("messages", "--id", "sess1"))
        assert(result.exitCode == 0)
        val data = JSONObject(result.output).getJSONObject("data")
        assert(data.getString("session_id") == "sess1")
        assert(data.getInt("total") == 1)
        assert(data.getBoolean("full") == false)
        assert(data.getInt("max_chars") == 600)
    }

    @Test
    fun `handle messages with --full`() {
        val rows = listOf(mockMessageRow(text = "long text"))
        `when`(runBlocking { repo.loadMessagePage("sess1", 0, 50, 50000) }).thenReturn(rows)
        `when`(runBlocking { repo.messageCount("sess1") }).thenReturn(1)

        val result = handler.handle(req("messages", "--id", "sess1", "--full"))
        assert(result.exitCode == 0)
        val data = JSONObject(result.output).getJSONObject("data")
        assert(data.getBoolean("full"))
        assert(data.getInt("max_chars") == 50000)
    }

    @Test
    fun `handle messages with --offset`() {
        val rows = listOf(mockMessageRow(messageId = "msg2"))
        `when`(runBlocking { repo.loadMessagePage("sess1", 20, 50, 600) }).thenReturn(rows)
        `when`(runBlocking { repo.messageCount("sess1") }).thenReturn(25)

        val result = handler.handle(req("messages", "--id", "sess1", "--offset", "20"))
        assert(result.exitCode == 0)
        val data = JSONObject(result.output).getJSONObject("data")
        assert(data.getInt("offset") == 20)
        // total >= offset+count
        assert(data.getInt("total") == 25)
    }

    @Test
    fun `handle messages with truncated flag`() {
        val rows = listOf(mockMessageRow(truncated = true))
        `when`(runBlocking { repo.loadMessagePage("sess1", 0, 50, 600) }).thenReturn(rows)
        `when`(runBlocking { repo.messageCount("sess1") }).thenReturn(1)

        val result = handler.handle(req("messages", "--id", "sess1"))
        assert(result.exitCode == 0)
        val msg = JSONObject(result.output)
            .getJSONObject("data")
            .getJSONArray("messages")
            .getJSONObject(0)
        assert(msg.getBoolean("truncated"))
    }

    @Test
    fun `handle internal exception returns error exit code 1`() {
        // simulate a repo failure
        `when`(runBlocking { repo.querySessionsMeta(null, null, 50, null, null) })
            .thenThrow(RuntimeException("DB failure"))

        val result = handler.handle(req("list"))
        assert(result.exitCode == 1)
        assert(result.output.contains("INTERNAL"))
        assert(result.output.contains("DB failure"))
    }

    @Test
    fun `handle list with limit exceeding max clamps to 100`() {
        val meta = listOf(mockSessionMeta())
        `when`(runBlocking { repo.querySessionsMeta(null, null, 100, null, null) }).thenReturn(meta)

        val result = handler.handle(req("list", "--limit", "200"))
        assert(result.exitCode == 0)
        // output should be fine
        assert(JSONObject(result.output).getJSONObject("data").getInt("count") == 1)
    }

    @Test
    fun `handle list with limit 0 clamps to 1`() {
        val meta = listOf(mockSessionMeta())
        `when`(runBlocking { repo.querySessionsMeta(null, null, 1, null, null) }).thenReturn(meta)

        val result = handler.handle(req("list", "--limit", "0"))
        assert(result.exitCode == 0)
    }

    @Test
    fun `handle search with --ids and --keywords`() {
        val matches = listOf(mockMessageMeta())
        `when`(runBlocking { repo.searchMessages(listOf("a", "b"), listOf("kw1", "kw2"), 50, null, null) })
            .thenReturn(matches)

        val result = handler.handle(req("search", "--keywords", "kw1 kw2", "--ids", "a,b"))
        assert(result.exitCode == 0)
        val data = JSONObject(result.output).getJSONObject("data")
        assert(data.getInt("count") == 1)
    }

    @Test
    fun `handle messages with --limit`() {
        val rows = listOf(mockMessageRow(), mockMessageRow())
        `when`(runBlocking { repo.loadMessagePage("sess1", 0, 5, 600) }).thenReturn(rows)
        `when`(runBlocking { repo.messageCount("sess1") }).thenReturn(10)

        val result = handler.handle(req("messages", "--id", "sess1", "--limit", "5"))
        assert(result.exitCode == 0)
        val data = JSONObject(result.output).getJSONObject("data")
        assert(data.getInt("limit") == 5)
        assert(data.getInt("count") == 2)
    }

    @Test
    fun `handle messages with large limit capped to 100`() {
        val rows = listOf(mockMessageRow())
        `when`(runBlocking { repo.loadMessagePage("sess1", 0, 100, 600) }).thenReturn(rows)
        `when`(runBlocking { repo.messageCount("sess1") }).thenReturn(1)

        val result = handler.handle(req("messages", "--id", "sess1", "--limit", "150"))
        assert(result.exitCode == 0)
        assert(JSONObject(result.output).getJSONObject("data").getInt("limit") == 100)
    }

    @Test
    fun `handle list with session having no title`() {
        val meta = listOf(mockSessionMeta(title = null))
        `when`(runBlocking { repo.querySessionsMeta(null, null, 50, null, null) }).thenReturn(meta)

        val result = handler.handle(req("list"))
        val session = JSONObject(result.output).getJSONObject("data").getJSONArray("sessions").getJSONObject(0)
        // title should not be present
        assert(!session.has("title"))
    }

    @Test
    fun `handle list with empty preview`() {
        val meta = listOf(mockSessionMeta(preview = ""))
        `when`(runBlocking { repo.querySessionsMeta(null, null, 50, null, null) }).thenReturn(meta)

        val result = handler.handle(req("list"))
        val session = JSONObject(result.output).getJSONObject("data").getJSONArray("sessions").getJSONObject(0)
        assert(!session.has("preview"))
    }

    @Test
    fun `handle list with empty source`() {
        val meta = listOf(mockSessionMeta(source = ""))
        `when`(runBlocking { repo.querySessionsMeta(null, null, 50, null, null) }).thenReturn(meta)

        val result = handler.handle(req("list"))
        val session = JSONObject(result.output).getJSONObject("data").getJSONArray("sessions").getJSONObject(0)
        assert(!session.has("source"))
    }
}