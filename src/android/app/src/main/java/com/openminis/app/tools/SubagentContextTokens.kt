package com.openminis.app.tools

import com.openminis.app.data.model.LLMUsage
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.GLOBAL_MAX_OUTPUT_CEILING

/**
 * [feat/subagent-roster-routing] Context and output sizing for the sub-agent
 * loop, split out of `SubagentRunner.kt`.
 *
 * Why this file exists: every function here is pure (a few ints in, an int
 * out), but they lived inside `SubagentRunner.kt` — a file that drags in
 * Context, the provider gateway and the sandbox coordinator. That is exactly
 * why the sub-agent's context-pressure rule could not be unit-tested on the
 * plain JVM, and it is the reason the clamp boundaries below are unpinned
 * by any test.
 *
 * The same lesson the repo already learned (see `SubagentParentDelivery`):
 * the decision logic is pure, only its LOCATION prevented the test. Moving
 * it here is behaviour-preserving — the bodies are unchanged.
 */

/**
 * [T-subagent-output-from-model] Context size for the next turn's output
 * sizing, mirroring the main loop's Usage-chunk rule (ChatViewModel):
 * `latestContextTokens` when the provider reports it; otherwise fresh
 * `inputTokens` + cache-read + cache-creation (inputTokens is fresh-only —
 * the cached portion is subtracted by the parser, so a cache-hit turn would
 * otherwise under-report context pressure and every turn would ask for the
 * FULL output ceiling even on a nearly-full window). 0 = nothing reported.
 */
internal fun subagentContextTokensFromUsage(usage: LLMUsage): Int =
    when {
        usage.latestContextTokens > 0 -> usage.latestContextTokens
        usage.inputTokens > 0 -> usage.inputTokens +
            (usage.cacheReadInputTokens ?: 0) +
            (usage.cacheCreationInputTokens ?: 0)
        else -> 0
    }

/**
 * [T-subagent-output-from-model] Upper bound for one sub-agent turn's output
 * request: the run model's configured Max Output Tokens (管理提供商 / Manage
 * Providers → the model's "Max Output Tokens", which may come from the model
 * catalog, a per-entry override, or the provider default), never above the
 * shared 128K cap that [com.openminis.app.provider.clampOutboundMaxTokens]
 * enforces for every provider family. Pure so the clamp boundaries are
 * unit-testable without a provider.
 */
internal fun subagentOutputCeiling(modelMaxOutputTokens: Int): Int =
    modelMaxOutputTokens.coerceIn(1, GLOBAL_MAX_OUTPUT_CEILING)

/**
 * [T-subagent-output-from-model] Fallback floor when the remaining context
 * window cannot fit another meaningful reply — mirrors the main loop's
 * `MIN_MAX_TOKENS`. A value below this would starve the turn entirely, which
 * is worse than letting the provider report the overrun loudly.
 */
private const val SUBAGENT_MIN_MAX_TOKENS = 1024

/**
 * [T-subagent-output-from-model] Per-turn `maxTokens` for a sub-agent request:
 * the model's output ceiling ([subagentOutputCeiling]), reduced to what still
 * fits the remaining context window (window − last API-reported input tokens)
 * with a floor of [SUBAGENT_MIN_MAX_TOKENS]. Mirrors the main loop's
 * `dynamicMaxTokens()` sizing so a delegated run and an inline run against the
 * same model behave identically. [lastContextTokens] is 0 until the first
 * Usage chunk reports a figure.
 */
internal fun resolveSubagentTurnMaxTokens(
    modelMaxOutputTokens: Int,
    contextWindowTokens: Int,
    lastContextTokens: Int = 0,
): Int {
    val ceiling = subagentOutputCeiling(modelMaxOutputTokens)
    if (contextWindowTokens <= 0) return ceiling
    val remaining = contextWindowTokens - lastContextTokens
    return minOf(ceiling, maxOf(remaining, SUBAGENT_MIN_MAX_TOKENS))
}

/**
 * [T-subagent-thinking] Effective reasoning level for a sub-agent run.
 *
 * The skill's frontmatter states an INTENT (`thinking: low`); whether it can
 * be honoured depends on the model that the spawn actually resolved to. The
 * rule mirrors the main chat's: a model that cannot reason runs with reasoning
 * off rather than sending an unsupported parameter (which some providers
 * reject outright). [modelSupportsReasoning] is tri-state — `null` means the
 * catalog does not know, and this deliberately treats unknown as capable so
 * the user's explicit request is not silently dropped for a model the app has
 * no information about.
 *
 * Pure + top-level so the downgrade rule is unit-testable without a provider.
 */
internal fun resolveSubagentThinkingLevel(
    requested: ThinkingLevel,
    modelSupportsReasoning: Boolean?,
): ThinkingLevel =
    if (!requested.isEnabled) ThinkingLevel.OFF
    else if (modelSupportsReasoning == false) ThinkingLevel.OFF
    else requested
