package com.openminis.app.backup

import android.util.Log
import com.openminis.app.config.ConfigAccess
import com.openminis.app.config.ConfigRegistry
import com.openminis.app.config.ConfigValue
import com.openminis.app.data.model.FallbackStrategy
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.RoutingStrategy
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.data.repository.EnvVarRepository
import com.openminis.app.data.repository.MCPRepository
import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.data.repository.SkillRepository
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream

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
        "soul",         // SOUL.md persona fields
        "memory",       // memory feature toggles
        "logs",         // log retention preferences
    )
    // NOTE: `session.*` is deliberately NOT backed up. Despite the dot-path
    // prefix it is not a persisted preference — session.primaryModel /
    // session.thinkingLevel read and write the *currently foregrounded chat*
    // via ChatViewModelStore.activeSessionId. On the settings screen where a
    // backup is taken or restored there is no active session, so the writer
    // throws "No active session" and the reader returns empty/null. Carrying
    // them only produced guaranteed skip entries on every restore.

    /** Outcome of an import: what landed, and what needs the user's attention. */
    data class ImportResult(
        val fieldsApplied: Int,
        val providersImported: Int,
        /** Model groups recreated (with member entry ids remapped to this install). */
        val groupsImported: Int,
        /** Environment variables restored (keys + notes; values if the
         *  backup carried secrets, empty-value stub otherwise). */
        val envVarsImported: Int,
        /** Skills restored, including their bundled files. */
        val skillsImported: Int,
        /** Memory files restored (GLOBAL.md + daily logs). */
        val memoryFilesImported: Int,
        /** MCP servers restored (OAuth credentials always need re-auth). */
        val mcpServersImported: Int,
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
        envVarRepo: EnvVarRepository? = null,
        skillRepo: SkillRepository? = null,
        memoryRepo: MemoryRepository? = null,
        mcpRepo: MCPRepository? = null,
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
            // [T-backup-group-idmap] exportInstanceJSON serializes model entries
            // by (modelId, displayName) but drops their uuids, and importInstance
            // JSON re-mints a fresh uuid for every entry. Model groups reference
            // entries by uuid, and defaults.primaryGroup references a group by
            // id — so without a mapping those references dangle on restore and
            // every group-typed default is rejected. Carry the source entry
            // uuids here, in the SAME order exportInstanceJSON emits its `models`
            // array (visible entries, then hidden), so import can pair old→new
            // uuid positionally. `_`-prefixed to signal a backup-layer annotation;
            // importInstanceJSON ignores unknown keys, so the provider wire
            // format is untouched.
            val entryIds = JSONArray()
            for (id in orderedEntryIds(providerRepo, instance.id)) entryIds.put(id)
            obj.put("_entryIds", entryIds)
            providers.put(obj)
        }

        // [T-backup-group-idmap] Model groups are NOT part of a single provider's
        // export (they span providers), so they are backed up here as a distinct
        // top-level array. member entry ids are the SOURCE uuids; import remaps
        // them through the per-provider old→new entry map before creating groups.
        val groups = JSONArray()
        for (group in providerRepo.config.value.modelGroups) {
            groups.put(JSONObject().apply {
                put("id", group.id)
                put("name", group.name)
                put("memberEntryIds", JSONArray().apply {
                    for (eid in group.memberEntryIds) put(eid)
                })
                put("strategy", group.strategy.name)
                put("fallbackStrategy", group.fallbackStrategy.name)
                group.defaultThinkingLevel?.let { put("defaultThinkingLevel", it.name) }
                group.contextLimitTokens?.let { put("contextLimitTokens", it) }
                group.lastContextLimitTokens?.let { put("lastContextLimitTokens", it) }
            })
        }

        // Environment variables live in EnvVarRepository (metadata in a JSON
        // file, values in encrypted prefs) — NOT in the flat ConfigRegistry
        // field space, so they need their own export pass much like providers.
        // Values are credentials, so they ride the same includeSecrets gate as
        // provider apiKeys: without secrets we carry key+note only and the user
        // refills the value after import.
        val envVars = JSONArray()
        if (envVarRepo != null) {
            for (entry in envVarRepo.entries.value) {
                envVars.put(JSONObject().apply {
                    put("key", entry.key)
                    put("note", entry.note)
                    if (includeSecrets) {
                        envVarRepo.getValue(entry.key)?.let { put("value", it) }
                    }
                })
            }
        }

        // Skills are a directory tree per skill (SKILL.md plus bundled
        // scripts/, references/, assets/), mirrored by a row in skills.db. The
        // registry models none of it, so — like providers — they need a
        // dedicated pass. We embed the whole directory as a base64 zip rather
        // than SKILL.md alone: a skill whose scripts are missing still *looks*
        // installed but fails the moment it runs, which is a worse outcome than
        // a larger backup file. [T-backup-skills]
        val skills = JSONArray()
        if (skillRepo != null) {
            for (skill in skillRepo.skills.value) {
                val entry = JSONObject().apply {
                    put("id", skill.id)
                    put("name", skill.name)
                    put("description", skill.description)
                    put("version", skill.version)
                    put("importSource", skill.importSource.value)
                    skill.sourceURL?.let { put("sourceURL", it) }
                    put("isEnabled", skill.isEnabled)
                    put("installedAt", skill.installedAt)
                    put("updatedAt", skill.updatedAt)
                    put("useCount", skill.useCount)
                }
                // Prefer the full archive; fall back to SKILL.md text so a skill
                // whose zip could not be produced is still recoverable in part.
                val zip = runCatching { skillRepo.exportSkillToZip(skill.id) }.getOrNull()
                val zipBytes = zip?.let { f -> runCatching { f.readBytes() }.getOrNull() }
                if (zipBytes != null && zipBytes.isNotEmpty()) {
                    entry.put(
                        "archive",
                        android.util.Base64.encodeToString(zipBytes, android.util.Base64.NO_WRAP),
                    )
                    entry.put("archiveBytes", zipBytes.size)
                    entry.put("fileCount", skillRepo.listSkillFiles(skill.id).size)
                } else {
                    entry.put("body", skill.body)
                    Log.w(TAG, "export: no archive for skill ${skill.id}, carrying SKILL.md only")
                }
                // Clean up the cache artifact immediately — exportSkillToZip is
                // designed for the share sheet (TTL-swept), but here the bytes
                // are already in the payload and the file is dead weight. The
                // name check pins the contract that the zip sits in its own
                // per-export dir, so this can never widen into a shared cache.
                zip?.parentFile
                    ?.takeIf { it.name.startsWith("skill-export-") }
                    ?.let { dir -> runCatching { dir.deleteRecursively() } }
                skills.put(entry)
            }
        }

        // Memory: `memory.enabled` in the registry is only the *toggle*. The
        // actual content is GLOBAL.md + the YYYY-MM-DD.md daily logs owned by
        // MemoryRepository, which is why restoring a backup used to come back
        // with the switch in the right position and nothing behind it.
        val memoryFiles = JSONArray()
        if (memoryRepo != null) {
            for (info in runCatching { memoryRepo.listAllFiles() }.getOrDefault(emptyList())) {
                val content = runCatching { memoryRepo.readFile(info.name) }.getOrNull() ?: continue
                memoryFiles.put(JSONObject().apply {
                    put("name", info.name)
                    put("content", content)
                })
            }
        }

        // MCP servers live in their own servers.json. Note their client secrets
        // and issued OAuth tokens are in MCPOAuthStore, NOT here — those stay
        // out of the payload entirely and are reported on import as needing
        // re-authorization.
        val mcpServers = JSONArray()
        if (mcpRepo != null) {
            for (server in mcpRepo.servers.value) {
                val raw = runCatching { mcpRepo.exportServerJSON(server) }.getOrNull() ?: continue
                val obj = runCatching { JSONObject(raw) }.getOrNull() ?: continue
                mcpServers.put(obj)
            }
        }

        return JSONObject().apply {
            put("format", "openminis.config.backup")
            put("version", FORMAT_VERSION)
            put("createdAt", System.currentTimeMillis())
            put("includesSecrets", includeSecrets)
            put("fields", fields)
            put("providers", providers)
            put("groups", groups)
            put("envVars", envVars)
            put("skills", skills)
            put("memoryFiles", memoryFiles)
            put("mcpServers", mcpServers)
            if (readFailures > 0) put("readFailures", readFailures)
        }.toString(2)
    }

    /**
     * Entry uuids for [instanceId] in the exact order
     * [ProviderRepository.exportInstanceJSON] serializes its `models` array:
     * visible entries first, then hidden ones. Keeping this in lock-step with
     * that method is what makes positional old→new uuid pairing correct on
     * import; if exportInstanceJSON's ordering ever changes, this must follow.
     */
    private fun orderedEntryIds(
        providerRepo: ProviderRepository,
        instanceId: String,
    ): List<String> {
        val visible = providerRepo.visibleEntries(instanceId)
        val hidden = providerRepo.config.value.modelEntries.filter {
            it.providerInstanceId == instanceId && it.isHidden
        }
        return (visible + hidden).map { it.id }
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
        envVarRepo: EnvVarRepository? = null,
        skillRepo: SkillRepository? = null,
        memoryRepo: MemoryRepository? = null,
        mcpRepo: MCPRepository? = null,
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

        // [T-backup-group-idmap] Order matters. Providers create the model
        // entries that groups reference; groups create the ids that
        // defaults.primaryGroup / agentLoopGroups reference. So the sequence is
        // providers → groups → fields, and each stage publishes an old→new id
        // map the next stage rewrites through. Doing fields first (the old
        // order) meant defaults.primaryGroup was validated against groups that
        // did not exist yet and was always rejected.
        val entryIdMap = HashMap<String, String>()   // source entry uuid → restored uuid
        val groupIdMap = HashMap<String, String>()    // source group id  → restored id

        // -- Stage 1: providers (also builds the entry-id map) --
        var providersImported = 0
        val providers = root.optJSONArray("providers")
        if (providers != null) {
            for (i in 0 until providers.length()) {
                val obj = providers.optJSONObject(i) ?: continue
                val label = obj.optString("label", "provider #${i + 1}")
                // Pull our backup-layer annotation out before handing the object
                // to importInstanceJSON (which ignores it anyway, but keeping the
                // wire payload clean avoids surprises).
                val srcEntryIds = obj.optJSONArray("_entryIds")
                val instancesBefore = providerRepo.instances.map { it.id }.toSet()
                try {
                    val resolvedLabel = providerRepo.importInstanceJSON(obj.toString())
                    if (resolvedLabel == null) {
                        skipped.add("provider \"$label\": import rejected")
                        continue
                    }
                    providersImported++
                    // Identify the instance importInstanceJSON just created (the
                    // one id that wasn't present before) and pair its entries to
                    // the source uuids positionally — orderedEntryIds mirrors the
                    // export ordering exactly.
                    val newId = providerRepo.instances
                        .map { it.id }
                        .firstOrNull { it !in instancesBefore }
                    if (newId != null && srcEntryIds != null) {
                        val newEntryIds = orderedEntryIds(providerRepo, newId)
                        val n = minOf(srcEntryIds.length(), newEntryIds.size)
                        for (k in 0 until n) {
                            val oldEid = srcEntryIds.optString(k, "")
                            if (oldEid.isNotEmpty()) entryIdMap[oldEid] = newEntryIds[k]
                        }
                        if (srcEntryIds.length() != newEntryIds.size) {
                            // Non-fatal: model set differs from when the backup
                            // was taken (a model was hidden/added since). Groups
                            // referencing the unmapped entries will report them.
                            Log.w(
                                TAG,
                                "import: provider \"$resolvedLabel\" entry count " +
                                    "${srcEntryIds.length()}→${newEntryIds.size}; " +
                                    "some group members may not remap"
                            )
                        }
                    }
                } catch (t: Throwable) {
                    skipped.add("provider \"$label\": ${t.message ?: "import failed"}")
                }
            }
        }

        // -- Stage 2: model groups (remaps member entry ids, builds group map) --
        var groupsImported = 0
        val groups = root.optJSONArray("groups")
        if (groups != null) {
            val existingGroupIds = providerRepo.config.value.modelGroups.map { it.id }.toSet()
            val existingGroupNames = providerRepo.config.value.modelGroups.map { it.name }.toSet()
            for (i in 0 until groups.length()) {
                val g = groups.optJSONObject(i) ?: continue
                val srcId = g.optString("id", "")
                val name = g.optString("name", "group #${i + 1}")
                // Remap member entries through the entry map; drop members whose
                // source entry never made it in (missing provider/model).
                val srcMembers = g.optJSONArray("memberEntryIds")
                val members = ArrayList<String>()
                var droppedMembers = 0
                if (srcMembers != null) {
                    for (j in 0 until srcMembers.length()) {
                        val old = srcMembers.optString(j, "")
                        val mapped = entryIdMap[old]
                        if (mapped != null) members.add(mapped) else droppedMembers++
                    }
                }
                // Fresh id unless the source id is somehow free on this install;
                // renaming on name-collision keeps a restore from silently
                // merging into an existing group.
                val newId = if (srcId.isNotEmpty() && srcId !in existingGroupIds) {
                    srcId
                } else {
                    java.util.UUID.randomUUID().toString()
                }
                var resolvedName = name
                if (resolvedName in existingGroupNames) {
                    var suffix = 2
                    while ("$name ($suffix)" in existingGroupNames) suffix++
                    resolvedName = "$name ($suffix)"
                }
                try {
                    val group = ModelGroup(
                        id = newId,
                        name = resolvedName,
                        memberEntryIds = members,
                        strategy = enumOrDefault(
                            g.optString("strategy"),
                            com.openminis.app.data.model.RoutingStrategy.fallback,
                        ),
                        fallbackStrategy = enumOrDefault(
                            g.optString("fallbackStrategy"),
                            com.openminis.app.data.model.FallbackStrategy.default,
                        ),
                        defaultThinkingLevel = g.optString("defaultThinkingLevel")
                            .takeIf { it.isNotEmpty() }
                            ?.let { runCatching { ThinkingLevel.valueOf(it) }.getOrNull() },
                        contextLimitTokens = if (g.has("contextLimitTokens"))
                            g.optInt("contextLimitTokens").takeIf { it > 0 } else null,
                        lastContextLimitTokens = if (g.has("lastContextLimitTokens"))
                            g.optInt("lastContextLimitTokens").takeIf { it > 0 } else null,
                    )
                    providerRepo.addGroup(group)
                    if (srcId.isNotEmpty()) groupIdMap[srcId] = newId
                    groupsImported++
                    if (droppedMembers > 0) {
                        skipped.add(
                            "group \"$name\": $droppedMembers member(s) skipped " +
                                "(their model/provider isn't in this backup)"
                        )
                    }
                } catch (t: Throwable) {
                    skipped.add("group \"$name\": ${t.message ?: "import failed"}")
                }
            }
        }

        // -- Stage 3: scalar fields (defaults.* group/entry ids remapped) --
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
                val decoded = ConfigValue.decode(raw)
                if (decoded == null) {
                    skipped.add("$path: unreadable value in backup")
                    continue
                }
                // Rewrite the group/entry ids these fields carry from source ids
                // to the ids just minted above. An id with no mapping is left
                // as-is so the field's own writer reports it as unknown rather
                // than this layer swallowing it.
                val value = remapDefaultsIds(path, decoded, groupIdMap, entryIdMap)
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

        // -- Stage 4: environment variables (own repository, secret-gated) --
        var envVarsImported = 0
        val envVarsArr = root.optJSONArray("envVars")
        if (envVarsArr != null && envVarRepo != null) {
            for (i in 0 until envVarsArr.length()) {
                val ev = envVarsArr.optJSONObject(i) ?: continue
                val key = ev.optString("key", "").trim()
                if (key.isEmpty()) {
                    skipped.add("env var #${i + 1}: missing key")
                    continue
                }
                if (envVarRepo.isDuplicateKey(key)) {
                    skipped.add("env var \"$key\": already exists, left as-is")
                    continue
                }
                // A backup taken without secrets carries no value; add the key
                // with an empty value so the metadata/note survive and the user
                // only has to refill the secret rather than recreate the entry.
                val value = ev.optString("value", "")
                val note = ev.optString("note", "")
                if (envVarRepo.add(key, value, note)) {
                    envVarsImported++
                    if (value.isEmpty()) {
                        skipped.add("env var \"$key\": restored without value — re-enter it")
                    }
                } else {
                    skipped.add("env var \"$key\": rejected (invalid key)")
                }
            }
        } else if (envVarsArr != null && envVarsArr.length() > 0 && envVarRepo == null) {
            skipped.add("${envVarsArr.length()} env var(s): not restorable here")
        }

        // -- Stage 5: skills (db row + full directory from the embedded zip) --
        // Skill ids are slugify(name), not random uuids, so they are stable
        // across installs — no id remapping needed here, unlike providers.
        var skillsImported = 0
        val skillsArr = root.optJSONArray("skills")
        if (skillsArr != null && skillRepo != null) {
            for (i in 0 until skillsArr.length()) {
                val s = skillsArr.optJSONObject(i) ?: continue
                val name = s.optString("name", "skill #${i + 1}")
                val archive = s.optString("archive", "")
                try {
                    // importFromContent/importFromArchive replace an existing
                    // skill of the same id in place, which is what we want:
                    // restoring should refresh, not create "skill (2)".
                    val imported = if (archive.isNotEmpty()) {
                        val bytes = android.util.Base64.decode(archive, android.util.Base64.NO_WRAP)
                        skillRepo.importFromArchive(ByteArrayInputStream(bytes))
                    } else {
                        val body = s.optString("body", "")
                        if (body.isBlank()) null
                        else skillRepo.importFromContent(
                            body,
                            SkillRepository.ImportSource.from(s.optString("importSource", "file")),
                            s.optString("sourceURL", "").takeIf { it.isNotEmpty() },
                        )
                    }
                    if (imported == null) {
                        skipped.add("skill \"$name\": archive unreadable or SKILL.md invalid")
                        continue
                    }
                    // The enabled flag is user intent, not part of SKILL.md, so
                    // it has to be reapplied after the content import.
                    if (!s.optBoolean("isEnabled", true)) {
                        runCatching { skillRepo.setEnabled(imported.id, false) }
                    }
                    skillsImported++
                    if (archive.isEmpty()) {
                        skipped.add(
                            "skill \"$name\": restored SKILL.md only — bundled scripts were " +
                                "not in the backup"
                        )
                    }
                } catch (t: Throwable) {
                    skipped.add("skill \"$name\": ${t.message ?: "import failed"}")
                }
            }
            runCatching { skillRepo.reloadFromDisk() }
        } else if (skillsArr != null && skillsArr.length() > 0 && skillRepo == null) {
            skipped.add("${skillsArr.length()} skill(s): not restorable here")
        }

        // -- Stage 6: memory files (GLOBAL.md + daily logs) --
        var memoryFilesImported = 0
        val memArr = root.optJSONArray("memoryFiles")
        if (memArr != null && memoryRepo != null) {
            for (i in 0 until memArr.length()) {
                val m = memArr.optJSONObject(i) ?: continue
                val name = m.optString("name", "").trim()
                if (name.isEmpty()) {
                    skipped.add("memory file #${i + 1}: missing name")
                    continue
                }
                // Defence in depth: these names become file names under the
                // memory dir, so anything with a path separator is rejected
                // outright rather than trusted from the payload.
                if (name.contains('/') || name.contains('\\') || name.contains("..")) {
                    skipped.add("memory file \"$name\": unsafe name, skipped")
                    continue
                }
                val content = m.optString("content", "")
                try {
                    memoryRepo.saveFile(name, content)
                    memoryFilesImported++
                } catch (t: Throwable) {
                    skipped.add("memory file \"$name\": ${t.message ?: "write failed"}")
                }
            }
        } else if (memArr != null && memArr.length() > 0 && memoryRepo == null) {
            skipped.add("${memArr.length()} memory file(s): not restorable here")
        }

        // -- Stage 7: MCP servers --
        var mcpServersImported = 0
        val mcpArr = root.optJSONArray("mcpServers")
        if (mcpArr != null && mcpRepo != null) {
            var needsReauth = 0
            for (i in 0 until mcpArr.length()) {
                val srv = mcpArr.optJSONObject(i) ?: continue
                // exportServerJSON emits the importable wrapper shape
                // {"mcpServers":{"<id>":{…}}} — the id is the *key*, and
                // "oauth" sits on the inner object, not the root.
                val inner = srv.optJSONObject("mcpServers")
                val id = inner?.keys()?.asSequence()?.firstOrNull()
                    ?: srv.optString("id", "").ifEmpty { "server #${i + 1}" }
                val entry = inner?.optJSONObject(id) ?: srv
                try {
                    val imported = mcpRepo.importJSON(srv.toString())
                    if (imported.isEmpty()) {
                        skipped.add("MCP server \"$id\": import rejected")
                        continue
                    }
                    mcpServersImported += imported.size
                    if (entry.has("oauth")) needsReauth++
                } catch (t: Throwable) {
                    skipped.add("MCP server \"$id\": ${t.message ?: "import failed"}")
                }
            }
            if (needsReauth > 0) {
                // Client secrets and issued tokens live in MCPOAuthStore and are
                // deliberately never exported — reconnecting is interactive.
                skipped.add(
                    "$needsReauth MCP server(s) use OAuth — reconnect them to sign in again"
                )
            }
        } else if (mcpArr != null && mcpArr.length() > 0 && mcpRepo == null) {
            skipped.add("${mcpArr.length()} MCP server(s): not restorable here")
        }


        return ImportResult(
            fieldsApplied = applied,
            providersImported = providersImported,
            groupsImported = groupsImported,
            envVarsImported = envVarsImported,
            skillsImported = skillsImported,
            memoryFilesImported = memoryFilesImported,
            mcpServersImported = mcpServersImported,
            skipped = skipped,
            hadSecrets = root.optBoolean("includesSecrets", false),
        ).also { result ->
            // Mirror the outcome into the diagnostic log. The result dialog
            // only shows the first few skipped lines (screen budget), and the
            // whole import otherwise leaves no trace — so when a restore comes
            // back half-applied there is nothing to look at after dismissing
            // the sheet. One summary line plus one line per skip fixes that.
            Log.i(
                TAG,
                "import: applied=$applied providers=$providersImported " +
                    "groups=$groupsImported envVars=$envVarsImported " +
                    "skills=$skillsImported memoryFiles=$memoryFilesImported " +
                    "mcpServers=$mcpServersImported " +
                    "skipped=${result.skipped.size} hadSecrets=${result.hadSecrets}"
            )
            for (line in result.skipped) Log.w(TAG, "import skipped — $line")
        }
    }

    /** [enumValueOf] that falls back to [default] instead of throwing on a
     *  token this build doesn't know (forward-compat with newer backups). */
    private inline fun <reified T : Enum<T>> enumOrDefault(name: String, default: T): T =
        runCatching { enumValueOf<T>(name) }.getOrDefault(default)

    /**
     * Rewrite the source group/entry ids embedded in a `defaults.*` field to the
     * ids minted during this import. Group-typed fields
     * (primaryGroup / subGroup / agentLoopGroups) map through [groupIdMap];
     * agentLoopEntries maps through [entryIdMap]. Everything else is returned
     * unchanged. Unmapped ids are passed through so the field's own writer can
     * report them as unknown rather than this layer silently dropping them.
     */
    private fun remapDefaultsIds(
        path: String,
        value: ConfigValue,
        groupIdMap: Map<String, String>,
        entryIdMap: Map<String, String>,
    ): ConfigValue {
        val map = when (path) {
            "defaults.primaryGroup", "defaults.subGroup", "defaults.agentLoopGroups" -> groupIdMap
            "defaults.agentLoopEntries" -> entryIdMap
            else -> return value
        }
        if (map.isEmpty()) return value
        return when (value) {
            is ConfigValue.Str -> ConfigValue.Str(map[value.value] ?: value.value)
            is ConfigValue.Arr -> ConfigValue.Arr(
                value.value.map { el ->
                    if (el is ConfigValue.Str) ConfigValue.Str(map[el.value] ?: el.value) else el
                }
            )
            else -> value
        }
    }

    /** Default filename for a fresh export, e.g. `openminis-backup-20260802.json`. */
    fun suggestedFileName(now: Long = System.currentTimeMillis()): String {
        val fmt = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
        return "openminis-backup-${fmt.format(java.util.Date(now))}.json"
    }
}
