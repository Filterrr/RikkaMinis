package com.openminis.app.sandbox.offload

import android.content.Context
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.ProviderFactory
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import com.openminis.app.sandbox.PRootKernel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mock
import org.mockito.MockedStatic
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File

@ExtendWith(MockitoExtension::class)
class ModelUseOffloadHandlerTest {

    @Mock
    private lateinit var context: Context

    @Mock
    private lateinit var providerRepository: ProviderRepository

    private lateinit var handler: ModelUseOffloadHandler

    private lateinit var prRootKernelStaticMock: MockedStatic<PRootKernel>
    private lateinit var providerFactoryStaticMock: MockedStatic<ProviderFactory>

    @BeforeEach
    fun setUp() {
        handler = ModelUseOffloadHandler(context, providerRepository)
        prRootKernelStaticMock = mockStatic(PRootKernel::class.java)
        providerFactoryStaticMock = mockStatic(ProviderFactory::class.java)
    }

    @AfterEach
    fun tearDown() {
        prRootKernelStaticMock.close()
        providerFactoryStaticMock.close()
    }

    // ========== HELP / UNKNOWN SUBCOMMAND ==========

    @Test
    fun `handle with help flag returns help text`() {
        val request = makeRequest(listOf("minis-model-use", "-h"))
        val result = handler.handle(request)
        assert(result.exitCode == 0)
        assert(result.output.contains("Usage:"))
    }

    @Test
    fun `handle with no positional args returns code 2 and help`() {
        val request = makeRequest(listOf("minis-model-use"))
        val result = handler.handle(request)
        assert(result.exitCode == 2)
        assert(result.output.contains("Usage:"))
    }

    @Test
    fun `handle with unknown subcommand returns error`() {
        val request = makeRequest(listOf("minis-model-use", "unknown"))
        val result = handler.handle(request)
        assert(result.exitCode == 2)
        assert(result.output.contains("unknown subcommand"))
    }

    // ========== LIST ==========

    @Test
    fun `handle list without filters returns all models`() {
        val entries = listOf(makeModelEntry("gpt-4o", "openai"), makeModelEntry("claude", "anthropic"))
        `when`(providerRepository.resolvedAgentLoopEntries()).thenReturn(entries)

        val request = makeRequest(listOf("minis-model-use", "list"))
        val result = handler.handle(request)
        assert(result.exitCode == 0)
        assert(result.output.contains("count"))
        assert(result.output.contains("gpt-4o"))
        assert(result.output.contains("claude"))
    }

    @Test
    fun `handle list with provider filter`() {
        val entries = listOf(makeModelEntry("gpt-4o", "openai"), makeModelEntry("claude", "anthropic"))
        `when`(providerRepository.resolvedAgentLoopEntries()).thenReturn(entries)
        // mock provider instance label
        `when`(providerRepository.instance(anyString())).thenReturn(
            makeProviderInstance("openai"),
            makeProviderInstance("anthropic")
        )

        val request = makeRequest(listOf("minis-model-use", "list", "--provider", "openai"))
        val result = handler.handle(request)
        assert(result.exitCode == 0)
        assert(result.output.contains("gpt-4o"))
        assert(!result.output.contains("claude"))
    }

    @Test
    fun `handle list with modality filter`() {
        val entryWithImage = makeModelEntry("imagen", "google", inputModalities = listOf("text", "image"), outputModalities = listOf("image"))
        val entryTextOnly = makeModelEntry("gpt-4o", "openai", inputModalities = listOf("text"), outputModalities = listOf("text"))
        `when`(providerRepository.resolvedAgentLoopEntries()).thenReturn(listOf(entryWithImage, entryTextOnly))

        val request = makeRequest(listOf("minis-model-use", "list", "--modality", "image_input"))
        val result = handler.handle(request)
        assert(result.exitCode == 0)
        assert(result.output.contains("imagen"))
        assert(!result.output.contains("gpt-4o"))
    }

    @Test
    fun `handle list with no models shows hint`() {
        `when`(providerRepository.resolvedAgentLoopEntries()).thenReturn(emptyList())
        val request = makeRequest(listOf("minis-model-use", "list"))
        val result = handler.handle(request)
        assert(result.exitCode == 0)
        assert(result.output.contains("No models available"))
    }

    // ========== SEARCH ==========

    @Test
    fun `handle search with no query returns error`() {
        val request = makeRequest(listOf("minis-model-use", "search"))
        val result = handler.handle(request)
        assert(result.exitCode == 2)
        assert(result.output.contains("no query"))
    }

    @Test
    fun `handle search finds matching models`() {
        val entries = listOf(
            makeModelEntry("gpt-4o", "openai", displayName = "GPT-4o"),
            makeModelEntry("claude-3", "anthropic", displayName = "Claude 3")
        )
        `when`(providerRepository.resolvedAgentLoopEntries()).thenReturn(entries)

        val request = makeRequest(listOf("minis-model-use", "search", "gpt"))
        val result = handler.handle(request)
        assert(result.exitCode == 0)
        assert(result.output.contains("gpt-4o"))
        assert(!result.output.contains("claude-3"))
    }

    @Test
    fun `handle search with modality filter`() {
        val entryWithImage = makeModelEntry("imagen", "google", inputModalities = listOf("text", "image"))
        val entryText = makeModelEntry("gpt-4o", "openai", inputModalities = listOf("text"))
        `when`(providerRepository.resolvedAgentLoopEntries()).thenReturn(listOf(entryWithImage, entryText))

        val request = makeRequest(listOf("minis-model-use", "search", "imagen", "--modality", "image_input"))
        val result = handler.handle(request)
        assert(result.exitCode == 0)
        assert(result.output.contains("imagen"))
        assert(!result.output.contains("gpt-4o"))
    }

    // ========== RUN ==========

    @Test
    fun `handle run without --model returns error`() {
        val request = makeRequest(listOf("minis-model-use", "run"))
        val result = handler.handle(request)
        assert(result.exitCode == 2)
        assert(result.output.contains("--model is required"))
    }

    @Test
    fun `handle run with model not found returns error`() {
        `when`(providerRepository.resolvedAgentLoopEntries()).thenReturn(emptyList())
        val request = makeRequest(listOf("minis-model-use", "run", "--model", "nonexistent"))
        val result = handler.handle(request)
        assert(result.exitCode == 2)
        assert(result.output.contains("model_not_found"))
    }

    @Test
    fun `handle run with relative output path returns error`() {
        val entry = makeModelEntry("gpt-4o", "openai")
        `when`(providerRepository.resolvedAgentLoopEntries()).thenReturn(listOf(entry))
        `when`(providerRepository.instance(anyString())).thenReturn(makeProviderInstance("openai"))

        val request = makeRequest(listOf("minis-model-use", "run", "--model", "gpt-4o", "--output", "relative.txt"))
        val result = handler.handle(request)
        assert(result.exitCode == 2)
        assert(result.output.contains("invalid_output_path"))
    }

    @Test
    fun `handle run with output extension mismatch for image model returns error`() {
        val entry = makeModelEntry("dall-e", "openai", outputModalities = listOf("image"))
        `when`(providerRepository.resolvedAgentLoopEntries()).thenReturn(listOf(entry))
        `when`(providerRepository.instance(anyString())).thenReturn(makeProviderInstance("openai", ProviderType.openAI))

        val request = makeRequest(listOf("minis-model-use", "run", "--model", "dall-e", "--output", "/tmp/out.txt"))
        val result = handler.handle(request)
        assert(result.exitCode == 2)
        assert(result.output.contains("modality_not_supported"))
    }

    @Test
    fun `handle run with audio input on non-audio model returns error`() {
        val entry = makeModelEntry("gpt-4o", "openai", inputModalities = listOf("text"), outputModalities = listOf("text"))
        `when`(providerRepository.resolvedAgentLoopEntries()).thenReturn(listOf(entry))
        `when`(providerRepository.instance(anyString())).thenReturn(makeProviderInstance("openai", ProviderType.openAI))

        val request = makeRequest(listOf("minis-model-use", "run", "--model", "gpt-4o", "--input", "/tmp/input.json"))
        // mock input file content with audio
        val inputJson = """{"messages":[{"role":"user","content":[{"type":"input_audio","input_audio":{"data":"dGVzdA==","format":"wav"}}]}]}"""
        prRootKernelStaticMock.`when`<File?> { PRootKernel.resolveHostPath("/tmp/input.json") }.thenReturn(File("/tmp/input.json"))
        // file read: we need to mock readText? PRootKernel.resolveHostPath returns a File, but readLinuxPath calls hostFile.readText().
        // We'll mock the file's existence and content: we can create a real temp file or use a mock. Simpler: create a real file.
        val tempFile = File.createTempFile("test", ".json")
        tempFile.writeText(inputJson)
        prRootKernelStaticMock.`when`<File?> { PRootKernel.resolveHostPath("/tmp/input.json") }.thenReturn(tempFile)

        // We need to handle the case where the model does not have audio_input, so it should fail with modality_not_supported
        // But the code checks after parsing messages: if nonSystem.any { it.audios.isNotEmpty() } && "audio" !in entry.model.inputModalities -> error
        // So it should return error
        val result = handler.handle(request)
        assert(result.exitCode == 2)
        assert(result.output.contains("modality_not_supported"))
        tempFile.delete()
    }

    @Test
    fun `handle run with missing API key returns error`() {
        val entry = makeModelEntry("gpt-4o", "openai")
        `when`(providerRepository.resolvedAgentLoopEntries()).thenReturn(listOf(entry))
        `when`(providerRepository.instance(anyString())).thenReturn(makeProviderInstance("openai", ProviderType.openAI))
        `when`(providerRepository.loadApiKey(anyString())).thenReturn(null)

        val request = makeRequest(listOf("minis-model-use", "run", "--model", "gpt-4o", "--input", "/tmp/input.json"))
        prRootKernelStaticMock.`when`<File?> { PRootKernel.resolveHostPath("/tmp/input.json") }.thenReturn(File("/tmp/input.json"))
        val tempFile = File.createTempFile("test", ".json")
        tempFile.writeText("""{"messages":[{"role":"user","content":"hello"}]}""")
        prRootKernelStaticMock.`when`<File?> { PRootKernel.resolveHostPath("/tmp/input.json") }.thenReturn(tempFile)

        val result = handler.handle(request)
        assert(result.exitCode == 2)
        assert(result.output.contains("missing_api_key"))
        tempFile.delete()
    }

    @Test
    fun `handle run with audio input on non-OpenAI provider returns error`() {
        val entry = makeModelEntry("some-model", "anthropic", inputModalities = listOf("audio"))
        `when`(providerRepository.resolvedAgentLoopEntries()).thenReturn(listOf(entry))
        `when`(providerRepository.instance(anyString())).thenReturn(makeProviderInstance("anthropic", ProviderType.anthropic))
        `when`(providerRepository.loadApiKey(anyString())).thenReturn("key")
        val mockProvider = mock<LLMProvider>()
        providerFactoryStaticMock.`when`<LLMProvider> { ProviderFactory.create(any(), anyString(), any(), any()) }.thenReturn(mockProvider)

        val request = makeRequest(listOf("minis-model-use", "run", "--model", "some-model", "--input", "/tmp/input.json"))
        prRootKernelStaticMock.`when`<File?> { PRootKernel.resolveHostPath("/tmp/input.json") }.thenReturn(File("/tmp/input.json"))
        val tempFile = File.createTempFile("test", ".json")
        tempFile.writeText("""{"messages":[{"role":"user","content":[{"type":"input_audio","input_audio":{"data":"dGVzdA==","format":"wav"}}]}]}""")
        prRootKernelStaticMock.`when`<File?> { PRootKernel.resolveHostPath("/tmp/input.json") }.thenReturn(tempFile)

        val result = handler.handle(request)
        assert(result.exitCode == 2)
        assert(result.output.contains("audio_input_unsupported_provider"))
        tempFile.delete()
    }

    @Test
    fun `handle run successful with text output`() {
        val entry = makeModelEntry("gpt-4o", "openai")
        `when`(providerRepository.resolvedAgentLoopEntries()).thenReturn(listOf(entry))
        `when`(providerRepository.instance(anyString())).thenReturn(makeProviderInstance("openai", ProviderType.openAI))
        `when`(providerRepository.loadApiKey(anyString())).thenReturn("key")
        val mockProvider = mock<LLMProvider>()
        providerFactoryStaticMock.`when`<LLMProvider> { ProviderFactory.create(any(), anyString(), any(), any()) }.thenReturn(mockProvider)

        // mock sendMessage
        val response = LLMMessage.LLMResponse(text = "Hello world", mediaAttachments = emptyList(), usage = LLMMessage.Usage(10, 20))
        // We need to mock runBlocking? Actually we can use runBlocking in test, but we need to mock the suspend function.
        // Use Mockito's suspend function mocking: we can use mockito-kotlin's coEvery? But we are using standard Mockito.
        // Instead, we can use a real implementation? We'll use a mock that returns a result via runBlocking.
        // Since we cannot easily mock suspend functions with mockito, we can create a fake provider that returns the response.
        // We'll create a mock of LLMProvider that overrides sendMessage using a lambda.
        // We'll use `mock` and then stub the method using `whenever` with answer.
        // But sendMessage is suspend, so we need to use `org.mockito.kotlin.coEvery` from mockito-kotlin.
        // We'll use `org.mockito.kotlin.coEvery` which is available in mockito-kotlin.
        // Add import: org.mockito.kotlin.coEvery
        // We'll use that.
        val providerMock = mock<LLMProvider> {
            onBlocking { sendMessage(any(), any(), any(), any(), any()) }.thenReturn(response)
        }
        // But we need to inject the mock into the handler. The handler creates a provider via ProviderFactory, so we need to mock that.
        providerFactoryStaticMock.`when`<LLMProvider> { ProviderFactory.create(any(), anyString(), any(), any()) }.thenReturn(providerMock)

        val request = makeRequest(listOf("minis-model-use", "run", "--model", "gpt-4o", "--input", "/tmp/input.json"))
        prRootKernelStaticMock.`when`<File?> { PRootKernel.resolveHostPath("/tmp/input.json") }.thenReturn(File("/tmp/input.json"))
        val tempFile = File.createTempFile("test", ".json")
        tempFile.writeText("""{"messages":[{"role":"user","content":"hello"}]}""")
        prRootKernelStaticMock.`when`<File?> { PRootKernel.resolveHostPath("/tmp/input.json") }.thenReturn(tempFile)

        val result = handler.handle(request)
        assert(result.exitCode == 0)
        assert(result.output.contains("Hello world"))
        assert(result.output.contains("input_tokens"))
        tempFile.delete()
    }

    @Test
    fun `handle run with output path saves file`() {
        // Similar to above, but with --output, and verify that file is written.
        val entry = makeModelEntry("gpt-4o", "openai")
        `when`(providerRepository.resolvedAgentLoopEntries()).thenReturn(listOf(entry))
        `when`(providerRepository.instance(anyString())).thenReturn(makeProviderInstance("openai", ProviderType.openAI))
        `when`(providerRepository.loadApiKey(anyString())).thenReturn("key")

        val providerMock = mock<LLMProvider> {
            onBlocking { sendMessage(any(), any(), any(), any(), any()) }.thenReturn(
                LLMMessage.LLMResponse(text = "result", mediaAttachments = emptyList())
            )
        }
        providerFactoryStaticMock.`when`<LLMProvider> { ProviderFactory.create(any(), anyString(), any(), any()) }.thenReturn(providerMock)

        // Mock PRootKernel.resolveHostPath for output path
        val outputFile = File.createTempFile("output", ".txt")
        outputFile.delete()
        val outputPath = "/var/minis/workspace/out.txt"
        prRootKernelStaticMock.`when`<File?> { PRootKernel.resolveHostPath(outputPath) }.thenReturn(outputFile)

        val request = makeRequest(listOf("minis-model-use", "run", "--model", "gpt-4o", "--input", "/tmp/input.json", "--output", outputPath))
        prRootKernelStaticMock.`when`<File?> { PRootKernel.resolveHostPath("/tmp/input.json") }.thenReturn(File("/tmp/input.json"))
        val tempInput = File.createTempFile("input", ".json")
        tempInput.writeText("""{"messages":[{"role":"user","content":"hello"}]}""")
        prRootKernelStaticMock.`when`<File?> { PRootKernel.resolveHostPath("/tmp/input.json") }.thenReturn(tempInput)

        val result = handler.handle(request)
        assert(result.exitCode == 0)
        assert(result.output.contains("output_file"))
        assert(outputFile.exists())
        assert(outputFile.readText() == "result")
        outputFile.delete()
        tempInput.delete()
    }

    // ========== HELPER FUNCTIONS ==========

    private fun makeRequest(argv: List<String>): NativeOffloadRequest {
        return NativeOffloadRequest(
            argv = argv,
            sessionId = "test-session",
            env = emptyMap(),
            stdin = null
        )
    }

    private fun makeModelEntry(
        id: String,
        provider: String,
        displayName: String = id,
        providerInstanceId: String = "inst-$provider",
        inputModalities: List<String> = listOf("text"),
        outputModalities: List<String> = listOf("text")
    ): ModelEntry {
        return ModelEntry(
            id = id,
            model = com.openminis.app.data.model.LLMModel(
                id = id,
                displayName = displayName,
                provider = provider,
                inputModalities = inputModalities,
                outputModalities = outputModalities
            ),
            providerInstanceId = providerInstanceId,
            isEnabled = true
        )
    }

    private fun makeProviderInstance(
        label: String,
        providerType: ProviderType = ProviderType.openAI,
        id: String = "inst-$label"
    ): ProviderInstance {
        return ProviderInstance(
            id = id,
            label = label,
            providerType = providerType,
            credentialType = com.openminis.app.data.model.ProviderCredential.apiKey,
            imageEndpointMode = com.openminis.app.data.model.ImageEndpointMode.auto,
            imageEndpointResolved = null,
            baseUrl = "https://api.example.com",
            apiKey = "key"
        )
    }
}