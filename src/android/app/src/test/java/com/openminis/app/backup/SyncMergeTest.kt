package com.openminis.app.backup

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM coverage for [SyncMerge] — the pure reconciliation of two auto-sync
 * payloads. Drives the contract no Android Context can: no-clobber union,
 * version-fold edit wins, tombstone delete propagation, resurrect-after-delete,
 * field-level convergence, and no-op detection.
 */
class SyncMergeTest {

    private fun provider(providerType: String, label: String, baseUrl: String = ""): JSONObject =
        JSONObject().apply {
            put("providerType", providerType)
            put("label", label)
            if (baseUrl.isNotEmpty()) put("customBaseURL", baseUrl)
        }

    private fun group(name: String): JSONObject =
        JSONObject().apply { put("name", name) }

    private fun doc(vararg providers: JSONObject): String {
        val root = JSONObject()
        root.put("format", "openminis.config.backup")
        root.put("version", 1)
        root.put("providers", org.json.JSONArray().apply { providers.forEach { put(it) } })
        root.put("groups", org.json.JSONArray())
        root.put("envVars", org.json.JSONArray())
        root.put("memoryFiles", org.json.JSONArray())
        root.put("fields", JSONObject())
        root.put("createdAt", 1000L)
        return root.toString()
    }

    // ── No clobber: a fresh union of two non-overlapping sets ─────────────

    @Test
    fun `two disjoint provider sets union without clobbering either side`() {
        val local = doc(provider("openAI", "ChatGPT"))
        val remote = doc(provider("anthropic", "Claude"))
        val r = SyncMerge.reconcile(local, remote, SyncMerge.Store())

        val merged = JSONObject(r.mergedJson)
        val providers = merged.getJSONArray("providers")
        assertEquals(2, providers.length())
        val labels = (0 until providers.length()).map { providers.getJSONObject(it).getString("label") }
        assertTrue("ChatGPT" in labels)
        assertTrue("Claude" in labels)
        assertTrue(r.deletions.isEmpty())
    }

    // ── Sibling edit wins over our stale value (whole-object fold) ────────

    @Test
    fun `sibling edit to a shared provider survives our stale snapshot`() {
        // Both know "ChatGPT" with the same base URL; on device B the user
        // changes the base URL, then A (stale) syncs.
        val shared = provider("openAI", "ChatGPT", "https://old.example/v1")
        val aLocal = doc(shared)
        // First sync converges both sides onto the shared object (store holds hash).
        val first = SyncMerge.reconcile(aLocal, null, SyncMerge.Store())
        val store = first.store

        // Now A is stale (same as before), but B edited the base URL.
        val bEdited = provider("openAI", "ChatGPT", "https://new.example/v1")
        val bLocal = doc(bEdited)
        val bFirst = SyncMerge.reconcile(bLocal, first.mergedJson, SyncMerge.storeFromJson(SyncMerge.storeToJson(store)))
        val bMerged = bFirst.mergedJson

        // A now syncs its STALE state against B's merged doc. The sibling edit
        // (higher version) must win, not get reverted by A's stale push.
        val aStale = SyncMerge.reconcile(
            doc(shared),
            bMerged,
            SyncMerge.storeFromJson(SyncMerge.storeToJson(store)),
        )
        val merged = JSONObject(aStale.mergedJson)
        val p = merged.getJSONArray("providers").getJSONObject(0)
        assertEquals("https://new.example/v1", p.getString("customBaseURL"))
    }

    // ── Deletion propagates via tombstone ─────────────────────────────────

    @Test
    fun `a provider deleted on one device is deleted on the sibling`() {
        val shared = provider("openAI", "ChatGPT")
        val first = SyncMerge.reconcile(doc(shared), null, SyncMerge.Store())
        val storeA = first.store
        val common = first.mergedJson

        // Device A deletes "ChatGPT" (its local doc no longer contains it).
        val aAfterDelete = SyncMerge.reconcile(
            doc(), // empty local
            common,
            SyncMerge.storeFromJson(SyncMerge.storeToJson(storeA)),
        )
        // A now pushes a tombstone-bearing doc.
        val afterDeleteJson = JSONObject(aAfterDelete.mergedJson)
        assertFalse("merged doc must not resurrect the deleted provider",
            afterDeleteJson.getJSONArray("providers").length() > 0)

        // Device B (still holds the provider) syncs against A's tombstone doc.
        val bStore = SyncMerge.Store()
        val bFirst = SyncMerge.reconcile(doc(shared), null, bStore)
        val bSecond = SyncMerge.reconcile(
            doc(shared), // B still has it locally, unchanged
            aAfterDelete.mergedJson,
            bFirst.store,
        )
        assertTrue("device B must delete the provider", bSecond.deletions.isNotEmpty())
        assertEquals(SyncMerge.Kind.PROVIDER, bSecond.deletions.first().kind)
        assertEquals("ChatGPT", bSecond.deletions.first().b)
        assertEquals(0, JSONObject(bSecond.mergedJson).getJSONArray("providers").length())
    }

    // ── Resurrect after delete (re-add bumps past tombstone) ──────────────

    @Test
    fun `re-adding a deleted provider resurrects it on both sides`() {
        val shared = provider("openAI", "ChatGPT")
        val first = SyncMerge.reconcile(doc(shared), null, SyncMerge.Store())
        val common = first.mergedJson

        // A deletes it.
        val afterDelete = SyncMerge.reconcile(doc(), common, first.store)
        // A re-adds it (fresh content).
        val reAdd = SyncMerge.reconcile(
            doc(provider("openAI", "ChatGPT", "https://re-add.example/v1")),
            afterDelete.mergedJson,
            afterDelete.store,
        )
        assertTrue(reAdd.deletions.isEmpty())
        assertTrue(JSONObject(reAdd.mergedJson).getJSONArray("providers").length() > 0)
    }

    // ── Field-level convergence ───────────────────────────────────────────

    @Test
    fun `a field edited on sibling survives while our own edit wins`() {
        val withFields = { theme: String ->
            JSONObject().apply {
                put("format", "openminis.config.backup")
                put("version", 1)
                put("providers", org.json.JSONArray())
                put("groups", org.json.JSONArray())
                put("envVars", org.json.JSONArray())
                put("memoryFiles", org.json.JSONArray())
                put("createdAt", 1000L)
                put("fields", JSONObject().put("appearance.theme", theme))
            }.toString()
        }
        val first = SyncMerge.reconcile(withFields("dark"), null, SyncMerge.Store())
        val storeJson = SyncMerge.storeToJson(first.store)

        // Sibling changes theme to "light"; we are stale ("dark").
        val siblingEdited = SyncMerge.reconcile(withFields("light"), first.mergedJson,
            SyncMerge.storeFromJson(storeJson))
        val stale = SyncMerge.reconcile(withFields("dark"), siblingEdited.mergedJson,
            SyncMerge.storeFromJson(storeJson))
        assertEquals("light", JSONObject(stale.mergedJson).getJSONObject("fields").getString("appearance.theme"))
    }

    // ── No-op detection ───────────────────────────────────────────────────

    @Test
    fun `unchanged content report changed=false and share the stored hash`() {
        val first = SyncMerge.reconcile(doc(provider("openAI", "ChatGPT")), null, SyncMerge.Store())
        assertTrue("first push changed", first.changed)
        val again = SyncMerge.reconcile(doc(provider("openAI", "ChatGPT")), null, first.store)
        assertFalse("second identical push must be a no-op", again.changed)
    }

    // ── Store round-trip ──────────────────────────────────────────────────

    @Test
    fun `store survives json round trip`() {
        val s = SyncMerge.Store()
        s.objects["p:openAI|ChatGPT"] = SyncMerge.ObjMeta("abc", 5L, false)
        s.fields["appearance.theme"] = SyncMerge.ObjMeta("def", 7L, false)
        s.lastPushedHash = "hash123"
        val back = SyncMerge.storeFromJson(SyncMerge.storeToJson(s))
        assertEquals("abc", back.objects["p:openAI|ChatGPT"]!!.hash)
        assertEquals(5L, back.objects["p:openAI|ChatGPT"]!!.ver)
        assertEquals("def", back.fields["appearance.theme"]!!.hash)
        assertEquals("hash123", back.lastPushedHash)
    }

    @Test
    fun `malformed store json yields empty store`() {
        val back = SyncMerge.storeFromJson("{not json")
        assertTrue(back.objects.isEmpty())
        assertTrue(back.fields.isEmpty())
        assertTrue(back.lastPushedHash == null)
    }

    // ── Convergence: repeated sync reaches the same document ─────────────

    @Test
    fun `two devices converge to one document after repeated sync`() {
        // Device A and B both start from the same shared provider, diverge
        // (each edits a different field), then sync alternately until stable.
        val shared = provider("openAI", "ChatGPT", "https://old.example/v1")

        var aStore = SyncMerge.Store()
        var bStore = SyncMerge.Store()
        var aDoc = doc(shared)
        var bDoc = doc(shared)
        // Seed both stores with the common object so edits are versioned.
        aDoc = SyncMerge.reconcile(aDoc, null, aStore).mergedJson
        bDoc = SyncMerge.reconcile(bDoc, null, bStore).mergedJson
        aStore = SyncMerge.storeFromJson(SyncMerge.storeToJson(aStore))
        bStore = SyncMerge.storeFromJson(SyncMerge.storeToJson(bStore))

        // A edits its provider, B edits a different provider.
        aDoc = doc(provider("openAI", "ChatGPT", "https://a.example/v1"))
        bDoc = doc(provider("anthropic", "Claude"))

        repeat(6) {
            val ar = SyncMerge.reconcile(aDoc, bDoc, aStore)
            val br = SyncMerge.reconcile(bDoc, aDoc, bStore)
            aDoc = ar.mergedJson; bDoc = br.mergedJson
            aStore = ar.store; bStore = br.store
        }

        val aMerged = JSONObject(aDoc)
        val bMerged = JSONObject(bDoc)
        val aLabels = (0 until aMerged.getJSONArray("providers").length())
            .map { aMerged.getJSONArray("providers").getJSONObject(it).getString("label") }
            .toSet()
        val bLabels = (0 until bMerged.getJSONArray("providers").length())
            .map { bMerged.getJSONArray("providers").getJSONObject(it).getString("label") }
            .toSet()
        assertEquals("both devices must converge to the same provider set", aLabels, bLabels)
        assertTrue("ChatGPT" in aLabels)
        assertTrue("Claude" in aLabels)
    }

    // ── Memory file versioning ────────────────────────────────────────────

    @Test
    fun `a memory file edited on sibling wins over our stale content`() {
        fun memDoc(content: String): String {
            val root = JSONObject()
            root.put("format", "openminis.config.backup")
            root.put("version", 1)
            root.put("providers", org.json.JSONArray())
            root.put("groups", org.json.JSONArray())
            root.put("envVars", org.json.JSONArray())
            root.put("memoryFiles", org.json.JSONArray().put(
                JSONObject().put("name", "GLOBAL.md").put("content", content)
            ))
            root.put("fields", JSONObject())
            root.put("createdAt", 1000L)
            return root.toString()
        }

        val first = SyncMerge.reconcile(memDoc("v1"), null, SyncMerge.Store())
        val storeJson = SyncMerge.storeToJson(first.store)
        // Sibling edits GLOBAL.md to "v2".
        val sibling = SyncMerge.reconcile(memDoc("v2"), first.mergedJson, SyncMerge.storeFromJson(storeJson))
        // We are stale ("v1").
        val stale = SyncMerge.reconcile(memDoc("v1"), sibling.mergedJson, SyncMerge.storeFromJson(storeJson))
        val merged = JSONObject(stale.mergedJson).getJSONArray("memoryFiles").getJSONObject(0)
        assertEquals("v2", merged.getString("content"))
    }

    @Test
    fun `a memory file deleted on sibling is flagged for deletion on our device`() {
        fun memDoc(content: String?): String {
            val root = JSONObject()
            root.put("format", "openminis.config.backup")
            root.put("version", 1)
            root.put("providers", org.json.JSONArray())
            root.put("groups", org.json.JSONArray())
            root.put("envVars", org.json.JSONArray())
            root.put("memoryFiles", org.json.JSONArray().apply {
                if (content != null) put(JSONObject().put("name", "GLOBAL.md").put("content", content))
            })
            root.put("fields", JSONObject())
            root.put("createdAt", 1000L)
            return root.toString()
        }
        val first = SyncMerge.reconcile(memDoc("v1"), null, SyncMerge.Store())
        val common = first.mergedJson

        // Sibling deletes GLOBAL.md locally (empty memory array), producing a
        // tombstone-bearing document.
        val siblingAfterDelete = SyncMerge.reconcile(memDoc(null), common, first.store)
        assertEquals(0, JSONObject(siblingAfterDelete.mergedJson).getJSONArray("memoryFiles").length())

        // Our device still holds GLOBAL.md and syncs against the sibling's doc:
        // it must be flagged for deletion.
        val ourStore = SyncMerge.Store()
        val ourFirst = SyncMerge.reconcile(memDoc("v1"), null, ourStore)
        val ourSecond = SyncMerge.reconcile(memDoc("v1"), siblingAfterDelete.mergedJson, ourFirst.store)
        assertTrue("our device must delete the file the sibling deleted", ourSecond.deletions.isNotEmpty())
        assertEquals(SyncMerge.Kind.MEMORY, ourSecond.deletions.first().kind)
        assertEquals(0, JSONObject(ourSecond.mergedJson).getJSONArray("memoryFiles").length())
    }
}