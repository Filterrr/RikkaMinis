package com.openminis.app.tools

/**
 * [feat/subagent-roster-routing] Resolve a delegation target to a RUN.
 *
 * Why this exists: the repository carried TWO shapes for a sub-agent and
 * neither was connected to a runtime.
 *
 *  - The SKILL shape (`subagent: true` in a SKILL.md) is the runtime: it
 *    has a system prompt, a tool set, a budget, an artifacts directory.
 *    It is what `spawn_agent` actually executes — but it names an
 *    IMPLEMENTATION ("general-agent"), not a speciality, and the parent
 *    had to guess its name.
 *
 *  - The CONFIGURED roster shape (`SubAgentDefinition`) is the TRAE shape:
 *    a name plus a DESCRIPTION that says WHEN to delegate there, plus
 *    optional INSTRUCTIONS. It had NO runtime — it was dead weight in the
 *    data model.
 *
 * The resolution rule this object encodes: **a delegation is a skill
 * (the runtime) plus optional extra instructions (the speciality).**
 *
 *  - A roster entry whose name matches a skill → that skill, with the
 *    roster's instructions appended to its prompt.
 *  - A roster entry with no matching skill → the GENERAL skill, with the
 *    roster's instructions as its whole persona. This is what makes the
 *    configured route work at all: the user configures "security-reviewer"
 *    with an instruction set, and it runs on the general runtime.
 *  - A bare skill name → that skill, unmodified.
 *
 * Pure by construction: no Context, no provider, no repository. That is
 * what makes the routing table unit-testable on the plain JVM.
 */
object SubagentDelegation {

    /**
     * One resolved delegation.
     *
     * @param skillName the SKILL to run — the runtime.
     * @param extraInstructions extra system-prompt text from a configured
     *   roster entry — the speciality. Empty for a plain skill spawn.
     * @param description the ROUTING text the parent saw.
     * @param fromRoster true when this target came from the configured
     *   roster rather than from a skill name.
     */
    data class Target(
        val skillName: String,
        val extraInstructions: String,
        val description: String,
        val fromRoster: Boolean,
        /**
         * [feat/subagent-roster-routing] The configured roster entry's
         * model group — the models this agent may run on.
         *
         * Null = no group pinned, so the run inherits whatever the parent
         * resolved. It is a CONSTRAINT, not a demand: when the parent
         * names a model outside the group, the group wins, because the
         * user pinned the group precisely to keep this agent on it.
         */
        val modelGroupId: String? = null,
        /**
         * [feat/subagent-roster-routing] The configured roster entry's
         * reasoning-level override.
         *
         * Null = inherit the skill's own `thinking:` (or the parent's).
         * The value is a NAME, not the enum, so the tools layer stays free
         * of the data layer's Android-bound types.
         */
        val thinkingLevelOverride: String? = null,
    )

    /**
     * The skill that runs a configured roster entry with no matching skill.
     *
     * Named by constant, not by "the first skill that happens to be
     * installed": the fallback must be deterministic, and the general
     * agent is the one whose contract is "any self-contained task with
     * its own tool loop".
     */
    const val GENERAL_SKILL_NAME = "general-agent"

    /**
     * Resolve [requested] against the two rosters.
     *
     * @param requested the name the parent emitted. Blank = the general
     *   agent (the built-in's own rule: a null/blank name resolves to the
     *   built-in).
     * @param skillRoster the installed sub-agent skills (the runtimes).
     * @param configured the user-configured roster (the specialities).
     * @param skillExists predicate that answers "is this an installed
     *   skill?" — injected so this object stays free of the repository.
     * @return the target, or null when nothing can serve the request.
     */
    fun resolve(
        requested: String?,
        skillRoster: List<SubagentRosterHint.Entry>,
        configured: List<ConfiguredAgent>,
        skillExists: (String) -> Boolean,
    ): Target? {
        val key = normalize(requested)

        // 0. NO NAME → the built-in entry.
        //
        // The data layer's `SubAgentRoster.resolve` had this rule (an empty
        // name resolves to the built-in, else the first entry) and the
        // tools layer did not, so the two routes disagreed on what an
        // unnamed spawn means. An empty name is the parent saying "use
        // the default agent", and the built-in IS the default.
        if (key.isEmpty()) {
            // The built-in is identified by its FLAG. Its display name is
            // "General Sub Agent", which is NOT the wire name the parent
            // emits — matching on the name would never find it.
            val builtIn = configured.firstOrNull { it.isBuiltIn }
                ?: configured.firstOrNull { normalize(it.name) == normalize(GENERAL_SKILL_NAME) }
                ?: configured.firstOrNull()
            if (builtIn != null) {
                // The built-in's display name is not a skill name; it runs
                // on the general runtime.
                val runtime = if (builtIn.isBuiltIn) {
                    GENERAL_SKILL_NAME
                } else if (skillExists(builtIn.name)) {
                    builtIn.name
                } else {
                    GENERAL_SKILL_NAME
                }
                return Target(
                    skillName = runtime,
                    extraInstructions = builtIn.instructions.trim(),
                    description = builtIn.description.ifBlank { builtIn.name },
                    fromRoster = true,
                    modelGroupId = builtIn.modelGroupId?.takeIf { it.isNotBlank() },
                    thinkingLevelOverride = builtIn.thinkingLevelOverride?.takeIf { it.isNotBlank() },
                )
            }
            // No configured roster at all: fall back to the general
            // runtime, which is always available.
            return Target(
                skillName = GENERAL_SKILL_NAME,
                extraInstructions = "",
                description = GENERAL_SKILL_NAME,
                fromRoster = false,
            )
        }

        // 1. A configured entry that matches wins on the ROUTING question:
        //    the user wrote this description to say WHEN to delegate here.
        val cfg = configured.firstOrNull { normalize(it.name) == key }
        if (cfg != null) {
            val instructions = cfg.instructions.trim()
            // A configured entry whose name matches an installed skill runs
            // THAT skill (the user may have refined an existing agent);
            // otherwise it runs on the general runtime.
            // The built-in's display name is not a skill name; it runs on
            // the general runtime.
            val runtime = if (cfg.isBuiltIn) {
                GENERAL_SKILL_NAME
            } else if (skillExists(cfg.name)) {
                cfg.name
            } else {
                GENERAL_SKILL_NAME
            }
            return Target(
                skillName = runtime,
                extraInstructions = instructions,
                description = cfg.description.ifBlank { cfg.name },
                fromRoster = true,
                modelGroupId = cfg.modelGroupId?.takeIf { it.isNotBlank() },
                thinkingLevelOverride = cfg.thinkingLevelOverride?.takeIf { it.isNotBlank() },
            )
        }

        // 2. Otherwise it must be a skill name.
        val skill = skillRoster.firstOrNull { normalize(it.name) == key }
            ?: return null
        return Target(
            skillName = skill.name,
            extraInstructions = "",
            description = skill.description,
            fromRoster = false,
        )
    }

    /**
     * A configured roster entry, in the shape this object needs.
     *
     * Kept separate from `SubAgentDefinition` so the tools layer does not
     * depend on the data layer's Android-bound model (that class resolves
     * localized strings through a `Context`).
     */
    data class ConfiguredAgent(
        val name: String,
        val description: String,
        val instructions: String = "",
        /** The model group this agent is pinned to. */
        val modelGroupId: String? = null,
        /** The reasoning-level override, by name. */
        val thinkingLevelOverride: String? = null,
        /**
         * [feat/subagent-roster-routing] The roster entry's stable id.
         *
         * The built-in entry's NAME is a display name ("General Sub
         * Agent"), not the wire name the parent emits ("general-agent").
         * The id is what identifies it — and the data layer's own
         * `SubAgentRoster.resolve` matched on the id for exactly this
         * reason. Without the id here, the built-in entry could not be
         * recognised as the built-in, and an unnamed spawn would fall
         * through to "the first entry" instead.
         */
        val id: String? = null,
        /**
         * True for the built-in entry — the one an unnamed spawn uses.
         */
        val isBuiltIn: Boolean = false,
    )

    /**
     * The comparison form of a name: trimmed and case-folded.
     *
     * The name comes back from a MODEL, which may not reproduce the case
     * or spacing the user typed, and the stored side may carry stray
     * whitespace. Folding both is what stops "General Agent" from failing
     * to resolve a roster entry named "general-agent".
     */
    /**
     * The comparison form of a name.
     *
     * [feat/subagent-roster-routing] This now matches
     * `SubAgentRoster.nameKey` EXACTLY — diacritics folded, trimmed,
     * case-folded.
     *
     * The two used to disagree: the data layer folded diacritics and the
     * tools layer did not. A roster entry named "Résumé-agent" resolved
     * on one path and not on the other, so the same name could work or
     * fail depending on which layer answered. A name that comes back from
     * a MODEL may not reproduce accents exactly, and the stored side is
     * user-typed and may carry stray whitespace — folding both is what
     * stops that from becoming a lost delegation.
     */
    fun normalize(s: String?): String = SubagentNameKey.normalize(s)

}
