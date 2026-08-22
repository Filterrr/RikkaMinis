package com.openminis.app.sandbox.offload

import android.content.Context
import android.content.Intent
import android.util.Log
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ThinkingLevel
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
    /** Bounded wait for the worker's self-reap before deleting the request dir. */
    private const val REAP_WAIT_MS = 3_000L
    private const val REAP_SETTLE_MS = 300L
    private const val REAP_POLL_MS = 50L

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
        tools: List<AgentToolDefinition> = emptyList(),
        thinkingLevel: ThinkingLevel = ThinkingLevel.OFF,
        streaming: Boolean = false,
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

            if (messages.isNotEmpty()) {
                put("messages", JSONArray().apply {
                    messages.forEach { m ->
                        put(JSONObject().apply {
                            put("role", m.role.value)
                            put("content", m.content)
                            if (m.contentParts.isNotEmpty()) {
                                put("contentParts", JSONArray().apply {
                                    m.contentParts.forEach { part ->
                                        put(JSONObject().apply {
                                            when (part) {
                                                is AgentContentPart.Text -> {
                                                    put("kind", "text")
                                                    put("text", part.text)
                                                }
                                                is AgentContentPart.ToolUse -> {
                                                    put("kind", "tooluse")
                                                    put("toolUseId", part.id)
                                                    put("name", part.name)
                                                    put("arguments", part.input ?: JSONObject())
                                                }
                                                is AgentContentPart.ToolResult -> {
                                                    put("kind", "toolresult")
                                                    put("toolUseId", part.id)
                                                    put("name", part.name)
                                                    put("isError", part.isError)
                                                    put("content", part.content)
                                                    part.imageData?.takeIf { it.isNotEmpty() }?.let {
                                                        put("imageDataB64", java.util.Base64.getEncoder().encodeToString(it))
                                                    }
                                                    part.imageMimeType?.let { put("imageMimeType", it) }
                                                    part.imageLinuxPath?.let { put("imageLinuxPath", it) }
                                                }
                                                is AgentContentPart.ImageData -> {
                                                    put("kind", "image")
                                                    put("mimeType", part.mimeType)
                                                    if (part.data.isNotEmpty()) {
                                                        put("b64Data", java.util.Base64.getEncoder().encodeToString(part.data))
                                                    }
                                                    part.linuxPath?.let { put("linuxPath", it) }
                                                }
                                            }
                                        })
                                    }
                                })
                            }
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
            }
            systemPrompt?.let { put("system_prompt", it) }
            put("max_tokens", maxTokens)
            temperature?.let { put("temperature", it) }

            if (imageParts.isNotEmpty()) {
                put("image_parts", JSONArray().apply {
                    imageParts.forEach { img ->
                        put(JSONObject().apply {
                            if (img.data.isNotEmpty()) {
                                put("data", java.util.Base64.getEncoder().encodeToString(img.data))
                            }
                            put("mime_type", img.mimeType)
                            img.linuxPath?.let { put("linux_path", it) }
                        })
                    }
                })
            }

            if (inputJson.isNotBlank()) put("input_json", inputJson)
            outputExt?.let { put("output_ext", it) }

            if (tools.isNotEmpty()) {
                put("tools", JSONArray().apply {
                    tools.forEach { t ->
                        put(JSONObject().apply {
                            put("name", t.name)
                            put("description", t.description)
                            if (t.parameters.isNotEmpty()) {
                                put("parameters", JSONObject().apply {
                                    t.parameters.forEach { (k, v) ->
                                        put(k, JSONObject().apply {
                                            put("type", v.type)
                                            put("description", v.description)
                                            v.enumValues?.takeIf { it.isNotEmpty() }?.let { put("enum", JSONArray(it)) }
                                        })
                                    }
                                })
                            }
                            if (t.required.isNotEmpty()) put("required", JSONArray(t.required))
                            t.propertyOrdering?.takeIf { it.isNotEmpty() }?.let { put("property_ordering", JSONArray(it)) }
                        })
                    }
                })
            }
            if (thinkingLevel != ThinkingLevel.OFF) put("thinking_level", thinkingLevel.name)
            if (streaming) put("streaming", true)
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
        val result: String? = withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
            var read: String? = null
            while (true) {
                if (resultFile.exists()) {
                    read = try {
                        resultFile.readText()
                    } catch (e: Exception) {
                        Log.w(TAG, "read result failed: ${e.message}")
                        null
                    }
                    break
                }
                delay(POLL_INTERVAL_MS)
            }
            read
        }

        if (result != null) {
            // [TF-B ack] Tell the worker we consumed the result so it can
            // self-reap immediately instead of waiting out its ack timeout.
            try {
                ModelExecutionMailbox.writeClientAck(dir)
            } catch (_: Exception) {}
        } else {
            Log.w(TAG, "timeout waiting for model-exec result — falling back in-process")
            // Try to stop the worker (it may be mid-run); it self-reaps on cancel
            // / shutdown. Then settle briefly before deleting the dir so we never
            // delete under a worker still writing result.json.
            try { ModelExecutionMailbox.writeCancel(dir) } catch (_: Exception) {}
        }

        // [TF-B] Delete the request dir ONLY after the worker acknowledged the
        // terminal state (client.ack consumed / cancel.ack received) or its
        // process has gone away — never delete under a worker still appending
        // to result.json / stream.jsonl. Bounded wait: if the worker is already
        // gone (self-reaped after ack), we proceed immediately.
        waitForWorkerReap(dir, REAP_WAIT_MS)
        try { dir.deleteRecursively() } catch (_: Exception) {}
        return result
    }

    /**
     * [TF-B] Wait (bounded) for the :modelservice worker's process to disappear
     * after a result was consumed — the worker self-reaps when quiescent, so
     * once its pid is gone the dir is safe to delete. Absent pid file → assume
     * safe immediately (the worker may never have started the pid write).
     */
    private suspend fun waitForWorkerReap(dir: File, timeoutMs: Long) {
        val pidFile = File(dir, "worker.pid")
        var pid = runCatching { pidFile.readText().trim().toInt() }.getOrNull()
        if (pid == null) {
            // No pid yet (worker may still be starting). Give it a short settle
            // before the caller deletes, to avoid tearing a just-written result.
            kotlinx.coroutines.delay(REAP_SETTLE_MS)
            pid = runCatching { pidFile.readText().trim().toInt() }.getOrNull()
        }
        if (pid == null) return
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!java.io.File("/proc/$pid").exists()) return
            delay(REAP_POLL_MS)
        }
        Log.w(TAG, "worker pid $pid still alive after ${timeoutMs}ms — deleting dir anyway (worker may be mid-drain)")
    }

    private fun logDispatchFailure(dir: File) {
        try { dir.deleteRecursively() } catch (_: Exception) {}
    }
}
