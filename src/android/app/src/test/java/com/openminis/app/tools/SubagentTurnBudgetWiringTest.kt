package com.openminis.app.tools

import com.openminis.app.ProductionSources
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [feat/subagent-max-turns-75] Guards for the shipped sub-agent turn budget.
 *
 * Three separate things have to be true for "max_turns: 75" to mean
 * anything at runtime, and each one has its own way of silently failing:
 *
 *  1. the SHIPPED asset declares it — a number in a file nothing reads is
 *     a wish;
 *  2. the RUNTIME parses it — the parser clamps to [1, 100] and falls back
 *     to 12, so a frontmatter the parser mis-reads turns 75 into something
 *     else with no error;
 *  3. the CLOCK can deliver it — the run stops at whichever limit comes
 *     first, so a 75-turn skill under a 900s wall-clock budget is killed
 *     at ~turn 60 and the parent sees a TIMED_OUT verdict that reads like
 *     the model ran out of patience.
 *
 * The fourth guard is about DELIVERY: bundled skills are version-gated
 * (`SkillRepository.installBundledSkill` skips an installed skill whose
 * version is same-or-newer), so changing `max_turns` without bumping
 * `version` leaves every already-installed app on the OLD number forever —
 * the change is in the APK and never reaches the device.
 */
class SubagentTurnBudgetWiringTest {

    /** The sub-agent skills that ship in the APK and carry a turn budget. */
    private val shippedSubagentSkills = listOf(
        "general-agent" to "1.4.6",
        "read-only-researcher" to "1.0.4",
    )

    private fun assetsDir(): File {
        val root = ProductionSources.mainRoot()
            ?: error("production source root not found — the guard cannot run")
        val assets = File(root.parentFile.parentFile.parentFile.parentFile, "assets/skills")
        if (!assets.isDirectory) {
            error("shipped skills directory not found at ${assets.absolutePath}")
        }
        return assets
    }

    private fun skillFrontmatter(dir: String): String {
        val f = File(assetsDir(), "$dir/SKILL.md")
        if (!f.isFile) error("shipped skill not found: ${f.absolutePath}")
        val text = f.readText()
        val start = text.indexOf("---")
        val end = text.indexOf("---", start + 3)
        if (start < 0 || end < 0) error("no frontmatter fences in ${f.absolutePath}")
        return text.substring(start + 3, end)
    }

    /** Semantic-ish compare: 1.10.0 > 1.9.0 (a lexical compare gets this wrong). */
    private fun compareVersions(a: String, b: String): Int {
        fun parts(raw: String) = raw.trim().removePrefix("v").split('.')
            .map { it.trim().toIntOrNull() ?: 0 }
        val ap = parts(a)
        val bp = parts(b)
        for (i in 0 until maxOf(ap.size, bp.size)) {
            val av = ap.getOrElse(i) { 0 }
            val bv = bp.getOrElse(i) { 0 }
            if (av != bv) return av.compareTo(bv)
        }
        return 0
    }

    // ── 1. the asset declares it ──────────────────────────────────────────

    @Test
    fun `every shipped sub-agent skill declares the 75-turn budget`() {
        for ((dir, _) in shippedSubagentSkills) {
            val fm = SubagentFrontmatter.parse(skillFrontmatter(dir))
            val declared = fm.int("max_turns")
            assertTrue("$dir must declare max_turns", declared != null)
            assertEquals(
                "$dir must declare max_turns: 75",
                75,
                declared,
            )
        }
    }

    // ── 2. the runtime parses it ──────────────────────────────────────────

    @Test
    fun `the runtime actually resolves 75 turns from the shipped assets`() {
        // The frontmatter is stripped from the skill body before it reaches
        // the model, and `parseSubagentConfig` reads `skill.frontmatter`
        // FIRST with the body as a fallback. This pins the whole path:
        // asset file → frontmatter → parsed config → the number the run
        // loop will actually enforce.
        for ((dir, _) in shippedSubagentSkills) {
            val fm = skillFrontmatter(dir)
            val skill = object : SkillInfo {
                override val name = dir
                override val description = ""
                override val body = ""
                override val frontmatter: String? = fm
            }
            val config = SubagentSkill.parseSubagentConfig(skill)
            assertTrue("$dir must be a sub-agent skill", config.isSubagent)
            assertEquals(
                "$dir must resolve to 75 turns at runtime",
                75,
                config.maxTurns,
            )
        }
    }

    @Test
    fun `the runtime turn budget agrees with the shipped constant`() {
        // One number, one source. If the constant and the assets disagree,
        // the constant is a lie the clock is calibrated against.
        assertEquals(
            "SHIPPED_SUBAGENT_MAX_TURNS must match what the skills declare",
            75,
            SubagentSkill.SHIPPED_SUBAGENT_MAX_TURNS,
        )
    }

    // ── 3. the clock can deliver it ───────────────────────────────────────

    @Test
    fun `the default wall-clock budget can deliver the advertised turn budget`() {
        // This is the invariant that was broken: 900s covered ~60 turns, so
        // a 75-turn skill died on the clock at turn 60 and reported
        // TIMED_OUT — indistinguishable, to the parent, from the model
        // giving up.
        val required = SubagentSkill.wallClockSecondsForTurnBudget(
            SubagentSkill.SHIPPED_SUBAGENT_MAX_TURNS,
        )
        assertNotNull(
            "the 75-turn budget must be deliverable within the max timeout",
            required,
        )
        assertTrue(
            "the default wall-clock budget ($required s required, " +
                "${SubagentSkill.DEFAULT_TIMEOUT_SECONDS} s default) must cover the turn budget",
            required!! <= SubagentSkill.DEFAULT_TIMEOUT_SECONDS,
        )
    }

    @Test
    fun `a turn budget beyond the wall-clock ceiling is reported as unreachable`() {
        // The failure mode has to be LOUD. If the ceiling cannot deliver the
        // turns, the function returns null instead of a number that silently
        // promises something impossible.
        assertEquals(
            "a zero turn budget needs no time",
            0,
            SubagentSkill.wallClockSecondsForTurnBudget(0),
        )
        // assertEquals(null, x) would bind to the assertEquals(String, Object)
        // overload and read the null as the MESSAGE — assert it explicitly.
        val unreachable = SubagentSkill.wallClockSecondsForTurnBudget(
            (SubagentSkill.MAX_TIMEOUT_SECONDS / SubagentSkill.SECONDS_PER_TURN_ESTIMATE) + 10,
        )
        assertTrue(
            "a turn budget above the ceiling must be unreachable, not clamped (got $unreachable)",
            unreachable == null,
        )
    }

    // ── the spawn site actually applies it ───────────────────────────────

    @Test
    fun `the spawn site derives the wall-clock budget from the turn budget`() {
        // The pure function is worth nothing if nobody calls it. The call
        // must be at the SPAWN SITE, where the skill's config is known and
        // the timeout is chosen.
        val runner = ProductionSources.read("tools/SubagentRunner.kt")
        assertTrue(
            "the spawn site must derive the timeout from the turn budget",
            runner.contains("SubagentSkill.wallClockSecondsForTurnBudget(config.maxTurns)"),
        )
        assertTrue(
            "the derived budget must actually feed the run's timeout",
            runner.contains("timeoutForTurnBudget.coerceAtLeast(SubagentSkill.DEFAULT_TIMEOUT_SECONDS)"),
        )
    }

    // ── 4. the change can reach an installed app ──────────────────────────

    @Test
    fun `the shipped skills bumped their version above the last release`() {
        // Bundled skills are version-gated on install: a skill already
        // installed at the same-or-newer version is left untouched. So a
        // `max_turns` change without a version bump ships in the APK and
        // never lands on the device — every existing install keeps the old
        // number, with no error anywhere.
        for ((dir, lastReleased) in shippedSubagentSkills) {
            val fm = SubagentFrontmatter.parse(skillFrontmatter(dir))
            val version = fm.string("version")
            assertNotNull("$dir must declare a version", version)
            assertTrue(
                "$dir ships version $version, which is not newer than the last " +
                    "released $lastReleased — the change would never be installed",
                compareVersions(version!!, lastReleased) > 0,
            )
        }
    }
}
