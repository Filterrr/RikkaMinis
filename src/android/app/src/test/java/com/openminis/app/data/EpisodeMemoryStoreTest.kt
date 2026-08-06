package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EpisodeMemoryStore 纯逻辑测试（JVM）。
 *
 * 覆盖：分词、打分、检索排序/过滤、注入模板、JSONL 容错、
 * 近重复合并、验证计数器反馈、滚动上限、清空。
 */
class EpisodeMemoryStoreTest {

    // ── tokenize ──

    @Test
    fun `tokenize splits CJK chars and latin words`() {
        assertEquals(listOf("修", "滚", "动", "bug"), EpisodeMemoryStore.tokenize("修 滚动 bug"))
    }

    @Test
    fun `tokenize lowercases and drops single latin chars`() {
        assertEquals(listOf("build", "apk"), EpisodeMemoryStore.tokenize("Build APK"))
        assertEquals(listOf("apk"), EpisodeMemoryStore.tokenize("a APK"))
    }

    @Test
    fun `tokenize handles mixed queries and drops stop chars`() {
        assertEquals(listOf("查", "模", "型", "余", "额"), EpisodeMemoryStore.tokenize("帮我查下模型余额"))
    }

    @Test
    fun `tokenize returns empty for blank and pure stop words`() {
        assertTrue(EpisodeMemoryStore.tokenize("   ").isEmpty())
        assertTrue(EpisodeMemoryStore.tokenize("帮我一下").isEmpty())
    }

    // ── score ──

    private fun ep(q: String, reply: String = "", tools: List<EpisodeMemoryStore.ToolCall> = emptyList()): EpisodeMemoryStore.Episode =
        EpisodeMemoryStore.Episode(
            t = 1754567890000L, q = q, tools = tools, ok = true,
            durMs = 1000, reply = reply, sid = "s",
        )

    @Test
    fun `score counts query hits double`() {
        val e = ep("模型余额")
        // tokenize("模型余额") → [模,型,余,额] 4 token，每个命中 q +2 → 8
        assertEquals(8, EpisodeMemoryStore.score(EpisodeMemoryStore.tokenize("模型余额"), e))
    }

    @Test
    fun `score counts reply hits once`() {
        val e = ep("查询", reply = "这是关于模型的回复")
        assertEquals(2, EpisodeMemoryStore.score(EpisodeMemoryStore.tokenize("模型"), e))
    }

    @Test
    fun `score counts tool name hits once`() {
        val e = ep("查询", tools = listOf(EpisodeMemoryStore.ToolCall("shell_execute", true)))
        assertEquals(1, EpisodeMemoryStore.score(EpisodeMemoryStore.tokenize("shell"), e))
    }

    @Test
    fun `score returns zero on no match`() {
        val e = ep("模型余额")
        assertEquals(0, EpisodeMemoryStore.score(EpisodeMemoryStore.tokenize("天气"), e))
    }

    // ── JSONL roundtrip ──

    @Test
    fun `toLine then parseLine roundtrips all fields`() {
        val rec = EpisodeMemoryStore.ExchangeRecord(
            query = "查余额", tools = listOf(EpisodeMemoryStore.ToolCall("shell_execute", true)),
            ok = false, durationMs = 5000, reply = "余额不足", sessionId = "sid-1",
        )
        val line = EpisodeMemoryStore.toLine(rec, v = 3)
        val parsed = EpisodeMemoryStore.parseLine(line)!!
        assertEquals("查余额", parsed.q)
        assertEquals("shell_execute", parsed.tools[0].n)
        assertTrue(parsed.tools[0].ok)
        assertFalse(parsed.ok)
        assertEquals(5000, parsed.durMs)
        assertEquals("余额不足", parsed.reply)
        assertEquals("sid-1", parsed.sid)
        assertEquals(3, parsed.v)
    }

    @Test
    fun `parseLine tolerates malformed json`() {
        assertEquals(null, EpisodeMemoryStore.parseLine("not json at all"))
        assertEquals(null, EpisodeMemoryStore.parseLine(""))
    }

    // ── injection block ──

    @Test
    fun `injection block contains query tools and ok marker`() {
        val r = listOf(
            EpisodeMemoryStore.Retrieved(
                index = 0,
                episode = ep("模型余额", reply = "余额不足需要充值", tools = listOf(EpisodeMemoryStore.ToolCall("shell_execute", true))),
                score = 8,
            )
        )
        val block = EpisodeMemoryStore.buildInjectionBlock(r)
        assertTrue(block.contains("<experience-memory>"))
        assertTrue(block.contains("模型余额"))
        assertTrue(block.contains("shell_execute(✓)"))
        assertTrue(block.contains("成功"))
    }

    @Test
    fun `injection block empty for no hits`() {
        assertEquals("", EpisodeMemoryStore.buildInjectionBlock(emptyList()))
    }

    // ── file-backed store behaviour ──

    private fun tmpFile(): java.io.File = java.io.File.createTempFile("epmem", ".jsonl")

    @Test
    fun `record appends and retrieve finds it`() {
        val f = tmpFile()
        val store = EpisodeMemoryStore(f)
        store.record(
            EpisodeMemoryStore.ExchangeRecord(
                query = "模型余额", tools = listOf(EpisodeMemoryStore.ToolCall("shell_execute", true)),
                ok = true, durationMs = 1000, reply = "查到了", sessionId = "s",
            )
        )
        val hits = store.retrieve("查模型余额")
        assertEquals(1, hits.size)
        assertEquals("模型余额", hits[0].episode.q)
    }

    @Test
    fun `retrieve returns empty below min score`() {
        val f = tmpFile()
        val store = EpisodeMemoryStore(f, minScore = 4)
        store.record(
            EpisodeMemoryStore.ExchangeRecord(
                query = "模型余额", tools = emptyList(),
                ok = true, durationMs = 1000, reply = "", sessionId = "s",
            )
        )
        // "天气" 无命中 → score 0 < 4 → 空
        assertTrue(store.retrieve("天气").isEmpty())
    }

    @Test
    fun `duplicate query updates in place and keeps v`() {
        val f = tmpFile()
        val store = EpisodeMemoryStore(f)
        store.record(
            EpisodeMemoryStore.ExchangeRecord(
                query = "查余额", tools = listOf(EpisodeMemoryStore.ToolCall("shell_execute", true)),
                ok = true, durationMs = 1000, reply = "v1", sessionId = "s",
            )
        )
        // 手动给这条加一次反馈，v 变成 1
        store.applyFeedback(listOf(0), ok = true)
        // 同 query 再 record → 原位替换，v 保留
        store.record(
            EpisodeMemoryStore.ExchangeRecord(
                query = "查余额", tools = listOf(EpisodeMemoryStore.ToolCall("shell_execute", true)),
                ok = true, durationMs = 2000, reply = "v2", sessionId = "s",
            )
        )
        assertEquals(1, store.size())
        assertEquals("v2", store.snapshot()[0].reply)
        assertEquals(1, store.snapshot()[0].v)
    }

    @Test
    fun `feedback increments and decrements verification counter`() {
        val f = tmpFile()
        val store = EpisodeMemoryStore(f)
        store.record(
            EpisodeMemoryStore.ExchangeRecord(
                query = "查余额", tools = emptyList(),
                ok = true, durationMs = 1000, reply = "", sessionId = "s",
            )
        )
        store.applyFeedback(listOf(0), ok = true)
        store.applyFeedback(listOf(0), ok = true)
        store.applyFeedback(listOf(0), ok = false)
        assertEquals(1, store.snapshot()[0].v)
    }

    @Test
    fun `rollover drops oldest entries beyond max`() {
        val f = tmpFile()
        val store = EpisodeMemoryStore(f, maxEntries = 3)
        for (i in 1..5) {
            store.record(
                EpisodeMemoryStore.ExchangeRecord(
                    query = "q$i", tools = emptyList(),
                    ok = true, durationMs = 1000, reply = "r$i", sessionId = "s",
                )
            )
        }
        assertEquals(3, store.size())
        assertEquals("q3", store.snapshot()[0].q)
        assertEquals("q5", store.snapshot()[2].q)
    }

    @Test
    fun `clear empties the store`() {
        val f = tmpFile()
        val store = EpisodeMemoryStore(f)
        store.record(
            EpisodeMemoryStore.ExchangeRecord(
                query = "查余额", tools = emptyList(),
                ok = true, durationMs = 1000, reply = "", sessionId = "s",
            )
        )
        store.clear()
        assertEquals(0, store.size())
        assertTrue(store.retrieve("查余额").isEmpty())
    }

    @Test
    fun `retrieve ranks by score then recency`() {
        val f = tmpFile()
        val store = EpisodeMemoryStore(f)
        store.record(
            EpisodeMemoryStore.ExchangeRecord(
                query = "滚动条跳动", tools = listOf(EpisodeMemoryStore.ToolCall("file_edit", true)),
                ok = true, durationMs = 1000, reply = "", sessionId = "s",
            )
        )
        store.record(
            EpisodeMemoryStore.ExchangeRecord(
                query = "天气", tools = emptyList(),
                ok = true, durationMs = 1000, reply = "", sessionId = "s",
            )
        )
        val hits = store.retrieve("滚动条跳动修复")
        assertEquals(1, hits.size)
        assertEquals("滚动条跳动", hits[0].episode.q)
    }

    @Test
    fun `retrieve caps at maxInject`() {
        val f = tmpFile()
        val store = EpisodeMemoryStore(f, maxInject = 2)
        for (i in 1..4) {
            store.record(
                EpisodeMemoryStore.ExchangeRecord(
                    query = "模型余额$i", tools = emptyList(),
                    ok = true, durationMs = 1000, reply = "r", sessionId = "s",
                )
            )
        }
        val hits = store.retrieve("模型余额")
        assertEquals(2, hits.size)
    }
}
