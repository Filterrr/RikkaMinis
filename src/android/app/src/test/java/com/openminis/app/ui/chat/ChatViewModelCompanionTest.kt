package com.openminis.app.ui.chat

import com.openminis.app.agent.AgentToolDefinition
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class ChatViewModelCompanionTest {

    // ==================== preflightValidateToolCallImpl 测试 ====================

    @Test
    fun `未知工具名应返回 null`() {
        val tools = listOf(
            AgentToolDefinition(
                name = "known_tool",
                description = "A known tool",
                inputSchema = emptyMap(),
                required = emptyList()
            )
        )
        val args = JSONObject("""{"key": "value"}""")
        val result = ChatViewModel.preflightValidateToolCallImpl("unknown_tool", args, tools)
        assertNull(result, "未知工具名应返回 null")
    }

    @Test
    fun `空参数且工具需要参数应返回错误信息`() {
        val tools = listOf(
            AgentToolDefinition(
                name = "tool_with_required",
                description = "Tool with required params",
                inputSchema = mapOf("param1" to "string"),
                required = listOf("param1")
            )
        )
        val args = JSONObject("{}")
        val result = ChatViewModel.preflightValidateToolCallImpl("tool_with_required", args, tools)
        assertNotNull(result)
        assertTrue(result!!.contains("empty arguments {}"))
        assertTrue(result.contains("requires: param1"))
    }

    @Test
    fun `缺失必填参数应返回错误信息`() {
        val tools = listOf(
            AgentToolDefinition(
                name = "tool_with_two_required",
                description = "Tool with two required params",
                inputSchema = mapOf("param1" to "string", "param2" to "string"),
                required = listOf("param1", "param2")
            )
        )
        val args = JSONObject("""{"param1": "value1"}""")
        val result = ChatViewModel.preflightValidateToolCallImpl("tool_with_two_required", args, tools)
        assertNotNull(result)
        assertTrue(result!!.contains("missing required parameter(s): param2"))
    }

    @Test
    fun `null 参数值应检测为缺失`() {
        val tools = listOf(
            AgentToolDefinition(
                name = "tool_with_null_param",
                description = "Tool with null param",
                inputSchema = mapOf("param1" to "string"),
                required = listOf("param1")
            )
        )
        val args = JSONObject("""{"param1": null}""")
        val result = ChatViewModel.preflightValidateToolCallImpl("tool_with_null_param", args, tools)
        assertNotNull(result)
        assertTrue(result!!.contains("missing required parameter(s): param1"))
    }

    @Test
    fun `空字符串参数且非白名单字段应检测为缺失`() {
        val tools = listOf(
            AgentToolDefinition(
                name = "tool_with_empty_string",
                description = "Tool with empty string param",
                inputSchema = mapOf("param1" to "string"),
                required = listOf("param1")
            )
        )
        val args = JSONObject("""{"param1": ""}""")
        val result = ChatViewModel.preflightValidateToolCallImpl("tool_with_empty_string", args, tools)
        assertNotNull(result)
        assertTrue(result!!.contains("missing required parameter(s): param1"))
    }

    @Test
    fun `空字符串参数且file_edit_new_string白名单字段应正常通过`() {
        val tools = listOf(
            AgentToolDefinition(
                name = "file_edit",
                description = "File edit tool",
                inputSchema = mapOf("new_string" to "string", "other" to "string"),
                required = listOf("new_string", "other")
            )
        )
        // 空字符串的 new_string 应该通过，但 other 为空字符串会失败
        val args = JSONObject("""{"new_string": "", "other": "valid_value"}""")
        val result = ChatViewModel.preflightValidateToolCallImpl("file_edit", args, tools)
        assertNull(result, "file_edit 的 new_string 字段允许空字符串，应通过")
    }

    @Test
    fun `正常参数应返回 null`() {
        val tools = listOf(
            AgentToolDefinition(
                name = "normal_tool",
                description = "Normal tool",
                inputSchema = mapOf("param1" to "string", "param2" to "int"),
                required = listOf("param1", "param2")
            )
        )
        val args = JSONObject("""{"param1": "value1", "param2": 123}""")
        val result = ChatViewModel.preflightValidateToolCallImpl("normal_tool", args, tools)
        assertNull(result, "正常参数应返回 null")
    }

    @Test
    fun `tool_title是非阻塞字段缺失不报错`() {
        val tools = listOf(
            AgentToolDefinition(
                name = "tool_with_title",
                description = "Tool with tool_title",
                inputSchema = mapOf("tool_title" to "string", "param1" to "string"),
                required = listOf("tool_title", "param1")
            )
        )
        // 缺失 tool_title 但不应该报错，只检查 param1
        val args = JSONObject("""{"param1": "value1"}""")
        val result = ChatViewModel.preflightValidateToolCallImpl("tool_with_title", args, tools)
        assertNull(result, "tool_title 是非阻塞字段，缺失不应报错")
    }

    @Test
    fun `tool_title缺失但其他必填参数缺失应报错`() {
        val tools = listOf(
            AgentToolDefinition(
                name = "tool_with_title_and_param",
                description = "Tool with tool_title and param",
                inputSchema = mapOf("tool_title" to "string", "param1" to "string"),
                required = listOf("tool_title", "param1")
            )
        )
        // 缺失 tool_title 和 param1，只应报告 param1 缺失
        val args = JSONObject("{}")
        val result = ChatViewModel.preflightValidateToolCallImpl("tool_with_title_and_param", args, tools)
        assertNotNull(result)
        assertTrue(result!!.contains("missing required parameter(s): param1"))
        assertFalse(result.contains("tool_title"))
    }

    // ==================== preflightEmptyStringAllowed 测试 ====================

    @ParameterizedTest
    @CsvSource(
        "file_edit, new_string, true",
        "file_edit, other_field, false",
        "other_tool, new_string, false",
        "other_tool, any_field, false",
        "file_edit, '', false",
        "'', new_string, false"
    )
    fun `preflightEmptyStringAllowed 行为正确`(tool: String, field: String, expected: Boolean) {
        assertEquals(expected, ChatViewModel.preflightEmptyStringAllowed(tool, field))
    }
}