package com.openminis.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Semantic accent colors for data-classified UI: tool-type accents, session
 * category icons, and provider brand dots.
 *
 * These are NOT part of [ChatPalette] (which covers app chrome: background,
 * text, bubbles, borders, etc.) and NOT part of Material [androidx.compose.material3.ColorScheme]
 * (which covers the settings/component surfaces). They describe *what an item
 * is* — which tool, which category, which provider — so their meaning is data,
 * not styling. Centralising them here is the single authority that keeps the
 * same tool/category/provider the same colour everywhere it appears.
 *
 * Before FE-1 this was three near-identical hand-copied `when` tables spread
 * across ChatToolFormatting / SessionsShared+MoveToSessionSheet /
 * ChatModelPickerSheet+ModelEntryPicker. Keeping them in sync by hand is how
 * the two `categoryStyle` copies (SessionsShared vs MoveToSessionSheet) and
 * two `providerDotColor` copies drifted into existence.
 */

/**
 * Accent colour for a tool pill / duration / status.
 *
 * [ui-polish-A] Split into a light and a dark table. The single table that
 * used to live here was lifted verbatim from iOS, whose system colours are
 * tuned for dark backgrounds; on white they lose their hue at small sizes.
 * Measured against #FFFFFF, four of the eight fell under the 3:1 WCAG
 * non-text threshold — and these values are drawn as 14dp glyphs and 3dp
 * rails, which is exactly the "large-scale" exemption they cannot claim:
 *
 *     shell (#34C759) 2.22   fileRead/search (#32ADE6) 2.54
 *     fileEdit (#FF9500) 2.20   fileWrite (#007AFF) 4.02
 *
 * The light table keeps each hue but darkens it until it clears 4.5:1, which
 * also makes the values safe for the places that use them as *text* (the
 * duration label, the tool-detail header). The dark table is the original
 * iOS set, which already measured >=4.5:1 on the #1C1C1E card — read it via
 * [ToolAccentsDark]. Pick between them with `ChatColors.isDark`, as
 * `toolAccentColor()` does.
 */
object ToolAccents {
    // Light mode. Same hue family as the iOS originals, darkened to clear
    // 4.5:1 on white (values in the comment are the measured ratios).
    val shell = Color(0xFF15803D)     // was #34C759 -> 5.02
    val fileRead = Color(0xFF0E7490)  // was #32ADE6 -> 5.36
    val fileWrite = Color(0xFF0B5FCC) // was #007AFF -> 5.96
    val fileEdit = Color(0xFFB45309)  // was #FF9500 -> 5.02
    val browser = Color(0xFF0B5FCC)
    val image = Color(0xFF7E22CE)     // was #AF52DE -> 6.98
    val memory = Color(0xFFBE123C)    // was #FF2D55 -> 6.29
    val search = Color(0xFF0E7490)
    val fallback = Color(0xFF6B7280)  // was #8E8E93 -> 4.83
}

/**
 * Dark-mode counterpart of [ToolAccents] — the original iOS system colours,
 * which were already above threshold on the dark card (#1C1C1E / #000):
 * shell 8.42, fileRead 9.89, fileWrite 6.01, fileEdit 8.28, image 4.83,
 * memory 4.83, fallback 5.93.
 */
object ToolAccentsDark {
    val shell = Color(0xFF30D158)
    val fileRead = Color(0xFF64D2FF)
    val fileWrite = Color(0xFF409CFF)
    val fileEdit = Color(0xFFFF9F0A)
    val browser = Color(0xFF409CFF)
    val image = Color(0xFFBF5AF2)
    val memory = Color(0xFFFF375F)
    val search = Color(0xFF64D2FF)
    val fallback = Color(0xFF98989D)
}

/**
 * Session category icon tint. Mirrors the iOS 16-category table
 * (ContentView.swift) — same category keeps the same hue across the session
 * drawer, the "Move to…" sheet and the edit sheet's category grid.
 *
 * [ui-contrast] Split into a light and a dark table, same rationale as
 * [ToolAccents]: the single table was lifted verbatim from iOS, whose system
 * colours are tuned for dark surfaces. Rendered as a 17dp glyph on an 18%
 * self-tinted chip over the white drawer card, 9 of the 16 fell under the
 * 3:1 WCAG non-text threshold — chat green measured 1.92:1, productivity
 * yellow 1.39:1, finance teal 1.82:1. The light table darkens each hue
 * (per-value factor, hue preserved) until it clears 3:1 on its own chip;
 * the dark table keeps iOS dark-variant system colours, which all measure
 * >= 5.9:1 on the dark cards.
 *
 * Pick between them with `ChatColors.isDark` — [categoryStyle] does this via
 * its `isDark` parameter.
 */
object CategoryAccents {
    // Light mode. Same hue family as the iOS originals, darkened just enough
    // for the 17dp glyph to clear 3:1 on its own 18% chip over white.
    val code = Color(0xFFB67529)         // was #F09A37 (1.95) -> 3.07
    val writing = Color(0xFF3478F6)      // unchanged — 3.26
    val research = Color(0xFF2790A3)     // was #30B0C7 (2.19) -> 3.04
    val analysis = Color(0xFF5856D6)     // unchanged — 4.39
    val creative = Color(0xFFF42B51)     // was #FF2D55 (2.82) -> 3.05
    val chat = Color(0xFF279743)         // was #34C759 (1.92) -> 3.03
    val math = Color(0xFF9B59B6)         // unchanged — 3.70
    val translation = Color(0xFF008EA1)  // was #00BCD4 (1.95) -> 3.13
    val health = Color(0xFFEF372D)       // was #FF3B30 (2.79) -> 3.10
    val finance = Color(0xFF00938C)      // was #00C7BE (1.82) -> 3.04
    val travel = code
    val education = writing
    val design = creative
    val productivity = Color(0xFF9E7E00) // was #FFCC00 (1.39) -> 3.14
    val support = Color(0xFF8B6914)      // unchanged — 4.01
    val other = Color(0xFF85858A)        // was #8E8E93 (2.74) -> 3.03
    val fallback = other
}

/**
 * Dark-mode counterpart of [CategoryAccents] — iOS dark-variant system
 * colours, all well above threshold on the dark cards (#1C1C1E / #000):
 * code 9.54, research 8.55, creative 5.99, chat 9.25, health 6.03,
 * finance 11.27, productivity 12.05, other 5.93.
 */
object CategoryAccentsDark {
    val code = Color(0xFFFFB340)
    val writing = Color(0xFF6CA6FF)
    val research = Color(0xFF40C8E0)
    val analysis = Color(0xFF8C8AF0)
    val creative = Color(0xFFFF6482)
    val chat = Color(0xFF4CD964)
    val math = Color(0xFFC085E0)
    val translation = Color(0xFF40C8E0)
    val health = Color(0xFFFF6961)
    val finance = Color(0xFF4AE8E0)
    val travel = code
    val education = writing
    val design = creative
    val productivity = Color(0xFFFFD60A)
    val support = Color(0xFFC0A468)
    val other = Color(0xFF98989D)
    val fallback = other
}

/**
 * Provider brand dot. Same colour across the model picker, model-group sheets,
 * and the agent-loop sheets so the cue stays consistent (see ModelEntryPicker).
 */
object ProviderAccents {
    val anthropic = Color(0xFFAB47BC) // purple
    val gemini = Color(0xFF42A5F5)    // blue
    val openAI = Color(0xFF4CAF50)    // green
    val openRouter = Color(0xFF00BCD4) // cyan
    val xAI = Color(0xFFFF7043)        // orange — Grok brand
    val kimiCode = Color(0xFF5C6BC0)   // indigo — Kimi accent
    val antigravity = Color(0xFF4A90D9) // blue-violet — Antigravity accent
    // [T-workbuddy-oauth] WorkBuddy — the CodeBuddy brand blue.
    val workBuddy = Color(0xFF2E7CE0)
    val fallback = Color(0xFF8E8E93)   // gray
}
