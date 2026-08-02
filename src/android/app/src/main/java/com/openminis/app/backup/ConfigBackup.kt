package com.openminis.app.backup

import android.util.Log
import com.openminis.app.config.ConfigAccess
import com.openminis.app.config.ConfigRegistry
import com.openminis.app.config.ConfigValue
import com.openminis.app.data.repository.ProviderRepository
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Local export/import of app configuration.
 *
 * Deliberately built on top of [ConfigRegistry] rather than enumerating the
 * dozen-plus SharedPreferences files by hand: every settable field already
 * declares its own storage location and carries read()/write(), so a backup is
 * just "walk the registry and read" / "walk the payload and write". New config
 * fields are picked up for free; a hand-rolled key list would silently rot.
 *
 * Providers are the one thing NOT modelled as plain registry scalars. They keep
 * their own richer serialization (models, overrides, base64 credentials) in
 * [ProviderRepository.exportInstanceJSON], so backups embed that verbatim
 * instead of reimplementing it.
 *
 * Scope is local-file only — no cloud, no WebDAV. Anything that would need an
 * interactive step to restore (re-authorizing an expired OAuth login, resolving
 * a binding to a group that no longer exists) is reported in
 * [ImportResult.skipped] rather than silently guessed at.
 */
object ConfigBackup {
    private const val TAG = "ConfigBackup"

    /** Bumped only on breaking payload changes; readers reject newer majors. */
    const val FORMAT_VERSION = 1

    /**
     * Registry scopes included in a backup, i.e. the settings a user expects to
     * carry to a new install. Everything else in the registry is either
     * device-local state (session ids, cached metadata) or derived, and
     * restoring it would do more harm than good.
     */
    private val BACKED_UP_SCOPES = setOf(
        "appearance",   // theme, font scale, chat bubble/background look
        "chat",         // composer + rendering preferences
        "background",   // background image / effect settings
        "defaults",     // default model group, agent-loop entries and groups
        "session",      // per-session defaults (not the sessions themselves)
        "soul",         // SOUL.md persona fields
        "memory",       // memory feature toggles
        "logs",         // log retention preferences
    )

    /** Outcome of an import: what landed, and what needs the user's attention. */
    data class ImportResult(
        val fieldsApplied: Int,
        val providersImported: Int,
        /** Human-readable "path: why" lines for anything deliberately not applied. */
        val skipped: List<String>,
        /** True when the payload carried credentials (affects the post-import hint). */
        val hadSecrets: Boolean,
    )

    /**
     * Serialize current settings to a backup document.
     *
     * @param includeSecrets when false, API keys and OAuth tokens are stripped.
     *   Defaults to true: a restore that drops every credential leaves the user
     *   retyping keys by hand, which defeats the point of a backup. Callers are
     *   expected to warn before writing the file somewhere shareable.
     */
    fun export(
        providerRepo: ProviderRepository,
        includeSecrets: Boolean = true,
    ): String {
        val registry = ConfigRegistry.get()

        val fields = JSONObject()
        var readFailures = 0
        for (path in registry.allVisibleFieldPaths()) {
            val field = registry.resolveField(path) ?: continue
            if (field.scope !in BACKED_UP_SCOPES) continue
            // READONLY fields would fail on the way back in, so there is no
            // point carrying them. Feature-unavailable fields likewise refuse
            // reads on this device.
            if (field.access != ConfigAccess.READWRITE) continue
            if (field.unavailableReason != null) continue
            try {
                val value = field.read().let { if (includeSecrets) it else it.redactingSecrets() }
                // Store each value as its JSON *string* form and decode with
                // ConfigValue.decode() on the way back in. ConfigValue's
                // Any-tree conversion is private, and going through the
                // documented jsonString()/decode() pair keeps the round-trip
                // symmetric without reaching into its internals.
                fields.put(path, value.jsonString())
            } catch (t: Throwable) {
                // A single unreadable field must not sink the whole backup.
                readFailures++
                Log.w(TAG, "export: skipped unreadable field $path: ${t.message}")
            }
        }

        val providers = JSONArray()
        for (instance in providerRepo.instances) {
            val json = providerRepo.exportInstanceJSON(instance.id) ?: continue
            val obj = try {
                JSONObject(json)
            } catch (t: Throwable) {
                Log.w(TAG, "export: unparseable provider ${instance.id}: ${t.message}")
                continue
            }
            if (!includeSecrets) {
                for (key in SECRET_PROVIDER_KEYS) obj.remove(key)
            }
            providers.put(obj)
        }

        return JSONObject().apply {
            put("format", "openminis.config.backup")
            put("version", FORMAT_VERSION)
            put("createdAt", System.currentTimeMillis())
            put("includesSecrets", includeSecrets)
            put("fields", fields)
            put("providers", providers)
            if (readFailures > 0) put("readFailures", readFailures)
        }.toString(2)
    }

    /**
     * Provider credential keys, mirroring [ConfigValue.SECRET_KEYS] plus the
     * Gemini-only OAuth side-channel strings that are equally sensitive.
     */
    private val SECRET_PROVIDER_KEYS = listOf(
        "apiKey", "oauthToken", "manualOAuthToken", "oauthEmail", "oauthGcpProject",
    )

    /** Thrown for payloads that aren't ours, or are from a future major format. */
    class InvalidBackupException(message: String) : Exception(message)

    /**
     * Apply a backup document produced by [export].
     *
     * Import is deliberately best-effort per item: one field that no longer
     * validates (a default group id that doesn't exist on this install, an enum
     * value from a newer build) is recorded in [ImportResult.skipped] and the
     * rest still lands. An all-or-nothing import would make backups useless
     * across versions.
     *
     * Providers are appended, not merged — [ProviderRepository.importInstanceJSON]
     * already auto-renames on label conflict, so restoring onto a non-empty
     * install duplicates rather than clobbering the user's existing setup.
     */
    fun import(
        providerRepo: ProviderRepository,
        json: String,
    ): ImportResult {
        val root = try {
            JSONTokener(json).nextValue() as? JSONObject
                ?: throw InvalidBackupException("Backup root is not a JSON object")
        } catch (e: InvalidBackupException) {
            throw e
        } catch (t: Throwable) {
            throw InvalidBackupException("Malformed JSON: ${t.message}")
        }

        if (root.optString("format") != "openminis.config.backup") {
            throw InvalidBackupException("Not an OpenMinis backup file")
        }
        val version = root.optInt("version", 0)
        if (version > FORMAT_VERSION) {
            throw InvalidBackupException(
                "Backup was created by a newer version of the app (format $version)"
            )
        }

        val skipped = ArrayList<String>()
        val registry = ConfigRegistry.get()

        var applied = 0
        val fields = root.optJSONObject("fields")
        if (fields != null) {
            val keys = fields.keys()
            while (keys.hasNext()) {
                val path = keys.next()
                val field = registry.resolveField(path)
                if (field == null) {
                    // Field was removed or renamed since the backup was taken.
                    skipped.add("$path: no longer exists in this version")
                    continue
                }
                if (field.scope !in BACKED_UP_SCOPES) {
                    skipped.add("$path: outside backup scope")
                    continue
                }
                if (field.access != ConfigAccess.READWRITE) {
                    skipped.add("$path: read-only")
                    continue
                }
                val unavailable = field.unavailableReason
                if (unavailable != null) {
                    skipped.add("$path: unavailable on this device ($unavailable)")
                    continue
                }

                val raw = fields.optString(path, "")
                val value = ConfigValue.decode(raw)
                if (value == null) {
                    skipped.add("$path: unreadable value in backup")
                    continue
                }
                try {
                    // Validate against the field's own schema before writing so
                    // a stale enum / out-of-range number is reported instead of
                    // being forced into prefs.
                    field.valueSchema.validate(value)
                    field.write(value)
                    applied++
                } catch (t: Throwable) {
                    skipped.add("$path: ${t.message ?: "rejected"}")
                }
            }
        }

        var providersImported = 0
        val providers = root.optJSONArray("providers")
        if (providers != null) {
            for (i in 0 until providers.length()) {
                val obj = providers.optJSONObject(i) ?: continue
                val label = obj.optString("label", "provider #${i + 1}")
                try {
                    if (providerRepo.importInstanceJSON(obj.toString()) != null) {
                        providersImported++
                    } else {
                        skipped.add("provider \"$label\": import rejected")
                    }
                } catch (t: Throwable) {
                    skipped.add("provider \"$label\": ${t.message ?: "import failed"}")
                }
            }
        }

        return ImportResult(
            fieldsApplied = applied,
            providersImported = providersImported,
            skipped = skipped,
            hadSecrets = root.optBoolean("includesSecrets", false),
        )
    }

    /** Default filename for a fresh export, e.g. `openminis-backup-20260802.json`. */
    fun suggestedFileName(now: Long = System.currentTimeMillis()): String {
        val fmt = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
        return "openminis-backup-${fmt.format(java.util.Date(now))}.json"
    }
}
