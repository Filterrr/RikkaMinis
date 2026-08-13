package com.openminis.app.backup

import android.content.Context
import com.openminis.app.data.repository.EnvVarRepository
import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.data.repository.ProviderRepository
import okhttp3.OkHttpClient

/**
 * Multi-device auto-sync of light configuration state.
 *
 * Scope matched against the user's decision: ONLY the settings a user expects
 * to carry between their own devices — app config (BACKED_UP_SCOPES),
 * providers, model groups, env vars and memory files. Skills, MCP servers and
 * chat history are deliberately excluded:
 *  - skills live on GitHub already (cross-device by construction)
 *  - MCP servers have OAuth leases that always need re-auth
 *  - chat history is a per-device audit copy, not a shared resource
 *
 * Transport is the SAME WebDAV pipeline as manual backups (WebDavClient +
 * WebDavSync), but under a distinct [WebDavSync.SYNC_PREFIX] filename so
 * auto-sync snapshots never pollute the manual remote-backup list. Restore
 * reuses ConfigBackup.import() unchanged — a sync payload is a normal backup
 * document minus the skills/mcp/chat sections, which import() skips silently
 * (optJSONArray returns null/empty for absent keys).
 *
 * Conflict handling is deliberately the simplest possible: the newest snapshot
 * wins (whole-file push, per-entry import merge). There is no merge editor.
 * This is acceptable because no device is a heavy concurrent writer — the
 * rare collision resolves to "the last device to act is the latest intent".
 */
object MultiDeviceSync {
    private const val TAG = "MultiDeviceSync"

    /** Preferences key for the user-facing master switch. */
    const val PREF_KEY_ENABLED = "multi_device_sync_enabled"

    /** Preferences key: whether the user has confirmed that auto-sync
     *  snapshots may contain API keys / OAuth tokens / env var values.
     *  Not confirmed → sync pushes without secrets (includeSecrets=false). */
    const val PREF_KEY_SECRETS_CONFIRMED = "multi_device_sync_secrets_confirmed"

    /** Debounce window: config writes within this interval coalesce into a
     *  single push, so a settings edit that fires N writes pushes once. */
    const val PUSH_DEBOUNCE_MS = 4000L

    /** Keep at most this many auto-sync snapshots in the folder. Older ones
     *  are pruned so the user does not have to. */
    const val MAX_REMOTE_SYNC_FILES = 7

    /**
     * Whether auto-sync is turned on for this install.
     */
    fun isEnabled(context: Context): Boolean {
        return context.getSharedPreferences("backup_prefs", Context.MODE_PRIVATE)
            .getBoolean(PREF_KEY_ENABLED, false)
    }

    /**
     * Whether the user has confirmed that auto-sync snapshots may carry
     * API keys and credentials. Before this is true, sync runs with
     * includeSecrets=false (provider keys and env var values omitted).
     * Persisted alongside [PREF_KEY_ENABLED] in the same prefs file.
     */
    fun hasConfirmedSecretsSync(context: Context): Boolean {
        return context.getSharedPreferences("backup_prefs", Context.MODE_PRIVATE)
            .getBoolean(PREF_KEY_SECRETS_CONFIRMED, false)
    }

    /** Mark the secrets-sync confirmation as given (one-shot, permanent). */
    fun markSecretsSyncConfirmed(context: Context) {
        context.getSharedPreferences("backup_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean(PREF_KEY_SECRETS_CONFIRMED, true).apply()
    }

    /**
     * Compute the sync-subset payload — a ConfigBackup document containing only
     * the light state (config + providers + groups + env vars + memory), sized
     * KB not 70MB. Achieved by passing null repos for the excluded sections:
     * ConfigBackup.export() guards each section on a non-null repo, so passing
     * null skillRepo / mcpRepo / chatRepo makes those sections vanish while the
     * rest of the (unchanged) export body runs untouched. Zero new
     * serialization logic — the format/version sentinels and ConfigBackup.import()
     * compatibility are inherited for free.
     */
    suspend fun exportSyncPayload(
        providerRepo: ProviderRepository,
        envVarRepo: EnvVarRepository?,
        memoryRepo: MemoryRepository?,
        includeSecrets: Boolean,
    ): String {
        // NOTE: chatWindowDays is irrelevant here (no chatRepo) but kept to a
        // non-zero default for symmetry; passing 0 explicitly documents the
        // intent that chat is never part of an auto-sync snapshot.
        //
        // includeHiddenModels=false: a sync snapshot carries only what the user
        // actually selected (visible + custom models), never the provider's
        // full public catalog cache. Hidden non-custom models are re-pullable
        // from the provider's /models endpoint, dominate the payload size, and
        // must not leak to sibling devices as if they were user state. [T-sync-hide-prune]
        return ConfigBackup.export(
            providerRepo = providerRepo,
            includeSecrets = includeSecrets,
            envVarRepo = envVarRepo,
            skillRepo = null,          // skills excluded (GitHub already syncs them)
            memoryRepo = memoryRepo,
            mcpRepo = null,            // MCP excluded (OAuth needs re-auth)
            chatRepo = null,           // chat excluded (per-device audit copy)
            chatWindowDays = 0,
            includeHiddenModels = false,
        )
    }

    /**
     * Push the sync subset to WebDAV, then prune old snapshots down to
     * [MAX_REMOTE_SYNC_FILES]. Returns the displayName of the pushed file.
     */
    fun pushSyncPayload(
        config: WebDavConfig,
        payload: String,
        client: OkHttpClient,
    ): String {
        val name = WebDavSync.pushSync(config, payload, client)
        pruneSyncFiles(config, client)
        return name
    }

    /** Delete old auto-sync snapshots beyond the retention cap. */
    private fun pruneSyncFiles(config: WebDavConfig, client: OkHttpClient) {
        val remote = WebDavSync.listSyncFiles(config, client)
        if (remote.size <= MAX_REMOTE_SYNC_FILES) return
        for (stale in remote.drop(MAX_REMOTE_SYNC_FILES)) {
            runCatching { WebDavSync.deleteBackupFile(config, stale, client, WebDavSync.SYNC_SUBDIR) }
        }
    }

    /**
     * The complete "sync now" cycle run on app start / resume (when enabled):
     *  1. Pull the newest remote snapshot and import it (entry-level merge —
     *     a provider/config value only present locally is kept).
     *  2. Push the current local state so this device's latest changes are
     *     known to its siblings.
     * The order (pull-then-push) means a device that just started converges to
     * the newest sibling state before re-asserting its own — the user's most
     * recent action on any device ends up authoritative.
     *
     * @return a short descriptor for logging / the settings screen status.
     */
    suspend fun syncNow(
        providerRepo: ProviderRepository,
        envVarRepo: EnvVarRepository?,
        memoryRepo: MemoryRepository?,
        config: WebDavConfig,
        client: OkHttpClient,
        includeSecrets: Boolean = false,
    ): String {
        // (1) Pull + apply the newest remote snapshot (best-effort per entry).
        val pulled = runCatching { WebDavSync.pullLatestSync(config, client) }.getOrElse {
            return "pull-failed: ${it.message}"
        }
        if (pulled != null) {
            runCatching {
                ConfigBackup.import(
                    providerRepo = providerRepo,
                    json = pulled,
                    envVarRepo = envVarRepo,
                    skillRepo = null,
                    memoryRepo = memoryRepo,
                    mcpRepo = null,
                    chatRepo = null,
                    isSyncMerge = true,
                )
            }
        }

        // (2) Export the local light state and push it (with retention prune).
        // Export failure (e.g. payload over MAX_PAYLOAD_BYTES) must degrade
        // gracefully — never crash the foreground-triggered coroutine.
        val payload = runCatching {
            exportSyncPayload(
                providerRepo = providerRepo,
                envVarRepo = envVarRepo,
                memoryRepo = memoryRepo,
                includeSecrets = includeSecrets,
            )
        }.getOrElse {
            return "export-failed: ${it.message}"
        }
        return try {
            val name = pushSyncPayload(config, payload, client)
            "pushed: $name"
        } catch (t: Throwable) {
            "push-failed: ${t.message}"
        }
    }
}
