package com.openminis.app.sandbox.offload

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Remote executor for [minis-model-use run]'s network call, running in a
 * separate Android process (`:modelservice`).
 *
 * Why: the LLM provider HTTP call allocates native heap (DirectByteBuffer
 * for response bodies, JSON parsing, image decoding) that GC cannot reclaim
 * (logs: Post-recycle GC freed 0MB). Running the call in this short-lived
 * process and killing it afterwards returns ALL its native memory to the OS
 * — the only reliable leak containment.
 *
 * Protocol (file-based, no Binder 1MB limit for media):
 *   Request:  [cacheDir]/model-exec-<uuid>/request.json
 *   Response: [cacheDir]/model-exec-<uuid>/result.json
 *
 * The service reads the request, reconstructs ProviderInstance + LLMModel,
 * builds the provider, injects passthrough extras, dispatches to
 * generateImage (media output ext) or sendMessage, and writes a
 * result JSON whose media attachments are base64-encoded (the caller —
 * ModelUseOffloadHandler in the main process — writes them to real paths,
 * keeping ALL output-file behaviour in one place).
 *
 * The process stops itself after writing the result; Android reaps it and
 * its native heap with it.
 */
class ModelExecutionService : Service() {

    companion object {
        private const val TAG = "ModelExecService"
        const val EXTRA_REQUEST_DIR = "request_dir"
        const val RESULT_FILE = "result.json"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requestDir = intent?.getStringExtra(EXTRA_REQUEST_DIR)
            ?: run { stopSelf(startId); return START_NOT_STICKY }

        val dir = File(requestDir)
        val requestFile = File(dir, "request.json")
        val resultFile = File(dir, RESULT_FILE)

        if (!requestFile.exists()) {
            Log.w(TAG, "request.json not found")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        // Execute on a background thread; stopSelf after the result lands.
        Thread {
            try {
                val result = executeRun(requestFile.readText())
                resultFile.writeText(result)
                Log.i(TAG, "result written ($result.length bytes), pid=${android.os.Process.myPid()}")
            } catch (t: Throwable) {
                Log.w(TAG, "execution failed: ${t.message}", t)
                try {
                    resultFile.writeText(JSONObject().apply {
                        put("error", "model_use_failed")
                        put("message", t.message ?: "unknown")
                        put("exit_code", 1)
                    }.toString())
                } catch (_: Throwable) {}
            } finally {
                stopSelf(startId)
            }
        }.apply { isDaemon = false }.start()

        return START_NOT_STICKY
    }

    private fun executeRun(requestJson: String): String {
        val req = JSONObject(requestJson)

        // ── Reconstruct ProviderInstance ──
        val instance = com.openminis.app.data.model.ProviderInstance(
            id = req.optString("instance_id", "remote"),
            label = req.optString("instance_label", "remote"),
            providerType = com.openminis.app.data.model.ProviderType.valueOf(
                req.getString("provider_type")
            ),
            credentialType = safeEnum(
                req.optString("credential_type", "apiKey"),
                com.openminis.app.data.model.ProviderCredential.apiKey,
            ),
            customBaseURL = req.optString("base_url", "").ifEmpty { null },
            appendV1Suffix = req.optBoolean("append_v1", true),
            customUserAgent = req.optString("user_agent", "").ifEmpty { null },
            useResponsesAPI = req.optBoolean("use_responses_api", false),
            imageEndpointMode = safeEnum(
                req.optString("image_endpoint_mode", "auto"),
                com.openminis.app.data.model.ImageEndpointMode.auto,
            ),
            imageEndpointResolved = req.optString("image_endpoint_resolved", "").let {
                if (it.isNotEmpty()) {
                    try { com.openminis.app.data.model.ImageEndpointMode.valueOf(it) }
                    catch (_: Exception) { null }
                } else null
            },
            azureMode = req.optBoolean("azure_mode", false),
            pinned = false,
        )

        // ── Reconstruct LLMModel ──
        val model = com.openminis.app.data.model.LLMModel(
            id = req.getString("model_id"),
            displayName = req.optString("model_display_name", req.getString("model_id")),
            provider = req.optString("model_provider", instance.providerType.name),
            inputModalities = jsonStrList(req.optJSONArray("input_modalities")),
            outputModalities = jsonStrList(req.optJSONArray("output_modalities")),
            contextWindow = req.optInt("context_window", 0).takeIf { it > 0 },
        )

        // ── Reconstruct messages ──
        val messages = jsonObjList(req.optJSONArray("messages")).map { obj ->
            com.openminis.app.data.model.LLMMessage(
                role = try {
                    com.openminis.app.data.model.LLMMessage.Role.valueOf(
                        obj.getString("role").uppercase()
                    )
                } catch (_: Exception) {
                    com.openminis.app.data.model.LLMMessage.Role.USER
                },
                content = obj.optString("content", ""),
                audioParts = jsonObjList(obj.optJSONArray("audio_parts")).mapNotNull { a ->
                    val b64 = a.optString("data", "")
                    if (b64.isEmpty()) null
                    else com.openminis.app.data.model.LLMMessage.AudioPart(
                        format = a.optString("format", "wav"),
                        base64Data = b64,
                    )
                },
            )
        }
        val systemPrompt = req.optString("system_prompt", "").ifEmpty { null }
        val maxTokens = req.optInt("max_tokens", 4096)
        val temperature = if (req.has("temperature")) {
            req.optDouble("temperature", Double.NaN).takeIf { !it.isNaN() }
        } else null
        val imageParts = jsonObjList(req.optJSONArray("image_parts")).map { obj ->
            com.openminis.app.data.model.LLMMessage.ImagePart(
                data = obj.optString("data", "").let { b64 ->
                    if (b64.isNotEmpty()) android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                    else ByteArray(0)
                },
                mimeType = obj.optString("mime_type", "image/png"),
                linuxPath = obj.optString("linux_path", "").ifEmpty { null },
            )
        }

        // ── API key: read from EncryptedSharedPreferences (same uid) ──
        val apiKey = try {
            com.openminis.app.util.EncryptedPrefsFactory.safeCreate(this, "provider_secrets")
                .getString("apikey_${instance.id}", null) ?: ""
        } catch (_: Exception) { "" }
        if (apiKey.isEmpty()) {
            return JSONObject().apply {
                put("error", "missing_api_key")
                put("message", "No API key configured for ${instance.label}.")
                put("exit_code", 2)
            }.toString()
        }

        // ── Build provider ──
        val provider = com.openminis.app.provider.ProviderFactory.create(
            instance = instance, apiKey = apiKey, model = model, context = this,
        )

        // ── Passthrough extras ──
        val inputJson = req.optString("input_json", "")
        val callWarnings = mutableListOf<String>()
        val appliedExtras = JSONObject()
        if (inputJson.isNotEmpty() && provider is com.openminis.app.provider.openai.OpenAIProvider) {
            val chatExtra = parseChatExtraBody(inputJson, callWarnings)
            if (chatExtra.isNotEmpty()) {
                provider.chatExtraBody = chatExtra
                appliedExtras.put("extra_body_keys", JSONArray(chatExtra.keys.sorted()))
            }
            parseExtraHeaders(inputJson, callWarnings).let { hdrs ->
                if (hdrs.isNotEmpty()) {
                    provider.chatExtraHeaders = hdrs
                    appliedExtras.put("extra_headers_keys", JSONArray(hdrs.keys.sorted()))
                }
            }
            parseCustomEndpointPath(inputJson)?.let { path ->
                provider.absoluteEndpointOverride = path
                appliedExtras.put("custom_endpoint", path)
            }
        }

        // ── Dispatch ──
        val outputExt = req.optString("output_ext", "").ifEmpty { null }
        val isMediaOutput = outputExt in listOf("png", "jpg", "jpeg", "webp", "gif")
        val openAI = provider as? com.openminis.app.provider.openai.OpenAIProvider

        // 1) Image generation route (media output ext + OpenAI-compat provider)
        if (openAI != null && isMediaOutput) {
            val genConfig = parseImageGenConfig(inputJson)
            val prompt = genConfig.prompt
                ?: messages.lastOrNull { it.role == com.openminis.app.data.model.LLMMessage.Role.USER }?.content?.takeIf { it.isNotEmpty() }
                ?: ""
            if (prompt.isNotEmpty()) {
                // Apply image passthrough extras from inputJson (mirrors
                // ModelUseOffloadHandler.tryImageGenerationRoute's passthrough
                // injection).
                applyImagePassthrough(openAI, inputJson)
                try {
                    val imgResult = runBlocking {
                        openAI.generateImage(prompt, genConfig.n, genConfig.size, genConfig.quality)
                    }
                    return JSONObject().apply {
                    return JSONObject().apply {
                        put("model", model.id)
                        put("text", imgResult.text)
                        imgResult.usage?.let { u ->
                            put("usage", JSONObject().apply {
                                put("input_tokens", u.inputTokens)
                                put("output_tokens", u.outputTokens)
                            })
                        }
                        put("media_files", JSONArray().apply {
                            imgResult.mediaAttachments.forEach { m ->
                                put(JSONObject().apply {
                                    put("type", m.type.value)
                                    put("mime_type", m.mimeType)
                                    put("data", android.util.Base64.encodeToString(m.data, android.util.Base64.DEFAULT))
                                })
                            }
                        })
                        if (appliedExtras.length() > 0) put("applied_extras", appliedExtras)
                        if (callWarnings.isNotEmpty()) put("warnings", JSONArray(callWarnings))
                        put("exit_code", 0)
                    }.toString()
                } catch (t: Throwable) {
                    // Fall through to text path — mirror tryImageGenerationRoute's
                    // route-missing fallback semantics.
                    callWarnings.add("image_route_fallback: ${t.message}")
                }
            }
        }

        // 2) Standard sendMessage path
        val response = try {
            runBlocking {
                provider.sendMessage(
                    messages = messages,
                    systemPrompt = systemPrompt,
                    maxTokens = maxTokens,
                    temperature = temperature,
                    imageParts = imageParts,
                )
            }
        } catch (e: Throwable) {
            Log.w(TAG, "sendMessage failed: ${e.message}", e)
            return JSONObject().apply {
                put("error", "model_use_failed")
                put("message", e.message ?: "unknown")
                put("exit_code", 1)
            }.toString()
        }

        return JSONObject().apply {
            put("model", model.id)
            put("text", response.text)
            response.stopReason?.let { put("stop_reason", it) }
            response.usage?.let { u ->
                put("usage", JSONObject().apply {
                    put("input_tokens", u.inputTokens)
                    put("output_tokens", u.outputTokens)
                })
            }
            put("media_files", JSONArray().apply {
                response.mediaAttachments.forEach { m ->
                    put(JSONObject().apply {
                        put("type", m.type.value)
                        put("mime_type", m.mimeType)
                        put("data", android.util.Base64.encodeToString(m.data, android.util.Base64.DEFAULT))
                    })
                }
            })
            if (appliedExtras.length() > 0) put("applied_extras", appliedExtras)
            if (callWarnings.isNotEmpty()) put("warnings", JSONArray(callWarnings))
            put("exit_code", 0)
        }.toString()
    }

    // ── helpers ──

    private inline fun <reified T : Enum<T>> safeEnum(name: String, default: T): T =
        try { java.lang.Enum.valueOf(T::class.java, name) } catch (_: Exception) { default }

    private fun jsonStrList(arr: JSONArray?): List<String> =
        if (arr == null) emptyList() else (0 until arr.length()).map { arr.getString(it) }

    private fun jsonObjList(arr: JSONArray?): List<JSONObject> =
        if (arr == null) emptyList() else (0 until arr.length()).map { arr.getJSONObject(it) }

    // ── passthrough parsing (mirrors ModelUseOffloadHandler) ──

    private fun parseChatExtraBody(inputJson: String, warnings: MutableList<String>): Map<String, Any> {
        val obj = try { val t = inputJson.trim(); if (t.startsWith("{")) JSONObject(t) else null }
        catch (_: Exception) { null } ?: return emptyMap()
        val eb = obj.optJSONObject("extra_body") ?: return emptyMap()
        val result = linkedMapOf<String, Any>()
        for (key in eb.keys()) {
            when (val v = eb.get(key)) {
                is Int, is Long, is Double, is Boolean, is String -> result[key] = v
                is JSONArray -> {
                    val list = (0 until v.length()).mapNotNull { e ->
                        e.let { if (it is Int || it is Long || it is Double || it is Boolean || it is String) it else null }
                    }
                    if (list.isNotEmpty()) result[key] = list
                }
                else -> warnings.add("extra_body.$key: unsupported type, dropped")
            }
        }
        return result
    }

    private fun parseExtraHeaders(inputJson: String, warnings: MutableList<String>): Map<String, String> {
        val obj = try { val t = inputJson.trim(); if (t.startsWith("{")) JSONObject(t) else null }
        catch (_: Exception) { null } ?: return emptyMap()
        val eh = obj.optJSONObject("extra_headers") ?: return emptyMap()
        val result = linkedMapOf<String, String>()
        for (key in eh.keys()) result[key] = eh.optString(key, "")
        return result
    }

    private fun parseCustomEndpointPath(inputJson: String): String? {
        val obj = try { val t = inputJson.trim(); if (t.startsWith("{")) JSONObject(t) else null }
        catch (_: Exception) { null } ?: return null
        return obj.optString("endpoint", "").ifEmpty { null }
    }

    private fun parseImageGenConfig(inputJson: String): ImageGenConfig {
        val obj = try { val t = inputJson.trim(); if (t.startsWith("{")) JSONObject(t) else null }
        catch (_: Exception) { null } ?: return ImageGenConfig()
        var prompt: String? = null
        var n = 1
        var size: String? = null
        var quality: String? = null
        if (obj.has("prompt")) prompt = obj.optString("prompt", null)
        if (obj.has("n")) n = obj.optInt("n", 1)
        if (obj.has("size")) size = obj.optString("size", null)
        if (obj.has("quality")) quality = obj.optString("quality", null)
        val gc = obj.optJSONObject("generation_config")
        if (gc != null) {
            if (prompt == null && gc.has("prompt")) prompt = gc.optString("prompt", null)
            if (gc.has("number_of_images")) n = gc.optInt("number_of_images", 1)
            if (gc.has("image_size")) size = gc.optString("image_size", null)
        }
        return ImageGenConfig(prompt, n, size, quality)
    }

    private data class ImageGenConfig(
        val prompt: String? = null,
        val n: Int = 1,
        val size: String? = null,
        val quality: String? = null,
    )

    /**
     * Parse image passthrough extras from the input JSON and apply them to
     * the [OpenAIProvider]. Mirrors
     * [ModelUseOffloadHandler.tryImageGenerationRoute] passthrough injection.
     */
    private fun applyImagePassthrough(
        openAI: com.openminis.app.provider.openai.OpenAIProvider,
        inputJson: String,
    ) {
        val obj = try { val t = inputJson.trim(); if (t.startsWith("{")) JSONObject(t) else null }
        catch (_: Exception) { null } ?: return
        val ip = obj.optJSONObject("image_passthrough") ?: return
        val body = ip.optJSONObject("extra_body")
        if (body != null) {
            val bodyMap = linkedMapOf<String, Any?>()
            for (key in body.keys()) { bodyMap[key] = body.get(key) }
            openAI.imageExtraBody = bodyMap
        }
        ip.optString("path", "").ifEmpty { null }?.let { openAI.imagePathOverride = it }
        val hdrs = ip.optJSONObject("extra_headers")
        if (hdrs != null) {
            val hdrsMap = linkedMapOf<String, String>()
            for (key in hdrs.keys()) { hdrsMap[key] = hdrs.optString(key, "") }
            openAI.imageExtraHeaders = hdrsMap
        }
    }
}