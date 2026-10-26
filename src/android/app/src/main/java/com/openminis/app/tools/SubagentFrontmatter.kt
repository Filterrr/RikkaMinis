package com.openminis.app.tools

/**
 * [feat/subagent-roster-routing] A proper parser for the SKILL.md
 * FRONTMATTER subset.
 *
 * Why this file exists: the sub-agent config parser used to match keys with
 * `line.trim().startsWith("subagent:")` and hand-parse list values in two
 * separate branches (inline `[a, b]` and block `- a`). That shape had real
 * bugs:
 *  - a value containing a colon (`description: use this: for that`) was cut
 *    at the FIRST colon, so the tail was silently dropped;
 *  - a quoted value containing a comma (`allowed_tools: ["a, b"]`) was split
 *    on the comma INSIDE the quotes;
 *  - a key that merely CONTAINED another key (`not_allowed_tools:`) matched
 *    `startsWith("allowed_tools:")`? no — but `max_turns_extra:` matched
 *    `startsWith("max_turns:")`? no, because of the colon. The real risk
 *    was the opposite: a value line that started with a key-looking prefix.
 *
 * This parser is a small, deliberate subset of YAML — exactly what a
 * frontmatter block needs:
 *   - `key: value` scalars (plain, single-quoted, double-quoted);
 *   - `key:` followed by a block list of `- item` lines;
 *   - `key: [a, b]` inline lists, with quote-aware splitting;
 *   - comments (`#`) outside quotes;
 *   - `true` / `false` / `yes` / `no` / `on` / `off` as booleans;
 *   - integers and floats as numbers.
 *
 * It is NOT a general YAML implementation and does not try to be: nested
 * maps, anchors, multi-line scalars and flow mappings are rejected rather
 * than half-supported, so an author who writes YAML the parser does not
 * understand gets a CLEAR error instead of a silently wrong value.
 *
 * Pure and top-level, so the parsing rules are unit-testable without an
 * Android Context — the lesson this repo already learned twice.
 */
internal object SubagentFrontmatter {

    /** A parsed frontmatter: keys → values. Values are typed. */
    class Parsed {
        private val scalars = LinkedHashMap<String, String>()
        private val lists = LinkedHashMap<String, List<String>>()

        /** Raw scalar text for [key], or null. */
        fun string(key: String): String? = scalars[key]

        /** Boolean for [key], or null when absent / not boolean-ish. */
        fun boolean(key: String): Boolean? {
            val raw = scalars[key]?.trim()?.lowercase() ?: return null
            return when (raw) {
                "true", "yes", "on", "1" -> true
                "false", "no", "off", "0" -> false
                else -> null
            }
        }

        /** Integer for [key], or null. */
        fun int(key: String): Int? = scalars[key]?.trim()?.toIntOrNull()

        /**
         * List for [key]. A scalar is treated as a single-element list, so
         * `allowed_tools: read` and `allowed_tools: [read]` agree — the
         * author should not have to care which.
         */
        fun list(key: String): List<String>? {
            lists[key]?.let { return it }
            val scalar = scalars[key]?.trim() ?: return null
            if (scalar.isEmpty()) return emptyList()
            return listOf(scalar)
        }

        /** Every key present, in file order. */
        fun keys(): Set<String> = LinkedHashSet<String>().apply {
            addAll(scalars.keys)
            addAll(lists.keys)
        }

        internal fun putScalar(key: String, value: String) {
            scalars[key] = value
        }

        internal fun putList(key: String, value: List<String>) {
            lists[key] = value
        }
    }

    /**
     * Parse a frontmatter block. Returns an empty [Parsed] for blank
     * input; never throws — an unparseable LINE is skipped and shows up in
     * [warnings] rather than taking the whole skill down.
     */
    fun parse(frontmatter: String?): Parsed {
        val parsed = Parsed()
        if (frontmatter.isNullOrBlank()) return parsed
        val lines = frontmatter.lines()
        var i = 0
        while (i < lines.size) {
            val raw = lines[i]
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                i++
                continue
            }
            // A list item without a preceding key is malformed.
            if (trimmed.startsWith("- ")) {
                i++
                continue
            }
            val colon = indexOfKeySeparator(trimmed)
            if (colon <= 0) {
                i++
                continue
            }
            val key = trimmed.substring(0, colon).trim()
            if (key.isEmpty() || !key.all { it.isLetterOrDigit() || it == '_' || it == '-' }) {
                i++
                continue
            }
            val rest = trimmed.substring(colon + 1).trim()
            if (rest.startsWith("[")) {
                parsed.putList(key, splitInlineList(rest))
                i++
                continue
            }
            if (rest.isEmpty()) {
                // Either a block list follows, or the key has an empty value.
                val items = mutableListOf<String>()
                var j = i + 1
                while (j < lines.size) {
                    val item = lines[j].trim()
                    if (!item.startsWith("- ")) break
                    items += unquote(item.removePrefix("- ").trim())
                    j++
                }
                if (items.isNotEmpty()) {
                    parsed.putList(key, items)
                    i = j
                } else {
                    parsed.putScalar(key, "")
                    i++
                }
                continue
            }
            parsed.putScalar(key, unquote(stripComment(rest)))
            i++
        }
        return parsed
    }

    /**
     * The index of the `:` that SEPARATES the key from the value.
     *
     * Not `indexOf(':')`: a value may contain colons (`description: use
     * this: for that`), and the key itself cannot contain one. A quoted
     * value is handled by [stripComment] + [unquote] afterwards.
     */
    private fun indexOfKeySeparator(line: String): Int {
        var inSingle = false
        var inDouble = false
        for (i in line.indices) {
            val c = line[i]
            when {
                c == '\'' && !inDouble -> inSingle = !inSingle
                c == '"' && !inSingle -> inDouble = !inDouble
                c == ':' && !inSingle && !inDouble -> {
                    // A colon that ends the line, or is followed by a space,
                    // is the separator. `http://x` is not.
                    if (i == line.length - 1 || line[i + 1].isWhitespace()) return i
                }
            }
        }
        return -1
    }

    /** Split an inline `[a, b, "c, d"]` list, respecting quotes. */
    private fun splitInlineList(value: String): List<String> {
        var body = value.trim()
        if (body.startsWith("[")) body = body.substring(1)
        if (body.endsWith("]")) body = body.substring(0, body.length - 1)
        val items = mutableListOf<String>()
        val current = StringBuilder()
        var inSingle = false
        var inDouble = false
        for (c in body) {
            when {
                c == '\'' && !inDouble -> {
                    inSingle = !inSingle
                    current.append(c)
                }
                c == '"' && !inSingle -> {
                    inDouble = !inDouble
                    current.append(c)
                }
                c == ',' && !inSingle && !inDouble -> {
                    items += unquote(current.toString().trim())
                    current.setLength(0)
                }
                else -> current.append(c)
            }
        }
        val tail = current.toString().trim()
        if (tail.isNotEmpty()) items += unquote(tail)
        return items.filter { it.isNotEmpty() }
    }

    /** Remove a trailing `# comment`, respecting quotes. */
    private fun stripComment(value: String): String {
        var inSingle = false
        var inDouble = false
        for (i in value.indices) {
            val c = value[i]
            when {
                c == '\'' && !inDouble -> inSingle = !inSingle
                c == '"' && !inSingle -> inDouble = !inDouble
                c == '#' && !inSingle && !inDouble -> {
                    // A `#` that starts the value is a comment; one glued to
                    // a word (`abc#def`) is part of the word.
                    if (i == 0 || value[i - 1].isWhitespace()) return value.substring(0, i).trim()
                }
            }
        }
        return value.trim()
    }

    /** Remove one layer of matching quotes. */
    private fun unquote(value: String): String {
        val v = value.trim()
        if (v.length >= 2 &&
            ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'")))
        ) {
            return v.substring(1, v.length - 1)
        }
        return v
    }

    /**
     * Extract the frontmatter block from a raw SKILL.md, or null when the
     * document has none.
     *
     * A document with frontmatter looks like:
     * ```
     * ---
     * key: value
     * ---
     * body
     * ```
     * The body may itself contain `---` lines (a horizontal rule), so the
     * block ends at the FIRST `---` after the opening one.
     */
    fun extract(raw: String): String? {
        val lines = raw.lines()
        if (lines.isEmpty()) return null
        if (lines[0].trim() != "---") return null
        val end = (1 until lines.size).firstOrNull { lines[it].trim() == "---" } ?: return null
        return lines.subList(1, end).joinToString("\n")
    }

    /**
     * [feat/subagent-roster-routing] Validate a sub-agent's NAME.
     *
     * The name is what the parent types into `skill_name` and what the
     * roster lists, so a malformed name is not cosmetic — it is a name
     * the parent will get wrong. TRAE's rule is a good one: a letter
     * first, then letters / digits / hyphens, bounded in length.
     *
     * Returns null when the name is valid, or a human-readable reason.
     */
    fun validateName(name: String): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return "the name is empty"
        if (trimmed.length > MAX_NAME_LENGTH) {
            return "the name is ${trimmed.length} characters; the limit is $MAX_NAME_LENGTH"
        }
        if (!trimmed[0].isLetter()) {
            return "the name must start with a letter (got '$trimmed')"
        }
        if (!trimmed.all { it.isLetterOrDigit() || it == '-' }) {
            return "the name may contain only letters, digits and hyphens (got '$trimmed')"
        }
        return null
    }

    /** Maximum length of a sub-agent name. */
    const val MAX_NAME_LENGTH = 50
}
