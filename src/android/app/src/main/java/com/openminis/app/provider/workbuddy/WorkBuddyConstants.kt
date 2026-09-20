package com.openminis.app.provider.workbuddy

import com.openminis.app.data.model.LLMModel

/**
 * Constants and catalog for the WorkBuddy (CodeBuddy / Tencent Copilot)
 * provider.
 *
 * WorkBuddy is a **native mobile client** for Tencent's CodeBuddy service:
 * it authenticates a real CodeBuddy account over OAuth (no API key), then
 * exposes that account's model quota through the CodeBuddy backend's own
 * OpenAI-shaped chat endpoint. RikkaMinis integrates the same upstream
 * surface directly — we do not proxy through the WorkBuddy app.
 *
 * ## Endpoints (verified against WorkBuddy2API Native 1.1.0)
 *
 * | Purpose              | Method | Path                                        |
 * |----------------------|--------|---------------------------------------------|
 * | OAuth state + URL    | POST   | `/v2/plugin/auth/state?platform=CLI`        |
 * | OAuth poll (token)   | GET    | `/v2/plugin/auth/token?state=<state>`       |
 * | Token refresh        | POST   | `/v2/plugin/auth/token/refresh`             |
 * | Model catalog        | GET    | `/console/enterprises/personal/models`      |
 * | Chat completions     | POST   | `/v2/chat/completions`                      |
 *
 * `code` == 0 (or absent) in a response envelope signals success; the payload
 * lives under `data`. Non-zero `code` carries the upstream message.
 */
object WorkBuddyConstants {

    /**
     * Region selected when the user adds the provider. WorkBuddy ships two
     * independent "versions" that share one wire protocol but sit on
     * different hosts and carry different catalogs — a domestic (China)
     * tenant on `copilot.tencent.com`, and an international tenant on
     * `www.codebuddy.ai`. The region is a *property of the account*, not a
     * preference: a token minted on one backend is meaningless on the other,
     * so the user picks it up front and it is stored on the instance.
     */
    enum class Region(
        val id: String,
        val label: String,
        val backend: String,
        val defaultDomain: String,
    ) {
        DOMESTIC("domestic", "国内版", "https://copilot.tencent.com", "www.codebuddy.cn"),
        INTERNATIONAL("international", "国际版", "https://www.codebuddy.ai", "www.codebuddy.ai");

        companion object {
            /** Lenient lookup used when reading persisted config. */
            fun from(value: String?): Region =
                entries.firstOrNull { it.id.equals(value?.trim(), ignoreCase = true) } ?: DOMESTIC

            /** Strict lookup — throws on an unknown id (write paths / tests). */
            fun fromStrict(value: String?): Region =
                entries.firstOrNull { it.id.equals(value?.trim(), ignoreCase = true) }
                    ?: throw IllegalArgumentException("版本必须是 domestic 或 international")
        }
    }

    /** Default region used when the instance carries no explicit choice. */
    val DEFAULT_REGION: Region = Region.DOMESTIC

    /**
     * Upstream client fingerprint. WorkBuddy's own Android build sends this
     * exact literal for authenticated calls; the browser UA is used only on
     * the unauthenticated OAuth/bootstrap calls and for the catalog fetch.
     * Kept verbatim so the traffic stays indistinguishable from the official
     * client (the backend gates some routes on UA).
     */
    const val CLIENT_UA = "Workbuddy2API-Android/1.0"

    /** UA used for the OAuth round-trip and the model catalog. */
    const val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/125.0 Mobile Safari/537.36"

    /**
     * Placeholder written into the encrypted credential slot for a
     * WorkBuddy instance. Mirrors [com.openminis.app.data.repository.ProviderRepository.ANTIGRAVITY_OAUTH_MARKER]:
     * the real tokens live in [WorkBuddyCredentialStore], while the marker
     * keeps the instance visible to every "has a credential" gate in the
     * repository (model refresh, connection test, routing eligibility).
     */
    const val OAUTH_MARKER = "workbuddy:oauth"

    /**
     * Envelope `code` meaning "success". WorkBuddy omits the field entirely
     * on some responses, which is equally a success.
     */
    const val CODE_OK = 0

    /**
     * Model ids that are chat-capable. The upstream catalog also lists image
     * and video generation endpoints (`gemini-*-image`, `hunyuan-*`); those
     * are filtered out of the chat list because RikkaMinis routes image
     * generation through a different surface and would otherwise offer
     * unusable entries in the model picker.
     */
    fun isChatCapable(id: String): Boolean {
        val lower = id.lowercase()
        if (lower.contains("image") || lower.contains("video") || lower.contains("vision-")) return false
        return true
    }

    /**
     * Bundled fallback catalog for the international tenant.
     *
     * Transcribed from WorkBuddy2API's `codebuddy-international-models.json`
     * (`@tencent-ai/codebuddy-code@2.150.0`). Used only while the first live
     * catalog fetch is in flight, or when the account has no usable token
     * yet — the live `/console/enterprises/personal/models` response always
     * wins once it arrives. Domestic accounts have no bundled list (their
     * catalog differs per tenant) and fall back to an empty list until the
     * first successful fetch.
     */
    val BUNDLED_INTERNATIONAL: List<LLMModel> = listOf(
        LLMModel("default-model", "Auto", "WorkBuddy", contextWindow = 176_000, maxOutputTokens = 24_000, supportsReasoning = true, inputModalities = listOf("text", "image"), outputModalities = listOf("text")),
        LLMModel("fast-model", "Fast", "WorkBuddy", contextWindow = 200_000, maxOutputTokens = 32_000, supportsReasoning = true, inputModalities = listOf("text", "image"), outputModalities = listOf("text")),
        LLMModel("balanced-model", "Balanced", "WorkBuddy", contextWindow = 256_000, maxOutputTokens = 32_000, supportsReasoning = true, inputModalities = listOf("text", "image"), outputModalities = listOf("text")),
        LLMModel("primary-model", "Primary", "WorkBuddy", contextWindow = 272_000, maxOutputTokens = 72_000, supportsReasoning = true, inputModalities = listOf("text", "image"), outputModalities = listOf("text")),
        LLMModel("deep-model", "Deep", "WorkBuddy", contextWindow = 200_000, maxOutputTokens = 24_000, supportsReasoning = null, inputModalities = listOf("text", "image"), outputModalities = listOf("text")),
        LLMModel("gpt-5.6-sol", "GPT-5.6-Sol", "WorkBuddy", contextWindow = 272_000, supportsReasoning = true, inputModalities = listOf("text", "image"), outputModalities = listOf("text")),
        LLMModel("gpt-5.6-terra", "GPT-5.6-Terra", "WorkBuddy", contextWindow = 272_000, supportsReasoning = true, inputModalities = listOf("text", "image"), outputModalities = listOf("text")),
        LLMModel("gpt-5.6-luna", "GPT-5.6-Luna", "WorkBuddy", contextWindow = 200_000, supportsReasoning = true, inputModalities = listOf("text", "image"), outputModalities = listOf("text")),
        LLMModel("gpt-5.5", "GPT-5.5", "WorkBuddy", supportsReasoning = true, inputModalities = listOf("text", "image"), outputModalities = listOf("text")),
        LLMModel("gpt-5.4", "GPT-5.4", "WorkBuddy", supportsReasoning = true, inputModalities = listOf("text", "image"), outputModalities = listOf("text")),
        LLMModel("gpt-5.3-codex", "GPT-5.3-Codex", "WorkBuddy", supportsReasoning = true, inputModalities = listOf("text", "image"), outputModalities = listOf("text")),
        LLMModel("gpt-5.1-codex", "GPT-5.1-Codex", "WorkBuddy", supportsReasoning = true, inputModalities = listOf("text", "image"), outputModalities = listOf("text")),
        LLMModel("gpt-5.1-codex-mini", "GPT-5.1-Codex-Mini", "WorkBuddy", supportsReasoning = true, inputModalities = listOf("text", "image"), outputModalities = listOf("text")),
        LLMModel("gemini-3.5-flash", "Gemini-3.5-Flash", "WorkBuddy", supportsReasoning = true, inputModalities = listOf("text", "image"), outputModalities = listOf("text")),
        LLMModel("gemini-3.1-pro", "Gemini-3.1-Pro", "WorkBuddy", supportsReasoning = true, inputModalities = listOf("text", "image"), outputModalities = listOf("text")),
        LLMModel("gemini-3.0-flash", "Gemini-3.0-Flash", "WorkBuddy", supportsReasoning = true, inputModalities = listOf("text", "image"), outputModalities = listOf("text")),
        LLMModel("gemini-3.1-flash-lite", "Gemini-3.1-Flash-Lite", "WorkBuddy", supportsReasoning = true, inputModalities = listOf("text", "image"), outputModalities = listOf("text")),
        LLMModel("gemini-2.5-pro", "Gemini-2.5-Pro", "WorkBuddy", supportsReasoning = true, inputModalities = listOf("text", "image"), outputModalities = listOf("text")),
        LLMModel("gemini-2.5-flash", "Gemini-2.5-Flash", "WorkBuddy", supportsReasoning = true, inputModalities = listOf("text", "image"), outputModalities = listOf("text")),
        LLMModel("glm-5.3", "GLM-5.3", "WorkBuddy", supportsReasoning = true, inputModalities = listOf("text", "image"), outputModalities = listOf("text")),
        LLMModel("glm-5.2", "GLM-5.2", "WorkBuddy", supportsReasoning = true, inputModalities = listOf("text", "image"), outputModalities = listOf("text")),
        LLMModel("glm-5.0", "GLM-5.0", "WorkBuddy", supportsReasoning = true, inputModalities = listOf("text"), outputModalities = listOf("text")),
        LLMModel("kimi-k3", "Kimi-K3", "WorkBuddy", supportsReasoning = true, inputModalities = listOf("text", "image"), outputModalities = listOf("text")),
        LLMModel("kimi-k2.6", "Kimi-K2.6", "WorkBuddy", supportsReasoning = true, inputModalities = listOf("text", "image"), outputModalities = listOf("text")),
        LLMModel("kimi-k2.5", "Kimi-K2.5", "WorkBuddy", supportsReasoning = true, inputModalities = listOf("text", "image"), outputModalities = listOf("text")),
        LLMModel("minimax-m3", "MiniMax-M3", "WorkBuddy", supportsReasoning = true, inputModalities = listOf("text", "image"), outputModalities = listOf("text")),
        LLMModel("deepseek-v4.1-flash", "DeepSeek-V4.1-Flash", "WorkBuddy", supportsReasoning = true, inputModalities = listOf("text"), outputModalities = listOf("text")),
        LLMModel("deepseek-v3-2-volc", "DeepSeek-V3.2", "WorkBuddy", supportsReasoning = true, inputModalities = listOf("text"), outputModalities = listOf("text")),
        LLMModel("hy3", "Hy3", "WorkBuddy", supportsReasoning = true, inputModalities = listOf("text", "image"), outputModalities = listOf("text")),
    )
}
