package com.openminis.app.sandbox.offload

import android.content.Context
import android.content.Intent
import android.util.Log
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ProviderInstance
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Main-process dispatcher for [ModelExecutionService].
 *
 * Serializes a [minis-model-use run]'s provider call (instance / model /
 * messages / params / passthrough input) into a JSON request file, starts
 * [ModelExecutionService] in the `:modelservice` process, polls for the
 * result file, and returns the result JSON. The service process is killed
 * (stopSelf) after writing the result, returning all its native heap
 * (DirectByteBuffer allocations from the LLM HTTP call) to the OS — the
 * leak containment that in-process GC cannot achieve.
 *
 * Returns `null` when the service cannot be dispatched (no context yet,
 * request dir unwritable, result not ready before timeout) so the caller
 * can fall back to the in-process path — the remote execution is an
 * optimization for leak containment, never a hard dependency.
 */
object ModelExecutionDispatcher {

    private const val TAG = "ModelExecDispatcher"
    private const val REQUEST_TIMEOUT_MS = 3 * 60_000L  // matches agent tool timeout headroom
    private const val POLL_INTERVAL_MS = 200L

    /** Default base directory for request/result staging. */
    private const val STAGING_ROOT = "model-exec"

    /**
     * Build the serialized request JSON for a model run. Pure function —
     * JVM-testable. The API key is deliberately NOT included: the service
     * reads it from EncryptedSharedPreferences directly (same uid, same
     * encrypted prefs file) so the plaintext never touches disk.
     */
    fun buildRequestJson(
        instance: ProviderInstance,
        model: LLMModel,
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        inputJson: String,
        outputExt: String?,
    ): String {
        return JSONObject().apply {
            put("instance_id", instance.id)
            put("instance_label", instance.label)
            put("provider_type", instance.providerType.name)
            put("credential_type", instance.credentialType.name)
            instance.customBaseURL?.let { put("base_url", it) }
            put("append_v1", instance.appendV1Suffix)
            instance.customUserAgent?.let { put("user_agent", it) }
            put("use_responses_api", instance.useResponsesAPI)
            put("image_endpoint_mode", instance.imageEndpointMode.name)
            instance.imageEndpointResolved?.let { put("image_endpoint_resolved", it.name) }
            put("azure_mode", instance.azureMode)

            put("model_id", model.id)
            put("model_display_name", model.displayName)
            put("model_provider", model.provider)
            model.inputModalities.orEmpty().let { if (it.isNotEmpty()) put("input_modalities", JSONArray(it)) }
            model.outputModalities.orEmpty().let { if (it.isNotEmpty()) put("output_modalities", JSONArray(it)) }
            model.contextWindow?.let { put("context_window", it) }

            put("messages", JSONArray().apply {
                messages.forEach { m ->
                    put(JSONObject().apply {
                        put("role", m.role.value)
                        put("content", m.content)
                        if (m.audioParts.isNotEmpty()) {
                            put("audio_parts", JSONArray().apply {
                                m.audioParts.forEach { a ->
                                    put(JSONObject().apply {
                                        put("format", a.format)
                                        put("data", a.base64Data)
                                    })
                                }
                            })
                        }
                    })
                }
            })
            systemPrompt?.let { put("system_prompt", it) }
            put("max_tokens", maxTokens)
            temperature?.let { put("temperature", it) }

            if (imageParts.isNotEmpty()) {
                put("image_parts", JSONArray().apply {
                    imageParts.forEach { img ->
                        put(JSONObject().apply {
                            if (img.data.isNotEmpty()) {
                                put("data", android.util.Base64.encodeToString(img.data, android.util.Base64.DEFAULT))
                            }
                            put("mime_type", img.mimeType)
                            img.linuxPath?.let { put("linux_path", it) }
                        })
                    }
                })
            }

            if (inputJson.isNotBlank()) put("input_json", inputJson)
            outputExt?.let { put("output_ext", it) }
        }.toString()
    }

    /**
     * Dispatch a serialized request to [ModelExecutionService] and wait for
     * the result file. Returns the result JSON string, or `null` on any
     * dispatch failure (timeout / IO / service unavailable) so callers can
     * fall back to the in-process path.
     */
    suspend fun dispatch(context: Context, requestJson: String): String? {
        val dir = try {
            val root = File(context.cacheDir, STAGING_ROOT)
            root.mkdirs()
            val d = File(root, "run-${UUID.randomUUID()}")
            if (!d.mkdir()) return null
            d
        } catch (_: Exception) { return null }

        val requestFile = File(dir, "request.json")
        val resultFile = File(dir, ModelExecutionService.RESULT_FILE)
        try {
            requestFile.writeText(requestJson)
        } catch (e: Exception) {
            Log.w(TAG, "write request failed: ${e.message}")
            dir.deleteRecursively()
            return null
        }

        try {
            val intent = Intent(context, ModelExecutionService::class.java).apply {
                putExtra(ModelExecutionService.EXTRA_REQUEST_DIR, dir.absolutePath)
            }
            context.startService(intent)
        } catch (e: Exception) {
            Log.w(TAG, "startService failed: ${e.message}")
            logDispatchFailure(dir)
            return null
        }

        // Poll for the result file.
        val result = withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
            while (true) {
                if (resultFile.exists()) {
                    return@withTimeoutOrNull try {
                        resultFile.readText()
                    } catch (e: Exception) {
                        Log.w(TAG, "read result failed: ${e.message}")
                        null
                        null
                        null
                    }
                }
                delay(POLL_INTERVAL_MS)
            }
        }

        if (result == null) {
            Log.w(TAG, "timeout waiting for model-exec result — falling back in-process")
        }
        // Cleanup best-effort; the service may still be draining.
        try { dir.deleteRecursively() } catch (_: Exception) {}
        return result
    }

    private fun logDispatchFailure(dir: File) {
        try { dir.deleteRecursively() } catch (_: Exception) {}
    }
}
