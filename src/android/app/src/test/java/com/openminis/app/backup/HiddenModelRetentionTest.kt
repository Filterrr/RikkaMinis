package com.openminis.app.backup

import com.openminis.app.data.repository.isPrunableCatalogCache
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-sync-hide-prune] Pure-logic coverage for the hidden-model retention /
 * sync-filtering decisions behind the multi-device sync traffic / storage fix.
 *
 * The two predicates under test are each ONE rule so they can be unit-tested
 * without Android repositories (ConfigBackup.export / prune need Android repos
 * and can't run in a JVM unit test — see ConfigBackupPayloadTest):
 *
 *  1. [isCatalogCacheModel] — what belongs in an auto-sync snapshot.
 *  2. [isPrunableCatalogCache] — when local storage may drop a hidden catalog
 *     entry.
 *
 * Both encode the same contract: hidden AND non-custom ⇒ public catalog cache
 * (re-pullable, don't sync, don't store unless recently seen). Hidden CUSTOM
 * models are user data and NEVER hit either path.
 */
class HiddenModelRetentionTest {

    // ── isCatalogCacheModel (sync-filter predicate) ──────────────────────

    @Test
    fun `hidden non-custom model is catalog cache - excluded from sync`() {
        assertTrue(isCatalogCacheModel(isHidden = true, isCustom = false))
    }

    @Test
    fun `visible model is user selection - kept in sync`() {
        assertFalse(isCatalogCacheModel(isHidden = false, isCustom = false))
        assertFalse(isCatalogCacheModel(isHidden = false, isCustom = true))
    }

    @Test
    fun `hidden custom model is user data - never dropped`() {
        assertFalse(isCatalogCacheModel(isHidden = true, isCustom = true))
    }

    // ── isPrunableCatalogCache (local TTL-staleness predicate) ───────────

    private val day = 24L * 60 * 60 * 1000

    @Test
    fun `stale hidden non-custom model is prunable`() {
        assertTrue(
            isPrunableCatalogCache(
                isHidden = true, isCustom = false,
                elapsedMs = 8 * day, ttlMs = 7 * day,
            )
        )
    }

    @Test
    fun `freshly observed model is not prunable - no refresh thrash`() {
        assertFalse(
            isPrunableCatalogCache(
                isHidden = true, isCustom = false,
                elapsedMs = 1 * day, ttlMs = 7 * day,
            )
        )
    }

    @Test
    fun `hidden custom model is never prunable even when stale - user data`() {
        assertFalse(
            isPrunableCatalogCache(
                isHidden = true, isCustom = true,
                elapsedMs = 30 * day, ttlMs = 7 * day,
            )
        )
    }

    @Test
    fun `visible model is never prunable`() {
        assertFalse(
            isPrunableCatalogCache(
                isHidden = false, isCustom = false,
                elapsedMs = 30 * day, ttlMs = 7 * day,
            )
        )
    }

    @Test
    fun `prune only fires past the ttl boundary`() {
        assertFalse(
            isPrunableCatalogCache(
                isHidden = true, isCustom = false,
                elapsedMs = 7 * day, ttlMs = 7 * day,
            )
        )
    }

    @Test
    fun `prune fires strictly after the ttl boundary`() {
        assertTrue(
            isPrunableCatalogCache(
                isHidden = true, isCustom = false,
                elapsedMs = 7 * day + 1, ttlMs = 7 * day,
            )
        )
    }
}
