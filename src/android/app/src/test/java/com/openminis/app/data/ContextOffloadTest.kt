package com.openminis.app.data

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import org.junit.Rule
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Assertions.*
import org.mockito.Mockito.*
import java.io.File
import java.nio.file.Path

class ContextOffloadTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @TempDir
    lateinit var tempDir: Path

    private lateinit var mockContext: Context

    @BeforeEach
    fun setUp() {
        mockContext = mock(Context::class.java)
        `when`(mockContext.filesDir).thenReturn(tempDir.toFile())
    }

    @Test
    fun `toolsDir returns correct path`() {
        val context = mockContext
        val sessionId = "test-session"
        val expectedPath = File(tempDir.toFile(), "minis-sessions/$sessionId/offloads/tools")
        
        val result = ContextOffload.toolsDir(context, sessionId)
        
        assertEquals(expectedPath, result)
    }

    @Test
    fun `offloadContent writes file and returns linux path`() {
        val context = mockContext
        val sessionId = "session-1"
        val content = "Test content"
        val toolId = "tool123"
        val toolName = "testTool"
        
        val result = ContextOffload.offloadContent(context, sessionId, content, toolId, toolName)
        
        assertTrue(result.startsWith(ContextOffload.LINUX_OFFLOADS_DIR))
        assertTrue(result.contains("testTool_tool123.txt"))
        
        val writtenFile = File(tempDir.toFile(), "minis-sessions/$sessionId/offloads/tools/testTool_tool123.txt")
        assertTrue(writtenFile.exists())
        assertEquals(content, writtenFile.readText())
    }

    @Test
    fun `offloadContent with default extension`() {
        val context = mockContext
        val sessionId = "session-2"
        val content = "Default ext content"
        val toolId = "tool456"
        val toolName = "defaultTool"
        
        val result = ContextOffload.offloadContent(context, sessionId, content, toolId, toolName)
        
        assertTrue(result.endsWith(".txt"))
        assertTrue(result.contains("defaultTool_tool456.txt"))
    }

    @Test
    fun `offloadContent with custom extension`() {
        val context = mockContext
        val sessionId = "session-3"
        val content = "Custom ext content"
        val toolId = "tool789"
        val toolName = "customTool"
        
        val result = ContextOffload.offloadContent(context, sessionId, content, toolId, toolName, "md")
        
        assertTrue(result.endsWith(".md"))
        assertTrue(result.contains("customTool_tool789.md"))
    }

    @Test
    fun `offloadContent handles empty toolName`() {
        val context = mockContext
        val sessionId = "session-4"
        val content = "Empty tool name"
        val toolId = "tool001"
        
        val result = ContextOffload.offloadContent(context, sessionId, content, toolId, "")
        
        assertTrue(result.contains("tool_tool001.txt"))
    }

    @Test
    fun `offloadImage writes png file`() {
        val context = mockContext
        val sessionId = "session-5"
        val bytes = byteArrayOf(1, 2, 3, 4)
        val toolId = "imgtool1"
        
        val result = ContextOffload.offloadImage(context, sessionId, bytes, toolId, "image/png")
        
        assertTrue(result.endsWith(".png"))
        assertTrue(result.contains("image_imgtool1.png"))
        
        val writtenFile = File(tempDir.toFile(), "minis-sessions/$sessionId/offloads/tools/image_imgtool1.png")
        assertTrue(writtenFile.exists())
        assertArrayEquals(bytes, writtenFile.readBytes())
    }

    @Test
    fun `offloadImage handles jpeg mime type`() {
        val context = mockContext
        val sessionId = "session-6"
        val bytes = byteArrayOf(5, 6, 7, 8)
        val toolId = "imgtool2"
        
        val result = ContextOffload.offloadImage(context, sessionId, bytes, toolId, "image/jpeg")
        
        assertTrue(result.endsWith(".jpg"))
        assertTrue(result.contains("image_imgtool2.jpg"))
    }

    @Test
    fun `offloadImage handles gif mime type`() {
        val context = mockContext
        val sessionId = "session-7"
        val bytes = byteArrayOf(9, 10, 11, 12)
        val toolId = "imgtool3"
        
        val result = ContextOffload.offloadImage(context, sessionId, bytes, toolId, "image/gif")
        
        assertTrue(result.endsWith(".gif"))
        assertTrue(result.contains("image_imgtool3.gif"))
    }

    @Test
    fun `offloadImage handles webp mime type`() {
        val context = mockContext
        val sessionId = "session-8"
        val bytes = byteArrayOf(13, 14, 15, 16)
        val toolId = "imgtool4"
        
        val result = ContextOffload.offloadImage(context, sessionId, bytes, toolId, "image/webp")
        
        assertTrue(result.endsWith(".webp"))
        assertTrue(result.contains("image_imgtool4.webp"))
    }

    @Test
    fun `offloadImage handles unknown mime type as bin`() {
        val context = mockContext
        val sessionId = "session-9"
        val bytes = byteArrayOf(17, 18, 19, 20)
        val toolId = "imgtool5"
        
        val result = ContextOffload.offloadImage(context, sessionId, bytes, toolId, "application/octet-stream")
        
        assertTrue(result.endsWith(".bin"))
        assertTrue(result.contains("image_imgtool5.bin"))
    }

    @Test
    fun `stub returns formatted string`() {
        val approxTokens = 100
        val byteCount = 2048
        val linuxPath = "/var/minis/offloads/tools/test_file.txt"
        
        val result = ContextOffload.stub(approxTokens, byteCount, linuxPath)
        
        assertTrue(result.startsWith(ContextOffload.OFFLOADED_PREFIX))
        assertTrue(result.contains("~100 tokens"))
        assertTrue(result.contains("2048 bytes"))
        assertTrue(result.contains(linuxPath))
        assertTrue(result.contains("Use file_read tool to retrieve if needed."))
    }

    @Test
    fun `offloadContent handles long toolId`() {
        val context = mockContext
        val sessionId = "session-10"
        val content = "Long tool id test"
        val toolId = "veryLongToolIdThatExceedsTwelveCharacters"
        val toolName = "longTool"
        
        val result = ContextOffload.offloadContent(context, sessionId, content, toolId, toolName)
        
        val expectedShortId = toolId.takeLast(12)
        assertTrue(result.contains("longTool_$expectedShortId.txt"))
    }

    @Test
    fun `offloadContent handles toolName with slashes`() {
        val context = mockContext
        val sessionId = "session-11"
        val content = "Slash test"
        val toolId = "tool002"
        val toolName = "path/to/tool"
        
        val result = ContextOffload.offloadContent(context, sessionId, content, toolId, toolName)
        
        assertTrue(result.contains("path_to_tool_tool002.txt"))
        assertFalse(result.contains("/"))
        assertTrue(result.contains("_"))
    }

    @Test
    fun `offloadContent creates directories if not exist`() {
        val context = mockContext
        val sessionId = "session-12"
        val content = "Directory creation test"
        val toolId = "tool003"
        val toolName = "dirTool"
        
        val result = ContextOffload.offloadContent(context, sessionId, content, toolId, toolName)
        
        val dir = File(tempDir.toFile(), "minis-sessions/$sessionId/offloads/tools")
        assertTrue(dir.exists())
        assertTrue(dir.isDirectory)
    }

    @Test
    fun `offloadContent handles write failure returns empty string`() {
        val context = mockContext
        val sessionId = "session-13"
        val content = "Failure test"
        val toolId = "tool004"
        val toolName = "failTool"
        
        // Make the directory read-only to cause write failure
        val dir = File(tempDir.toFile(), "minis-sessions/$sessionId/offloads/tools")
        dir.mkdirs()
        dir.setReadOnly()
        
        val result = ContextOffload.offloadContent(context, sessionId, content, toolId, toolName)
        
        assertEquals("", result)
        
        // Reset permissions for cleanup
        dir.setWritable(true)
    }

    @Test
    fun `offloadImage handles write failure returns empty string`() {
        val context = mockContext
        val sessionId = "session-14"
        val bytes = byteArrayOf(21, 22, 23, 24)
        val toolId = "tool005"
        
        // Make the directory read-only to cause write failure
        val dir = File(tempDir.toFile(), "minis-sessions/$sessionId/offloads/tools")
        dir.mkdirs()
        dir.setReadOnly()
        
        val result = ContextOffload.offloadImage(context, sessionId, bytes, toolId, "image/png")
        
        assertEquals("", result)
        
        // Reset permissions for cleanup
        dir.setWritable(true)
    }

    @Test
    fun `compose test for offloadContent result display`() {
        composeTestRule.setContent {
            val context = mockContext
            val sessionId = "compose-session"
            val result = ContextOffload.offloadContent(
                context = context,
                sessionId = sessionId,
                content = "Compose test content",
                toolId = "composeTool1",
                toolName = "composeTest"
            )
            
            androidx.compose.material.Text(text = result)
        }
        
        composeTestRule.onRoot().printToString()
        composeTestRule.onNodeWithText(
            ContextOffload.LINUX_OFFLOADS_DIR + "/tools/composeTest_composeTool1.txt",
            substring = true
        ).assertIsDisplayed()
    }

    @Test
    fun `compose test for stub display`() {
        composeTestRule.setContent {
            val stubText = ContextOffload.stub(50, 1024, "/test/path/file.txt")
            androidx.compose.material.Text(text = stubText)
        }
        
        composeTestRule.onRoot().printToString()
        composeTestRule.onNodeWithText(
            ContextOffload.OFFLOADED_PREFIX,
            substring = true
        ).assertIsDisplayed()
    }

    @Test
    fun `compose test for offloadImage result display`() {
        composeTestRule.setContent {
            val context = mockContext
            val sessionId = "compose-image-session"
            val result = ContextOffload.offloadImage(
                context = context,
                sessionId = sessionId,
                bytes = byteArrayOf(1, 2, 3),
                toolId = "composeImgTool",
                mimeType = "image/png"
            )
            
            androidx.compose.material.Text(text = result)
        }
        
        composeTestRule.onRoot().printToString()
        composeTestRule.onNodeWithText(
            ContextOffload.LINUX_OFFLOADS_DIR + "/tools/image_composeImgTool.png",
            substring = true
        ).assertIsDisplayed()
    }

    @Test
    fun `compose test for offloadContent with click action`() {
        composeTestRule.setContent {
            val context = mockContext
            val sessionId = "click-session"
            var clickCount = 0
            
            androidx.compose.material.Button(
                onClick = {
                    ContextOffload.offloadContent(
                        context = context,
                        sessionId = sessionId,
                        content = "Click test",
                        toolId = "clickTool",
                        toolName = "clickTest"
                    )
                    clickCount++
                }
            ) {
                androidx.compose.material.Text("Offload Content")
            }
            
            androidx.compose.material.Text("Click count: $clickCount")
        }
        
        composeTestRule.onNodeWithText("Offload Content").performClick()
        composeTestRule.onNodeWithText("Click count: 1").assertIsDisplayed()
    }
}