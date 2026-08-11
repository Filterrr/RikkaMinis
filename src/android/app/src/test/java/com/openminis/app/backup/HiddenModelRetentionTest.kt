package com.openminis.app.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-sync-hide-prune] Pure-logic coverage for the hidden-model sync-filtering
 * decisions behind the multi-device sync traffic / storage fix.
 *
 * The predicate under test is ONE rule so it can be unit-tested without
 * Android repositories (ConfigBackup.export needs Android repos and can't run
 * in a JVM unit test — see ConfigBackupPayloadTest):
 *
 *  1. [isCatalogCacheModel] — what belongs in an auto-sync snapshot.
 *
 * The contract: hidden AND non-custom ⇒ public catalog cache (re-pullable,
 * don't sync). Hidden CUSTOM models are user data and are always included.
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

    // ── shouldSkipSyncField (sync-merge guard: personality not overwritten) ──

    @Test
    fun `soul field skipped during sync merge - personality stays per-device`() {
        assertTrue(shouldSkipSyncField("soul.name", isSyncMerge = true))
        assertTrue(shouldSkipSyncField("soul.style", isSyncMerge = true))
        assertTrue(shouldSkipSyncField("soul.lang", isSyncMerge = true))
    }

    @Test
    fun `plain config field is never skipped even during sync merge`() {
        assertFalse(shouldSkipSyncField("appearance.theme", isSyncMerge = true))
        assertFalse(shouldSkipSyncField("defaults.primaryGroup", isSyncMerge = true))
    }

    @Test
    fun `soul field written on a full manual restore`() {
        // isSyncMerge=false (manual restore) ⇒ personality fields apply.
        assertFalse(shouldSkipSyncField("soul.name", isSyncMerge = false))
    }
}
