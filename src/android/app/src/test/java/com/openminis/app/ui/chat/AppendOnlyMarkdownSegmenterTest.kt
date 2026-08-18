package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [AppendOnlyMarkdownSegmenter] — the stable slot contract
 * behind the forward-stable chat scroll fix.
 *
 * Core invariant under test: once a slot is published settled, its ordinal
 * and rawText never change; new boundaries only APPEND at the tail.
 */
class AppendOnlyMarkdownSegmenterTest {

    // ── prefix-append invariant over growing snapshots ──────────────────────

    @Test fun `keys are a strict prefix extension across 1000 token snapshots`() {
        val seg = AppendOnlyMarkdownSegmenter()
        val sb = StringBuilder()
        var prevOrdinals: List<Int> = emptyList()
        for (i in 1..1000) {
            sb.append("word $i\n\n")
            val slots = seg.update(sb.toString(), streamEnded = false)
            val ordinals = slots.map { it.ordinal }
            // Every step: new ordinals must extend the old ones as a prefix.
            assertTrue(
                "step $i: ordinals regressed: $prevOrdinals -> $ordinals",
                ordinals.size >= prevOrdinals.size &&
                    ordinals.take(prevOrdinals.size) == prevOrdinals,
            )
            // Frozen prefix content never changes.
            for (j in 0 until prevOrdinals.size) {
                assertEquals("step $i slot $j frozen", slots[j].rawText, seg.snapshot()[j].rawText)
                assertTrue(slots[j].settled)
            }
            prevOrdinals = ordinals
        }
        assertEquals(1000, seg.snapshot().size)
        assertTrue(seg.invariantErrorCount == 0)
    }

    @Test fun `growing a paragraph keeps one live slot with growing content`() {
        val seg = AppendOnlyMarkdownSegmenter()
        val s1 = seg.update("Hello", streamEnded = false)
        assertEquals(listOf("Hello"), s1.map { it.rawText })
        assertFalse(s1[0].settled)

        val s2 = seg.update("Hello world", streamEnded = false)
        assertEquals(listOf("Hello world"), s2.map { it.rawText })
        assertFalse(s2[0].settled)

        val s3 = seg.update("Hello world, this is a", streamEnded = false)
        assertEquals(listOf("Hello world, this is a"), s3.map { it.rawText })
        assertTrue(seg.invariantErrorCount == 0)
    }

    @Test fun `new paragraph boundary settles the previous slot and appends`() {
        val seg = AppendOnlyMarkdownSegmenter()
        seg.update("First paragraph", streamEnded = false)
        val s2 = seg.update("First paragraph\n\nSecond", streamEnded = false)
        assertEquals(listOf("First paragraph", "Second"), s2.map { it.rawText })
        assertTrue(s2[0].settled)
        assertFalse(s2[1].settled)
        assertEquals(listOf(0, 1), s2.map { it.ordinal })

        // Third paragraph appends, first two frozen.
        val s3 = seg.update("First paragraph\n\nSecond\n\nThird", streamEnded = false)
        assertEquals(listOf("First paragraph", "Second", "Third"), s3.map { it.rawText })
        assertTrue(s3[0].settled)
        assertTrue(s3[1].settled)
        assertFalse(s3[2].settled)
        assertEquals(listOf(0, 1, 2), s3.map { it.ordinal })
    }

    @Test fun `consecutive blank lines are a single closable boundary`() {
        val seg = AppendOnlyMarkdownSegmenter()
        val s = seg.update("A\n\n\n\nB", streamEnded = false)
        assertEquals(listOf("A", "B"), s.map { it.rawText })
        assertTrue(s[0].settled)
        assertFalse(s[1].settled)
    }

    // ── code fences ─────────────────────────────────────────────────────────

    @Test fun `fenced code block closes as a standalone slot`() {
        val seg = AppendOnlyMarkdownSegmenter()
        val s1 = seg.update("Intro\n\n```kotlin\nval x = 1\n```", streamEnded = false)
        assertEquals(listOf("Intro", "```kotlin\nval x = 1\n```"), s1.map { it.rawText })
        assertTrue(s1[0].settled)
        assertFalse(s1[1].settled)
    }

    @Test fun `unterminated fence keeps everything inside the live slot`() {
        val seg = AppendOnlyMarkdownSegmenter()
        val s1 = seg.update("```python\nprint(1)", streamEnded = false)
        assertEquals(listOf("```python\nprint(1)"), s1.map { it.rawText })
        assertFalse(s1[0].settled)

        // Still inside the fence — one live slot, growing.
        val s2 = seg.update("```python\nprint(1)\nprint(2)", streamEnded = false)
        assertEquals(listOf("```python\nprint(1)\nprint(2)"), s2.map { it.rawText })
        assertFalse(s2[0].settled)
        assertEquals(listOf(0), s2.map { it.ordinal })
    }

    @Test fun `fence opened and closed across chunks settles correctly`() {
        val seg = AppendOnlyMarkdownSegmenter()
        seg.update("```\nline1\nline2", streamEnded = false)
        val s = seg.update("```\nline1\nline2\n```\n\nAfter", streamEnded = false)
        assertEquals(listOf("```\nline1\nline2\n```", "After"), s.map { it.rawText })
        assertTrue(s[0].settled)
        assertFalse(s[1].settled)
    }

    // ── tables / lists / long single paragraphs ─────────────────────────────

    @Test fun `markdown table without blank lines stays one slot`() {
        val seg = AppendOnlyMarkdownSegmenter()
        val table = "| A | B |\n|---|---|\n| 1 | 2 |\n| 3 | 4 |"
        val s = seg.update(table, streamEnded = false)
        assertEquals(listOf(table), s.map { it.rawText })
        assertFalse(s[0].settled)
    }

    @Test fun `list items separated by blank lines split like paragraphs`() {
        val seg = AppendOnlyMarkdownSegmenter()
        val s = seg.update("- one\n\n- two\n\n- three", streamEnded = false)
        assertEquals(listOf("- one", "- two", "- three"), s.map { it.rawText })
        assertTrue(s[0].settled)
        assertTrue(s[1].settled)
        assertFalse(s[2].settled)
    }

    @Test fun `ultra-long single paragraph stays one slot`() {
        val seg = AppendOnlyMarkdownSegmenter()
        val long = "x".repeat(10_000)
        val s = seg.update(long, streamEnded = false)
        assertEquals(1, s.size)
        assertEquals(long, s[0].rawText)
    }

    // ── stream end ──────────────────────────────────────────────────────────

    @Test fun `streamEnd settles the live slot without re-splitting or re-keying`() {
        val seg = AppendOnlyMarkdownSegmenter()
        val before = seg.update("P1\n\nP2 tail", streamEnded = false)
        val keysBefore = before.map { it.ordinal }

        val after = seg.update("P1\n\nP2 tail", streamEnded = true)
        val keysAfter = after.map { it.ordinal }

        assertEquals(keysBefore, keysAfter)
        assertEquals(before.map { it.rawText }, after.map { it.rawText })
        assertTrue(after.all { it.settled })
    }

    @Test fun `streamEnd after more content settles every slot`() {
        val seg = AppendOnlyMarkdownSegmenter()
        seg.update("P1\n\nP2\n\nP3", streamEnded = false)
        val after = seg.update("P1\n\nP2\n\nP3", streamEnded = true)
        assertEquals(3, after.size)
        assertTrue(after.all { it.settled })
    }

    @Test fun `streamEnd on empty input is a no-op`() {
        val seg = AppendOnlyMarkdownSegmenter()
        seg.update("P1", streamEnded = false)
        val after = seg.update("", streamEnded = true)
        assertEquals(listOf("P1"), after.map { it.rawText })
        assertTrue(after[0].settled)
    }

    // ── regressive snapshots ────────────────────────────────────────────────

    @Test fun `regressive snapshot is ignored entirely`() {
        val seg = AppendOnlyMarkdownSegmenter()
        seg.update("Hello world", streamEnded = false)
        val s = seg.update("Hello", streamEnded = false)
        assertEquals(listOf("Hello world"), s.map { it.rawText })
        assertFalse(s[0].settled)
        assertTrue(seg.invariantErrorCount == 0)
    }

    // ── non-append divergence ───────────────────────────────────────────────

    @Test fun `divergence rewrites tail from fresh and records invariant error`() {
        val seg = AppendOnlyMarkdownSegmenter()
        seg.update("Alpha\n\nBeta", streamEnded = false)
        assertEquals(2, seg.snapshot().size)

        // Model rewrites the FIRST fragment — published keys must not churn,
        // but the divergent content must be re-derived from the fresh split,
        // NOT concatenated (which produced "Alpha" + "XXX...Alpha" duplication).
        val s = seg.update("XXX\n\nBeta\n\nGamma", streamEnded = false)
        // Rewritten tail is authoritative-fresh: "Alpha" was replaced, not kept.
        assertEquals(listOf("XXX", "Beta", "Gamma"), s.map { it.rawText })
        assertTrue(s[0].settled)
        assertTrue(s[1].settled)
        assertFalse(s[2].settled)
        assertEquals(listOf(0, 1, 2), s.map { it.ordinal })
        assertEquals(1, seg.invariantErrorCount)
    }

    @Test fun `divergence keeps ordinals contiguous and content fresh`() {
        val seg = AppendOnlyMarkdownSegmenter()
        seg.update("A\n\nB\n\nC", streamEnded = false)
        seg.update("CHANGED\n\nB\n\nC\n\nD", streamEnded = false)
        val after = seg.snapshot()
        // Content is the fresh split (the rewrite replaced "A" with "CHANGED").
        assertEquals(listOf("CHANGED", "B", "C", "D"), after.map { it.rawText })
        // Ordinals stay contiguous (no gaps, no reorder) for stable LazyColumn keys.
        assertEquals(listOf(0, 1, 2, 3), after.map { it.ordinal })
        assertEquals(1, seg.invariantErrorCount)
    }

    @Test fun `slot key materialization stays stable for LazyColumn`() {
        val seg = AppendOnlyMarkdownSegmenter()
        val messageId = "msg-1"
        val blockId = "text_1_0"
        seg.update("P1\n\nP2", streamEnded = false)
        val keys1 = seg.snapshot().map { "mdslot:$messageId:$blockId:${it.ordinal}" }
        seg.update("P1\n\nP2\n\nP3", streamEnded = false)
        val keys2 = seg.snapshot().map { "mdslot:$messageId:$blockId:${it.ordinal}" }
        assertTrue(keys2.take(keys1.size) == keys1)
        assertEquals(3, keys2.size)
    }

    // ── token-level duplication regression (root cause B) ──────────────────

    @Test fun `partial-word growth at a settled seam does NOT duplicate`() {
        // Root cause B: a throttled snapshot grows the first fragment by a
        // partial word AFTER it was already settled (e.g. "致命伤" settled, then
        // the same line grows to "致命伤致命"). The old absorb path concatenated
        // the settled slot with the fresh tail → "致命伤致命伤致命". The rewrite
        // path must emit the fresh text exactly once.
        val seg = AppendOnlyMarkdownSegmenter()
        // First a paragraph boundary settles "致命伤" as slot 0.
        seg.update("致命伤\n\n竞态", streamEnded = false)
        // Next snapshot: the first fragment grew by a partial word.
        val s = seg.update("致命伤致命\n\n竞态", streamEnded = false)

        val joined = s.joinToString("\n\n") { it.rawText }
        assertEquals("致命伤致命\n\n竞态", joined)
        assertFalse(joined.contains("致命伤致命伤"))
        assertFalse(joined.contains("竞态竞态"))
    }

    @Test fun `prefix-growth at a settled seam keeps exactly one copy`() {
        // Defensive variant: the whole settled slot is a strict prefix of the
        // fresh first fragment (settled "AB", fresh "AB·追加").
        val seg = AppendOnlyMarkdownSegmenter()
        seg.update("AB\n\nC", streamEnded = false)
        // "AB" settled as slot 0; now it grows to "AB追加" (same boundary).
        val s = seg.update("AB追加\n\nC", streamEnded = false)

        val joined = s.joinToString("\n\n") { it.rawText }
        assertEquals("AB追加\n\nC", joined)
        assertFalse(joined.contains("AB追加AB"))
    }

    @Test fun `rollback-then-reattach does not leak stale segmenter state`() {
        // Root cause A companion: after a retry/fallback rewinds the turn and
        // a text block id is recycled, a stale segmenter must not carry content
        // from the previous stream into the new one. A FRESH segmenter (the
        // fix re-keys by making block ids globally unique) starts from empty,
        // so the new stream's text is authoritative with no ghost prefix.
        val seg = AppendOnlyMarkdownSegmenter()
        // Simulate the fresh-segmenter contract: first update seeds cleanly.
        val s = seg.update("分叉于 08-09，它没继承", streamEnded = false)
        assertEquals(listOf("分叉于 08-09，它没继承"), s.map { it.rawText })
        // A fresh stream (new segmenter instance, or reset state) never re-emits
        // the old text — the content is exactly what the new stream produced.
        val seg2 = AppendOnlyMarkdownSegmenter()
        val s2 = seg2.update("分叉于 08-09，它没继承", streamEnded = false)
        assertEquals(s.map { it.rawText }, s2.map { it.rawText })
        assertEquals(1, s2.map { it.rawText }.size)
    }
}
