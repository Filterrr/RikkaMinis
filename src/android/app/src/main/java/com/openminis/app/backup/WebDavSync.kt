package com.openminis.app.backup

import okhttp3.OkHttpClient
import java.time.Instant

/**
 * Backup-domain operations on top of [WebDavClient]: pushing the JSON payload
 * produced by [ConfigBackup.export], listing/restoring/deleting remote copies.
 *
 * Mirrors rikkahub's WebDavSync (AGPL-3.0) responsibilities — filename
 * convention filtering, descending sort, directory auto-creation — minus the
 * zip packing (RikkaMinis backups are a single self-contained JSON document,
 * so upload is a raw PUT and restore a raw GET feeding ConfigBackup.import).
 * Pure JVM, no Android imports, unit-testable against MockWebServer.
 */
object WebDavSync {

    /** Filename convention for remote copies, matching
     *  [ConfigBackup.suggestedFileName]. Only files matching this prefix are
     *  shown in the remote list, so unrelated files in the user's WebDAV
     *  folder never surface as backups. */
    const val BACKUP_PREFIX = "rikkaminis-backup-"

    /** Pre-rename convention (openminis-backup-*). Still matched so copies
     *  pushed before the rename remain visible and restorable. */
    const val LEGACY_BACKUP_PREFIX = "openminis-backup-"

    const val BACKUP_SUFFIX = ".json"

    /**
     * Filename convention for multi-device auto-sync snapshots
     * ([MultiDeviceSync]). Distinct from [BACKUP_PREFIX] on purpose: automatic
     * sync produces a *subset* payload (config + providers + env vars + memory,
     * no skills / chat) named under its own prefix, so it never mixes with the
     * full manual backups a user chooses to keep in the same folder.
     */
    const val SYNC_PREFIX = "rikkaminis-sync-"

    /**
     * On the WebDAV server the auto-sync snapshots live in their own
     * *subdirectory* (`<backup-path>/sync/`) rather than mixed alongside the
     * manual full backups in the backup root. That way the sync snapshots —
     * auto-generated, pruned, and key-bearing — never share a folder with the
     * curated manual backups the user chooses to keep.
     */
    const val SYNC_SUBDIR = "sync"

    /** Verify the server + credentials. Throws on failure. */
    fun testConnection(config: WebDavConfig, client: OkHttpClient = WebDavClient.defaultClient()) {
        WebDavClient(config, client).testConnection()
    }

    /**
     * Uploads [payload] as a new timestamped file into the configured backup
     * folder. The file name uses second precision (yyyyMMdd-HHmmss) rather
     * than [ConfigBackup.suggestedFileName]'s minute precision: a local
     * export and a WebDAV push within the same minute would otherwise
     * silently overwrite each other on the server. The shared
     * `rikkaminis-backup-*.json` convention is kept so local files dropped
     * into the folder manually are still picked up by [listBackupFiles].
     */
    fun backup(
        config: WebDavConfig,
        payload: String,
        client: OkHttpClient = WebDavClient.defaultClient(),
    ) {
        val dav = WebDavClient(config, client)
        dav.ensureCollectionExists()
        val name = "rikkaminis-backup-${
            java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                .format(java.util.Date())
        }.json"
        dav.put(name, payload.toByteArray(Charsets.UTF_8), "application/json")
    }

    /** Remote backups, newest first. */
    fun listBackupFiles(
        config: WebDavConfig,
        client: OkHttpClient = WebDavClient.defaultClient(),
    ): List<WebDavBackupItem> {
        val dav = WebDavClient(config, client)
        dav.ensureCollectionExists()
        return dav.list()
            .filter {
                !it.isCollection &&
                    (it.displayName.startsWith(BACKUP_PREFIX) ||
                        it.displayName.startsWith(LEGACY_BACKUP_PREFIX)) &&
                    it.displayName.endsWith(BACKUP_SUFFIX)
            }
            .map {
                WebDavBackupItem(
                    href = it.href,
                    displayName = it.displayName,
                    size = it.contentLength,
                    lastModified = it.lastModified ?: Instant.EPOCH,
                )
            }
            .sortedByDescending { it.lastModified }
    }

    /** Download a remote backup and return its JSON document, ready for
     *  [ConfigBackup.import]. */
    fun restore(
        config: WebDavConfig,
        item: WebDavBackupItem,
        client: OkHttpClient = WebDavClient.defaultClient(),
    ): String {
        return WebDavClient(config, client)
            .get(item.displayName)
            .toString(Charsets.UTF_8)
    }

    /** Remove a remote backup. [subdir] scopes the delete to a child folder
     *  of the configured backup path (used for auto-sync snapshots kept in
     *  [SYNC_SUBDIR]); leave empty to delete a file in the backup root. */
    fun deleteBackupFile(
        config: WebDavConfig,
        item: WebDavBackupItem,
        client: OkHttpClient = WebDavClient.defaultClient(),
        subdir: String = "",
    ) {
        val path = if (subdir.isBlank()) item.displayName else "$subdir/${item.displayName}"
        WebDavClient(config, client).delete(path)
    }

    /**
     * Push a multi-device sync snapshot ([MultiDeviceSync]) as a new
     * timestamped file named under [SYNC_PREFIX]. Same transport and
     * auto-create semantics as [backup] — the only difference is the filename,
     * which keeps auto-sync snapshots out of the manual remote-backup list.
     * Returns the created displayName.
     */
    fun pushSync(
        config: WebDavConfig,
        payload: String,
        client: OkHttpClient = WebDavClient.defaultClient(),
    ): String {
        val dav = WebDavClient(config, client)
        dav.ensureCollectionExists()
        dav.ensureCollectionExists(SYNC_SUBDIR)
        val name = "$SYNC_PREFIX${
            java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                .format(java.util.Date())
        }$BACKUP_SUFFIX"
        dav.put("$SYNC_SUBDIR/$name", payload.toByteArray(Charsets.UTF_8), "application/json")
        return name
    }

    /** Remote auto-sync snapshots, newest first. They live in the
     *  [SYNC_SUBDIR] subdirectory, isolated from manual full backups.
     *  Returns an empty list when the subdirectory does not exist yet
     *  (nothing has ever been synced). */
    fun listSyncFiles(
        config: WebDavConfig,
        client: OkHttpClient = WebDavClient.defaultClient(),
    ): List<WebDavBackupItem> {
        val dav = WebDavClient(config, client)
        dav.ensureCollectionExists()
        return try {
            dav.list(SYNC_SUBDIR)
                .filter {
                    !it.isCollection &&
                        it.displayName.startsWith(SYNC_PREFIX) &&
                        it.displayName.endsWith(BACKUP_SUFFIX)
                }
                .map {
                    WebDavBackupItem(
                        href = it.href,
                        displayName = it.displayName,
                        size = it.contentLength,
                        lastModified = it.lastModified ?: Instant.EPOCH,
                    )
                }
                .sortedByDescending { it.lastModified }
        } catch (e: WebDavException) {
            if (e.statusCode == 404) emptyList() else throw e
        }
    }

    /** Download the newest auto-sync snapshot, or null when the folder has
     *  none yet. */
    fun pullLatestSync(
        config: WebDavConfig,
        client: OkHttpClient = WebDavClient.defaultClient(),
    ): String? {
        return listSyncFiles(config, client).firstOrNull()?.let {
            WebDavClient(config, client)
                .get("$SYNC_SUBDIR/${it.displayName}")
                .toString(Charsets.UTF_8)
        }
    }
}
