package com.openminis.app.data.model

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import java.util.UUID

class ProviderConfigTest {

    // ── ProviderType ─────────────────────────────────────────────────────────

    @Test
    fun `ProviderType members have correct displayName`() {
        assertEquals("Anthropic", ProviderType.anthropic.displayName)
        assertEquals("Google Gemini", ProviderType.gemini.displayName)
        assertEquals("OpenAI", ProviderType.openAI.displayName)
        assertEquals("OpenRouter", ProviderType.openRouter.displayName)
        assertEquals("xAI (Grok)", ProviderType.xAI.displayName)
        assertEquals("Kimi Code", ProviderType.kimiCode.displayName)
    }

    @Test
    fun `ProviderType builtInModels returns correct list for each type`() {
        // 假设 LLMModel 的 companion object 属性已定义
        assertSame(LLMModel.allAnthropic, ProviderType.anthropic.builtInModels)
        assertSame(LLMModel.allGemini, ProviderType.gemini.builtInModels)
        assertSame(LLMModel.allOpenAI, ProviderType.openAI.builtInModels)
        assertSame(LLMModel.allOpenRouter, ProviderType.openRouter.builtInModels)
        assertSame(LLMModel.allXAI, ProviderType.xAI.builtInModels)
        assertSame(LLMModel.allKimi, ProviderType.kimiCode.builtInModels)
    }

    // ── ProviderCredential ────────────────────────────────────────────────────

    @Test
    fun `ProviderCredential enum values exist`() {
        assertNotNull(ProviderCredential.apiKey)
        assertNotNull(ProviderCredential.oauth)
    }

    // ── ThinkingLevel ────────────────────────────────────────────────────────

    @Test
    fun `ThinkingLevel isEnabled property`() {
        assertFalse(ThinkingLevel.OFF.isEnabled)
        assertTrue(ThinkingLevel.LOW.isEnabled)
        assertTrue(ThinkingLevel.MEDIUM.isEnabled)
        assertTrue(ThinkingLevel.HIGH.isEnabled)
        assertTrue(ThinkingLevel.XHIGH.isEnabled)
        assertTrue(ThinkingLevel.MAX.isEnabled)
        assertTrue(ThinkingLevel.ULTRA.isEnabled)
    }

    @Test
    fun `ThinkingLevel displayName`() {
        assertEquals("Off", ThinkingLevel.OFF.displayName)
        assertEquals("Low", ThinkingLevel.LOW.displayName)
        assertEquals("Medium", ThinkingLevel.MEDIUM.displayName)
        assertEquals("High", ThinkingLevel.HIGH.displayName)
        assertEquals("XHigh", ThinkingLevel.XHIGH.displayName)
        assertEquals("Max", ThinkingLevel.MAX.displayName)
        assertEquals("Ultra", ThinkingLevel.ULTRA.displayName)
    }

    @Test
    fun `ThinkingLevel rank corresponds to ordinal`() {
        assertEquals(0, ThinkingLevel.OFF.rank)
        assertEquals(1, ThinkingLevel.LOW.rank)
        assertEquals(2, ThinkingLevel.MEDIUM.rank)
        assertEquals(3, ThinkingLevel.HIGH.rank)
        assertEquals(4, ThinkingLevel.XHIGH.rank)
        assertEquals(5, ThinkingLevel.MAX.rank)
        assertEquals(6, ThinkingLevel.ULTRA.rank)
    }

    @Test
    fun `ThinkingLevel decoded returns correct value for valid string`() {
        assertEquals(ThinkingLevel.OFF, ThinkingLevel.decoded("OFF"))
        assertEquals(ThinkingLevel.HIGH, ThinkingLevel.decoded("HIGH"))
        assertEquals(ThinkingLevel.XHIGH, ThinkingLevel.decoded("XHIGH"))
    }

    @Test
    fun `ThinkingLevel decoded returns XHIGH for invalid string`() {
        assertEquals(ThinkingLevel.XHIGH, ThinkingLevel.decoded("INVALID"))
        assertEquals(ThinkingLevel.XHIGH, ThinkingLevel.decoded(""))
        assertEquals(ThinkingLevel.XHIGH, ThinkingLevel.decoded("123"))
    }

    // ── RoutingStrategy ──────────────────────────────────────────────────────

    @Test
    fun `RoutingStrategy enum values exist`() {
        assertNotNull(RoutingStrategy.fallback)
        assertNotNull(RoutingStrategy.loadBalance)
    }

    // ── ImageEndpointMode ────────────────────────────────────────────────────

    @Test
    fun `ImageEndpointMode enum values exist`() {
        assertNotNull(ImageEndpointMode.auto)
        assertNotNull(ImageEndpointMode.imagesGenerations)
        assertNotNull(ImageEndpointMode.chatCompletions)
    }

    // ── FallbackStrategy ─────────────────────────────────────────────────────

    @Test
    fun `FallbackStrategy enum values exist`() {
        assertNotNull(FallbackStrategy.default)
        assertNotNull(FallbackStrategy.always)
    }

    // ── RecoveryStrategy ─────────────────────────────────────────────────────

    @Test
    fun `RecoveryStrategy enum values exist`() {
        assertNotNull(RecoveryStrategy.continueLast)
        assertNotNull(RecoveryStrategy.honorFirst)
        assertNotNull(RecoveryStrategy.cooldown)
    }

    // ── ModelGroup ───────────────────────────────────────────────────────────

    @Test
    fun `ModelGroup has default values`() {
        val group = ModelGroup(name = "TestGroup")
        assertNotNull(group.id)
        assertEquals("TestGroup", group.name)
        assertTrue(group.memberEntryIds.isEmpty())
        assertEquals(RoutingStrategy.fallback, group.strategy)
        assertEquals(FallbackStrategy.default, group.fallbackStrategy)
        assertNull(group.defaultThinkingLevel)
        assertNull(group.contextLimitTokens)
        assertNull(group.lastContextLimitTokens)
        assertEquals(RecoveryStrategy.continueLast, group.recovery)
    }

    @Test
    fun `ModelGroup properties can be modified`() {
        val group = ModelGroup(name = "Initial")
        group.name = "Modified"
        group.memberEntryIds.add("entry1")
        group.strategy = RoutingStrategy.loadBalance
        group.fallbackStrategy = FallbackStrategy.always
        group.defaultThinkingLevel = ThinkingLevel.HIGH
        group.contextLimitTokens = 4096
        group.lastContextLimitTokens = 2048
        group.recovery = RecoveryStrategy.cooldown

        assertEquals("Modified", group.name)
        assertTrue(group.memberEntryIds.contains("entry1"))
        assertEquals(RoutingStrategy.loadBalance, group.strategy)
        assertEquals(FallbackStrategy.always, group.fallbackStrategy)
        assertEquals(ThinkingLevel.HIGH, group.defaultThinkingLevel)
        assertEquals(4096, group.contextLimitTokens)
        assertEquals(2048, group.lastContextLimitTokens)
        assertEquals(RecoveryStrategy.cooldown, group.recovery)
    }

    @Test
    fun `ModelGroup generates unique id by default`() {
        val group1 = ModelGroup(name = "A")
        val group2 = ModelGroup(name = "B")
        assertNotEquals(group1.id, group2.id)
    }

    // ── ProviderInstance ─────────────────────────────────────────────────────

    @Test
    fun `ProviderInstance has default values`() {
        val instance = ProviderInstance(
            id = "inst1",
            label = "My Provider",
            providerType = ProviderType.openAI,
            credentialType = ProviderCredential.apiKey
        )
        assertTrue(instance.isEnabled)
        assertTrue(instance.createdAt > 0)
        assertNull(instance.customBaseURL)
        assertTrue(instance.appendV1Suffix)
        assertNull(instance.customUserAgent)
        assertFalse(instance.useResponsesAPI)
        assertEquals(ImageEndpointMode.auto, instance.imageEndpointMode)
        assertNull(instance.imageEndpointResolved)
        assertFalse(instance.azureMode)
        assertFalse(instance.pinned)
    }

    @Test
    fun `ProviderInstance effectiveBaseURL returns null when customBaseURL is null`() {
        val instance = ProviderInstance(
            id = "inst1",
            label = "Test",
            providerType = ProviderType.openAI,
            credentialType = ProviderCredential.apiKey
        )
        assertNull(instance.effectiveBaseURL)
    }

    @Test
    fun `ProviderInstance effectiveBaseURL appends v1 suffix when enabled and does not end with v1`() {
        val instance = ProviderInstance(
            id = "inst1",
            label = "Test",
            providerType = ProviderType.openAI,
            credentialType = ProviderCredential.apiKey,
            customBaseURL = "https://api.example.com"
        )
        assertEquals("https://api.example.com/v1", instance.effectiveBaseURL)
    }

    @Test
    fun `ProviderInstance effectiveBaseURL does not append v1 when already ends with v1`() {
        val instance = ProviderInstance(
            id = "inst1",
            label = "Test",
            providerType = ProviderType.openAI,
            credentialType = ProviderCredential.apiKey,
            customBaseURL = "https://api.example.com/v1"
        )
        assertEquals("https://api.example.com/v1", instance.effectiveBaseURL)
    }

    @Test
    fun `ProviderInstance effectiveBaseURL does not append v1 when appendV1Suffix is false`() {
        val instance = ProviderInstance(
            id = "inst1",
            label = "Test",
            providerType = ProviderType.openAI,
            credentialType = ProviderCredential.apiKey,
            customBaseURL = "https://api.example.com",
            appendV1Suffix = false
        )
        assertEquals("https://api.example.com", instance.effectiveBaseURL)
    }

    @Test
    fun `ProviderInstance supportsImageEndpointSetting is true for OpenAI, OpenRouter, xAI`() {
        assertTrue(ProviderInstance("id", "label", ProviderType.openAI, ProviderCredential.apiKey).supportsImageEndpointSetting)
        assertTrue(ProviderInstance("id", "label", ProviderType.openRouter, ProviderCredential.apiKey).supportsImageEndpointSetting)
        assertTrue(ProviderInstance("id", "label", ProviderType.xAI, ProviderCredential.apiKey).supportsImageEndpointSetting)
        assertFalse(ProviderInstance("id", "label", ProviderType.anthropic, ProviderCredential.apiKey).supportsImageEndpointSetting)
        assertFalse(ProviderInstance("id", "label", ProviderType.gemini, ProviderCredential.apiKey).supportsImageEndpointSetting)
        assertFalse(ProviderInstance("id", "label", ProviderType.kimiCode, ProviderCredential.apiKey).supportsImageEndpointSetting)
    }

    @Test
    fun `ProviderInstance supportsAzureMode is true only for OpenAI apiKey`() {
        assertTrue(ProviderInstance("id", "label", ProviderType.openAI, ProviderCredential.apiKey).supportsAzureMode)
        assertFalse(ProviderInstance("id", "label", ProviderType.openAI, ProviderCredential.oauth).supportsAzureMode)
        assertFalse(ProviderInstance("id", "label", ProviderType.gemini, ProviderCredential.apiKey).supportsAzureMode)
        assertFalse(ProviderInstance("id", "label", ProviderType.anthropic, ProviderCredential.apiKey).supportsAzureMode)
    }

    // ── ModelOverrides ───────────────────────────────────────────────────────

    @Test
    fun `ModelOverrides isEmpty true when all fields are null`() {
        val overrides = ModelOverrides()
        assertTrue(overrides.isEmpty)
    }

    @Test
    fun `ModelOverrides isEmpty false when any field is non-null`() {
        assertFalse(ModelOverrides(displayName = "Custom").isEmpty)
        assertFalse(ModelOverrides(maxOutputTokens = 100).isEmpty)
        assertFalse(ModelOverrides(contextWindow = 2000).isEmpty)
        assertFalse(ModelOverrides(supportsReasoning = true).isEmpty)
        assertFalse(ModelOverrides(inputModalities = listOf("text")).isEmpty)
        assertFalse(ModelOverrides(outputModalities = listOf("text")).isEmpty)
        assertFalse(ModelOverrides(maxThinkingLevel = ThinkingLevel.HIGH).isEmpty)
    }

    // ── ModelEntry ───────────────────────────────────────────────────────────

    @Test
    fun `ModelEntry has default values`() {
        val baseModel = createTestLLMModel()
        val entry = ModelEntry(
            providerInstanceId = "inst1",
            baseModel = baseModel
        )
        assertEquals("inst1", entry.providerInstanceId)
        assertSame(baseModel, entry.baseModel)
        assertTrue(entry.overrides.isEmpty)
        assertFalse(entry.isCustom)
        assertFalse(entry.isHidden)
        assertNotNull(entry.uuid)
        assertNull(entry.userModifiedAt)
        assertEquals(entry.uuid, entry.id)
    }

    @Test
    fun `ModelEntry id returns uuid`() {
        val entry = ModelEntry(
            providerInstanceId = "inst1",
            baseModel = createTestLLMModel()
        )
        assertEquals(entry.uuid, entry.id)
    }

    @Test
    fun `ModelEntry model returns baseModel when overrides isEmpty`() {
        val baseModel = createTestLLMModel()
        val entry = ModelEntry(
            providerInstanceId = "inst1",
            baseModel = baseModel
        )
        assertSame(baseModel, entry.model)
    }

    @Test
    fun `ModelEntry model applies overrides correctly`() {
        val baseModel = createTestLLMModel(
            displayName = "Original",
            maxOutputTokens = 1000,
            contextWindow = 8000,
            supportsReasoning = false,
            inputModalities = listOf("text", "image"),
            outputModalities = listOf("text")
        )
        val overrides = ModelOverrides(
            displayName = "Overridden",
            maxOutputTokens = 2000,
            contextWindow = 16000,
            supportsReasoning = true,
            inputModalities = listOf("text"),
            outputModalities = listOf("text", "audio"),
            maxThinkingLevel = ThinkingLevel.HIGH
        )
        val entry = ModelEntry(
            providerInstanceId = "inst1",
            baseModel = baseModel,
            overrides = overrides
        )
        val model = entry.model
        assertEquals("Overridden", model.displayName)
        assertEquals(2000, model.maxOutputTokens)
        assertEquals(16000, model.contextWindow)
        assertEquals(true, model.supportsReasoning)
        assertEquals(listOf("text"), model.inputModalities)
        assertEquals(listOf("text", "audio"), model.outputModalities)
        // maxThinkingLevel 不在 model 属性中，而是在 overrides 上，model 不包含该字段
        // 注意：LLMModel 可能没有 maxThinkingLevel 字段，因此不测试
    }

    @Test
    fun `ModelEntry isUserModified true when isCustom true`() {
        val entry = ModelEntry(
            providerInstanceId = "inst1",
            baseModel = createTestLLMModel(),
            isCustom = true
        )
        assertTrue(entry.isUserModified)
    }

    @Test
    fun `ModelEntry isUserModified true when isHidden true`() {
        val entry = ModelEntry(
            providerInstanceId = "inst1",
            baseModel = createTestLLMModel(),
            isHidden = true
        )
        assertTrue(entry.isUserModified)
    }

    @Test
    fun `ModelEntry isUserModified true when overrides not empty`() {
        val entry = ModelEntry(
            providerInstanceId = "inst1",
            baseModel = createTestLLMModel(),
            overrides = ModelOverrides(displayName = "Custom")
        )
        assertTrue(entry.isUserModified)
    }

    @Test
    fun `ModelEntry isUserModified false when none of custom, hidden, overrides`() {
        val entry = ModelEntry(
            providerInstanceId = "inst1",
            baseModel = createTestLLMModel()
        )
        assertFalse(entry.isUserModified)
    }

    // ── ProviderConfig ───────────────────────────────────────────────────────

    @Test
    fun `ProviderConfig has default values`() {
        val config = ProviderConfig()
        assertTrue(config.instances.isEmpty())
        assertTrue(config.modelEntries.isEmpty())
        assertTrue(config.modelGroups.isEmpty())
        assertNull(config.defaultPrimaryGroupId)
        assertNull(config.defaultSubGroupId)
        assertNull