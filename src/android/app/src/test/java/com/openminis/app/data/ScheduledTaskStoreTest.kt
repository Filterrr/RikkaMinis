package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-scheduled-tasks] 任务数据层的契约。
 *
 * 解析是唯一会碰到"用户/AI 写坏的文件"的地方，所以重点在坏输入上：
 * 缺 id、缺 prompt、越界字段都必须被丢掉而不是崩溃。
 */
class ScheduledTaskStoreTest {

    @Test
    fun `tasks survive the json round-trip`() {
        val tasks = listOf(
            ScheduledTask(
                id = "task-1",
                label = "Morning check",
                prompt = "check the build status",
                sessionId = "sess-1",
                triggerAtMillis = 1_700_000_000_000L,
                daily = true,
            ),
            ScheduledTask(
                id = "task-2",
                label = "One off",
                prompt = "review the PR",
                sessionId = null,
                triggerAtMillis = 1_700_100_000_000L,
                daily = false,
            ),
        )
        val parsed = ScheduledTaskStore.parse(ScheduledTaskStore.toJsonArray(tasks).toString())
        assertEquals(tasks, parsed)
    }

    @Test
    fun `entries without an id or prompt are dropped`() {
        val json = """
            [
              {"id":"a","prompt":"ok"},
              {"id":"","prompt":"no id"},
              {"id":"b","prompt":"   "},
              {"prompt":"no id either"}
            ]
        """.trimIndent()
        val parsed = ScheduledTaskStore.parse(json)
        assertEquals(1, parsed.size)
        assertEquals("a", parsed.single().id)
    }

    @Test
    fun `an empty label falls back to the prompt head`() {
        val parsed = ScheduledTaskStore.parse(
            """[{"id":"a","prompt":"${
                "x".repeat(100)
            }"}]""",
        )
        assertEquals(40, parsed.single().label.length)
    }

    @Test
    fun `garbage in the file yields an empty list, not a crash`() {
        assertTrue(ScheduledTaskStore.parse("not json").isEmpty())
        assertTrue(ScheduledTaskStore.parse("{}").isEmpty())
        assertTrue(ScheduledTaskStore.parse("").isEmpty())
    }

    @Test
    fun `prompt is bounded at parse time`() {
        val long = "y".repeat(ScheduledTask.MAX_PROMPT_CHARS + 500)
        val parsed = ScheduledTaskStore.parse("""[{"id":"a","prompt":"$long"}]""")
        assertEquals(ScheduledTask.MAX_PROMPT_CHARS, parsed.single().prompt.length)
    }

    @Test
    fun `new ids are unique and prefixed`() {
        val ids = (1..50).map { ScheduledTaskStore.newId() }.toSet()
        assertEquals(50, ids.size)
        assertTrue(ids.all { it.startsWith("task-") })
    }
}