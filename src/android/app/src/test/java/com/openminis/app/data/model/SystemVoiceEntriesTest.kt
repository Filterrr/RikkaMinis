package com.openminis.app.data.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SystemVoiceEntriesTest {

    @Test
    fun `asrOnline has correct values`() {
        val entry = SystemVoiceEntries.asrOnline
        assertEquals(SystemVoiceIds.BUILTIN_PROVIDER_ID, entry.providerInstanceId)
        assertEquals(SystemVoiceIds.SYSTEM_ASR_ONLINE, entry.baseModel.id)
        assertEquals("System Recognition (Online)", entry.baseModel.displayName)
        assertEquals("system", entry.baseModel.provider)
        assertEquals(listOf("audio"), entry.baseModel.inputModalities)
        assertTrue(entry.isHidden)
        assertEquals(
            "${SystemVoiceIds.BUILTIN_PROVIDER_ID}/${SystemVoiceIds.SYSTEM_ASR_ONLINE}",
            entry.uuid
        )
    }

    @Test
    fun `asrOffline has correct values`() {
        val entry = SystemVoiceEntries.asrOffline
        assertEquals(SystemVoiceIds.BUILTIN_PROVIDER_ID, entry.providerInstanceId)
        assertEquals(SystemVoiceIds.SYSTEM_ASR_OFFLINE, entry.baseModel.id)
        assertEquals("System Recognition (Offline)", entry.baseModel.displayName)
        assertEquals("system", entry.baseModel.provider)
        assertEquals(listOf("audio"), entry.baseModel.inputModalities)
        assertTrue(entry.isHidden)
        assertEquals(
            "${SystemVoiceIds.BUILTIN_PROVIDER_ID}/${SystemVoiceIds.SYSTEM_ASR_OFFLINE}",
            entry.uuid
        )
    }

    @Test
    fun `tts has correct values`() {
        val entry = SystemVoiceEntries.tts
        assertEquals(SystemVoiceIds.BUILTIN_PROVIDER_ID, entry.providerInstanceId)
        assertEquals(SystemVoiceIds.SYSTEM_TTS, entry.baseModel.id)
        assertEquals("System Voice (Auto)", entry.baseModel.displayName)
        assertEquals("system", entry.baseModel.provider)
        assertEquals(listOf("audio"), entry.baseModel.outputModalities)
        assertTrue(entry.isHidden)
        assertEquals(
            "${SystemVoiceIds.BUILTIN_PROVIDER_ID}/${SystemVoiceIds.SYSTEM_TTS}",
            entry.uuid
        )
    }

    @Test
    fun `all contains exactly asrOnline, asrOffline, tts in order`() {
        val all = SystemVoiceEntries.all
        assertEquals(3, all.size)
        assertEquals(SystemVoiceEntries.asrOnline, all[0])
        assertEquals(SystemVoiceEntries.asrOffline, all[1])
        assertEquals(SystemVoiceEntries.tts, all[2])
    }

    @Test
    fun `resolve returns asrOnline when id matches`() {
        val resolved = SystemVoiceEntries.resolve(SystemVoiceIds.SYSTEM_ASR_ONLINE)
        assertNotNull(resolved)
        assertEquals(SystemVoiceEntries.asrOnline, resolved)
    }

    @Test
    fun `resolve returns asrOffline when id matches`() {
        val resolved = SystemVoiceEntries.resolve(SystemVoiceIds.SYSTEM_ASR_OFFLINE)
        assertNotNull(resolved)
        assertEquals(SystemVoiceEntries.asrOffline, resolved)
    }

    @Test
    fun `resolve returns tts when id matches`() {
        val resolved = SystemVoiceEntries.resolve(SystemVoiceIds.SYSTEM_TTS)
        assertNotNull(resolved)
        assertEquals(SystemVoiceEntries.tts, resolved)
    }

    @Test
    fun `resolve returns null when id does not match`() {
        assertNull(SystemVoiceEntries.resolve("non-existent-id"))
    }

    @Test
    fun `isSystemEntryId returns true when id starts with builtin provider id`() {
        assertTrue(SystemVoiceEntries.isSystemEntryId(SystemVoiceIds.BUILTIN_PROVIDER_ID))
        assertTrue(SystemVoiceEntries.isSystemEntryId("${SystemVoiceIds.BUILTIN_PROVIDER_ID}/something"))
    }

    @Test
    fun `isSystemEntryId returns false when id does not start with builtin provider id`() {
        assertFalse(SystemVoiceEntries.isSystemEntryId("other-provider"))
        assertFalse(SystemVoiceEntries.isSystemEntryId(""))
    }

    @Test
    fun `syntheticInstance uses default label when none provided`() {
        val instance = SystemVoiceEntries.syntheticInstance()
        assertEquals(SystemVoiceIds.BUILTIN_PROVIDER_ID, instance.id)
        assertEquals("System", instance.label)
        assertEquals(ProviderType.openAI, instance.providerType)
        assertEquals(ProviderCredential.apiKey, instance.credentialType)
        assertTrue(instance.isEnabled)
    }

    @Test
    fun `syntheticInstance uses provided label`() {
        val instance = SystemVoiceEntries.syntheticInstance(label = "Custom Label")
        assertEquals(SystemVoiceIds.BUILTIN_PROVIDER_ID, instance.id)
        assertEquals("Custom Label", instance.label)
        assertEquals(ProviderType.openAI, instance.providerType)
        assertEquals(ProviderCredential.apiKey, instance.credentialType)
        assertTrue(instance.isEnabled)
    }
}