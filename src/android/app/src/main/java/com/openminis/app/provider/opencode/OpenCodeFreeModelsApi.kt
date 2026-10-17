package com.openminis.app.provider.opencode

import com.openminis.app.data.model.LLMModel
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * [T-opencode-free] Discovers which models the free tier currently serves.
 *
 * ## Why discovery is needed
 *
 * The catalogue was a hardcoded list. That rots in two directions: a new free
 * model appears and the app cannot offer it, and a free model is withdrawn or
 * breaks and the app keeps offering it.
 *
 * ## Why naive discovery is wrong
 *
 * `/inference/v1/models` returns ~85 ids, and roughly 76 of them are PAID.
 * Offering them would let a user pick a model that cannot work. The list does
 * NOT carry a machine-readable free flag (`owned_by` is `"opencode"` for every
 * entry), and names only *partly* indicate it: `-free` is a strong hint, but
 * `big-pickle` is free without the suffix, and `jev-1.13-free` carries the
 * suffix yet answers `ModelProtocolUnsupported` on every request.
 *
 * ## What the endpoint does disclose, and what this class keys off
 *
 * Probing found a deterministic discriminator: a request for a PAID model
 * returns **401** (an account is required) while a FREE model is served
 * without one. That is what [classifyProbe] encodes, and it is what makes
 * verification possible without a curated allowlist.
 *
 * Discovery is therefore: fetch the list, keep `-free`-shaped ids minus a small
 * denylist of ids *observed* to be unusable, and fall back to the curated
 * catalogue when the fetch fails. If the fetch ever returns nothing usable we
 * prefer the curated list over an empty picker.
 *
 * Note this class reads the host and path from [OpenCodeFree] so there is a
 * single definition of each.
 */
object OpenCodeFreeModelsApi {

    /**
     * Ids that LOOK free but are not usable. Kept tiny and evidence-based —
     * each entry is a model observed to fail on every request, not a guess.
     *
     * This is not the curated catalogue by another name: it only has to list
     * ids that pass the name filter yet cannot serve traffic. Everything else
     * is discovered.
     */
    val KNOWN_UNUSABLE: Set<String> = setOf(
        // Listed with the -free suffix, but answers ModelProtocolUnsupported.
        "jev-1.13-free",
    )

    /** Substrings that mark an id as the PAID side of the catalogue. */
    private val PAID_MARKERS = listOf(
        "-pro", "-codex", "-max", "-mini", "-nano", "-backup",
    )

    /**
     * Vendor prefixes whose entries are paid on this endpoint. A `-free`
     * suffix overrides these (the free tier does serve some vendor models);
     * without the suffix these are all account-gated.
     */
    private val PAID_PREFIXES = listOf(
        "gpt-", "claude-", "gemini-", "grok-", "o1", "o3", "o4",
    )

    /** True when an id passes the cheap, offline "might be free" filter. */
    fun looksFree(id: String): Boolean {
        if (id.isBlank()) return false
        if (id in KNOWN_UNUSABLE) return false
        if (!id.endsWith("-free")) return false
        // A paid-tier marker wins over the suffix (e.g. "foo-pro-free").
        return PAID_MARKERS.none { id.contains(it) }
    }

    /**
     * [T-opencode-free] Interpret a probe response for one model id.
     *
     * The discriminator is the status code, not the body: the endpoint answers
     * **401** for a paid model (an account is required) and does not answer 401
     * for a free one. 200 means served. 403 is ambiguous on this upstream — it
     * covers both a contract violation and a throttle — so it is reported as
     * unknown rather than as a verdict.
     */
    fun classifyProbe(statusCode: Int): ProbeVerdict = when (statusCode) {
        in 200..299 -> ProbeVerdict.SERVED
        401 -> ProbeVerdict.PAID
        403 -> ProbeVerdict.UNKNOWN   // throttle or contract — not decisive
        else -> ProbeVerdict.UNKNOWN
    }

    enum class ProbeVerdict { SERVED, PAID, UNKNOWN }

    /**
     * Fetch the endpoint's model list and reduce it to free-tier candidates.
     *
     * Returns null (not an empty list) when the fetch itself fails, so callers
     * can tell "upstream unreachable" apart from "upstream listed nothing
     * free" — the first should keep the curated catalogue, the second should
     * not silently empty the picker either.
     */
    fun fetchFreeCandidates(
        connectTimeoutMs: Int = 10_000,
        readTimeoutMs: Int = 15_000,
    ): List<LLMModel>? = try {
        val url = URL("${OpenCodeFree.BASE_URL}/models")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = true
            // The identity block is required even for /models: omitting it
            // yields 403 FreeTierError.
            for ((k, v) in OpenCodeFree.identityHeaders(OpenCodeFree.newSessionId())) {
                setRequestProperty(k, v)
            }
        }
        try {
            val status = conn.responseCode
            if (status !in 200..299) return null
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val data = JSONObject(text).optJSONArray("data") ?: return null
            val out = ArrayList<LLMModel>()
            for (i in 0 until data.length()) {
                val id = data.optJSONObject(i)?.optString("id", "") ?: continue
                if (!looksFree(id)) continue
                out += OpenCodeFree.modelForId(id)
            }
            out
        } finally {
            conn.disconnect()
        }
    } catch (_: Exception) {
        null
    }
}
