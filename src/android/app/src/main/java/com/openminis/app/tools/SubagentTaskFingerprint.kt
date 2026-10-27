package com.openminis.app.tools

/**
 * [feat/subagent-roster-routing] P2-10 — the FINGERPRINT used to recognise
 * "the same delegated task, re-asked".
 *
 * The bug this closes: the duplicate check compared the query strings
 * EXACTLY. The parent agent re-issues a task across turns with different
 * wording every time ("audit the sub-agent runner" / "please audit the
 * sub-agent runner" / "audit the sub-agent runner again"), so the exact
 * comparison missed them all and each re-ask created REAL duplicate work
 * — several sub-agents doing the same investigation at once, burning the
 * user's budget for one answer.
 *
 * The fingerprint normalises the parts that carry no meaning for identity:
 *  - case;
 *  - whitespace (including newlines and tabs);
 *  - the common polite wrappers the parent adds ("please", "can you",
 *    "could you", "now", "again", "for me", "thanks");
 *  - trailing punctuation.
 *
 * What it deliberately does NOT normalise: the WORDS. Two tasks that
 * mention different files, different goals or different scopes are
 * different tasks, however similarly they are phrased. Over-normalising
 * would merge genuinely different delegations — which is a worse failure
 * than a missed duplicate, because it silently drops work the user asked
 * for.
 */
internal object SubagentTaskFingerprint {

    /**
     * Politeness / filler words the parent adds around the same task.
     * Kept short on purpose: only the words that carry no task identity.
     */
    private val FILLER = setOf(
        "please", "kindly", "can", "could", "would", "you", "now", "again",
        "for", "me", "thanks", "thank", "the", "a", "an", "to", "of", "it",
        "do", "and", "then", "also", "just", "want", "need", "i", "we",
    )

    /**
     * The normalised identity of a delegated task.
     */
    fun of(query: String): String {
        val words = query
            .lowercase()
            .split(Regex("""[^a-z0-9_./:-]+"""))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val meaningful = words.filterNot { it in FILLER }
        // If stripping the filler emptied the task, the task WAS the
        // filler — keep the original words so it is not collapsed into
        // the empty fingerprint (which would make every such task a
        // duplicate of every other).
        val kept = if (meaningful.isEmpty()) words else meaningful
        return kept.joinToString(" ")
    }

    /**
     * True when two queries name the same delegated task.
     */
    fun same(a: String, b: String): Boolean {
        val fa = of(a)
        val fb = of(b)
        // An empty fingerprint means the query had no content at all —
        // treat it as NOT a duplicate (two empty tasks are not the same
        // task; and refusing to merge them is the safe direction).
        if (fa.isEmpty() || fb.isEmpty()) return a == b
        return fa == fb
    }
}
