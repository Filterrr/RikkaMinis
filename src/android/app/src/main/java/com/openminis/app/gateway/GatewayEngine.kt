package com.openminis.app.gateway

import android.content.Context
import android.util.Base64
import android.util.Log
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.data.model.AgentToolSchema
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.provider.effectiveMaxThinkingLevel
import com.openminis.app.sandbox.offload.ProviderExecutionGateway
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject


/**
 * [T-local-llm-gateway] Dialect-neutral execution: normalised request →
 * the app's provider-agnostic message model → [ProviderExecutionGateway] →
 * normalised result.
 *
 * Why the worker's STREAM path is always used, even for a buffered client
 * request: [com.openminis.app.sandbox.offload.ModelExecutionService] only
 * reconstructs `tools` / `thinkingLevel` / `contentParts` on its streaming
 * branch. A gateway client that sends tools in a non-streaming request would
 * silently lose them through the non-stream hop — so every gateway call streams
 * and aggregates when the client wants one JSON body. Tool traffic is the main
 * reason a coding agent points at this endpoint, so the difference is decisive.
 *
 * The gateway never constructs a provider or opens an upstream socket itself:
 * everything goes through [ProviderExecutionGateway], which keeps the LLM
 * traffic in the `:modelservice` worker process (the TF-D/TF-E boundary the
 * CI scanner enforces).
 */
internal class GatewayEngine(
    private val context: Context,
    private val repository: ProviderRepository,
) {

    /**
     * A prepared upstream call. The router owns the collection loop so it can
     * either forward chunks to the client (streaming) or fold them into one
     * JSON body (buffered) without the engine knowing which.
     */
    class Prepared(
        val target: GatewayTarget,
        val flow: Flow<LLMStreamChunk>,
    )

    /** Resolve + build the upstream request. Throws 404 before any worker is dispatched. */
    suspend fun prepare(req: GatewayChatRequest): Prepared {
        val target = resolveTarget(req.model)
            ?: throw GatewayException(
                status = 404,
                type = "not_found_error",
                message = "model '${req.model}' is not available on this gateway. " +
                    "List models with GET /v1/models (or /api/tags).",
                code = "model_not_found",
            )

        val messages = GatewayMessageMapper.toAppMessages(req)
        val tools = req.tools.mapNotNull { GatewayMessageMapper.toAgentTool(it) }

        val flow = ProviderExecutionGateway.stream(
            context = context,
            instance = target.instance,
            model = target.entry.model,
            messages = messages,
            systemPrompt = req.systemPrompt,
            maxTokens = req.maxTokens,
            temperature = req.temperature,
            imageParts = emptyList(),
            tools = tools,
            thinkingLevel = if (req.thinkingRequested) pickThinkingLevel(target.entry) else ThinkingLevel.OFF,
        )
        return Prepared(target, flow)
    }

    /**
     * Execute [req] and aggregate the stream into one result.
     *
     * @param onChunk invoked once per streamed chunk — the streaming router
     *   passes its frame writer here.
     */
    suspend fun execute(
        req: GatewayChatRequest,
        onChunk: (LLMStreamChunk) -> Unit = {},
    ): GatewayResult {
        val prepared = prepare(req)
        var text = StringBuilder()
        var reasoning = StringBuilder()
        val calls = LinkedHashMap<String, GatewayToolCall>()
        var stop = "end_turn"
        var inTok = 0
        var outTok = 0
        var truncated = false

        prepared.flow.collect { chunk ->
            onChunk(chunk)
            when (chunk) {
                is LLMStreamChunk.Text -> text.append(chunk.text)
                is LLMStreamChunk.ThinkingDelta -> reasoning.append(chunk.text)
                is LLMStreamChunk.ReasoningContent ->
                    if (reasoning.isEmpty()) reasoning.append(chunk.content)
                is LLMStreamChunk.ToolUseStart -> calls.putIfAbsent(
                    chunk.id.ifBlank { "call_${calls.size}" },
                    GatewayToolCall(chunk.id, chunk.name, ""),
                )
                is LLMStreamChunk.ToolInputDelta -> {
                    // `accumulated` is the whole JSON so far; keep the latest per id.
                    val key = chunk.id.ifBlank { calls.keys.lastOrNull() ?: "call_0" }
                    val existing = calls[key]
                    calls[key] = existing?.copy(argsJson = chunk.accumulated)
                        ?: GatewayToolCall(key, "", chunk.accumulated)
                }
                is LLMStreamChunk.ToolCallComplete -> {
                    val key = chunk.id.ifBlank { calls.keys.lastOrNull() ?: "call_${calls.size}" }
                    val existing = calls[key]
                    calls[key] = GatewayToolCall(
                        id = key,
                        name = chunk.name.ifBlank { existing?.name.orEmpty() },
                        argsJson = chunk.args.toString(),
                    )
                }
                is LLMStreamChunk.Usage -> {
                    inTok = chunk.usage.inputTokens
                    outTok = chunk.usage.outputTokens
                }
                is LLMStreamChunk.Finished -> {
                    truncated = chunk.truncated
                    stop = GatewayErrors.canonicalStop(chunk.stopReason, calls.isNotEmpty())
                }
                else -> Unit
            }
        }

        // Fill ids/names for calls that only ever streamed arguments.
        val finalCalls = calls.values.map { c ->
            c.copy(id = c.id.ifBlank { GatewayJson.id("call") }, name = c.name)
        }.filter { it.name.isNotBlank() }

        val stopReason = GatewayErrors.canonicalStop(stop, finalCalls.isNotEmpty())
        return GatewayResult(
            text = text.toString(),
            reasoning = reasoning.toString(),
            toolCalls = finalCalls,
            stopReason = if (truncated && stopReason == "end_turn") "max_tokens" else stopReason,
            inputTokens = inTok,
            outputTokens = outTok,
            model = req.model,
        )
    }

    /**
     * Model routing. `entry.model.id` is matched first; a `<label>/<id>` form
     * (or the model-use convention `instance label + "/" + model id`) narrows to
     * one instance when two expose the same id. Unknown → 404 with a hint.
     */
    fun resolveTarget(modelId: String): GatewayTarget? {
        val entries = visibleEntries()
        if (entries.isEmpty()) return null
        var requested = modelId.trim()
        if (requested.isEmpty()) return null
        // An empty model name ("", "default", "minis", "latest") means "you pick":
        // honour the user's configured gateway default before the catalogue.
        if (requested.lowercase() in setOf("", "default", "minis", "latest")) {
            val preferred = GatewaySettings.defaultModel
            if (!preferred.isNullOrBlank()) {
                val hit = entries.firstOrNull { it.model.id == preferred }
                if (hit != null) return GatewayTarget(repository.instance(hit.providerInstanceId)!!, hit)
            }
            if (requested.isEmpty()) {
                val first = entries.first()
                return GatewayTarget(repository.instance(first.providerInstanceId)!!, first)
            }
        }

        fun instanceOf(entry: com.openminis.app.data.model.ModelEntry) =
            repository.instance(entry.providerInstanceId)

        // 1) explicit "<instance label>/<model id>" (split on the FIRST slash)
        val slash = requested.indexOf('/')
        if (slash > 0 && slash < requested.length - 1) {
            val label = requested.substring(0, slash)
            val id = requested.substring(slash + 1)
            entries.firstOrNull { entry ->
                val inst = instanceOf(entry)
                inst != null && inst.label.equals(label, ignoreCase = true) && entry.model.id == id
            }?.let { return GatewayTarget(instanceOf(it)!!, it) }
        }
        // 2) exact model id
        entries.firstOrNull { it.model.id == requested }?.let { return GatewayTarget(instanceOf(it)!!, it) }
        // 3) exact display name
        entries.firstOrNull { it.model.displayName == requested }?.let { return GatewayTarget(instanceOf(it)!!, it) }
        // 4) case-insensitive exact id (clients frequently normalise casing)
        entries.firstOrNull { it.model.id.equals(requested, ignoreCase = true) }
            ?.let { return GatewayTarget(instanceOf(it)!!, it) }
        // 5) vendor-prefixed guesses clients hard-code ("gpt-4", "claude-3-5-sonnet")
        //    resolve to the single configured model when there is only one.
        if (entries.size == 1) return GatewayTarget(instanceOf(entries[0])!!, entries[0])
        // 6) gateway default as last resort — a client that only ever sends its
        //    own model name (common with coding tools) still gets an answer.
        GatewaySettings.defaultModel?.let { preferred ->
            entries.firstOrNull { it.model.id == preferred }?.let {
                return GatewayTarget(instanceOf(it)!!, it)
            }
        }
        return null
    }

    /** Catalogue narrowed by [GatewaySettings.modelAllowlist] (empty = all). */
    private fun visibleEntries(): List<com.openminis.app.data.model.ModelEntry> {
        val all = repository.resolvedAgentLoopEntries()
        val allow = GatewaySettings.modelAllowlist
        if (allow.isEmpty()) return all
        val allowLower = allow.map { it.lowercase() }
        return all.filter { entry ->
            entry.model.id.lowercase() in allowLower || entry.model.displayName.lowercase() in allowLower
        }
    }

    data class GatewayTarget(
        val instance: com.openminis.app.data.model.ProviderInstance,
        val entry: com.openminis.app.data.model.ModelEntry,
    )

    /** Models the gateway advertises — the agent-loop catalogue, deduplicated. */
    fun catalogue(): List<GatewayCatalogEntry> {
        val seen = LinkedHashSet<String>()
        val out = mutableListOf<GatewayCatalogEntry>()
        for (entry in visibleEntries()) {
            val instance = repository.instance(entry.providerInstanceId) ?: continue
            if (!seen.add(entry.model.id)) continue
            out += GatewayCatalogEntry(
                modelId = entry.model.id,
                displayName = entry.model.displayName,
                instanceLabel = instance.label,
                provider = entry.model.provider,
                contextWindow = entry.model.contextWindow,
                maxOutputTokens = entry.model.maxOutputTokens,
                inputModalities = entry.model.inputModalities.orEmpty(),
                outputModalities = entry.model.outputModalities.orEmpty(),
                supportsTools = entry.model.supportsReasoning ?: true,
            )
        }
        return out
    }

    private fun pickThinkingLevel(entry: com.openminis.app.data.model.ModelEntry): ThinkingLevel {
        // `effectiveMaxThinkingLevel` is the app's four-level resolver (user
        // override > catalog ceiling). A client that merely asks for "thinking"
        // gets MEDIUM, never above what the model can actually take — the
        // provider clamps again at send time, but resolving here keeps the
        // gateway's own logs honest about what was requested.
        val ceiling = entry.effectiveMaxThinkingLevel
        val wanted = if (ceiling.rank < ThinkingLevel.MEDIUM.rank) ceiling else ThinkingLevel.MEDIUM
        return if (wanted.rank > ceiling.rank) ceiling else wanted
    }

    /** Catalogue row in the app's neutral terms, rendered per dialect by the router. */
    data class GatewayCatalogEntry(
        val modelId: String,
        val displayName: String,
        val instanceLabel: String,
        val provider: String,
        val contextWindow: Int?,
        val maxOutputTokens: Int?,
        val inputModalities: List<String>,
        val outputModalities: List<String>,
        val supportsTools: Boolean,
    ) {
        /** Anthropic's `/v1/models` id must be ASCII-safe; the display name carries the label. */
        val qualifiedId: String get() = "$instanceLabel/$modelId"
    }

    companion object {
        private const val TAG = "MinisGatewayEngine"

        fun logFailure(req: GatewayChatRequest, t: Throwable) {
            Log.w(TAG, "${req.protocol} model=${req.model} failed: ${t.message}")
        }
    }
}

/**
 * [T-local-llm-gateway] Normalised request → app provider types.
 *
 * Extracted from [GatewayEngine] as a Context-free object so the mapping — the
 * part most likely to silently corrupt a conversation — is unit-testable on the
 * JVM without a provider, a worker process or an Android runtime. Mirrors how
 * ProviderExecutionGateway exposes `parseNonStreamingResult` as a pure function
 * for the same reason.
 */
internal object GatewayMessageMapper {

    private const val TAG = "GatewayMessageMapper"

    /**
     * Map normalised turns onto the app's [LLMMessage] shape.
     *
     * Tool traffic must land as content parts, not text: the app's providers
     * read assistant `tool_use` and user `tool_result` from [AgentContentPart],
     * and [com.openminis.app.provider.sanitizeToolPairing] drops any `tool_use`
     * whose answer is not in the IMMEDIATELY following user message — so
     * consecutive tool results are grouped into ONE user message, exactly like
     * ChatViewModel does.
     */
    fun toAppMessages(req: GatewayChatRequest): List<LLMMessage> {
        val out = mutableListOf<LLMMessage>()
        val pendingResults = mutableListOf<AgentContentPart.ToolResult>()
        var pendingText = StringBuilder()

        fun flushResults() {
            if (pendingResults.isEmpty()) return
            out += LLMMessage(
                role = LLMMessage.Role.USER,
                content = pendingText.toString(),
                contentParts = pendingResults.toList(),
            )
            pendingResults.clear()
            pendingText = StringBuilder()
        }

        for (m in req.messages) {
            if (m.role == "tool") {
                pendingResults += AgentContentPart.ToolResult(
                    id = m.toolCallId ?: m.toolName ?: "",
                    name = m.toolName ?: "",
                    content = m.text,
                    isError = m.toolIsError,
                )
                continue
            }
            flushResults()
            val parts = mutableListOf<AgentContentPart>()
            if (m.role == "assistant" && m.toolCalls.isNotEmpty()) {
                if (m.text.isNotEmpty()) parts += AgentContentPart.Text(m.text)
                for (c in m.toolCalls) {
                    val input = try { JSONObject(GatewayResponseCodec.sanitizeArgs(c.argsJson)) }
                    catch (_: Exception) { JSONObject() }
                    parts += AgentContentPart.ToolUse(id = c.id, name = c.name, input = input)
                }
            }
            val images = m.images.mapNotNull { img -> decodeImage(img.base64)?.let { LLMMessage.ImagePart(it, img.mimeType) } }
            if (images.size != m.images.size && m.images.isNotEmpty()) {
                Log.w(TAG, "dropped ${m.images.size - images.size} undecodable image part(s) on a ${m.role} turn")
            }
            // Images also ride as ImageData PARTS whenever any part exists,
            // mirroring ChatViewModel (which fills BOTH imageParts and
            // contentParts). The OpenAI/Anthropic encoders walk contentParts as
            // soon as the array is non-empty and ignore the top-level list, so a
            // text + image turn would lose its image otherwise.
            if (images.isNotEmpty()) {
                if (parts.isEmpty() && m.text.isNotEmpty()) parts += AgentContentPart.Text(m.text)
                images.forEach { parts += AgentContentPart.ImageData(it.data, it.mimeType, it.linuxPath) }
            }
            out += LLMMessage(
                role = if (m.role == "assistant") LLMMessage.Role.ASSISTANT else LLMMessage.Role.USER,
                content = m.text,
                imageParts = images,
                contentParts = parts,
                reasoningContent = m.reasoning?.takeIf { it.isNotBlank() && m.role == "assistant" },
            )
        }
        flushResults()
        return out
    }

    /**
     * JSON-Schema tool declaration → [AgentToolDefinition].
     *
     * The provider layer rebuilds each dialect's own schema shape from the flat
     * property map, so this keeps the client's property ORDER
     * (`propertyOrdering`) — Gemini rejects a schema whose declared order is
     * missing, and Anthropic relays are order-sensitive in practice.
     */
    /**
     * Decode a base64 image payload. [java.util.Base64] is tried first because
     * that is what the :modelservice worker encodes with, and it is the only one
     * that also works in JVM tests (the Android framework stub returns null for
     * un-mocked statics); [android.util.Base64] stays as a fallback for the
     * variants it accepts and the strict RFC decoder rejects.
     */
    private fun decodeImage(raw: String): ByteArray? {
        val cleaned = raw.trim()
        if (cleaned.isEmpty()) return null
        runCatching { java.util.Base64.getDecoder().decode(cleaned) }.getOrNull()?.let {
            if (it.isNotEmpty()) return it
        }
        runCatching { Base64.decode(cleaned, Base64.DEFAULT) }.getOrNull()?.let {
            if (it.isNotEmpty()) return it
        }
        return null
    }

    fun toAgentTool(tool: GatewayTool): AgentToolDefinition? {
        if (tool.name.isBlank()) return null
        val schema = try { JSONObject(tool.schemaJson.ifBlank { "{}" }) } catch (_: Exception) { JSONObject() }
        val properties = schema.optJSONObject("properties") ?: JSONObject()
        val params = LinkedHashMap<String, AgentToolParam>()
        // Order of declaration matters downstream (Gemini's `propertyOrdering`).
        // org.json's key iteration order is insertion order on Android but
        // hash order in JVM tests, so prefer the client's explicit ordering when
        // one is present (Minis' own schema shape carries it), then the
        // required list, and only then whatever order iteration yields.
        val declaredOrder = schema.optJSONArray("propertyOrdering")?.let { a ->
            (0 until a.length()).map { a.optString(it) }
        } ?: emptyList()
        val requiredKeys = schema.optJSONArray("required")?.let { r ->
            (0 until r.length()).map { r.optString(it) }.filter { it.isNotBlank() }
        } ?: emptyList()
        val seenKeys = LinkedHashSet<String>()
        properties.keys().forEach { seenKeys.add(it) }
        val ordering = (declaredOrder + requiredKeys + seenKeys.toList())
            .filter { seenKeys.contains(it) }.distinct().toMutableList()
        ordering.forEach { key ->
            val node = properties.optJSONObject(key) ?: return@forEach
            params[key] = AgentToolParam(
                type = node.optString("type", "string"),
                description = node.optString("description", ""),
                enumValues = node.optJSONArray("enum")?.let { e ->
                    (0 until e.length()).map { e.optString(it) }
                },
                items = AgentToolSchema.fromJson(node.optJSONObject("items")),
            )
        }
        return AgentToolDefinition(
            name = tool.name,
            description = tool.description,
            parameters = params,
            required = requiredKeys,
            propertyOrdering = ordering.ifEmpty { null },
        )
    }
}
