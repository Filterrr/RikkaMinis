package com.openminis.app.backup

import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure, JVM-testable reconciliation of two multi-device auto-sync documents
 * (each a [ConfigBackup.export] light-state payload) into a single merged
 * document, plus the list of locally-held objects the sibling deleted (so this
 * device deletes them too before importing).
 *
 * #### Why this layer exists
 * The shipped sync is de-facto "last writer wins" across one canonical file,
 * which has three real defects:
 *  1. Whole-config clobber: a device that opens with a stale snapshot re-pushes
 *     its entire old config and silently reverts the fields its sibling edited
 *     ("changed" and "stale" both just read as "differs from remote").
 *  2. Deletion is not represented: [ConfigBackup.import] merges by union, so a
 *     provider / env var a sibling deleted comes back from the dead every merge.
 *  3. Shared memory files (GLOBAL.md / the rollup) are whole-file overwrites —
 *     the receiving device's same-day edits are destroyed.
 *
 * #### How it fixes them
 * Every syncable unit — provider, model group, env var, memory file, and each
 * scalar config field — gets an identity ([sid]) and a version, folded across
 * both sides (Lamport-style). The version pulls only live in the sync payload
 * as additive `_sid` / `_ver` annotations, a top-level `_tombstones` array and
 * `_fieldVers` map, plus a small persisted [Store]. `_`-prefixed keys are
 * ignored by [ConfigBackup.import] and tolerated by older builds, so the wire
 * format stays backward compatible. Persisted model classes are untouched (no
 * four-way-sync drift).
 *
 * Deletion is itself a versioned write: deleting an object bumps its version
 * and records a tombstone. "Sibling deleted → we adopt the delete"; "we deleted
 * → sibling adopts the delete"; "we re-added after a delete" bumps past the
 * tombstone and resurrects on both sides. The transport's optimistic lock
 * (If-Match / 412) remains the backstop for two devices editing the *same*
 * object within the same sync window — such objects aren't auto-mergeable.
 */
object SyncMerge {

    const val K_SID = "_sid"
    const val K_VER = "_ver"
    const val K_TOMBSTONES = "_tombstones"
    const val K_FIELD_VERS = "_fieldVers"

    enum class Kind { PROVIDER, GROUP, ENV_VAR, MEMORY }

    /** A locally-held object the sibling deleted. */
    data class Deletion(val kind: Kind, val a: String, val b: String = "")

    data class ObjMeta(val hash: String, val ver: Long, val gone: Boolean = false)

    data class Store(
        val objects: MutableMap<String, ObjMeta> = mutableMapOf(),
        val fields: MutableMap<String, ObjMeta> = mutableMapOf(),
        var lastPushedHash: String? = null,
    )

    data class Result(
        val mergedJson: String,
        val deletions: List<Deletion>,
        val contentHash: String,
        val changed: Boolean,
        val store: Store,
    )

    // ── Stable identities ─────────────────────────────────────────────────

    fun sidProvider(providerType: String, label: String): String = "p:$providerType|$label"
    fun sidGroup(name: String): String = "g:$name"
    fun sidEnv(key: String): String = "e:$key"
    fun sidMemory(name: String): String = "m:$name"

    /**
     * Reconcile [localDoc] (fresh export of this device) with [remoteDoc]
     * (pulled sibling snapshot, or null when the server has nothing yet)
     * against the previous [store].
     */
    fun reconcile(
        localDoc: String,
        remoteDoc: String?,
        store: Store,
        now: Long = System.currentTimeMillis(),
    ): Result {
        val local = runCatching { JSONObject(localDoc) }.getOrElse { JSONObject() }
        val remote = remoteDoc?.let { runCatching { JSONObject(it) }.getOrNull() }

        val remoteTombstones = readTombstones(remote)
        val remoteFieldVers = readLongMap(remote?.optJSONObject(K_FIELD_VERS))
        val remoteVers = versionIndex(remote)

        // ── Gather + stamp local objects with a folded version ────────────
        val localObjs = LinkedHashMap<String, ObjRef>()
        forEachObject(local) { sid, obj ->
            val hash = contentHash(obj)
            val prior = store.objects[sid]
            val rv = remoteVers[sid] ?: 0L
            // Unchanged content keeps its prior version — it must NOT adopt the
            // sibling's version, else a stale device would tie and re-win by
            // hash luck. Only new/changed/deleted content moves the clock.
            val ver = when {
                prior == null -> maxOf(rv, 0L) + 1
                prior.gone || prior.hash != hash -> maxOf(prior.ver, rv) + 1
                else -> prior.ver
            }
            obj.put(K_SID, sid)
            obj.put(K_VER, ver)
            localObjs[sid] = ObjRef(sid, obj, hash, ver)
        }
        val remoteObjs = LinkedHashMap<String, ObjRef>()
        remote?.let { forEachObject(it) { sid, obj ->
            remoteObjs[sid] = ObjRef(sid, obj, contentHash(obj), obj.optLong(K_VER, 0L))
        } }

        // ── Reconcile objects + memory (uniform state machine) ────────────
        val survivors = LinkedHashMap<String, JSONObject>()
        val deletions = mutableListOf<Deletion>()
        val newTombstones = HashMap<String, Long>()

        // Object triples (providers/groups/env) first.
        reconcileKeys(
            sidsFor(localObjs, remoteObjs, remoteTombstones, store.objects, prefix = listOf("p:", "g:", "e:")),
            localObjs, remoteObjs, remoteTombstones, store.objects,
            survivors, deletions, newTombstones,
        )
        // Memory objects share the exact same rules.
        val localMem = indexMemObjects(local.optJSONArray("memoryFiles"), store, remoteVers)
        val remoteMem = remote?.let { indexMemRemote(it.optJSONArray("memoryFiles")) } ?: emptyMap()
        reconcileKeys(
            sidsFor(localMem, remoteMem, remoteTombstones, store.objects, prefix = listOf("m:")),
            localMem, remoteMem, remoteTombstones, store.objects,
            survivors, deletions, newTombstones,
        )

        // ── Scalar fields (per-path versions) ─────────────────────────────
        val localFields = local.optJSONObject("fields") ?: JSONObject()
        val remoteFields = remote?.optJSONObject("fields") ?: JSONObject()
        val localFieldMeta = fieldMeta(localFields, store, remoteFieldVers)
        val mergedFields = JSONObject()
        val fieldVersOut = JSONObject()
        val fieldPaths = sortedSetOf<String>().apply {
            addAll(localFieldMeta.keys)
            addAll(remoteFieldVers.keys)
            val rk = remoteFields.keys()
            while (rk.hasNext()) add(rk.next())
        }
        for (path in fieldPaths) {
            val lm = localFieldMeta[path]
            val rm = if (remoteFields.has(path)) remoteFields.opt(path) else null
            val rv = remoteFieldVers[path] ?: 0L
            when {
                lm == null && rm != null -> { mergedFields.put(path, rm); fieldVersOut.put(path, rv) }
                rm == null && lm != null -> { mergedFields.put(path, localFields.opt(path)); fieldVersOut.put(path, lm.ver) }
                lm != null && rm != null -> {
                    val keepLocal = lm.ver >= rv
                    mergedFields.put(path, if (keepLocal) localFields.opt(path) else rm)
                    fieldVersOut.put(path, maxOf(lm.ver, rv))
                }
            }
        }

        // ── Assemble merged document ──────────────────────────────────────
        val out = JSONObject()
        copyTopLevel(out, local)
        out.put("providers", collectSurvivors(survivors, Kind.PROVIDER))
        out.put("groups", collectSurvivors(survivors, Kind.GROUP))
        out.put("envVars", collectSurvivors(survivors, Kind.ENV_VAR))
        out.put("memoryFiles", collectSurvivors(survivors, Kind.MEMORY))
        out.put("fields", mergedFields)
        out.put(K_FIELD_VERS, fieldVersOut)

        val tombstonesOut = JSONArray()
        val tombstoneSids = (remoteTombstones.keys + newTombstones.keys).toSortedSet()
        for (sid in tombstoneSids) {
            if (survivors.containsKey(sid)) continue
            val ver = maxOf(remoteTombstones[sid] ?: 0L, newTombstones[sid] ?: 0L)
            if (ver > 0L) tombstonesOut.put(JSONObject().put("sid", sid).put("ver", ver))
        }
        out.put(K_TOMBSTONES, tombstonesOut)
        out.put("createdAt", now)

        // ── Recompose store ───────────────────────────────────────────────
        val newStore = Store(mutableMapOf(), mutableMapOf())
        for ((sid, json) in survivors) {
            newStore.objects[sid] = ObjMeta(contentHash(json), json.optLong(K_VER, 0L), false)
        }
        for (sid in tombstoneSids) {
            val ver = maxOf(remoteTombstones[sid] ?: 0L, newTombstones[sid] ?: 0L)
            if (ver > 0L && !newStore.objects.containsKey(sid)) {
                newStore.objects[sid] = ObjMeta("", ver, true)
            }
        }
        for (path in fieldPaths) {
            val value = if (mergedFields.has(path)) mergedFields.opt(path).toString() else ""
            val ver = localFieldMeta[path]?.ver ?: remoteFieldVers[path] ?: 0L
            newStore.fields[path] = ObjMeta(sha256Hex(value), ver, false)
        }

        val mergedString = out.toString()
        val contentHash = sha256Hex(stripCreatedAt(mergedString))
        return Result(
            mergedJson = mergedString,
            deletions = deletions,
            contentHash = contentHash,
            changed = store.lastPushedHash != contentHash,
            store = newStore.apply { lastPushedHash = contentHash },
        )
    }

    // ── Object-state reconciliation ───────────────────────────────────────

    private data class ObjRef(val sid: String, val json: JSONObject, val hash: String, val ver: Long)

    /** Enumerate unique sids across sources, filtered to the given prefixes. */
    private fun sidsFor(
        local: Map<String, ObjRef>,
        remote: Map<String, ObjRef>,
        tombstones: Map<String, Long>,
        store: Map<String, ObjMeta>,
        prefix: List<String>,
    ): MutableSet<String> {
        val out = LinkedHashSet<String>()
        out.addAll(local.keys.filter { s -> prefix.any { s.startsWith(it) } })
        out.addAll(remote.keys.filter { s -> prefix.any { s.startsWith(it) } })
        out.addAll(tombstones.keys.filter { s -> prefix.any { s.startsWith(it) } })
        out.addAll(store.keys.filter { s -> prefix.any { s.startsWith(it) } })
        return out
    }

    /**
     * Reconcile one class of objects. Mutates [survivors], [deletions] and
     * [newTombstones]. All inputs already carry `_sid`/`_ver`.
     */
    private fun reconcileKeys(
        sids: Set<String>,
        local: Map<String, ObjRef>,
        remote: Map<String, ObjRef>,
        remoteTombstones: Map<String, Long>,
        store: Map<String, ObjMeta>,
        survivors: MutableMap<String, JSONObject>,
        deletions: MutableList<Deletion>,
        newTombstones: MutableMap<String, Long>,
    ) {
        for (sid in sids.toSortedSet()) {
            val l = local[sid]
            val r = remote[sid]
            val prior = store[sid]

            // Derive the local claim (alive + version, or deleted tombstone).
            val lAlive: Boolean; val lVer: Long; val lHash: String
            when {
                l != null -> { lAlive = true; lVer = l.ver; lHash = l.hash }
                prior == null -> { lAlive = true; lVer = Long.MIN_VALUE; lHash = "" }
                prior.gone -> { lAlive = false; lVer = prior.ver; lHash = "" }
                else -> { lAlive = false; lVer = prior.ver + 1; lHash = "" } // freshly deleted
            }
            // Derive the remote claim.
            val rAlive: Boolean; val rVer: Long; val rHash: String
            when {
                r != null -> { rAlive = true; rVer = r.ver; rHash = r.hash }
                remoteTombstones.containsKey(sid) -> {
                    rAlive = false; rVer = remoteTombstones[sid]!!; rHash = ""
                }
                else -> { rAlive = true; rVer = Long.MIN_VALUE; rHash = "" }
            }
            // "No claim" on both sides (never seen, absent): nothing to converge.
            if (lVer == Long.MIN_VALUE && rVer == Long.MIN_VALUE) continue

            val localWins = winner(lVer, lAlive, lHash, rVer, rAlive, rHash)

            when {
                localWins && lAlive && l != null -> survivors[sid] = l.json
                localWins && !lAlive -> newTombstones[sid] = maxOf(lVer, newTombstones[sid] ?: 0L)
                !localWins && rAlive && r != null -> survivors[sid] = r.json
                else -> {
                    // Remote deleted and local didn't win alive → adopt the
                    // delete; drop any local survivor (it isn't in `survivors`
                    // yet) and flag it for repository removal.
                    newTombstones[sid] = maxOf(rVer, newTombstones[sid] ?: 0L)
                    if (lAlive && l != null) deletions.add(toDeletion(sid))
                }
            }
        }
    }

    /** Deterministic winner: true = local side wins. Ties → alive wins,
     *  then content-hash order (stable convergence). */
    private fun winner(
        lVer: Long, lAlive: Boolean, lHash: String,
        rVer: Long, rAlive: Boolean, rHash: String,
    ): Boolean {
        if (lVer != rVer) return lVer > rVer
        if (lAlive != rAlive) return lAlive
        if (lAlive) return lHash >= rHash
        return true // both tombstones, equal version — arbitrary but stable
    }

    private fun toDeletion(sid: String): Deletion = when {
        sid.startsWith("p:") -> {
            val rest = sid.substring(2); val sep = rest.indexOf('|')
            Deletion(Kind.PROVIDER, rest.substring(0, sep), rest.substring(sep + 1))
        }
        sid.startsWith("g:") -> Deletion(Kind.GROUP, sid.substring(2))
        sid.startsWith("e:") -> Deletion(Kind.ENV_VAR, sid.substring(2))
        sid.startsWith("m:") -> Deletion(Kind.MEMORY, sid.substring(2))
        else -> Deletion(Kind.PROVIDER, sid)
    }

    // ── Object extraction ─────────────────────────────────────────────────

    private inline fun forEachObject(doc: JSONObject, block: (String, JSONObject) -> Unit) {
        doc.optJSONArray("providers")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                block(sidProvider(o.optString("providerType"), o.optString("label")), o)
            }
        }
        doc.optJSONArray("groups")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                block(sidGroup(o.optString("name")), o)
            }
        }
        doc.optJSONArray("envVars")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                block(sidEnv(o.optString("key")), o)
            }
        }
    }

    private fun versionIndex(doc: JSONObject?): Map<String, Long> {
        if (doc == null) return emptyMap()
        val out = HashMap<String, Long>()
        forEachObject(doc) { sid, obj -> out[sid] = obj.optLong(K_VER, 0L) }
        return out
    }

    private fun indexMemObjects(
        arr: JSONArray?,
        store: Store,
        remoteVers: Map<String, Long>,
    ): Map<String, ObjRef> {
        if (arr == null) return emptyMap()
        val out = LinkedHashMap<String, ObjRef>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val sid = sidMemory(o.optString("name"))
            val hash = contentHash(o)
            val prior = store.objects[sid]
            val rv = remoteVers[sid] ?: 0L
            val ver = when {
                prior == null -> maxOf(rv, 0L) + 1
                prior.gone || prior.hash != hash -> maxOf(prior.ver, rv) + 1
                else -> prior.ver
            }
            o.put(K_SID, sid); o.put(K_VER, ver)
            out[sid] = ObjRef(sid, o, hash, ver)
        }
        return out
    }

    private fun indexMemRemote(arr: JSONArray?): Map<String, ObjRef> {
        if (arr == null) return emptyMap()
        val out = LinkedHashMap<String, ObjRef>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val sid = sidMemory(o.optString("name"))
            out[sid] = ObjRef(sid, o, contentHash(o), o.optLong(K_VER, 0L))
        }
        return out
    }

    private fun contentHash(obj: JSONObject): String {
        val copy = JSONObject(obj.toString())
        copy.remove(K_SID)
        copy.remove(K_VER)
        // `_entryIds` is a per-device backup-layer annotation (source→local
        // uuid remap) that differs between devices for the *same* provider; it
        // is not user-visible state, so it must not drive version churn.
        copy.remove("_entryIds")
        return sha256Hex(copy.toString())
    }

    private fun collectSurvivors(surviving: Map<String, JSONObject>, kind: Kind): JSONArray {
        val arr = JSONArray()
        for ((sid, obj) in surviving) {
            if (belongsTo(sid, kind)) arr.put(obj)
        }
        return arr
    }

    private fun belongsTo(sid: String, kind: Kind): Boolean = when (kind) {
        Kind.PROVIDER -> sid.startsWith("p:")
        Kind.GROUP -> sid.startsWith("g:")
        Kind.ENV_VAR -> sid.startsWith("e:")
        Kind.MEMORY -> sid.startsWith("m:")
    }

    // ── Tombstones / long maps ────────────────────────────────────────────

    private fun readTombstones(remote: JSONObject?): Map<String, Long> {
        if (remote == null) return emptyMap()
        val out = HashMap<String, Long>()
        remote.optJSONArray(K_TOMBSTONES)?.let { arr ->
            for (i in 0 until arr.length()) {
                val t = arr.optJSONObject(i) ?: continue
                val sid = t.optString("sid")
                if (sid.isNotEmpty()) out[sid] = t.optLong("ver", 0L)
            }
        }
        return out
    }

    private fun readLongMap(o: JSONObject?): Map<String, Long> {
        if (o == null) return emptyMap()
        val out = HashMap<String, Long>()
        val k = o.keys()
        while (k.hasNext()) { val p = k.next(); out[p] = o.optLong(p, 0L) }
        return out
    }

    private fun fieldMeta(
        fields: JSONObject,
        store: Store,
        remoteFieldVers: Map<String, Long>,
    ): Map<String, ObjMeta> {
        val out = HashMap<String, ObjMeta>()
        val keys = fields.keys()
        while (keys.hasNext()) {
            val path = keys.next()
            val value = fields.opt(path)
            val hash = sha256Hex(value?.toString() ?: "")
            val prior = store.fields[path]
            val rv = remoteFieldVers[path] ?: 0L
            val ver = when {
                prior == null -> maxOf(rv, 0L) + 1
                prior.hash != hash -> maxOf(prior.ver, rv) + 1
                else -> prior.ver
            }
            out[path] = ObjMeta(hash, ver, false)
        }
        return out
    }

    // ── Document assembly helpers ─────────────────────────────────────────

    private fun copyTopLevel(out: JSONObject, local: JSONObject) {
        val keys = local.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (k == "providers" || k == "groups" || k == "envVars" || k == "fields" ||
                k == "memoryFiles" || k == "createdAt") continue
            out.put(k, local.opt(k))
        }
    }

    private fun sha256Hex(text: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun stripCreatedAt(doc: String): String =
        doc.replace(Regex("\"createdAt\"\\s*:\\s*\\d+"), "")

    // ── Store (de)serialization ───────────────────────────────────────────

    fun storeToJson(store: Store): String {
        val o = JSONObject()
        val objs = JSONObject()
        for ((sid, m) in store.objects) {
            objs.put(sid, JSONObject().put("h", m.hash).put("v", m.ver).put("gone", m.gone))
        }
        val flds = JSONObject()
        for ((p, m) in store.fields) {
            flds.put(p, JSONObject().put("h", m.hash).put("v", m.ver))
        }
        o.put("objects", objs).put("fields", flds)
        store.lastPushedHash?.let { o.put("lastPushedHash", it) }
        return o.toString()
    }

    fun storeFromJson(s: String): Store {
        val out = Store()
        val root = runCatching { JSONObject(s) }.getOrNull() ?: return out
        root.optJSONObject("objects")?.let { objs ->
            val k = objs.keys()
            while (k.hasNext()) {
                val sid = k.next()
                val m = objs.optJSONObject(sid) ?: continue
                out.objects[sid] = ObjMeta(m.optString("h"), m.optLong("v", 0L), m.optBoolean("gone", false))
            }
        }
        root.optJSONObject("fields")?.let { flds ->
            val k = flds.keys()
            while (k.hasNext()) {
                val p = k.next()
                val m = flds.optJSONObject(p) ?: continue
                out.fields[p] = ObjMeta(m.optString("h"), m.optLong("v", 0L), false)
            }
        }
        if (root.has("lastPushedHash")) out.lastPushedHash = root.optString("lastPushedHash")
        return out
    }
}