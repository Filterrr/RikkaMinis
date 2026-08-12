package com.openminis.app.workspace

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Executes one memory-rollup pass: reads the *previous day's* completed daily
 * log, distills the stable rules via [MemoryRollupEngine], and appends them to
 * [MemoryRollupEngine.ROLLUP_FILE] inside the memory directory.
 *
 * Why "yesterday": the daily alarm fires at 03:00, when today has barely
 * started — the last *complete* log day is yesterday. Rolling up a fully
 * finished day avoids distilling a half-written log.
 *
 * The source log is never modified — the rollup product (MEMORY-ROLLUP.md)
 * is a distilled index on top of it. Idempotency is file-based:
 * [MemoryRollupEngine.hasRollupForDate] skips dates already distilled.
 */
class MemoryRollupRunner(
    private val memoryDir: File,
    clock: () -> Date = { Date() },
) {
    companion object {
        private const val TAG = "MemoryRollupRunner"
        const val DEFAULT_FILE_NAME = MemoryRollupEngine.ROLLUP_FILE
    }

    private val now: () -> Date = clock

    enum class Outcome {
        /** Rollup written for yesterday's log. */
        ROLLED_UP,

        /** Yesterday's log was already distilled (idempotent). */
        SKIPPED_ALREADY,

        /** No daily log file for yesterday. */
        NO_LOG_YESTERDAY,

        /** Log existed but nothing in it qualified as a stable rule. */
        NOTHING_TO_DISTILL,

        /** I/O failure reading or writing. */
        ERROR,
    }

    fun runOnce(): Outcome {
        return try {
            val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val yesterday = Date(now().time - 86_400_000L)
            val dateStr = dateFmt.format(yesterday)

            val logFile = File(memoryDir, "$dateStr.md")
            if (!logFile.exists() || logFile.length() == 0L) {
                Log.i(TAG, "no daily log for $dateStr — nothing to roll up")
                return Outcome.NO_LOG_YESTERDAY
            }

            val rollupFile = File(memoryDir, MemoryRollupEngine.ROLLUP_FILE)
            if (rollupFile.exists()) {
                val existing = runCatching { rollupFile.readText() }.getOrDefault("")
                if (MemoryRollupEngine.hasRollupForDate(existing, dateStr)) {
                    Log.i(TAG, "$dateStr already rolled up — skip (idempotent)")
                    return Outcome.SKIPPED_ALREADY
                }
            }

            val logText = logFile.readText()
            val entries = MemoryRollupEngine.extractEntries(logText)
            val stableEntries = entries.filter {
                MemoryRollupEngine.classify(it) != MemoryRollupEngine.RollupClass.TRANSIENT
            }
            if (stableEntries.isEmpty()) {
                Log.i(TAG, "$dateStr.md had no distillable rules (${entries.size} entries, all transient)")
                return Outcome.NOTHING_TO_DISTILL
            }

            val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
            val generatedAt = timeFmt.format(now())
            val section = MemoryRollupEngine.buildRollupText(dateStr, entries, generatedAt)
            if (section.isEmpty()) {
                Log.i(TAG, "$dateStr.md produced an empty rollup section")
                return Outcome.NOTHING_TO_DISTILL
            }

            // Append the section; create the rollup file on first run.
            if (rollupFile.exists()) {
                rollupFile.appendText("\n$section")
            } else {
                rollupFile.writeText(section)
            }
            Log.i(
                TAG,
                "rolled up $dateStr.md → ${MemoryRollupEngine.ROLLUP_FILE} " +
                    "(${stableEntries.size}/${entries.size} entries distilled)",
            )
            Outcome.ROLLED_UP
        } catch (t: Throwable) {
            Log.e(TAG, "rollup failed", t)
            Outcome.ERROR
        }
    }
}