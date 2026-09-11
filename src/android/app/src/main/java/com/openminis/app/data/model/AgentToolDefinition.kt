package com.openminis.app.data.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * Provider-agnostic tool definition. Each tool registers with this structure,
 * and providers convert it to their native format (Anthropic input_schema,
 * Gemini function_declarations, OpenAI function calling).
 */
data class AgentToolDefinition(
    val name: String,
    val description: String,
    val parameters: Map<String, AgentToolParam>,
    val required: List<String> = emptyList(),
    val propertyOrdering: List<String>? = null,
) {
    /** Anthropic format: {name, description, input_schema: {type:object, properties, required}} */
    fun toAnthropicJson(): JSONObject {
        val props = JSONObject()
        for ((key, param) in parameters) {
            props.put(key, param.toJson())
        }
        val schema = JSONObject().apply {
            put("type", "object")
            put("properties", props)
            if (required.isNotEmpty()) put("required", JSONArray(required))
        }
        return JSONObject().apply {
            put("name", name)
            put("description", description)
            put("input_schema", schema)
        }
    }

    /** Gemini format: {name, description, parameters: {type:OBJECT, properties, required}} */
    fun toGeminiJson(): JSONObject {
        val props = JSONObject()
        for ((key, param) in parameters) {
            props.put(key, param.toGeminiJson())
        }
        val params = JSONObject().apply {
            put("type", "OBJECT")
            put("properties", props)
            if (required.isNotEmpty()) put("required", JSONArray(required))
            if (propertyOrdering != null) put("propertyOrdering", JSONArray(propertyOrdering))
        }
        return JSONObject().apply {
            put("name", name)
            put("description", description)
            put("parameters", params)
        }
    }

    /** OpenAI format: {type:function, function: {name, description, parameters: {type:object, ...}}} */
    fun toOpenAIJson(): JSONObject {
        val props = JSONObject()
        for ((key, param) in parameters) {
            props.put(key, param.toJson())
        }
        val params = JSONObject().apply {
            put("type", "object")
            put("properties", props)
            if (required.isNotEmpty()) put("required", JSONArray(required))
        }
        return JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", name)
                put("description", description)
                put("parameters", params)
            })
        }
    }
}

data class AgentToolParam(
    val type: String,
    val description: String,
    val enumValues: List<String>? = null,
    /**
     * Schema for array item types (Gemini Schema requires `items` on ARRAY
     * nodes — v1internal rejects `function_declarations[n].parameters
     * .properties[todos].items: missing field` otherwise). Nested schemas
     * are expressed with [AgentToolSchema] so OBJECT items can carry their
     * own properties / required / propertyOrdering.
     */
    val items: AgentToolSchema? = null,
) {
    private fun JSONObject.putCommon(gemini: Boolean) {
        put("type", if (gemini) type.uppercase() else type)
        put("description", description)
        if (enumValues != null) put("enum", JSONArray(enumValues))
        if (gemini && items != null) put("items", items.toGeminiJson())
    }

    fun toJson(): JSONObject = JSONObject().apply { putCommon(gemini = false) }

    fun toGeminiJson(): JSONObject = JSONObject().apply { putCommon(gemini = true) }
}

/**
 * Recursive schema node for structured [AgentToolParam.items]. Mirrors the
 * Gemini Schema subset RikkaMinis actually emits (object / string with
 * enum); extend as tools gain richer parameter shapes.
 */
data class AgentToolSchema(
    val type: String,
    val description: String? = null,
    val properties: Map<String, AgentToolParam>? = null,
    val required: List<String> = emptyList(),
    val propertyOrdering: List<String>? = null,
) {
    fun toGeminiJson(): JSONObject = JSONObject().apply {
        put("type", type.uppercase())
        if (description != null) put("description", description)
        if (properties != null) {
            val props = JSONObject()
            for ((key, param) in properties) props.put(key, param.toGeminiJson())
            put("properties", props)
            if (required.isNotEmpty()) put("required", JSONArray(required))
            if (propertyOrdering != null) put("propertyOrdering", JSONArray(propertyOrdering))
        }
    }

    /** Plain (lowercase, no propertyOrdering) variant for OpenAI/Anthropic. */
    fun toJson(): JSONObject = JSONObject().apply {
        put("type", type)
        if (description != null) put("description", description)
        if (properties != null) {
            val props = JSONObject()
            for ((key, param) in properties) props.put(key, param.toJson())
            put("properties", props)
            if (required.isNotEmpty()) put("required", JSONArray(required))
        }
    }

    companion object {
        /**
         * Recursive parser for tool-definition JSON (lowercase OpenAI-ish
         * shape, as reconstructed by ModelExecutionService for nested
         * subagent tool declarations). Lowercase types normalise through
         * toGeminiJson()'s uppercase mapping on the way out.
         */
        fun fromJson(node: JSONObject?): AgentToolSchema? {
            node ?: return null
            val propsRaw = node.optJSONObject("properties")
            val props = propsRaw?.let { p ->
                val map = linkedMapOf<String, AgentToolParam>()
                val keys = p.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val n = p.optJSONObject(k) ?: continue
                    map[k] = AgentToolParam(
                        type = n.optString("type", "string"),
                        description = n.optString("description", ""),
                        enumValues = n.optJSONArray("enum")
                            ?.let { e -> (0 until e.length()).map { e.getString(it) } },
                        items = fromJson(n.optJSONObject("items")),
                    )
                }
                map
            }
            return AgentToolSchema(
                type = node.optString("type", "object"),
                description = node.optString("description").ifEmpty { null },
                properties = props,
                required = node.optJSONArray("required")
                    ?.let { r -> (0 until r.length()).map { r.getString(it) } }
                    ?: emptyList(),
            )
        }
    }
}
