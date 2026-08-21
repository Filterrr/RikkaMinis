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
        /** Streaming-chunk log line-per-chunk file written by streaming runs. */
        const val STREAM_FILE = "stream.jsonl"
        /** Cancellation signal file: when created, a running stream aborts. */
        const val CANCEL_FILE = "cancel"

        /**
         * [native-oom Phase 1 / Tier 1 hard-injury 1] Idle-reap delay. After a
         * request finishes we do NOT immediately die — we hold the process alive
         * for this long so a burst of consecutive offload calls reuses the warm
         * process (no cold-start per call). If no new request arrives within this
         * window we [Process.killProcess] ourselves so the native heap
         * (DirectByteBuffer response bodies, JSON parse scratch, decoded media)
         * is returned to the OS deterministically.
         *
         * Why killProcess and not stopSelf: [stopSelf] only stops the Service
         * component; the process survives in Android's cached-process pool and
         * its native heap is NOT reclaimed. The 2026-08-17 plan (Tier 1) records
         * this exact injury — "stopSelf() 不杀进程，native 堆不归零".
         */
        const val IDLE_REAP_DELAY_MS = 30_000L
    }

    /**
     * [native-oom Phase 1] Main-looper handler + the armed reap runnable. Held
     * as fields so [cancelIdleReap] can remove the exact runnable that
     * [scheduleIdleReap] posted — without this, a stale killProcess would fire
     * mid-request and sever an in-flight stream.
     */
    private var reapHandler: android.os.Handler? = null
    private var reapRunnable: Runnable? = null

    /**
     * [process-idle-reap-aggressive-reclaim] Number of requests currently
     * being processed by this process (streaming or non-streaming).
     * Incremented in [onStartCommand] before dispatch, decremented in the
     * worker's finally. The idle-reap runnable consults it before
     * [Process.killProcess] — a concurrent request must never be severed
     * mid-answer. Without this guard, parallel sessions on the same
     * :modelservice process would kill each other: session A finishes,
     * arms the 30s reap, and 30s later session B (still streaming) is
     * silently killed, leaving its stream.jsonl without DONE/error and the
     * UI stalled until the 6-min poll timeout.
     */
    private val activeRequests = java.util.concurrent.atomic.AtomicInteger(0)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // [native-oom Phase 1] Prime the idle-reap deadline at process birth.
        // If no request is dispatched before the window elapses (the process
        // was spawned but never used — e.g. an aborted offload), reap it so it
        // doesn't sit in the cached-process pool holding native heap.
        scheduleIdleReap()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A real request arrived — register it BEFORE cancelling the reap.
        // Order matters: the reap runnable consults [activeRequests] before
        // killProcess; if we cancelled the reap first and registered after,
        // a reap firing in that gap would see count=0 and sever this very
        // request (the "mid-answer stall" race). Register-then-cancel closes
        // the window: the runnable can never observe 0 while this request is
        // being dispatched.
        activeRequests.incrementAndGet()
        // Defer any pending reap (reuse the warm process for this burst). The
        // deadline is re-armed in the worker's finally once the request completes.
        cancelIdleReap()

        val requestDir = intent?.getStringExtra(EXTRA_REQUEST_DIR)
            ?: run { activeRequests.decrementAndGet(); stopSelf(startId); return START_NOT_STICKY }

        val dir = File(requestDir)
        val requestFile = File(dir, "request.json")
        val resultFile = File(dir, RESULT_FILE)

        if (!requestFile.exists()) {
            Log.w(TAG, "request.json not found")
            activeRequests.decrementAndGet()
            stopSelf(startId)
            return START_NOT_STICKY
        }

        // Execute on a background thread; re-arm the idle-reap deadline after
        // the result lands — the process now lingers IDLE_REAP_DELAY_MS for a
        // burst of follow-up calls, then killProcess returns all native heap.
        // (The request was already registered via activeRequests increment at
        // the top of onStartCommand — this worker only owns the decrement.)
        Thread {
            try {
                val requestText = requestFile.readText()
                val isStreaming = JSONObject(requestText).optBoolean("streaming", false)
                if (isStreaming) {
                    executeStreamingRun(requestText, dir)
                } else {
                    val result = executeRun(requestText)
                    resultFile.writeText(result)
                    Log.i(TAG, "result written ($result.length bytes), pid=${android.os.Process.myPid()}")
                }
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
                activeRequests.decrementAndGet()
                // The result (success or error) is durably on disk (resultFile
                // written above, or stream.jsonl flushed by executeStreamingRun
                // before it returns). Re-arm the idle-reap; stopSelf only stops
                // the component — the process lives until the reap fires.
                scheduleIdleReap()
                stopSelf(startId)
            }
        }.apply { isDaemon = false }.start()

        return START_NOT_STICKY
    }

    /**
     * [native-oom Phase 1] Idle-reap machinery. The only reliable way to give
     * this process's native heap back to the OS is to kill the process itself;
     * [stopSelf] alone leaves it in the cached-process pool. [scheduleIdleReap]
     * arms a deferred [Process.killProcess]; [cancelIdleReap] defuses it when a
     * new request arrives so a burst reuses the warm process instead of paying
     * cold-start per call.
     */
    private fun scheduleIdleReap() {
        val handler = reapHandler ?: android.os.Handler(android.os.Looper.getMainLooper()).also {
            reapHandler = it
        }
        cancelIdleReap()
        val runnable = Runnable {
            // [process-idle-reap-aggressive-reclaim] Watchdog check: never kill
            // the process while a request is still in flight. If one is (a
            // parallel session is streaming right now), re-arm instead of
            // severing it — the kill would leave that stream's stream.jsonl
            // truncated (no DONE/error) and the UI stalled until the 6-min
            // poll timeout (exactly the "回答着回答着突然卡住" report).
            when (reapDecision(activeRequests.get())) {
                ReapDecision.DEFER -> {
                    Log.i(TAG, "idle reap deferred: ${activeRequests.get()} request(s) in flight — re-arming ${IDLE_REAP_DELAY_MS}ms")
                    scheduleIdleReap()
                }
                ReapDecision.KILL -> {
                    Log.i(TAG, "idle ${IDLE_REAP_DELAY_MS}ms — killing process to return native heap (pid=${android.os.Process.myPid()})")
                    android.os.Process.killProcess(android.os.Process.myPid())
                }
            }
        }
        reapRunnable = runnable
        handler.postDelayed(runnable, IDLE_REAP_DELAY_MS)
    }

    internal enum class ReapDecision { KILL, DEFER }

    companion object {
        /**
         * [process-idle-reap-aggressive-reclaim] Pure watchdog decision, kept
         * separate so the concurrency rule is JVM-testable: kill the process only
         * when NO request is in flight ([activeRequests] == 0); otherwise defer.
         */
        internal fun reapDecision(activeRequests: Int): ReapDecision =
            if (activeRequests > 0) ReapDecision.DEFER else ReapDecision.KILL
    }

    private fun cancelIdleReap() {
        val handler = reapHandler ?: return
        val runnable = reapRunnable ?: return
        handler.removeCallbacks(runnable)
        reapRunnable = null
    }

    private fun executeRun(requestJson: String): String {
        val req = JSONObject(requestJson)

        // ── Reconstruct ProviderInstance ──
        val instance = com.openminis.app.data.model.ProviderInstance(
            id = req.optString("instance_id", "remote"),
            label = req.optString("instance_label", "remote"),
            providerType = safeEnum(
                req.optString("provider_type", "openAI"),
                com.openminis.app.data.model.ProviderType.openAI,
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
                if (it.isNotEmpty()) safeEnumOrNull<com.openminis.app.data.model.ImageEndpointMode>(it) else null
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

    /**
     * Streaming execution path (direction A chat-offload primary path).
     *
     * Protocol (see ChatStreamOffloadHandler):
     *  - Reads request from [dir]/request.json with `"streaming":true`.
     *  - Calls provider.streamMessage(...) and appends every chunk to [dir]/[STREAM_FILE] as one JSON line.
     *  - On clean completion: appends [DONE_LINE] + writes result.json.
     *  - On failure: appends a `{"type":"error",...}` line + writes result.json error. Never fabricates Finished.
     *  - On cancel (main process creates [dir]/[CANCEL_FILE]): aborts the stream, writes an error line so
     *    the client's Flow terminates (hardened-3: cancellation propagation).
     */
    private fun executeStreamingRun(requestJson: String, dir: File) {
        val streamFile = File(dir, STREAM_FILE)
        val resultFile = File(dir, RESULT_FILE)
        val cancelFile = File(dir, CANCEL_FILE)
        val output = java.io.BufferedWriter(java.io.OutputStreamWriter(java.io.FileOutputStream(streamFile, true)))
        val appendLine = { line: String ->
            output.append(line).append('\n')
            output.flush()
        }
        try {
            val req = JSONObject(requestJson)

            // ── Reconstruct ProviderInstance (mirrors executeRun) ──
            val instance = com.openminis.app.data.model.ProviderInstance(
                id = req.optString("instance_id", "remote"),
                label = req.optString("instance_label", "remote"),
                providerType = safeEnum(
                    req.optString("provider_type", "openAI"),
                    com.openminis.app.data.model.ProviderType.openAI,
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
                    if (it.isNotEmpty()) safeEnumOrNull<com.openminis.app.data.model.ImageEndpointMode>(it) else null
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
                    contentParts = parseContentParts(obj),
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
                        if (b64.isNotEmpty()) java.util.Base64.getDecoder().decode(b64)
                        else ByteArray(0)
                    },
                    mimeType = obj.optString("mime_type", "image/png"),
                    linuxPath = obj.optString("linux_path", "").ifEmpty { null },
                )
            }
            val tools = parseToolsJson(req.optJSONArray("tools"))
            val thinkingLevel = safeEnum(getString(req, "thinking_level"), com.openminis.app.data.model.ThinkingLevel.OFF)

            // ── API key: read from EncryptedSharedPreferences (same uid) ──
            val apiKey = try {
                com.openminis.app.util.EncryptedPrefsFactory.safeCreate(this, "provider_secrets")
                    .getString("apikey_${instance.id}", null) ?: ""
            } catch (_: Exception) { "" }
            if (apiKey.isEmpty()) {
                appendLine(ChatStreamJsonl.errorLine("missing_api_key"))
                resultFile.writeText(JSONObject().apply {
                    put("error", "missing_api_key")
                    put("message", "No API key configured for ${instance.label}.")
                    put("exit_code", 2)
                }.toString())
                return
            }

            // ── Provider ──
            @Suppress("UNCHECKED_CAST")
            val provider = com.openminis.app.provider.ProviderFactory.create(instance, apiKey, model, this)
            kotlinx.coroutines.runBlocking {
                provider.streamMessage(
                    messages = messages,
                    systemPrompt = systemPrompt,
                    maxTokens = maxTokens,
                    temperature = temperature,
                    tools = tools,
                    thinkingLevel = thinkingLevel,
                ).collect { chunk ->
                    if (cancelFile.exists()) {
                        throw IllegalStateException("cancelled")
                    }
                    appendLine(ChatStreamJsonl.encode(chunk))
                }
            }
            appendLine(ChatStreamJsonl.DONE_LINE)
            resultFile.writeText(JSONObject().apply {
                put("ok", true)
                put("streaming", true)
            }.toString())
            Log.i(TAG, "stream done, pid=${android.os.Process.myPid()}")
        } catch (t: Throwable) {
            Log.w(TAG, "stream failed: ${t.message}", t)
            try {
                appendLine(ChatStreamJsonl.errorLine(t.message ?: "stream_failed"))
            } catch (_: Throwable) {}
            try {
                resultFile.writeText(JSONObject().apply {
                    put("error", "stream_failed")
                    put("message", t.message ?: "unknown")
                    put("exit_code", 1)
                }.toString())
            } catch (_: Throwable) {}
        } finally {
            try { output.close() } catch (_: Throwable) {}
        }
    }

    private fun parseContentParts(o: JSONObject): List<com.openminis.app.data.model.AgentContentPart> {
        val parts = mutableListOf<com.openminis.app.data.model.AgentContentPart>()
        o.optJSONArray("contentParts")?.let { arr ->
            for (i in 0 until arr.length()) {
                val p = arr.getJSONObject(i)
                when (getString(p, "kind").lowercase()) {
                    "tooluse" -> {
                        val id = getString(p, "toolUseId").ifBlank { getString(p, "id") }
                        val name = getString(p, "name")
                        val arguments = p.optJSONObject("arguments") ?: JSONObject()
                        parts.add(com.openminis.app.data.model.AgentContentPart.ToolUse(id = id, name = name, input = arguments))
                    }
                    "toolresult" -> {
                        val id = getString(p, "toolUseId").ifBlank { getString(p, "id") }
                        val name = getString(p, "name")
                        val error = p.optBoolean("isError", false)
                        val content = getString(p, "content")
                        val imageData: ByteArray? = p.optString("imageDataB64").takeIf { it.isNotBlank() }
                            ?.let { runCatching { java.util.Base64.getDecoder().decode(it) }.getOrNull() }
                        val imageMimeType = getString(p, "imageMimeType").ifEmpty { null }
                        val imageLinuxPath = getString(p, "imageLinuxPath").ifEmpty { null }
                        parts.add(com.openminis.app.data.model.AgentContentPart.ToolResult(
                            id = id,
                            name = name,
                            content = content,
                            isError = error,
                            imageData = imageData,
                            imageMimeType = imageMimeType,
                            imageLinuxPath = imageLinuxPath,
                        ))
                    }
                    "image", "imagedata" -> {
                        val b64 = getString(p, "b64Data")
                        val data = if (b64.isNotBlank()) { runCatching { java.util.Base64.getDecoder().decode(b64) }.getOrNull() ?: ByteArray(0) }
                        else ByteArray(0)
                        val mimeType = getString(p, "mimeType").ifEmpty { "image/png" }
                        val linuxPath = getString(p, "linuxPath").ifEmpty { null }
                        parts.add(com.openminis.app.data.model.AgentContentPart.ImageData(data = data, mimeType = mimeType, linuxPath = linuxPath))
                    }
                    "text" -> parts.add(com.openminis.app.data.model.AgentContentPart.Text(getString(p, "text")))
                }
            }
        }
        return parts
    }

    private fun parseToolsJson(arr: JSONArray?): List<com.openminis.app.data.model.AgentToolDefinition> {
        if (arr == null) return emptyList()
        val out = mutableListOf<com.openminis.app.data.model.AgentToolDefinition>()
        for (i in 0 until arr.length()) {
            val t = arr.getJSONObject(i)
            val params = linkedMapOf<String, com.openminis.app.data.model.AgentToolParam>()
            t.optJSONObject("parameters")?.let { ps ->
                ps.keys().forEach { k ->
                    val v = ps.getJSONObject(k)
                    params[k] = com.openminis.app.data.model.AgentToolParam(
                        type = getString(v, "type"),
                        description = getString(v, "description"),
                        enumValues = v.optJSONArray("enum")?.let { e -> (0 until e.length()).map { e.getString(it) } },
                    )
                }
            }
            val required = jsonStrList(t.optJSONArray("required"))
            val propertyOrdering = t.optJSONArray("property_ordering")?.let { e -> (0 until e.length()).map { e.getString(it) } }
            out.add(com.openminis.app.data.model.AgentToolDefinition(
                name = getString(t, "name"),
                description = getString(t, "description"),
                parameters = params,
                required = required,
                propertyOrdering = propertyOrdering,
            ))
        }
        return out
    }

    private fun getString(o: JSONObject, key: String): String = o.optString(key) ?: ""

    // ── helpers ──

    private inline fun <reified T : Enum<T>> safeEnum(name: String, default: T): T =
        try { java.lang.Enum.valueOf(T::class.java, name) } catch (_: Exception) { default }

    private inline fun <reified T : Enum<T>> safeEnumOrNull(name: String): T? =
        enumValues<T>().firstOrNull { it.name == name }

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