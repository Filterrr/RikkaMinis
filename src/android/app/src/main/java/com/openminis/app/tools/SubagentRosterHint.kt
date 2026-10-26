package com.openminis.app.tools

/**
 * [feat/subagent-roster-routing] The sub-agent ROSTER — the list the parent
 * agent can choose from — rendered for the parent context and resolved back
 * to a concrete target.
 *
 * ## The defect this closes
 *
 * The parent agent had NO WAY to know which sub-agents it could spawn:
 *
 *  - `spawn_agent`'s `skill_name` parameter said only "the name (or id) of
 *    the sub-agent skill to invoke" — no list, no hint;
 *  - the skills fragment injected into the parent listed ALL skills
 *    (sub-agent and ordinary ones mixed) with no `subagent` marker, so the
 *    parent could not tell a spawnable skill from a plain one;
 *  - the skills fragment is capped at 20 entries, and a sub-agent skill that
 *    lost that cut was invisible to the parent — the parent could only guess
 *    a name, and a wrong guess cost a failed tool call and a retry.
 *
 * Measured on the real runtime: the parent issued `spawn_agent` with a
 * `skill_name` that was not installed, got "Skill not found", and (correctly
 * per its own instructions) did NOT retry — so the delegation was simply
 * lost.
 *
 * ## The fix
 *
 * This object builds the roster TEXT that rides in the spawn_agent schema,
 * so the parent sees every spawnable sub-agent with a one-line description
 * it can route on. It is deliberately separate from
 * [SubagentModelResolver]'s model catalogue: that one lists MODELS, this one
 * lists AGENTS. Both use the same budget discipline (a hard entry cap and a
 * hard character cap) because both ride in every parent request.
 *
 * Routing remains the MODEL's decision — the roster does not pick. What it
 * does is make the choice INFORMED: name plus what the agent is FOR.
 */
object SubagentRosterHint {

    /** Max roster entries in the parent-facing hint. */
    const val MAX_ENTRIES = 20

    /** Max characters for the whole roster hint. */
    const val MAX_CHARS = 1600

    /** One spawnable target, as the parent sees it. */
    data class Entry(
        /** The value the parent must pass as `skill_name`. */
        val name: String,
        /** One line describing WHEN to delegate here. */
        val description: String,
    )

    /**
     * Build the roster from the spawnable SKILLS.
     *
     * A skill is spawnable when its SKILL.md frontmatter says
     * `subagent: true` — the same flag [SubagentSkill.parseSubagentConfig]
     * reads. The caller decides which skills are spawnable (it owns the
     * repository); this object only renders.
     *
     * The description is the routing text: it must say WHEN to delegate,
     * not WHAT the agent is. A description that only says what the agent is
     * gives the parent no basis to choose between two of them.
     */
    fun build(
        entries: List<Entry>,
        limit: Int = MAX_ENTRIES,
        maxChars: Int = MAX_CHARS,
    ): String {
        val usable = entries
            .map { it.copy(name = it.name.trim(), description = it.description.trim()) }
            .filter { it.name.isNotEmpty() }
        if (usable.isEmpty()) return ""
        val shown = usable.take(limit)
        val body = shown.joinToString("\n") { e ->
            val desc = e.description.replace('\n', ' ').trim()
            if (desc.isEmpty()) "- ${e.name}" else "- ${e.name}: $desc"
        }
        // The header says how many were dropped, so a truncated roster is
        // never mistaken for the complete one. (Without this the model reads
        // a 20-entry cut as "these are all of them" and never considers the
        // other 60.)
        val header = if (usable.size <= limit) {
            "Sub-agents you can spawn (pass the exact name as skill_name):"
        } else {
            "Sub-agents you can spawn (first $limit of ${usable.size}, " +
                "the rest are truncated — pass the exact name as skill_name):"
        }
        val hint = "$header\n$body"
        return if (hint.length <= maxChars) {
            hint
        } else {
            // Second guard: a handful of long descriptions can still blow the
            // budget. Cutting the TAIL is the right direction — the first
            // entries are the ones the parent would reach for anyway, and an
            // unlisted sub-agent is still reachable by exact name (the skill
            // is installed; only its DISCOVERY was cut).
            hint.take(maxChars).trimEnd() +
                " …(roster truncated; any installed sub-agent is still spawnable by its exact name)"
        }
    }

    /**
     * Merge the two roster routes into one list.
     *
     *  - [skills]: the installed `subagent: true` skills — the SPAWNABLE
     *    things. Each has a runtime, a tool set, a budget.
     *  - [configured]: the user-configured roster (TRAE-shaped) — a
     *    speciality with a description that says WHEN to delegate there.
     *
     * On a name clash the SKILL wins: it is the thing that can actually
     * run, and a configured entry whose name matches a skill is describing
     * that same agent. The configured DESCRIPTION is still preferred as
     * the routing text, because the user wrote it for exactly that
     * purpose — but only when it says something; an empty description
     * leaves the skill's own alone.
     *
     * Order: skills first, then the configured entries that did not clash.
     * The skills are the ones that can be spawned unconditionally, so they
     * must not be pushed out of the truncated head by configured entries.
     */
    fun merge(
        skills: List<Entry>,
        configured: List<Entry>,
    ): List<Entry> {
        val byKey = LinkedHashMap<String, Entry>()
        val configuredByKey = configured.associateBy { it.name.trim().lowercase() }
        for (s in skills) {
            val key = s.name.trim().lowercase()
            val cfg = configuredByKey[key]
            byKey[key] = if (cfg != null && cfg.description.isNotBlank()) {
                s.copy(description = cfg.description)
            } else {
                s
            }
        }
        for (c in configured) {
            val key = c.name.trim().lowercase()
            if (key.isEmpty() || byKey.containsKey(key)) continue
            byKey[key] = c
        }
        return byKey.values.toList()
    }

    /**
     * Resolve a parent-emitted name to a roster entry.
     *
     * Matching is case-insensitive and whitespace-trimmed, because the name
     * comes back from a MODEL that may not reproduce it exactly — the same
     * rule [SubAgentRoster.resolve] uses for its own list, so a name that
     * works in one route works in the other.
     *
     * Returns null when nothing matches: the caller then reports
     * "unknown sub-agent" and lists the roster, which is far more useful
     * than a generic "not found".
     */
    fun resolve(name: String?, entries: List<Entry>): Entry? {
        val key = name?.trim()?.lowercase().orEmpty()
        if (key.isEmpty()) return null
        return entries.firstOrNull { it.name.trim().lowercase() == key }
    }

    /**
     * The message a parent receives when it names a sub-agent that does not
     * exist. It MUST list the roster: a bare "not found" teaches the model
     * nothing, and the model's own rule is "do not retry the same spawn" —
     * so a failed name has to be corrected IN THE SAME RESPONSE or the
     * delegation is lost for the whole turn.
     */
    fun unknownAgentMessage(
        requested: String,
        entries: List<Entry>,
        maxChars: Int = MAX_CHARS,
    ): String {
        val roster = build(entries, maxChars = maxChars)
        return buildString {
            append("Error: no sub-agent named '").append(requested.trim()).append("' is installed.")
            if (roster.isNotEmpty()) {
                append("\n\n").append(roster)
                append("\n\nPick one of the names above and spawn THAT instead — Do not retry the same name.")
            } else {
                append(" No sub-agent is installed in this chat, so do the work inline.")
            }
        }
    }
}
