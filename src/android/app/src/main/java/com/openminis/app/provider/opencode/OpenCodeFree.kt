package com.openminis.app.provider.opencode

import com.openminis.app.data.model.LLMModel
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger

/**
 * [T-opencode-free] OpenCode free-tier ("keyless") wire contract.
 *
 * OpenCode publishes a no-account free tier at
 * `https://opencode.ai/inference/v1/chat/completions` (Bearer `public`). The
 * upstream accepts it ONLY from clients that look like the official CLI, gated
 * across three layers. This object owns the two that are pure data:
 * the identity headers and the body gate.
 *
 * Everything here was established by live probing against the real endpoint
 * (see `docs/opencode-free-tier.md` for the probe matrix), not by reading the
 * reference Go implementation — several of the "documented" rules turned out
 * to be stale, so the constants below encode what the endpoint actually does.
 */
object OpenCodeFree {

    /** Free-tier (keyless) inference endpoint. */
    const val BASE_URL = "https://opencode.ai/inference/v1"

    /** Cleartext marker the upstream expects in place of a real account key. */
    const val PUBLIC_KEY = "public"

    // ------------------------------------------------------- version identity

    /**
     * Pinned known-good CLI version — the last-resort fallback.
     *
     * The upstream enforces a **floor**, not an exact match, and it states that
     * floor in its own 426 body ("OpenCode 1.18.0 or newer is required…").
     * Probing confirmed there is **no upper bound**: `999.0.0` is accepted.
     * That is the property which makes the self-healing below sound — any
     * version at or above the floor works, so tracking the newest published
     * version is always safe.
     */
    const val DEFAULT_CLIENT_VERSION = "1.19.3"

    /** Lowest version the free tier has been observed to accept. */
    const val MIN_SUPPORTED_CLIENT_VERSION = "1.18.0"

    /**
     * Where the newest published CLI version is advertised. The `/latest`
     * endpoint is ~2 KB (the full packument is ~25 MB), which is why we read
     * the former.
     */
    const val VERSION_ENDPOINT = "https://registry.npmjs.org/opencode-ai/latest"

    /** UA channel segment (official release line). */
    private const val UA_CHANNEL = "prod"

    /** UA client segment. */
    private const val UA_CLIENT = "cli"

    /** `x-opencode-project` when there is no real project context (the CLI default). */
    private const val PROJECT = "global"

    private val adoptedVersion = java.util.concurrent.atomic.AtomicReference<String?>(null)
    private val advertisedVersion = java.util.concurrent.atomic.AtomicReference<String?>(null)
    private val refreshStarted = java.util.concurrent.atomic.AtomicBoolean(false)

    private val CORE_VERSION_RE = Regex("""\d+(?:\.\d+){1,3}""")
    private val REQUIRED_VERSION_RE =
        Regex("""[Oo]pen[Cc]ode\s+(\d+(?:\.\d+){1,3})""")

    /** Strip a leading `v` and any prerelease/build suffix: `v1.20.0-beta.3` -> `1.20.0`. */
    fun normalizeVersion(raw: String?): String? {
        val trimmed = raw?.trim()?.removePrefix("v") ?: return null
        val core = trimmed.substringBefore('-').substringBefore('+')
        return core.takeIf { CORE_VERSION_RE.matches(it) }
    }

    /** Numeric dotted comparison. Garbage input is treated as "not at least". */
    fun isVersionAtLeast(candidate: String, floor: String): Boolean {
        val a = normalizeVersion(candidate) ?: return false
        val b = normalizeVersion(floor) ?: return false
        val ap = a.split('.').map { it.toInt() }
        val bp = b.split('.').map { it.toInt() }
        for (i in 0 until maxOf(ap.size, bp.size)) {
            val x = ap.getOrElse(i) { 0 }
            val y = bp.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return true
    }

    /** `2.0.0` -> `2.0.1`. Adopt a hair ABOVE the demanded floor, never exactly at it. */
    fun bumpPatch(version: String): String {
        val parts = version.split('.').toMutableList()
        val last = parts.last().toIntOrNull() ?: 0
        parts[parts.size - 1] = (last + 1).toString()
        return parts.joinToString(".")
    }

    /**
     * Pull the demanded version out of a 426 body, e.g.
     * `{"type":"UpgradeRequired","message":"OpenCode 1.18.0 or newer is required…"}`
     * returns `1.18.0`. Null when the body says nothing usable.
     */
    fun parseRequiredVersion(body: String): String? {
        val match = REQUIRED_VERSION_RE.find(body) ?: return null
        return normalizeVersion(match.groupValues[1])
    }

    /**
     * Adopt a version the server explicitly demanded. Called when a request
     * comes back 426, so the very next attempt (the retry) is already above
     * the floor instead of failing the same way again.
     *
     * @return true when this call raised the effective version.
     */
    fun adoptRequiredVersion(body: String): Boolean {
        val required = parseRequiredVersion(body) ?: return false
        if (!isVersionAtLeast(required, MIN_SUPPORTED_CLIENT_VERSION)) return false
        val next = bumpPatch(required)
        while (true) {
            val current = adoptedVersion.get()
            if (current != null && isVersionAtLeast(current, next)) return false
            if (adoptedVersion.compareAndSet(current, next)) return true
        }
    }

    /**
     * Record the newest version the registry advertises. Only ever moves
     * forward, and only once past the known floor.
     *
     * @return true when this call raised the advertised version.
     */
    fun resolveAdvertisedVersion(raw: String?): Boolean {
        val version = normalizeVersion(raw) ?: return false
        if (!isVersionAtLeast(version, MIN_SUPPORTED_CLIENT_VERSION)) return false
        while (true) {
            val current = advertisedVersion.get()
            if (current != null && isVersionAtLeast(current, version)) return false
            if (advertisedVersion.compareAndSet(current, version)) return true
        }
    }

    /**
     * Effective version, highest wins:
     *  1. what the server demanded (most recent, most authoritative),
     *  2. what the registry advertises,
     *  3. [DEFAULT_CLIENT_VERSION].
     *
     * Layer 3 alone works today; 1 and 2 are what keep working after the floor
     * moves, so this can no longer rot into a permanent 426.
     */
    fun currentVersion(): String {
        val demanded = adoptedVersion.get()
        val advertised = advertisedVersion.get()
        return when {
            demanded != null && (advertised == null || isVersionAtLeast(demanded, advertised)) -> demanded
            advertised != null -> advertised
            else -> DEFAULT_CLIENT_VERSION
        }
    }

    /**
     * Probe the registry once per process, off the request path.
     *
     * Deliberately fire-and-forget: the happy path must not pay a network
     * round-trip for a value it usually does not need. The 426 path
     * ([adoptRequiredVersion]) is the one that has to be instantaneous, and it
     * needs no network at all — the server hands us the answer in the error.
     */
    fun refreshAdvertisedVersionAsync(fetch: ((String) -> String?)? = null) {
        if (!refreshStarted.compareAndSet(false, true)) return
        val job = Runnable {
            val payload = runCatching { (fetch ?: ::httpGet)(VERSION_ENDPOINT) }.getOrNull()
            val raw = runCatching {
                payload?.let { org.json.JSONObject(it).optString("version") }
            }.getOrNull()
            resolveAdvertisedVersion(raw)
        }
        Thread(job, "opencode-free-version").apply { isDaemon = true }.start()
    }

    /** Reset discovered state. Test seam only. */
    fun resetVersionStateForTest() {
        adoptedVersion.set(null)
        advertisedVersion.set(null)
        refreshStarted.set(false)
    }

    private fun httpGet(url: String): String? {
        val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 4_000
            readTimeout = 4_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", userAgent())
        }
        return try {
            if (conn.responseCode !in 200..299) null
            else conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Identity UA. The upstream matches `[Oo]penCode/…` followed by a numeric
     * version; any shape with a version and no channel segment is accepted, and
     * we emit the three-segment form the official CLI sends.
     */
    fun userAgent(): String = "opencode/$UA_CHANNEL/${currentVersion()}/$UA_CLIENT"

    // ---------------------------------------------------------------- session

    private val rng = SecureRandom()

    private const val BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    private const val BASE62_PREFIX_LEN = 14

    /** Low 12 bits hold the same-millisecond counter (matches the official generator). */
    private const val TIME_SHIFT = 12

    private val counter = AtomicInteger(0)

    /**
     * Encode a millisecond timestamp as the 12-hex-digit session prefix.
     *
     * A session id's prefix is not opaque: the upstream decodes it back into an
     * identifier timestamp and rejects ids whose prefix does not look like one.
     * Sessions use the DESCENDING variant (bitwise inversion), which is why a
     * random 12-hex prefix is rejected with FreeTierError while a derived one
     * passes.
     */
    private fun encodePrefix(ms: Long, count: Int): String {
        var v = (ms shl TIME_SHIFT) + count
        v = v.inv() // sessions are descending
        return String.format("%012x", v and 0xFFFFFFFFFFFL)
    }

    /**
     * Generate a session identity string: `ses_` + 12 hex + 14 base62.
     *
     * Generated once per process and reused for every request. The upstream
     * rate-limits per session, so minting a fresh id per call trades a
     * more-random fingerprint for immediate 429s.
     */
    fun newSessionId(): String {
        val ms = System.currentTimeMillis()
        val prefix = encodePrefix(ms, counter.incrementAndGet())
        val suffix = StringBuilder(BASE62_PREFIX_LEN)
        repeat(BASE62_PREFIX_LEN) { suffix.append(BASE62[rng.nextInt(BASE62.length)]) }
        return "ses_$prefix$suffix"
    }

    /**
     * Identity headers for a free-tier request.
     *
     * Notes from the probe matrix:
     *  - All four session headers must be present; the upstream does NOT
     *    require them to carry the same value, but the official CLI sends one
     *    value everywhere and we match that.
     *  - `x-opencode-request` must NOT be sent (the official client dropped it).
     *  - `Accept: text/event-stream` matches the forced-stream body.
     */
    fun identityHeaders(sessionId: String): Map<String, String> = linkedMapOf(
        "Authorization" to "Bearer $PUBLIC_KEY",
        "User-Agent" to userAgent(),
        "x-opencode-client" to UA_CLIENT,
        "x-opencode-project" to PROJECT,
        "x-opencode-session" to sessionId,
        "x-opencode-session-id" to sessionId,
        "x-session-affinity" to sessionId,
        "x-session-id" to sessionId,
        "Accept" to "text/event-stream",
    )

    /**
     * [T-opencode-free] True when the identity block we emit is complete and
     * self-consistent — i.e. a 403 arriving alongside it cannot be blamed on
     * our headers.
     *
     * This is what lets the provider tell a throttle apart from a genuine
     * contract violation, both of which arrive as `403 FreeTierError`. Every
     * header below was confirmed load-bearing by a 4-trial pass-rate probe:
     * dropping `x-opencode-project` or `x-opencode-client` drove the pass rate
     * to 0/4, while the full block passed 3/4 (the single failure being the
     * throttle this method exists to identify).
     */
    fun identityLooksValid(): Boolean {
        val session = newSessionId()
        val headers = identityHeaders(session)
        val required = listOf(
            "Authorization", "User-Agent", "x-opencode-client", "x-opencode-project",
            "x-opencode-session", "x-opencode-session-id", "x-session-affinity", "x-session-id",
        )
        return required.all { headers[it].isNullOrBlank().not() } &&
            // The UA must carry a version the endpoint can parse; a header
            // block with an unparseable version is a deterministic 403.
            headers["User-Agent"]?.contains('/') == true &&
            isVersionAtLeast(headers.getValue("User-Agent").split('/').getOrNull(2).orEmpty(), MIN_SUPPORTED_CLIENT_VERSION)
    }

    // -------------------------------------------------------------- body gate

    /**
     * Tools the upstream requires every free-tier request to declare. `bash` is
     * spelled `shell` in the v2 contract. All four must be present — omitting
     * any one (even `read`, which the caller never uses) is a FreeTierError.
     */
    private val GATE_TOOL_NAMES = listOf("shell", "glob", "grep", "read")

    /**
     * Appended to an EXISTING system/developer message when gate tools are
     * injected at a position the model could read as an instruction to call
     * them. We never add a new message (an extra system turn changes the
     * request shape more than the note is worth).
     */
    private const val GATE_TOOL_NOTE =
        "The tools named shell, glob, grep and read in this request are placeholders. " +
            "Do not call them; use the other tools you have been given."

    /**
     * Official definitions for the gate tools. Each carries a real schema —
     * the endpoint validates that these names are declared with genuine
     * parameters, so a bare name with an empty `properties` object is not
     * accepted.
     */
    private fun gateToolJson(name: String): JSONObject = JSONObject().apply {
        put("type", "function")
        put(
            "function",
            JSONObject().apply {
                put("name", name)
                put("description", GATE_TOOL_DESCRIPTIONS.getValue(name))
                put("parameters", JSONObject(GATE_TOOL_SCHEMAS.getValue(name)))
            },
        )
    }

    private val GATE_TOOL_DESCRIPTIONS = mapOf(
        "shell" to "Execute a shell command and return its output.",
        "glob" to "Search file paths using a glob pattern.",
        "grep" to "Search file contents using ripgrep syntax.",
        "read" to "Read the contents of a file or directory.",
    )

    private val GATE_TOOL_SCHEMAS = mapOf(
        "shell" to """
            {"type":"object","properties":{
              "command":{"type":"string","description":"Shell command string to execute"},
              "workdir":{"type":"string","description":"Working directory"},
              "timeout":{"type":"integer","description":"Timeout in milliseconds"},
              "background":{"type":"boolean","description":"Run in background"}},
             "required":["command"],"additionalProperties":false}
        """.trimIndent(),
        "glob" to """
            {"type":"object","properties":{
              "pattern":{"type":"string","description":"Glob pattern to match files against"},
              "path":{"type":"string","description":"Directory to search"},
              "hidden":{"type":"boolean","description":"Include hidden files"},
              "limit":{"type":"integer","description":"Max matches"}},
             "required":["pattern"],"additionalProperties":false}
        """.trimIndent(),
        "grep" to """
            {"type":"object","properties":{
              "pattern":{"type":"string","description":"Regex pattern to match"},
              "path":{"type":"string","description":"File or directory to search"},
              "include":{"type":"string","description":"Glob filter"},
              "literal":{"type":"boolean","description":"Treat pattern as exact text"},
              "caseSensitive":{"type":"boolean","description":"Use case-sensitive matching"},
              "limit":{"type":"integer","description":"Max matches"}},
             "required":["pattern"],"additionalProperties":false}
        """.trimIndent(),
        "read" to """
            {"type":"object","properties":{
              "path":{"type":"string","description":"File or directory to read"},
              "offset":{"type":"integer","description":"Line to start reading from"},
              "limit":{"type":"integer","description":"Max lines to read"}},
             "required":["path"],"additionalProperties":false}
        """.trimIndent(),
    )

    /**
     * Apply the free-tier body contract in place.
     *
     * 1. `stream` is forced true — a non-streaming body is rejected outright.
     * 2. Four gate tools are merged in. A caller tool with the same name is
     *    kept as-is (it already validated against this contract); a same-named
     *    tool declared without parameters is replaced by the official
     *    definition rather than dropped, so the gate is never half-satisfied.
     * 3. When gate tools were actually added, the placeholder note is appended
     *    to an existing system/developer message so the model does not try to
     *    invoke tools the user never enabled.
     *
     * @return true when gate tools were injected (i.e. the caller did not
     *   already declare all four).
     */
    fun applyBodyGate(body: JSONObject): Boolean {
        body.put("stream", true)
        enableStreamUsage(body)

        val existing = body.optJSONArray("tools")
        val kept = mutableListOf<JSONObject>()
        val declared = mutableSetOf<String>()
        if (existing != null) {
            for (i in 0 until existing.length()) {
                val tool = existing.optJSONObject(i) ?: continue
                val toolName = tool.optJSONObject("function")?.optString("name").orEmpty()
                if (toolName in GATE_TOOL_NAMES) {
                    // Same-named tool: keep it only if it carries real params;
                    // otherwise the official definition below takes its place.
                    if (hasNonEmptyParams(tool)) {
                        declared += toolName
                        kept += tool
                    }
                } else {
                    kept += tool
                }
            }
        }

        var injected = false
        for (name in GATE_TOOL_NAMES) {
            if (name in declared) continue
            kept += gateToolJson(name)
            injected = true
        }
        body.put("tools", JSONArray(kept))

        if (injected) appendGateNote(body)
        return injected
    }

    /** A tool is "declared" only if its parameter schema has real properties. */
    private fun hasNonEmptyParams(tool: JSONObject): Boolean {
        val props = tool.optJSONObject("function")
            ?.optJSONObject("parameters")
            ?.optJSONObject("properties")
        return props != null && props.length() > 0
    }

    /**
     * Append the placeholder note to an existing system/developer string
     * message. No-op when the request carries no such message.
     */
    private fun appendGateNote(body: JSONObject) {
        val messages = body.optJSONArray("messages") ?: return
        for (i in 0 until messages.length()) {
            val msg = messages.optJSONObject(i) ?: continue
            val role = msg.optString("role")
            if (role != "system" && role != "developer") continue
            // Only string content — array-shaped content is left untouched to
            // avoid restructuring a caller's multimodal/system block.
            val content = msg.optString("content", "")
            if (content.isEmpty()) continue
            msg.put("content", "$content\n\n$GATE_TOOL_NOTE")
            return
        }
    }

    /**
     * Ask for token accounting on the forced stream. The endpoint streams
     * regardless; this only makes usage arrive, matching a normal OpenAI
     * client's expectation.
     */
    private fun enableStreamUsage(body: JSONObject) {
        if (body.optJSONObject("stream_options") == null) {
            body.put("stream_options", JSONObject().put("include_usage", true))
        }
    }

    // ------------------------------------------------------------ model list

    /**
     * Models the free tier serves. These are the `-free` entries the endpoint
     * reports on `/inference/v1/models`, plus `big-pickle` (free without the
     * suffix). Kept static because the upstream catalogue also carries ~75
     * PAID entries that this provider cannot serve — filtering a live fetch
     * would show users a picker full of models that 403 on send.
     *
     * `jev-1.13-free` is listed by the endpoint but answers
     * `ModelProtocolUnsupported` on every request, so it is deliberately
     * omitted rather than offered and then failing.
     *
     * (id, display name, context window, max output tokens)
     */
    private val CATALOG = listOf(
        ModelSpec("deepseek-v4.1-flash-free", "DeepSeek V4.1 Flash（免费）", 128_000, 16_384),
        ModelSpec("beam-free", "Beam（免费·最快）", 128_000, 16_384),
        ModelSpec("mimo-v2.6-flash-free", "MiMo v2.6 Flash（免费）", 128_000, 16_384),
        ModelSpec("nemotron-3.5-super-vl-free", "Nemotron 3.5 Super VL（免费）", 128_000, 16_384),
        ModelSpec("nemotron-3.5-lightning-free", "Nemotron 3.5 Lightning（免费）", 128_000, 16_384),
        ModelSpec("longcat-2.5-preview-free", "LongCat 2.5 Preview（免费）", 128_000, 16_384),
        ModelSpec("glm-5.3-flash-free", "GLM 5.3 Flash（免费）", 128_000, 16_384),
        ModelSpec("space-bunny-free", "Space Bunny（免费）", 128_000, 16_384),
        ModelSpec("big-pickle", "Big Pickle（免费）", 128_000, 16_384),
    )

    private data class ModelSpec(
        val id: String,
        val displayName: String,
        val contextWindow: Int,
        val maxOutputTokens: Int,
    )

    /**
     * Built-in catalogue. Every entry is a free-tier model, so the picker and
     * agent loop only ever see models this instance can actually serve —
     * unlike a live `/v1/models` fetch, which would also return the ~75 paid
     * entries that 403 here.
     */
    val builtInModels: List<LLMModel> = CATALOG.map { spec ->
        LLMModel(
            id = spec.id,
            displayName = spec.displayName,
            provider = MODEL_PROVIDER_TAG,
            contextWindow = spec.contextWindow,
            maxOutputTokens = spec.maxOutputTokens,
        )
    }

    /** Provider tag stamped on [builtInModels] (not a ProviderType name). */
    const val MODEL_PROVIDER_TAG = "opencode-free"

    /**
     * [T-opencode-free] Build a model entry for an id discovered at runtime.
     *
     * When the id is one of the curated [CATALOG] entries its display name is
     * reused; otherwise the id is used verbatim, which is how the upstream
     * names its models anyway. Context/output figures are not published per
     * model on this endpoint, so discovered entries carry the same defaults
     * the catalogued ones do rather than inventing numbers.
     */
    fun modelForId(id: String): LLMModel {
        val known = DISPLAY_NAMES[id]
        return LLMModel(
            id = id,
            displayName = known ?: id,
            provider = MODEL_PROVIDER_TAG,
            contextWindow = DEFAULT_CONTEXT_WINDOW,
            maxOutputTokens = DEFAULT_MAX_OUTPUT_TOKENS,
        )
    }

    private const val DEFAULT_CONTEXT_WINDOW = 128_000
    private const val DEFAULT_MAX_OUTPUT_TOKENS = 16_384

    /** Display names for the curated ids; discovered ids fall back to the id. */
    private val DISPLAY_NAMES: Map<String, String> =
        CATALOG.associate { it.id to it.displayName }
}
