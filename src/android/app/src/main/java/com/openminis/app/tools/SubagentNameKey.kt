package com.openminis.app.tools

import java.text.Normalizer

/**
 * [feat/subagent-roster-routing] The ONE definition of "these two names
 * are the same".
 *
 * The rule used to exist in three places with two behaviours:
 *  - the data layer (`SubAgentRoster.nameKey`) folded diacritics,
 *    whitespace and case;
 *  - the tools resolver and the roster-hint merge folded only whitespace
 *    and case.
 *
 * So a roster entry named "Résumé-agent" was LISTED by the hint layer
 * under one key and MATCHED by the resolver under another — the same
 * delegation could work or fail depending on which layer answered.
 *
 * It lives in `tools` rather than in `data` on purpose: the data layer
 * already depends on the tools layer (the skill repository reads the
 * frontmatter parser), so putting it here adds no new edge. Putting it
 * in `data` would have made the tools layer depend back on the data
 * layer's Android-bound model, which the tools layer deliberately avoids.
 *
 * WHY the folding exists at all:
 *  - the name comes back from a MODEL, which may not reproduce accents
 *    exactly;
 *  - the stored side is user-typed and may carry stray whitespace.
 * Folding both is what stops "Résumé-agent" from working on one
 * platform and failing with "unknown sub-agent" on another.
 */
internal object SubagentNameKey {

    /** Hoisted: the merge path calls [normalize] O(local × remote) times. */
    private val COMBINING_MARKS = Regex("\\p{Mn}+")

    /** Trimmed, case-folded and diacritic-folded. */
    fun normalize(s: String?): String {
        val raw = s?.trim().orEmpty()
        if (raw.isEmpty()) return ""
        return Normalizer.normalize(raw, Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")
            .lowercase()
    }
}
