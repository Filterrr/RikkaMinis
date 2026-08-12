package com.openminis.app.data.model

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class LLMMessageTest {

    @Test
    fun testLLMMessageCreationAndDefaults() {
        val message = LLMMessage(
            role = LLMMessage.Role.USER,
            content = "Hello"
        )
        assertEquals(LLMMessage.Role.USER, message.role)
        assertEquals("Hello", message.content)
        assertTrue(message.imageParts.isEmpty())
        assertTrue(message.audioParts.isEmpty())
        assertTrue(message.contentParts.isEmpty())
        assertNull(message.dbMessageId)
        assertNull(message.reasoningContent)
    }

    @Test
    fun testLLMMessageWithAllFields() {
        val imagePart = LLMMessage.ImagePart(
            data = byteArrayOf(1, 2, 3),
            mimeType = "image/png",
            linuxPath = "/tmp/img.png"
        )
        val audioPart = LLMMessage.AudioPart(
            format = "wav",
            base64Data = "UklGRiQ="
        )
        val message = LLMMessage(
            role = LLMMessage.Role.ASSISTANT,
            content = "Hi",
            imageParts = listOf(imagePart),
            audioParts = listOf(audioPart),
            dbMessageId = "msg-123",
            reasoningContent = "Thinking..."
        )
        assertEquals(LLMMessage.Role.ASSISTANT, message.role)
        assertEquals("Hi", message.content)
        assertEquals(1, message.imageParts.size)
        assertSame(imagePart, message.imageParts[0])
        assertEquals(1, message.audioParts.size)
        assertSame(audioPart, message.audioParts[0])
        assertEquals("msg-123", message.dbMessageId)
        assertEquals("Thinking...", message.reasoningContent)
    }

    @Test
    fun testLLMMessageCopy() {
        val original = LLMMessage(
            role = LLMMessage.Role.USER,
            content = "Original"
        )
        val copied = original.copy(content = "Copied")
        assertEquals(LLMMessage.Role.USER, copied.role)
        assertEquals("Copied", copied.content)
    }

    @Test
    fun testLLMMessageEqualsAndHashCode() {
        val message1 = LLMMessage(LLMMessage.Role.USER, "Test")
        val message2 = LLMMessage(LLMMessage.Role.USER, "Test")
        val message3 = LLMMessage(LLMMessage.Role.ASSISTANT, "Test")

        assertEquals(message1, message2)
        assertEquals(message1.hashCode(), message2.hashCode())
        assertNotEquals(message1, message3)
    }

    @Test
    fun testRoleEnumValues() {
        assertEquals(2, LLMMessage.Role.values().size)
        assertEquals("user", LLMMessage.Role.USER.value)
        assertEquals("assistant", LLMMessage.Role.ASSISTANT.value)
        assertEquals(LLMMessage.Role.USER, LLMMessage.Role.valueOf("USER"))
        assertEquals(LLMMessage.Role.ASSISTANT, LLMMessage.Role.valueOf("ASSISTANT"))
    }

    @Test
    fun testImagePartCreation() {
        val data = byteArrayOf(0, 1, 0, 1)
        val part = LLMMessage.ImagePart(
            data = data,
            mimeType = "image/jpeg",
            linuxPath = null
        )
        assertSame(data, part.data)
        assertEquals("image/jpeg", part.mimeType)
        assertNull(part.linuxPath)
    }

    @Test
    fun testImagePartCopy() {
        val part = LLMMessage.ImagePart(byteArrayOf(1), "image/png", null)
        val copied = part.copy(mimeType = "image/jpeg")
        assertEquals("image/jpeg", copied.mimeType)
        assertSame(part.data, copied.data)
    }

    @Test
    fun testAudioPartCreation() {
        val part = LLMMessage.AudioPart(
            format = "mp3",
            base64Data = "base64string"
        )
        assertEquals("mp3", part.format)
        assertEquals("base64string", part.base64Data)
    }

    @Test
    fun testAudioPartCopy() {
        val part = LLMMessage.AudioPart("wav", "data")
        val copied = part.copy(format = "mp3")
        assertEquals("mp3", copied.format)
        assertEquals("data", copied.base64Data)
    }

    @Test
    fun testLLMResponseCreationAndDefaults() {
        val response = LLMResponse(
            text = "Response text",
            stopReason = null,
            usage = null
        )
        assertEquals("Response text", response.text)
        assertNull(response.stopReason)
        assertNull(response.usage)
        assertTrue(response.mediaAttachments.isEmpty())
    }

    @Test
    fun testLLMResponseWithMediaAttachments() {
        val attachment = LLMMediaAttachment(
            type = LLMMediaAttachment.MediaType.IMAGE,
            mimeType = "image/png",
            data = byteArrayOf(1, 2)
        )
        val response = LLMResponse(
            text = "Text",
            stopReason = "stop",
            usage = null,
            mediaAttachments = listOf(attachment)
        )
        assertEquals("Text", response.text)
        assertEquals("stop", response.stopReason)
        assertEquals(1, response.mediaAttachments.size)
        assertSame(attachment, response.mediaAttachments[0])
    }

    @Test
    fun testLLMResponseCopy() {
        val response = LLMResponse("text", null, null)
        val copied = response.copy(stopReason = "finished")
        assertEquals("text", copied.text)
        assertEquals("finished", copied.stopReason)
    }

    @Test
    fun testLLMMediaAttachmentCreation() {
        val data = byteArrayOf(10, 20, 30)
        val attachment = LLMMediaAttachment(
            type = LLMMediaAttachment.MediaType.AUDIO,
            mimeType = "audio/mpeg",
            data = data
        )
        assertEquals(LLMMediaAttachment.MediaType.AUDIO, attachment.type)
        assertEquals("audio/mpeg", attachment.mimeType)
        assertSame(data, attachment.data)
    }

    @Test
    fun testLLMMediaAttachmentCopy() {
        val attachment = LLMMediaAttachment(
            LLMMediaAttachment.MediaType.IMAGE, "image/png", byteArrayOf(1)
        )
        val copied = attachment.copy(type = LLMMediaAttachment.MediaType.VIDEO)
        assertEquals(LLMMediaAttachment.MediaType.VIDEO, copied.type)
        assertEquals("image/png", copied.mimeType)
    }

    @Test
    fun testMediaTypeEnumValues() {
        assertEquals(3, LLMMediaAttachment.MediaType.values().size)
        assertEquals("image", LLMMediaAttachment.MediaType.IMAGE.value)
        assertEquals("audio", LLMMediaAttachment.MediaType.AUDIO.value)
        assertEquals("video", LLMMediaAttachment.MediaType.VIDEO.value)
        assertEquals(LLMMediaAttachment.MediaType.IMAGE, LLMMediaAttachment.MediaType.valueOf("IMAGE"))
        assertEquals(LLMMediaAttachment.MediaType.AUDIO, LLMMediaAttachment.MediaType.valueOf("AUDIO"))
        assertEquals(LLMMediaAttachment.MediaType.VIDEO, LLMMediaAttachment.MediaType.valueOf("VIDEO"))
    }
}