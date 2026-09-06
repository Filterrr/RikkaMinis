package com.openminis.app.tools

/**
 * [T-subagent-ui-ansi] Minimal ANSI SGR (Select Graphic Rendition) parser
 * for rendering colored tool output in the sub-agent detail page.
 *
 * Why here and not TerminalSanitizer: the sanitizer's job is to make text
 * safe for LLM consumption — escape codes are noise to a model. Rendering
 * is the opposite: the escape codes ARE the styling information. This
 * parser keeps a compatible-but-looser CSI matcher and turns SGR runs into
 * [AnsiSpan]s; everything it cannot understand is dropped silently so a
 * malformed stream can never break the log UI.
 *
 * Pure Kotlin (no Compose / no Android imports) so it unit-tests on the
 * JVM alongside SubagentRunRegistryTest. Color values are packed ARGB ints
 * (0xAARRGGBB) decoded from xterm-256 / truecolor SGR params; the Compose
 * layer maps them with alpha adjustments against the chat palette.
 */
object SubagentAnsiText {

    /** One styled run of text. [color] / [bgColor] null = inherit default. */
    data class AnsiSpan(
        val text: String,
        val color: Int? = null,
        val bgColor: Int? = null,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val underline: Boolean = false,
    )

    /**
     * Render-time safety cap. Pathological streams (escape spam from a
     * runaway TUI) would otherwise allocate one span per code; past the
     * cap the remainder is emitted as a single unstyled span with escapes
     * stripped. Generous enough that real tool output (ls --color, pytest,
     * grep --color, cargo, npm) never hits it.
     */
    const val MAX_RENDER_SPANS = 2_000

    /**
     * Input cap before parsing. The registry keeps the last 60 lines, but
     * a single line can still be tens of KB (base64 dumps, minified JSON);
     * past this cap the HEAD is dropped and only the tail is parsed — the
     * tail is where streaming output and results live.
     */
    const val MAX_PARSE_CHARS = 30_000

    /**
     * Matches ANSI/VT escape sequences, deliberately broader than
     * TerminalSanitizer's pattern: CSI with private-marker chars (? > =),
     * OSC strings (BEL or ST terminated), and 2-char simple escapes.
     * Only SGR sequences (CSI ... 'm') carry styling; every other match is
     * consumed and discarded.
     */
    private val ESCAPE_REGEX = Regex(
        """\x1B(?:\[[0-9;?]*[A-Za-z]|\][^\x07\x1B]*(?:\x07|\x1B\\)|[@-Z\\=>])""",
    )

    // xterm-256 base/bright palette lookup (indices 0-15) — packed ARGB.
    private val BASE16 = intArrayOf(
        0xFF000000.toInt(), // 0  black
        0xFFCD3131.toInt(), // 1  red
        0xFF0DBC79.toInt(), // 2  green
        0xFFE5E510.toInt(), // 3  yellow
        0xFF2472C8.toInt(), // 4  blue
        0xFFBC3FBC.toInt(), // 5  magenta
        0xFF11A8CD.toInt(), // 6  cyan
        0xFFE5E5E5.toInt(), // 7  white
        0xFF666666.toInt(), // 8  bright black (gray)
        0xFFF14C4C.toInt(), // 9  bright red
        0xFF23D18B.toInt(), // 10 bright green
        0xFFF5F543.toInt(), // 11 bright yellow
        0xFF3B8EEA.toInt(), // 12 bright blue
        0xFFD670D6.toInt(), // 13 bright magenta
        0xFF29B8DB.toInt(), // 14 bright cyan
        0xFFFFFFFF.toInt(), // 15 bright white
    )

    /** 6×6×6 cube step values (xterm indices 16-231). */
    private val CUBE_STEPS = intArrayOf(0, 95, 135, 175, 215, 255)

    /** 24 grays (xterm indices 232-255): 8 + 10·i. */
    private fun gray(index: Int): Int {
        val v = 8 + 10 * index
        return argb(v, v, v)
    }

    private fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    /**
     * Parse [raw] into styled spans. Unknown/non-SGR escapes are dropped;
     * a dangling (truncated) escape at the end of a chunk yields one plain
     * span of the remaining text so live output never swallows a tail.
     */
    fun parse(rawInput: String): List<AnsiSpan> {
        if (rawInput.isEmpty()) return emptyList()
        // Tail-keep cap: a single oversized line must not stall the UI thread.
        val raw = if (rawInput.length > MAX_PARSE_CHARS) {
            rawInput.takeLast(MAX_PARSE_CHARS)
        } else {
            rawInput
        }
        if (!raw.contains('\u001B')) return listOf(AnsiSpan(text = raw))

        val spans = mutableListOf<AnsiSpan>()
        val plain = StringBuilder()
        var fg: Int? = null
        var bg: Int? = null
        var bold = false
        var italic = false
        var underline = false

        fun flush() {
            if (plain.isNotEmpty()) {
                spans += AnsiSpan(plain.toString(), fg, bg, bold, italic, underline)
                plain.clear()
            }
        }

        var i = 0
        val n = raw.length
        while (i < n) {
            val ch = raw[i]
            if (ch != '\u001B') {
                plain.append(ch)
                i++
                continue
            }
            val match = ESCAPE_REGEX.matchAt(raw, i)
            if (match == null) {
                // Dangling "\x1B" or "\x1B[3" split across stream chunks —
                // keep the literal remainder visible instead of eating it.
                plain.append(raw.substring(i))
                break
            }
            val sequence = match.value
            i += sequence.length
            if (sequence.length > 2 && sequence[1] == '[' && sequence.endsWith("m")) {
                val params = sequence.substring(2, sequence.length - 1)
                var nextFg = fg
                var nextBg = bg
                var nextBold = bold
                var nextItalic = italic
                var nextUnderline = underline
                for (param in params.split(';')) {
                    val code = param.toIntOrNull() ?: continue
                    when (code) {
                        0 -> {
                            nextFg = null; nextBg = null
                            nextBold = false; nextItalic = false; nextUnderline = false
                        }
                        1 -> nextBold = true
                        3 -> nextItalic = true
                        4 -> nextUnderline = true
                        21, 22 -> nextBold = false
                        23 -> nextItalic = false
                        24 -> nextUnderline = false
                        39 -> nextFg = null
                        49 -> nextBg = null
                        in 30..37 -> nextFg = BASE16[code - 30]
                        in 90..97 -> nextFg = BASE16[code - 90 + 8]
                        in 40..47 -> nextBg = BASE16[code - 40]
                        in 100..107 -> nextBg = BASE16[code - 100 + 8]
                        38, 48 -> {
                            // Extended color: 38;5;<idx> or 38;2;<r>;<g>;<b>.
                            val list = params.split(';')
                            val idx = list.indexOfFirst { it == param }
                            val target = if (code == 38) 0 else 1
                            val decoded = decodeExtended(list, idx)
                            if (decoded != null) {
                                if (target == 0) nextFg = decoded else nextBg = decoded
                            }
                        }
                    }
                }
                // An SGR run always restyles: anything buffered before it
                // becomes its own span so the styles don't bleed together.
                flush()
                fg = nextFg; bg = nextBg
                bold = nextBold; italic = nextItalic; underline = nextUnderline
            } else {
                // Cursor movement / OSC / private mode — styling-neutral.
                // Drop, do NOT flush: text before and after shares style.
            }
            if (spans.size >= MAX_RENDER_SPANS) {
                flush()
                val rest = ESCAPE_REGEX.replace(raw.substring(i), "")
                if (rest.isNotEmpty()) spans += AnsiSpan(rest)
                return spans
            }
        }
        flush()
        return spans
    }

    /**
     * Decode an extended-color sequence starting at [idx] (which holds
     * "38" or "48"): "5;<idx>" for the 256 palette, "2;<r>;<g>;<b>" for
     * truecolor. Returns null when malformed — the caller keeps the
     * previous color.
     */
    private fun decodeExtended(list: List<String>, idx: Int): Int? {
        if (idx + 1 >= list.size) return null
        return when (list[idx + 1]) {
            "5" -> {
                val c = list.getOrNull(idx + 2)?.toIntOrNull() ?: return null
                when {
                    c < 16 -> BASE16[c]
                    c < 232 -> {
                        val rest = c - 16
                        val b = rest % 6
                        val g = (rest / 6) % 6
                        val r = rest / 36
                        argb(CUBE_STEPS[r], CUBE_STEPS[g], CUBE_STEPS[b])
                    }
                    else -> gray((c - 232).coerceIn(0, 23))
                }
            }
            "2" -> {
                val r = list.getOrNull(idx + 2)?.toIntOrNull() ?: return null
                val g = list.getOrNull(idx + 3)?.toIntOrNull() ?: return null
                val b = list.getOrNull(idx + 4)?.toIntOrNull() ?: return null
                if (r !in 0..255 || g !in 0..255 || b !in 0..255) return null
                argb(r, g, b)
            }
            else -> null
        }
    }

    /**
     * Last [lineCount] lines of an already-parsed [spans] list, preserving
     * each span's styling. Collapsed output boxes slice the TAIL (the
     * informative end of a stream); slicing the parsed spans — not the raw
     * string — is what keeps escape sequences from being split mid-code.
     * Leading spans on the first kept line may have started on an earlier
     * line; their styling carries over, which mirrors what a terminal
     * would show when scrolled.
     */
    fun tailLines(spans: List<AnsiSpan>, lineCount: Int): List<AnsiSpan> {
        if (lineCount <= 0) return emptyList()
        val lineBreaks = spans.indices.map { idx -> spans[idx].text.count { it == '\n' } }
        val totalLines = lineBreaks.sum() + if (spans.isEmpty()) 0 else 1
        if (totalLines <= lineCount) return spans
        var linesToSkip = totalLines - lineCount
        val out = mutableListOf<AnsiSpan>()
        for (idx in spans.indices) {
            val text = spans[idx].text
            val breaks = lineBreaks[idx]
            if (breaks < linesToSkip) {
                linesToSkip -= breaks
                continue  // this span lies entirely above the cut
            }
            // Walk the span, dropping whole lines until the cut lands mid-span.
            var start = 0
            while (linesToSkip > 0) {
                val nl = text.indexOf('\n', start)
                if (nl < 0) break
                start = nl + 1
                linesToSkip--
            }
            val remainder = if (start == 0) text else text.substring(start)
            if (remainder.isNotEmpty()) {
                out += AnsiSpan(
                    remainder, spans[idx].color, spans[idx].bgColor,
                    spans[idx].bold, spans[idx].italic, spans[idx].underline,
                )
            }
            // Everything after idx is below the cut.
            for (j in idx + 1 until spans.size) {
                out += spans[j]
            }
            return out
        }
        return out
    }
}
