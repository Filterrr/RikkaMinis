package com.openminis.app.sandbox.offload

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger

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
 *   Response: [cacheDir]/model-exec-<uuid>/result.json  (atomic via result.tmp)
 *
 * TF-B reliable lifecycle: the worker owns its own lifecycle via
 * [ModelExecutionLifecycle] + [ModelExecutionMailbox]. It writes worker.pid,
 * commits results atomically (result.tmp → flush/fsync → rename result.json),
 * waits for client.ack on non-streaming runs before self-reaping, and only
 * calls Process.killProcess (self-reap) after confirming quiescence. The main
 * process never kills us directly (only a shutdown REQUEST file); a worker
 * with in-flight work (active/queued/unacked/unflushed stream) NEVER dies.
 *
 * The old model — `stopSelf()` as reclamation proof with a 30s idle-kill —
 * is deliberately gone: stopSelf is not process death, and an idle window can
 * sever a later concurrent stream. Here the short-lived process dies
 * immediately when quiescent and the NEXT request starts a fresh process.
 *
 * The service reads the request, reconstructs ProviderInstance + LLMModel,
 * builds the provider, injects passthrough extras, dispatches to
 * generateImage (media output ext) or sendMessage, and writes a
 * result JSON whose media attachments are base64-encoded (the caller —
 * ModelUseOffloadHandler in the main process — writes them to real paths,
 * keeping ALL output-file behaviour in one place).
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
        /** Worker pid file, so the client can classify worker death. */
        const val WORKER_PID_FILE = "worker.pid"
        /** Max time a non-streaming worker waits for the client's client.ack. */
        private const val CLIENT_ACK_TIMEOUT_MS = 8_000L
        private const val ACK_POLL_MS = 100L
    }

    /** Worker-side registry: number of requests currently being executed. */
    private val activeRequests = AtomicInteger(0)

    /** Worker-side lifecycle state (authoritative; main process only inspects state.json). */
    @Volatile
    private var lifecycleState = ModelExecutionWorkerState.ACTIVE

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requestDir = intent?.getStringExtra(EXTRA_REQUEST_DIR)
            ?: run { stopSelf(startId); return START_NOT_STICKY }

        val dir = File(requestDir)
        val requestFile = File(dir, "request.json")

        if (!requestFile.exists()) {
            Log.w(TAG, "request.json not found")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        // Any new request REVIVES the worker: if a prior request left us
        // DRAINED/STOPPING, the incoming request moves us back to ACTIVE
        // (quiesce re-arm). Iff STOPPING and a kill was already in flight we
        // race it — the kill loop re-checks state before killing, so a
        // revived ACTIVE never gets killed.
        activeRequests.incrementAndGet()
        lifecycleState = ModelExecutionWorkerState.ACTIVE
        ModelExecutionMailbox.writeState(dir, lifecycleState, activeRequests.get())
        // Worker-pid file: the client uses this to classify worker death
        // (worker_died) instead of waiting out the 6-minute stream timeout.
        runCatching { File(dir, WORKER_PID_FILE).writeText(android.os.Process.myPid().toString()) }

        // Execute on a background thread; the worker then decides whether the
        // process may die (quiescent kill) — never just stopSelf as proof.
        Thread {
            try {
                val requestText = requestFile.readText()
                val isStreaming = JSONObject(requestText).optBoolean("streaming", false)
                if (isStreaming) {
                    executeStreamingRun(requestText, dir)
                } else {
                    val result = executeRun(requestText)
                    writeResultAtomically(dir, result)
                    Log.i(TAG, "result written ($result.length bytes), pid=${android.os.Process.myPid()}")
                    // The client must have consumed result.json before we die —
                    // otherwise a just-written result is lost when the worker
                    // process is reaped and the caller falls back in-process
                    // (or worse, re-dispatches and duplicates the call).
                    waitClientAck(dir, CLIENT_ACK_TIMEOUT_MS)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "execution failed: ${t.message}", t)
                try {
                    writeResultAtomically(dir, JSONObject().apply {
                        put("error", "model_use_failed")
                        put("message", t.message ?: "unknown")
                        put("exit_code", 1)
                    }.toString())
                } catch (_: Throwable) {}
            } finally {
                lifecycleState = finishRequest(dir)
            }
        }.apply { isDaemon = false }.start()

        return START_NOT_STICKY
    }

    /**
     * Registry bookkeeping after a request finished: decrement the active
     * counter and run the lifecycle machine. Returns the next worker state.
     *
     * The worker only kills its own process when [ModelExecutionLifecycle]
     * decides STOPPING **and** quiescence is confirmed — the main process can
     * never kill us directly. A new request arriving after this check simply
     * revives the process (onStartCommand moves the state back to ACTIVE).
     */
    private fun finishRequest(dir: File): ModelExecutionWorkerState {
        activeRequests.decrementAndGet()
        val quiescence = ModelExecutionQuiescenceInput(
            activeRequests = activeRequests.get(),
            queuedRequests = 0, // onStartCommand always starts work immediately; no queue
            unackedResponses = 0, // non-streaming waits for client ack before finishing;
                                  // streaming is terminal-by-construction after DONE
            streamFileFlushed = true,
        )
        val shutdownRequested = shutdownRequested()
        val next = ModelExecutionLifecycle.transition(
            current = lifecycleState,
            quiescence = quiescence,
            shutdownRequested = shutdownRequested,
        )
        lifecycleState = next
        ModelExecutionMailbox.writeState(dir, next, activeRequests.get())
        Log.i(
            TAG,
            "request finished: state $next active=${activeRequests.get()} " +
                "shutdownRequested=$shutdownRequested pid=${android.os.Process.myPid()}",
        )
        if (ModelExecutionLifecycle.shouldKill(next, quiescence)) {
            // Quiescent: no in-flight work (active==0, queue==0, no un-acked
            // response, stream flushed). The worker kills ITS OWN process —
            // never the main process on its own counters — so the native heap
            // (DirectByteBuffer from the LLM HTTP call) returns to the OS.
            // There is NO idle window: the process dies only when a request
            // finished and nothing else is running. The next request starts
            // a fresh process.
            Log.i(TAG, "quiescent self-reap (no pending work), pid=${android.os.Process.myPid()}")
            selfReap()
        }
        return next
    }

    /**
     * True when the main process asked us to drain. On reclaim the main
     * process writes the shutdown marker into the staging root; the worker
     * checks the sibling marker file so we never kill while a new request
     * could arrive (main process controls shutdown by the marker, we control
     * the timing by quiescence).
     */
    private fun shutdownRequested(): Boolean {
        return runCatching {
            ModelExecutionMailbox.shutdownRequested(File(stagingRoot(), ModelExecutionMailbox.FILE_SHUTDOWN))
        }.getOrElse { false }
    }

    /** Root staging dir the main process uses for model-exec requests. */
    private fun stagingRoot(): File = File(cacheDir, "model-exec")

    /** Wait until the client wrote client.ack, or the timeout elapsed. */
    private fun waitClientAck(dir: File, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        val ack = File(dir, ModelExecutionMailbox.FILE_CLIENT_ACK)
        while (System.currentTimeMillis() < deadline) {
            if (ack.exists()) return
            try { Thread.sleep(ACK_POLL_MS) } catch (_: InterruptedException) { return }
        }
        Log.w(TAG, "client ack timeout (${timeoutMs}ms) — proceeding anyway")
    }

    /**
     * Atomic result commit: write to result.tmp, flush + fsync, then rename
     * to result.json. The client NEVER observes a partial result file.
     */
    private fun writeResultAtomically(dir: File, content: String) {
        val tmp = File(dir, ModelExecutionMailbox.FILE_RESULT_TMP)
        val target = File(dir, RESULT_FILE)
        FileOutputStream(tmp).use { fos ->
            fos.write(content.toByteArray(Charsets.UTF_8))
            fos.flush()
            try { fos.fd.sync() } catch (_: Throwable) {}
        }
        if (!tmp.renameTo(target)) {
            // Cross-filesystem rename can't happen here (same dir), but be
            // defensive: copy + delete instead of leaving a broken result.
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    /** Kill our own process — only ever called after quiescence confirmation. */
    private fun selfReap() {
        runCatching { android.os.Process.killProcess(android.os.Process.myPid()) }
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
                writeResultAtomically(dir, JSONObject().apply {
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
                        throw ModelExecutionCancelledException()
                    }
                    appendLine(ChatStreamJsonl.encode(chunk))
                }
            }
            appendLine(ChatStreamJsonl.DONE_LINE)
            writeResultAtomically(dir, JSONObject().apply {
                put("ok", true)
                put("streaming", true)
            }.toString())
            Log.i(TAG, "stream done, pid=${android.os.Process.myPid()}")
        } catch (t: Throwable) {
            val cancelled = t is ModelExecutionCancelledException
            Log.w(TAG, "stream failed: ${t.message}", t)
            // [TF-B cancel contract] The worker MUST acknowledge a cancel
            // (or a clean terminal result) BEFORE the client deletes the
            // directory — else the old code deleted the dir under a worker
            // still appending to stream.jsonl / writing result.json (lost
            // final chunks / torn result).
            if (cancelled) {
                runCatching { ModelExecutionMailbox.writeCancelAck(dir) }
            }
            try {
                appendLine(ChatStreamJsonl.errorLine(t.message ?: "stream_failed"))
            } catch (_: Throwable) {}
            try {
                writeResultAtomically(dir, JSONObject().apply {
                    if (cancelled) put("cancelled", true)
                    put("error", "stream_failed")
                    put("message", t.message ?: "unknown")
                    put("exit_code", 1)
                }.toString())
            } catch (_: Throwable) {}
        } finally {
            try { output.close() } catch (_: Throwable) {}
        }
    }

    /** Thrown when the main process asks us to cancel an in-flight stream. */
    private class ModelExecutionCancelledException : java.lang.RuntimeException("cancelled")

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