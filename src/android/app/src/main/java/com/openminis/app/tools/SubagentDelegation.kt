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
 *    optional INSTRUCTIONS. It has no runtime, and `SubAgentRoster.resolve`
 *    had zero call sites — it was dead weight in the data model.
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

        // 1. A configured entry that matches wins on the ROUTING question:
        //    the user wrote this description to say WHEN to delegate here.
        val cfg = configured.firstOrNull { normalize(it.name) == key }
        if (cfg != null) {
            val instructions = cfg.instructions.trim()
            // A configured entry whose name matches an installed skill runs
            // THAT skill (the user may have refined an existing agent);
            // otherwise it runs on the general runtime.
            val runtime = if (skillExists(cfg.name)) cfg.name else GENERAL_SKILL_NAME
            return Target(
                skillName = runtime,
                extraInstructions = instructions,
                description = cfg.description.ifBlank { cfg.name },
                fromRoster = true,
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
    )

    /**
     * The comparison form of a name: trimmed and case-folded.
     *
     * The name comes back from a MODEL, which may not reproduce the case
     * or spacing the user typed, and the stored side may carry stray
     * whitespace. Folding both is what stops "General Agent" from failing
     * to resolve a roster entry named "general-agent".
     */
    fun normalize(s: String?): String = s?.trim()?.lowercase().orEmpty()
}
