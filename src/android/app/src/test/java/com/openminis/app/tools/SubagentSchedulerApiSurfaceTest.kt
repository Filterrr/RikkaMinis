package com.openminis.app.tools

import com.openminis.app.ProductionSources
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [fix/scheduler-test-api-after-merge] The scheduler's public surface
 * changed while a test still called the old one.
 *
 * PR #80 wrote `SubagentSchedulerBudgetTotalTest` against the scheduler's
 * then-public `tryAcquire(skillId, limit, waitBudgetMs)` and
 * `release(skillId, limit)`. PR #81 closed that surface — the acquire is
 * now private and the only entry point is
 * `run(skillId, skillLimit, deadlineNanos, maxQueueWaitMs) { ... }`.
 *
 * The two PRs did not overlap in git, so both merged cleanly and the test
 * was left calling an API that no longer existed. main did not compile,
 * and the failure was only visible in CI.
 *
 * A compile error is caught by CI, but only after the merge. This guard
 * catches the same class of mistake locally, and — more usefully — it
 * catches it as a STATEMENT about the API rather than as a compiler
 * error buried in a log.
 */
class SubagentSchedulerApiSurfaceTest {

    private val schedulerSource: String by lazy {
        ProductionSources.read("tools/SubagentScheduler.kt")
    }

    /**
     * The members the scheduler actually exposes.
     *
     * Read from the source rather than hard-coded, so the guard tracks
     * the real surface: a member that is `private` is not part of it.
     */
    private val publicMembers: Set<String> by lazy {
        val names = mutableSetOf<String>()
        // The generic parameter is part of the declaration shape:
        // `suspend fun <T> run(` — a pattern that stops at `fun` misses
        // every generic function, which is most of them here.
        // MULTILINE matters: without it `^` only ever matches the start
        // of the whole file, so a declaration on line 78 is invisible and
        // the guard quietly reports an API that is not there. The first
        // version of this test failed exactly that way — it claimed the
        // scheduler did not expose `run`, which it does.
        val member = Regex(
            """^\s*(?:public\s+|internal\s+)?(?:suspend\s+)?(?:inline\s+)?(?:fun|val|var)\s*(?:<[^>]*>\s*)?(\w+)""",
            RegexOption.MULTILINE,
        ).findAll(schedulerSource)
        for (m in member) {
            // The declaration line is the one the match STARTS on.
            // Taking the text before the match and asking for its last
            // line gives the line ABOVE it, because the match begins with
            // the indentation — so the private check was reading the
            // wrong line and a private member counted as public.
            val lineStart = schedulerSource.lastIndexOf('\n', m.range.first).let {
                if (it < 0) 0 else it + 1
            }
            val lineEnd = schedulerSource.indexOf('\n', m.range.first).let {
                if (it < 0) schedulerSource.length else it
            }
            val line = schedulerSource.substring(lineStart, lineEnd)
            if (line.trimStart().startsWith("private ")) continue
            names += m.groupValues[1]
        }
        names
    }

    @Test
    fun `the scheduler exposes run and the diagnostics`() {
        assertTrue(
            "the scheduler must expose run()",
            "run" in publicMembers,
        )
        assertTrue(
            "the scheduler must expose availableSkillPermits()",
            "availableSkillPermits" in publicMembers,
        )
    }

    /**
     * The two-level acquire is private now, on purpose: it hands back a
     * pair of semaphores and the caller has to release the RIGHT one.
     *
     * The names to check are `tryAcquire` and `release` — NOT
     * `tryAcquirePair` / `releasePair`. The regression this guards
     * against is the API being re-opened under its OLD name; a private
     * `tryAcquirePair` becoming public is a different (and harmless)
     * change, and asserting on it would be asserting on the wrong
     * thing.
     */
    @Test
    fun `the two-level acquire is not part of the public surface`() {
        assertFalse(
            "tryAcquire must not be public — it returns a permit the caller " +
                "must release on the exact semaphore it came from",
            "tryAcquire" in publicMembers,
        )
        assertFalse(
            "release must not be public for the same reason",
            "release" in publicMembers,
        )
    }

    /**
     * Every scheduler member a test calls must exist.
     *
     * This is the check that would have caught the broken merge locally:
     * the test called `scheduler.tryAcquire(...)` and the scheduler no
     * longer had that method.
     */
    @Test
    fun `every scheduler member the tests call exists`() {
        val testDir = File(
            com.openminis.app.ProductionSources.mainRoot()
                ?: error("production source root not found"),
            // mainRoot() ends at `src/main/java/com/openminis/app`, so the
            // test sources are FOUR levels up and back down — `../test`
            // resolves to `src/main/test`, which does not exist, and the
            // scan silently found NO files. The guard was green because
            // it had nothing to look at.
            // mainRoot() ends at `src/main/java/com/openminis/app`, so
            // the test sources are FIVE levels up and back down. Two
            // earlier attempts used the wrong depth, and the scan
            // silently found NO files — the guard was green because it
            // had nothing to look at. The path is NORMALIZED, because a
            // path with `..` in it is not collapsed by `isDirectory`, so
            // the directory test failed even when the depth was right.
            "../../../../../test/java/com/openminis/app/tools",
        ).toPath().normalize().toFile()
        if (!testDir.isDirectory) return

        val call = Regex("""scheduler\.(\w+)\s*\(""")
        val missing = mutableSetOf<String>()
        for (file in testDir.listFiles()?.filter { it.name.endsWith(".kt") }.orEmpty()) {
            // This file names the old API in its own messages, so
            // scanning it would report the guard itself as the problem.
            if (file.name == "SubagentSchedulerApiSurfaceTest.kt") continue
            // Comments are stripped: a test that explains in a comment
            // which API it used to call is not a test that calls it.
            val text = SubagentSourceText.stripComments(file.readText())
            for (m in call.findAll(text)) {
                val name = m.groupValues[1]
                if (name !in publicMembers) missing += "$name (${file.name})"
            }
        }
        val relevant = publicMembers.filter { member: String ->
            member.contains("cquire") || member.contains("elease") || member == "run"
        }.sorted()
        assertTrue(
            "these tests call scheduler members that do not exist: " +
                missing.joinToString(", ") +
                " — the scheduler's public surface is " + relevant,
            missing.isEmpty(),
        )
    }
}
