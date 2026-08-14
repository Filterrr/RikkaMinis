package com.openminis.app.provider

import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.provider.openai.OpenAIProvider
import com.openminis.app.provider.openai.THINK_TAG_FORMATS
import com.openminis.app.provider.openai.ThinkTagDef
import com.openminis.app.provider.openai.scanThinkTags
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [thinking-fold-content-extract] Think-tag extraction must handle:
 * 1. Variant tags — `<thinking>`, `<reasoning>`, `[Think]`, `[REASONING]`
 *    (case-insensitive), not just `<think>`.
 * 2. Tags split across SSE chunks (cross-chunk buffering).
 * 3. Middleman relays that merge reasoning into the content field —
 *    extraction must run for ALL models (hasThinkTags defaults true),
 *    not just qwen/dashscope.
 * 4. Regression: official `reasoning_content` field still takes priority
 *    when present alongside content (relay double-write scenario).
 */
class ThinkTagExtractionTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun provider(model: LLMModel): OpenAIProvider = OpenAIProvider(
        apiKey = "test-key",
        model = model,
        basePath = server.url("/").toString().trimEnd('/'),
    )

    private fun sseBody(vararg events: String): String = buildString {
        for (event in events) {
            append("data: $event")
            append("\n\n")
        }
        append("data: [DONE]")
        append("\n\n")
    }

    // -- scanThinkTags pure-function tests (cross-chunk stateful simulation) --

    /**
     * Stateful wrapper mirroring how OpenAIProvider.extractThinkTags uses
     * scanThinkTags: keeps the remaining buffer + inside/format state between
     * chunks, accumulates emitted visible/thinking text.
     */
    private class ScanAccumulator(private val formats: List<ThinkTagDef> = THINK_TAG_FORMATS) {
        var buffer = ""
        var inside = false
        var fmt: ThinkTagDef? = null
        val visible = StringBuilder()
        val thinking = StringBuilder()

        fun append(text: String) {
            val r = scanThinkTags(buffer + text, inside, fmt, formats)
            buffer = r.remainingBuffer
            inside = r.insideTag
            fmt = r.currentFormat
            visible.append(r.visible)
            thinking.append(r.thinking)
        }

        /** EOF: pending buffer goes to thinking if inside a tag, else visible. */
        fun flush(): Pair<String, String> {
            if (inside) thinking.append(buffer) else visible.append(buffer)
            return visible.toString() to thinking.toString()
        }
    }

    @Test
    fun `extracts thinking tag with visible text around it`() {
        val acc = ScanAccumulator()
        acc.append("Hello <thinking>secret plan</thinking> world")
        val (visible, thinking) = acc.flush()
        assertEquals("Hello  world", visible)
        assertEquals("secret plan", thinking)
    }

    @Test
    fun `extracts reasoning variant tag`() {
        val acc = ScanAccumulator()
        acc.append("Before <reasoning>deep thought</reasoning> after")
        val (visible, thinking) = acc.flush()
        assertEquals("Before  after", visible)
        assertEquals("deep thought", thinking)
    }

    @Test
    fun `extracts bracket Think variant`() {
        val acc = ScanAccumulator()
        acc.append("A [Think]inner voice[/Think] B")
        val (visible, thinking) = acc.flush()
        assertEquals("A  B", visible)
        assertEquals("inner voice", thinking)
    }

    @Test
    fun `extracts bracket REASONING variant`() {
        val acc = ScanAccumulator()
        acc.append("[REASONING]analysis[/REASONING]result")
        val (visible, thinking) = acc.flush()
        assertEquals("result", visible)
        assertEquals("analysis", thinking)
    }

    @Test
    fun `is case-insensitive on uppercase tags`() {
        val acc = ScanAccumulator()
        acc.append("X <THINKING>upper</THINKING> Y")
        val (visible, thinking) = acc.flush()
        assertEquals("X  Y", visible)
        assertEquals("upper", thinking)
    }

    @Test
    fun `passes through plain text unchanged`() {
        val acc = ScanAccumulator()
        acc.append("Just regular text with no tags")
        val (visible, thinking) = acc.flush()
        assertEquals("Just regular text with no tags", visible)
        assertEquals("", thinking)
    }

    @Test
    fun `buffers partial open tag across chunks`() {
        // SSE splits the tag: "<th" + "inking>..."
        val acc = ScanAccumulator()
        acc.append("intro <th")
        acc.append("inking>inside</thinking>outro")
        val (visible, thinking) = acc.flush()
        assertEquals("intro outro", visible)
        assertEquals("inside", thinking)
    }

    @Test
    fun `buffers partial close tag across chunks`() {
        // Close tag split: "</think" + "ing>"
        val acc = ScanAccumulator()
        acc.append("a <thinking>secret</th")
        acc.append("inking>b")
        val (visible, thinking) = acc.flush()
        assertEquals("a b", visible)
        assertEquals("secret", thinking)
    }

    @Test
    fun `buffers variant open tag split across many chunks`() {
        // <reasoning> is 11 chars — split into 3 chunks
        val acc = ScanAccumulator()
        acc.append("Hi <rea")
        acc.append("soning>work")
        acc.append("ing on it</reasoning>done")
        val (visible, thinking) = acc.flush()
        assertEquals("Hi done", visible)
        assertEquals("working on it", thinking)
    }

    @Test
    fun `handles consecutive tags in one stream`() {
        val acc = ScanAccumulator()
        acc.append("<thinking>one</thinking><thinking>two</thinking>end")
        val (visible, thinking) = acc.flush()
        assertEquals("end", visible)
        assertEquals("onetwo", thinking)
    }

    @Test
    fun `thinking tag closes with response altClose`() {
        // DeepSeek R1 style: <thinking> content closes with <response> (no
        // </thinking>). The <response> terminator must be recognized too.
        val acc = ScanAccumulator()
        acc.append("A <thinking>secret plan<response>reply text B")
        val (visible, thinking) = acc.flush()
        assertEquals("A reply text B", visible)
        assertEquals("secret plan", thinking)
    }

    @Test
    fun `altClose split across chunks is extracted`() {
        // <response> itself split across SSE chunks
        val acc = ScanAccumulator()
        acc.append("A <thinking>secret<re")
        acc.append("sponse>reply B")
        val (visible, thinking) = acc.flush()
        assertEquals("A reply B", visible)
        assertEquals("secret", thinking)
    }

    @Test
    fun `standard close preferred when both terminators present`() {
        val acc = ScanAccumulator()
        acc.append("<thinking>t1</thinking>vis<thinking>t2<response>vis2")
        val (visible, thinking) = acc.flush()
        assertEquals("visvis2", visible)
        assertEquals("t1t2", thinking)
    }

    @Test
    fun `plain text containing the word thinking is not misdetected`() {
        // "No thinking here" must NOT trigger extraction — explicit tags only.
        val acc = ScanAccumulator()
        acc.append("No thinking here, just text")
        val (visible, thinking) = acc.flush()
        assertEquals("No thinking here, just text", visible)
        assertEquals("", thinking)
    }

    @Test
    fun `tag formats list contains all documented variants`() {
        val opens = THINK_TAG_FORMATS.map { it.open }
        assertTrue(opens.contains("<thinking>"))
        assertTrue(opens.contains("<reasoning>"))
        assertTrue(opens.contains("[think]"))
        assertTrue(opens.contains("[reasoning]"))
        // DeepSeek R1 alternative terminator rides on the thinking entry
        assertEquals("<response>", THINK_TAG_FORMATS.first { it.open == "<thinking>" }.altClose)
        // standard-close entries have no altClose
        assertTrue(THINK_TAG_FORMATS.filter { it.open != "<thinking>" }.all { it.altClose == null })
    }

    // -- Integration: relay merges reasoning into content (all models) --

    @Test
    fun `deepseek model extracts thinking from content field`() = runBlocking {
        // Relay scenario: deepseek (non-qwen) model, thinking merged into content
        val deepseek = LLMModel("deepseek-v4", "DeepSeek V4", "OpenAI", supportsReasoning = true)
        val p = provider(deepseek)
        val responseBody = sseBody(
            """{"choices":[{"delta":{"content":"Let me think first <thinking>need to check the API</thinking> Here is the answer."},"finish_reason":"stop"}]}"""
        )
        server.enqueue(MockResponse().setBody(responseBody).setHeader("Content-Type", "text/event-stream"))

        val chunks = p.streamMessage(listOf(LLMMessage(LLMMessage.Role.USER, "Hi")), null, 1024).toList()
        val thinking = chunks.filterIsInstance<LLMStreamChunk.ThinkingDelta>().joinToString("") { it.text }
        val text = chunks.filterIsInstance<LLMStreamChunk.Text>().joinToString("") { it.text }

        assertEquals("need to check the API", thinking)
        assertEquals("Let me think first  Here is the answer.", text)
    }

    @Test
    fun `glm model extracts reasoning variant from content field`() = runBlocking {
        val glm = LLMModel("glm-4.5", "GLM 4.5", "OpenAI", supportsReasoning = true)
        val p = provider(glm)
        val responseBody = sseBody(
            """{"choices":[{"delta":{"content":"<reasoning>step by step</reasoning>Final answer"},"finish_reason":"stop"}]}"""
        )
        server.enqueue(MockResponse().setBody(responseBody).setHeader("Content-Type", "text/event-stream"))

        val chunks = p.streamMessage(listOf(LLMMessage(LLMMessage.Role.USER, "Hi")), null, 1024).toList()
        val thinking = chunks.filterIsInstance<LLMStreamChunk.ThinkingDelta>().joinToString("") { it.text }
        val text = chunks.filterIsInstance<LLMStreamChunk.Text>().joinToString("") { it.text }

        assertEquals("step by step", thinking)
        assertEquals("Final answer", text)
    }

    @Test
    fun `plain text without tags still streams as text for non-qwen models`() = runBlocking {
        val deepseek = LLMModel("deepseek-v4", "DeepSeek V4", "OpenAI", supportsReasoning = true)
        val p = provider(deepseek)
        val responseBody = sseBody(
            """{"choices":[{"delta":{"content":"No thinking here, just text"},"finish_reason":"stop"}]}"""
        )
        server.enqueue(MockResponse().setBody(responseBody).setHeader("Content-Type", "text/event-stream"))

        val chunks = p.streamMessage(listOf(LLMMessage(LLMMessage.Role.USER, "Hi")), null, 1024).toList()
        val text = chunks.filterIsInstance<LLMStreamChunk.Text>().joinToString("") { it.text }
        val thinking = chunks.filterIsInstance<LLMStreamChunk.ThinkingDelta>().joinToString("") { it.text }

        assertEquals("No thinking here, just text", text)
        assertEquals("", thinking)
    }

    @Test
    fun `reasoning_content field still streams ThinkingDelta when present`() = runBlocking {
        // Official contract: rc field streams as thinking; untagged content
        // stays visible text. (Relay double-write — both fields present —
        // results in two ThinkingDeltas, which is acceptable duplication.)
        val deepseek = LLMModel("deepseek-v4", "DeepSeek V4", "OpenAI", supportsReasoning = true)
        val p = provider(deepseek)
        val responseBody = sseBody(
            """{"choices":[{"delta":{"reasoning_content":"official reasoning","content":"answer text"},"finish_reason":"stop"}]}"""
        )
        server.enqueue(MockResponse().setBody(responseBody).setHeader("Content-Type", "text/event-stream"))

        val chunks = p.streamMessage(listOf(LLMMessage(LLMMessage.Role.USER, "Hi")), null, 1024).toList()
        val thinking = chunks.filterIsInstance<LLMStreamChunk.ThinkingDelta>().joinToString("") { it.text }
        val text = chunks.filterIsInstance<LLMStreamChunk.Text>().joinToString("") { it.text }

        // rc field emitted as ThinkingDelta; untagged content stays Text
        assertTrue(thinking.contains("official reasoning"))
        assertEquals("answer text", text)
    }

    @Test
    fun `tag split across SSE events is extracted in integration`() = runBlocking {
        val deepseek = LLMModel("deepseek-v4", "DeepSeek V4", "OpenAI", supportsReasoning = true)
        val p = provider(deepseek)
        val responseBody = sseBody(
            """{"choices":[{"delta":{"content":"A <th"},"finish_reason":null}]}""",
            """{"choices":[{"delta":{"content":"inking>split tag</thinking> B"},"finish_reason":"stop"}]}""",
        )
        server.enqueue(MockResponse().setBody(responseBody).setHeader("Content-Type", "text/event-stream"))

        val chunks = p.streamMessage(listOf(LLMMessage(LLMMessage.Role.USER, "Hi")), null, 1024).toList()
        val thinking = chunks.filterIsInstance<LLMStreamChunk.ThinkingDelta>().joinToString("") { it.text }
        val text = chunks.filterIsInstance<LLMStreamChunk.Text>().joinToString("") { it.text }

        assertEquals("split tag", thinking)
        assertEquals("A  B", text)
    }
}
