package com.openminis.app.gateway

import org.json.JSONArray
import org.json.JSONObject


/**
 * [T-local-llm-gateway] Per-request streaming encoder.
 *
 * One instance per in-flight stream. Fed the app's provider-agnostic
 * [com.openminis.app.data.model.LLMStreamChunk] events and emits ready-to-send
 * SSE payloads in the client's dialect. Each method returns the list of `data:`
 * payloads to write (Anthropic needs an `event:` name too, so those frames are
 * returned as [SseFrame]).
 *
 * Dialect constraints this handles:
 *  - OpenAI: every chunk repeats `{id, object, created, model}` and carries a
 *    `choices[].delta`; the stream is terminated by a literal `[DONE]` sentinel.
 *  - Anthropic: a state machine — `message_start`, then one
 *    `content_block_start`/`_delta`/`_stop` triple per block (thinking, text,
 *    each tool_use), then `message_delta` + `message_stop`. Blocks may not
 *    interleave, so opening a new one closes the previous.
 *  - Gemini: `alt=sse` frames each carry a whole `GenerateContentResponse`
 *    candidate chunk (there is no delta envelope); tool calls are emitted as a
 *    single complete `functionCall` part in the final chunk.
 *  - Ollama: NOT SSE — newline-delimited JSON objects (`{"model":..,"message":{..},"done":false}`),
 *    so payloads go out raw with no `data:` prefix ([rawNdjson] tells the writer).
 *  - Responses API: typed events (`response.output_text.delta`, …) wrapped in
 *    `event:`/`data:` frames whose `data.type` mirrors the event name.
 */
internal class GatewayStreamWriter(
    private val req: GatewayChatRequest,
    private val model: String,
) {
    /** Anthropic-style frame with an explicit event name; others leave it null. */
    data class SseFrame(val event: String?, val data: String)

    /** Ollama answers with NDJSON, so the writer must not prefix `data:`. */
    val rawNdjson: Boolean get() = req.protocol == GatewayProtocol.ollama

    private var created = System.currentTimeMillis() / 1000
    private val started = System.nanoTime()

    // Accumulated output, for the terminal frames.
    private val textBuf = StringBuilder()
    private val reasoningBuf = StringBuilder()
    private var lastStop: String = "end_turn"
    private var inTokens = 0
    private var outTokens = 0
    private var closed = false

    private class ToolSlot(val index: Int) {
        var id: String = ""
        var name: String = ""
        /** How many argument characters have already gone out on the wire. */
        var emittedArgs = 0
        /**
         * True once ANY argument increment was streamed. Distinct from
         * `emittedArgs > 0` only in intent, but the terminal frame needs the
         * explicit flag: backends that never stream deltas (they hand the parsed
         * object straight to ToolCallComplete) must still get their arguments
         * emitted, and a slot whose final length happens to be 0 cannot tell the
         * two cases apart by counting characters.
         */
        var argsStreamed = false
    }

    /**
     * The app's chunk protocol keys tool calls by id, OpenAI's wire format by
     * ordinal. This map is the translation, and it lives here (per stream) so
     * two concurrent requests can never cross slots.
     */
    private val slots = LinkedHashMap<String, ToolSlot>()

    private fun slotFor(id: String, name: String): ToolSlot {
        val key = id.ifBlank { "anon_${slots.size}" }
        val slot = slots.getOrPut(key) { ToolSlot(slots.size) }
        if (id.isNotBlank()) slot.id = id
        if (name.isNotBlank()) slot.name = name
        return slot
    }

    private fun slotForId(id: String): ToolSlot? =
        slots[id.ifBlank { "anon_0" }] ?: slots.values.lastOrNull()

    // Anthropic block bookkeeping.
    private var anthBlockIndex = 0
    private var anthOpenBlock: String? = null   // "thinking" | "text" | "tool"
    private var anthToolBlock = HashMap<Int, Int>()

    // Responses-API bookkeeping.
    private var respOutputIndex = 0
    private var respTextItemId = "msg_0"
    private var respTextOpen = false

    fun open(): List<SseFrame> = when (req.protocol) {
        GatewayProtocol.anthropic -> listOf(
            SseFrame("message_start", JSONObject().apply {
                put("type", "message_start")
                put("message", JSONObject().apply {
                    put("id", req.requestId)
                    put("type", "message")
                    put("role", "assistant")
                    put("content", JSONArray())
                    put("model", model)
                    put("stop_reason", JSONObject.NULL)
                    put("stop_sequence", JSONObject.NULL)
                    put("usage", JSONObject().put("input_tokens", 0).put("output_tokens", 0))
                })
            }.toString()),
            SseFrame("ping", """{"type":"ping"}"""),
        )
        GatewayProtocol.openaiResponses -> listOf(
            respEvent("response.created", JSONObject().put("type", "response.created").put(
                "response", JSONObject().apply {
                    put("id", req.requestId)
                    put("object", "response")
                    put("status", "in_progress")
                    put("model", model)
                    put("output", JSONArray())
                },
            )),
            respEvent("response.in_progress", JSONObject().put("type", "response.in_progress").put(
                "response", JSONObject().put("id", req.requestId).put("status", "in_progress"),
            )),
        )
        // OpenAI / Gemini / Ollama have no explicit open frame; the first
        // content chunk doubles as it. Emit an OpenAI role delta so clients
        // that build the message incrementally see "assistant" first.
        GatewayProtocol.openai -> listOf(SseFrame(null, GatewayStreamFrames.openaiRoleChunk(req.requestId, created, model)))
        GatewayProtocol.openaiCompletions -> emptyList()
        else -> emptyList()
    }

    fun onReasoning(delta: String): List<SseFrame> {
        if (delta.isEmpty()) return emptyList()
        reasoningBuf.append(delta)
        return when (req.protocol) {
            // OpenAI-compat backends (DeepSeek/V1/Kimi) use reasoning_content in the delta.
            GatewayProtocol.openai -> listOf(SseFrame(null,
                GatewayStreamFrames.openaiChunk(req.requestId, created, model, reasoningDelta = delta)))
            GatewayProtocol.anthropic -> {
                val out = mutableListOf<SseFrame>()
                if (anthOpenBlock != "thinking") {
                    out += anthClose()
                    anthOpenBlock = "thinking"
                    out += SseFrame("content_block_start", JSONObject().apply {
                        put("type", "content_block_start")
                        put("index", anthBlockIndex)
                        put("content_block", JSONObject().apply {
                            put("type", "thinking")
                            put("thinking", "")
                            put("signature", "")
                        })
                    }.toString())
                }
                out += SseFrame("content_block_delta", anthDelta(JSONObject().apply {
                    put("type", "thinking_delta"); put("thinking", delta)
                }))
                out
            }
            GatewayProtocol.gemini -> listOf(SseFrame(null, GatewayStreamFrames.geminiChunk(model, text = null, thought = delta)))
            GatewayProtocol.ollama -> listOf(SseFrame(null, ollamaFrame(thinking = delta)))
            GatewayProtocol.openaiCompletions -> listOf(SseFrame(null,
                GatewayStreamFrames.completionChunk(req.requestId, created, model, "")))
            GatewayProtocol.openaiResponses -> listOf(respEvent("response.reasoning_summary_text.delta",
                JSONObject().apply {
                    put("type", "response.reasoning_summary_text.delta")
                    put("item_id", respTextItemId)
                    put("output_index", respOutputIndex)
                    put("summary_index", 0)
                    put("delta", delta)
                }))
        }
    }

    /**
     * Emit [delta] only when no reasoning has streamed yet. Providers send both
     * a live ThinkingDelta stream AND a final accumulated ReasoningContent for
     * history replay; forwarding the replay a second time would duplicate the
     * thinking block on the client.
     */
    fun onReasoningIfAbsent(delta: String): List<SseFrame> =
        if (reasoningBuf.isEmpty()) onReasoning(delta) else emptyList()

    fun onText(delta: String): List<SseFrame> {
        if (delta.isEmpty()) return emptyList()
        textBuf.append(delta)
        return when (req.protocol) {
            GatewayProtocol.openai -> listOf(SseFrame(null,
                GatewayStreamFrames.openaiChunk(req.requestId, created, model, textDelta = delta)))
            GatewayProtocol.openaiCompletions -> listOf(SseFrame(null,
                GatewayStreamFrames.completionChunk(req.requestId, created, model, delta)))
            GatewayProtocol.anthropic -> {
                val out = mutableListOf<SseFrame>()
                if (anthOpenBlock != "text") {
                    out += anthClose()
                    anthOpenBlock = "text"
                    out += SseFrame("content_block_start", JSONObject().apply {
                        put("type", "content_block_start")
                        put("index", anthBlockIndex)
                        put("content_block", JSONObject().put("type", "text").put("text", ""))
                    }.toString())
                }
                out += SseFrame("content_block_delta", anthDelta(JSONObject().apply {
                    put("type", "text_delta"); put("text", delta)
                }))
                out
            }
            GatewayProtocol.gemini -> listOf(SseFrame(null, GatewayStreamFrames.geminiChunk(model, text = delta, thought = null)))
            GatewayProtocol.ollama -> listOf(SseFrame(null, ollamaFrame(text = delta)))
            GatewayProtocol.openaiResponses -> {
                val out = mutableListOf<SseFrame>()
                if (!respTextOpen) {
                    respTextOpen = true
                    respTextItemId = "msg_$respOutputIndex"
                    out += respEvent("response.output_item.added", JSONObject().apply {
                        put("type", "response.output_item.added")
                        put("output_index", respOutputIndex)
                        put("item", JSONObject().apply {
                            put("id", respTextItemId)
                            put("type", "message")
                            put("role", "assistant")
                            put("content", JSONArray())
                        })
                    })
                    out += respEvent("response.content_part.added", JSONObject().apply {
                        put("type", "response.content_part.added")
                        put("item_id", respTextItemId)
                        put("output_index", respOutputIndex)
                        put("content_index", 0)
                        put("part", JSONObject().put("type", "output_text").put("text", ""))
                    })
                }
                out += respEvent("response.output_text.delta", JSONObject().apply {
                    put("type", "response.output_text.delta")
                    put("item_id", respTextItemId)
                    put("output_index", respOutputIndex)
                    put("content_index", 0)
                    put("delta", delta)
                })
                out
            }
        }
    }

    /** A new tool call started; its ordinal is assigned by [slotFor]. */
    fun onToolStart(id: String, name: String): List<SseFrame> {
        val slot = slotFor(id, name)
        val slotIndex = slot.index
        return when (req.protocol) {
            GatewayProtocol.openai -> listOf(SseFrame(null,
                GatewayStreamFrames.openaiChunk(req.requestId, created, model, toolStart = Triple(slotIndex, slot.id, slot.name))))
            GatewayProtocol.anthropic -> {
                val out = anthClose()
                anthOpenBlock = "tool"
                anthToolBlock[slotIndex] = anthBlockIndex
                out + SseFrame("content_block_start", JSONObject().apply {
                    put("type", "content_block_start")
                    put("index", anthBlockIndex)
                    put("content_block", JSONObject().apply {
                        put("type", "tool_use")
                        put("id", slot.id.ifBlank { GatewayJson.id("toolu") })
                        put("name", slot.name)
                        put("input", JSONObject())
                    })
                }.toString())
            }
            GatewayProtocol.openaiResponses -> {
                val callId = slot.id.ifBlank { "call_$slotIndex" }
                slot.id = callId
                respOutputIndex++
                respEvent("response.output_item.added", JSONObject().apply {
                    put("type", "response.output_item.added")
                    put("output_index", respOutputIndex)
                    put("item", JSONObject().apply {
                        put("type", "function_call")
                        put("call_id", callId)
                        put("name", slot.name)
                        put("arguments", "")
                    })
                }).let { listOf(it) }
            }
            // Gemini/Ollama stream complete calls, so nothing to open here.
            else -> emptyList()
        }
    }

    /**
     * [accumulated] is the app's contract — the whole JSON string so far, not a
     * delta. The wire formats want increments, so diff against what was sent.
     */
    fun onToolArgs(id: String, accumulated: String): List<SseFrame> {
        val slot = slotForId(id) ?: return emptyList()
        val slotIndex = slot.index
        if (accumulated.length <= slot.emittedArgs) return emptyList()
        val increment = accumulated.substring(slot.emittedArgs)
        slot.emittedArgs = accumulated.length
        slot.argsStreamed = true
        return when (req.protocol) {
            GatewayProtocol.openai -> listOf(SseFrame(null,
                GatewayStreamFrames.openaiChunk(req.requestId, created, model, toolArgs = Pair(slotIndex, increment))))
            GatewayProtocol.anthropic -> {
                val blockIndex = anthToolBlock[slotIndex] ?: return emptyList()
                listOf(SseFrame("content_block_delta", JSONObject().apply {
                    put("type", "content_block_delta")
                    put("index", blockIndex)
                    put("delta", JSONObject().put("type", "input_json_delta").put("partial_json", increment))
                }.toString()))
            }
            GatewayProtocol.openaiResponses -> listOf(respEvent("response.function_call_arguments.delta",
                JSONObject().apply {
                    put("type", "response.function_call_arguments.delta")
                    put("item_id", slot.id)
                    put("output_index", respOutputIndex)
                    put("delta", increment)
                }))
            else -> emptyList()
        }
    }

    /** A tool call reached its final parsed arguments. */
    fun onToolComplete(id: String, name: String, args: JSONObject): List<SseFrame> {
        val slot = slotFor(id, name)
        val slotIndex = slot.index
        val finalArgs = args.toString()
        // Record the final arguments so a client that only receives the terminal
        // frame (Gemini/Ollama) still gets a complete call.
        if (slot.emittedArgs < finalArgs.length) slot.emittedArgs = finalArgs.length
        return when (req.protocol) {
            // Gemini/Ollama only report complete calls, so this is where their
            // functionCall part is emitted.
            GatewayProtocol.gemini -> listOf(SseFrame(null, GatewayStreamFrames.geminiChunk(
                model, text = null, thought = null,
                toolCall = GatewayToolCall(slot.id.ifBlank { "call_${slot.index}" }, slot.name, finalArgs),
            )))
            GatewayProtocol.ollama -> listOf(SseFrame(null, JSONObject().apply {
                put("model", model); put("created_at", ollamaNow())
                put("message", JSONObject().apply {
                    put("role", "assistant")
                    put("content", "")
                    put("tool_calls", JSONArray().put(JSONObject().apply {
                        put("function", JSONObject().apply {
                            put("name", slot.name)
                            put("arguments", args)
                        })
                    }))
                })
                put("done", false)
                if (req.isGenerateApi) put("response", "")
            }.toString()))
            GatewayProtocol.openai -> {
                // Some backends never stream arguments deltas (they hand the
                // parsed object straight to ToolCallComplete); forward them once.
                if (!slot.argsStreamed && finalArgs != "{}") {
                    listOf(SseFrame(null, GatewayStreamFrames.openaiChunk(
                        req.requestId, created, model,
                        toolStart = Triple(slotIndex, slot.id, slot.name),
                        toolArgs = Pair(slotIndex, finalArgs),
                    )))
                } else emptyList()
            }
            GatewayProtocol.anthropic -> {
                // A backend that never sent ToolUseStart arrives here directly.
                // Anthropic has no "complete block" frame, so the block must be
                // opened on the fly or the call would vanish from the stream.
                val opened = if (anthToolBlock.containsKey(slotIndex)) emptyList() else onToolStart(id, name)
                val blockIndex = anthToolBlock[slotIndex] ?: return opened
                val delta = if (!slot.argsStreamed && finalArgs != "{}") {
                    listOf(SseFrame("content_block_delta", JSONObject().apply {
                        put("type", "content_block_delta")
                        put("index", blockIndex)
                        put("delta", JSONObject().put("type", "input_json_delta").put("partial_json", finalArgs))
                    }.toString()))
                } else emptyList()
                opened + delta
            }
            GatewayProtocol.openaiResponses -> listOf(respEvent("response.function_call_arguments.done",
                JSONObject().apply {
                    put("type", "response.function_call_arguments.done")
                    put("item_id", slot.id)
                    put("output_index", respOutputIndex)
                    put("arguments", finalArgs)
                }))
            GatewayProtocol.openaiCompletions -> emptyList()
        }
    }

    fun onUsage(input: Int, output: Int) {
        if (input > 0) inTokens = input
        if (output > 0) outTokens = output
    }

    /**
     * Terminal frames. [canonicalStop] is the app's stop reason; the sentinel
     * [DONE] for OpenAI is appended by the caller via [doneSentinel].
     */
    fun close(canonicalStop: String, truncated: Boolean): List<SseFrame> {
        if (closed) return emptyList()
        closed = true
        lastStop = canonicalStop
        val finish = if (truncated && textBuf.isEmpty() && slots.isEmpty()) "api_error" else
            GatewayErrors.stopReason(canonicalStop, req.protocol)
        return when (req.protocol) {
            GatewayProtocol.openai, GatewayProtocol.openaiCompletions -> listOf(SseFrame(null,
                GatewayStreamFrames.openaiChunk(req.requestId, created, model, finishReason = finish)))
            GatewayProtocol.anthropic -> {
                val out = anthClose().toMutableList()
                out += SseFrame("message_delta", JSONObject().apply {
                    put("type", "message_delta")
                    put("delta", JSONObject().apply {
                        put("stop_reason", GatewayErrors.stopReason(canonicalStop, GatewayProtocol.anthropic))
                        put("stop_sequence", JSONObject.NULL)
                    })
                    put("usage", JSONObject().put("output_tokens", outTokens))
                }.toString())
                out += SseFrame("message_stop", """{"type":"message_stop"}""")
                out
            }
            GatewayProtocol.gemini -> listOf(SseFrame(null, GatewayStreamFrames.geminiChunk(
                model, text = null, thought = null, finishReason = finish,
            )))
            GatewayProtocol.ollama -> listOf(SseFrame(null, JSONObject().apply {
                put("model", model); put("created_at", ollamaNow())
                put("message", JSONObject().put("role", "assistant").put("content", ""))
                if (req.isGenerateApi) put("response", "")
                put("done", true)
                put("done_reason", if (canonicalStop == "max_tokens") "length" else "stop")
                put("total_duration", (System.nanoTime() - started) / 1000)
                put("load_duration", 0L)
                put("prompt_eval_count", inTokens)
                put("eval_count", outTokens)
                put("eval_duration", (System.nanoTime() - started) / 1000)
            }.toString()))
            GatewayProtocol.openaiResponses -> {
                val out = mutableListOf<SseFrame>()
                if (respTextOpen) {
                    out += respEvent("response.output_text.done", JSONObject().apply {
                        put("type", "response.output_text.done")
                        put("item_id", respTextItemId)
                        put("output_index", respOutputIndex)
                        put("content_index", 0)
                        put("text", textBuf.toString())
                    })
                    out += respEvent("response.output_item.done", JSONObject().apply {
                        put("type", "response.output_item.done")
                        put("output_index", respOutputIndex)
                        put("item", JSONObject().apply {
                            put("id", respTextItemId)
                            put("type", "message")
                            put("role", "assistant")
                            put("status", "completed")
                            put("content", JSONArray().put(JSONObject().apply {
                                put("type", "output_text")
                                put("text", textBuf.toString())
                                put("annotations", JSONArray())
                            }))
                        })
                    })
                }
                slots.values.forEach { slot ->
                    out += respEvent("response.output_item.done", JSONObject().apply {
                        put("type", "response.output_item.done")
                        put("output_index", respOutputIndex)
                        put("item", JSONObject().apply {
                            put("type", "function_call")
                            put("call_id", slot.id)
                            put("name", slot.name)
                            put("arguments", "{}")
                            put("status", "completed")
                        })
                    })
                }
                out += respEvent("response.completed", JSONObject().apply {
                    put("type", "response.completed")
                    put("response", JSONObject().apply {
                        put("id", req.requestId)
                        put("object", "response")
                        put("status", if (canonicalStop == "max_tokens") "incomplete" else "completed")
                        put("model", model)
                        put("output_text", textBuf.toString())
                        put("usage", JSONObject().apply {
                            put("input_tokens", inTokens)
                            put("output_tokens", outTokens)
                            put("total_tokens", inTokens + outTokens)
                        })
                    })
                })
                out
            }
        }
    }

    /** OpenAI's stream terminator: a bare `data: [DONE]`, not a JSON object. */
    fun doneSentinel(): SseFrame? = when (req.protocol) {
        GatewayProtocol.openai, GatewayProtocol.openaiCompletions -> SseFrame(null, "[DONE]")
        else -> null
    }

    /** Final buffered result, for callers that need it (token accounting, logs). */
    fun snapshot(): GatewayResult = GatewayResult(
        text = textBuf.toString(),
        reasoning = reasoningBuf.toString(),
        toolCalls = slots.values.map { GatewayToolCall(it.id, it.name, "{}") }.toList(),
        stopReason = lastStop,
        inputTokens = inTokens,
        outputTokens = outTokens,
        model = model,
    )

    private fun anthDelta(delta: JSONObject) = JSONObject().apply {
        put("type", "content_block_delta")
        put("index", anthBlockIndex)
        put("delta", delta)
    }.toString()

    /** Close whatever Anthropic block is open; empty when nothing is. */
    private fun anthClose(): List<SseFrame> {
        if (anthOpenBlock == null) return emptyList()
        val frame = SseFrame("content_block_stop",
            """{"type":"content_block_stop","index":$anthBlockIndex}""")
        anthBlockIndex++
        anthOpenBlock = null
        return listOf(frame)
    }

    private fun respEvent(name: String, payload: JSONObject) =
        SseFrame(name, payload.put("type", name).toString())

    private fun ollamaFrame(text: String = "", thinking: String? = null): String = JSONObject().apply {
        put("model", model)
        put("created_at", ollamaNow())
        if (req.isGenerateApi) {
            put("response", text)
            if (thinking != null) put("thinking", thinking)
        } else {
            put("message", JSONObject().put("role", "assistant").put("content", text))
            if (thinking != null) put("thinking", thinking)
        }
        put("done", false)
    }.toString()

    private fun ollamaNow(): String = java.time.Instant.now().toString()
}

/** Frame builders shared between the stream writer and the error path. */
private object GatewayStreamFrames {

    fun openaiRoleChunk(id: String, created: Long, model: String): String =
        JSONObject().apply {
            put("id", id); put("object", "chat.completion.chunk"); put("created", created); put("model", model)
            put("choices", JSONArray().put(JSONObject().apply {
                put("index", 0)
                put("delta", JSONObject().put("role", "assistant").put("content", ""))
                put("logprobs", JSONObject.NULL)
                put("finish_reason", JSONObject.NULL)
            }))
        }.toString()

    fun openaiChunk(
        id: String,
        created: Long,
        model: String,
        textDelta: String? = null,
        reasoningDelta: String? = null,
        toolStart: Triple<Int, String, String>? = null,
        toolArgs: Pair<Int, String>? = null,
        finishReason: String? = null,
    ): String {
        val delta = JSONObject()
        if (textDelta != null) delta.put("content", textDelta)
        if (reasoningDelta != null) delta.put("reasoning_content", reasoningDelta)
        if (toolStart != null) {
            val (index, callId, name) = toolStart
            delta.put("tool_calls", JSONArray().put(JSONObject().apply {
                put("index", index)
                if (callId.isNotBlank()) put("id", callId)
                put("type", "function")
                put("function", JSONObject().apply {
                    if (name.isNotBlank()) put("name", name)
                    if (toolArgs == null) put("arguments", "")
                })
            }))
        }
        if (toolArgs != null) {
            val (index, increment) = toolArgs
            val toolCallJson = JSONObject().apply {
                put("index", index)
                put("function", JSONObject().put("arguments", increment))
            }
            if (toolStart != null) {
                // Overwrite the starter's empty arguments with the real increment.
                delta.getJSONArray("tool_calls").put(0, JSONObject().apply {
                    val (callIdx, callId, name) = toolStart
                    put("index", callIdx)
                    if (callId.isNotBlank()) put("id", callId)
                    put("type", "function")
                    put("function", JSONObject().apply {
                        if (name.isNotBlank()) put("name", name)
                        put("arguments", increment)
                    })
                })
            } else {
                delta.put("tool_calls", JSONArray().put(toolCallJson))
            }
        }
        return JSONObject().apply {
            put("id", id); put("object", "chat.completion.chunk"); put("created", created); put("model", model)
            put("choices", JSONArray().put(JSONObject().apply {
                put("index", 0)
                put("delta", delta)
                put("logprobs", JSONObject.NULL)
                put("finish_reason", finishReason ?: JSONObject.NULL)
            }))
        }.toString()
    }

    fun completionChunk(id: String, created: Long, model: String, text: String): String =
        JSONObject().apply {
            put("id", id); put("object", "text_completion"); put("created", created); put("model", model)
            put("choices", JSONArray().put(JSONObject().apply {
                put("index", 0)
                put("text", text)
                put("finish_reason", JSONObject.NULL)
            }))
        }.toString()

    /**
     * Gemini's SSE frame IS a full (partial) GenerateContentResponse — clients
     * concatenate `candidates[0].content.parts[].text` across frames, so a
     * `thought:true` part marks the reasoning channel.
     */
    fun geminiChunk(
        model: String,
        text: String?,
        thought: String?,
        toolCall: GatewayToolCall? = null,
        finishReason: String? = null,
    ): String {
        val parts = JSONArray()
        if (thought != null) parts.put(JSONObject().apply {
            put("text", thought); put("thought", true)
        })
        if (text != null) parts.put(JSONObject().put("text", text))
        if (toolCall != null) parts.put(JSONObject().apply {
            put("functionCall", JSONObject().apply {
                put("name", toolCall.name)
                put("args", try { JSONObject(toolCall.argsJson) } catch (_: Exception) { JSONObject() })
            })
        })
        return JSONObject().apply {
            put("candidates", JSONArray().put(JSONObject().apply {
                put("content", JSONObject().put("role", "model").put("parts", parts))
                if (finishReason != null) put("finishReason", finishReason)
                put("index", 0)
            }))
            put("modelVersion", model)
            put("responseId", GatewayJson.id("resp"))
        }.toString()
    }
}
