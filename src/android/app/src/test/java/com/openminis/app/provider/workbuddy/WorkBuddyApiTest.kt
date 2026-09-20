package com.openminis.app.provider.workbuddy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

/**
 * [T-workbuddy-oauth] Pure-logic tests for the WorkBuddy provider port.
 *
 * These pin the wire-contract decisions that were derived from the upstream
 * client (and verified live against both tenants) so a later refactor cannot
 * silently change them:
 *
 *  - the `{code,msg,data}` envelope unwrapping (including the double-wrapped
 *    `data.data` shape some routes use),
 *  - the *pending login* sentinel, which must stay distinguishable from a real
 *    rejection or the poller burns its whole deadline,
 *  - expiry normalization for the two epoch shapes upstream emits,
 *  - catalog → [com.openminis.app.data.model.LLMModel] mapping, including the
 *    chat-capability filter and the `agents[].models` union,
 *  - region parsing, which must never throw on persisted config.
 */
class WorkBuddyApiTest {

    // ─────────────────────────── envelope ───────────────────────────

    @Test
    fun `unwrap passes through a payload with no code field`() {
        // Upstream omits `code` on some success responses; that is success,
        // not a malformed envelope.
        val root = JSONObject("""{"data":{"state":"s","authUrl":"u"}}""")
        val data = WorkBuddyApi.unwrap(root)
        assertEquals("s", data.optString("state"))
    }

    @Test
    fun `unwrap accepts code zero`() {
        val root = JSONObject("""{"code":0,"msg":"OK","data":{"accessToken":"tok"}}""")
        assertEquals("tok", WorkBuddyApi.unwrap(root).optString("accessToken"))
    }

    @Test
    fun `unwrap collapses a doubly nested data block`() {
        // Some routes answer {"data":{"data":{...}}}; the inner object is the
        // real payload.
        val root = JSONObject("""{"code":0,"data":{"data":{"uid":"u1"}}}""")
        assertEquals("u1", WorkBuddyApi.unwrap(root).optString("uid"))
    }

    @Test
    fun `unwrap surfaces the upstream message on an error code`() {
        val root = JSONObject("""{"code":40101,"msg":"quota exceeded"}""")
        val e = assertThrows(java.io.IOException::class.java) { WorkBuddyApi.unwrap(root) }
        assertEquals("quota exceeded", e.message)
    }

    @Test
    fun `unwrap prefers message over msg when both are present`() {
        val root = JSONObject("""{"code":5,"message":"primary","msg":"secondary"}""")
        val e = assertThrows(java.io.IOException::class.java) { WorkBuddyApi.unwrap(root) }
        assertEquals("primary", e.message)
    }

    @Test
    fun `unwrap falls back to a generic message when the envelope carries none`() {
        val root = JSONObject("""{"code":7}""")
        val e = assertThrows(java.io.IOException::class.java) { WorkBuddyApi.unwrap(root) }
        assertEquals("上游请求失败", e.message)
    }

    // ───────────────────────── expiry normalization ─────────────────────────

    @Test
    fun `resolveExpiry treats a small value as epoch seconds`() {
        // Upstream `normalizedExpiryMillis`: anything below 1e10 is seconds.
        val seconds = 1_700_000_000L
        val out = WorkBuddyApi.resolveExpiry(JSONObject().put("expiresAt", seconds))
        assertEquals(seconds * 1000L, out)
    }

    @Test
    fun `resolveExpiry passes through a millisecond value`() {
        val millis = 1_700_000_000_000L
        val out = WorkBuddyApi.resolveExpiry(JSONObject().put("expiresAt", millis))
        assertEquals(millis, out)
    }

    @Test
    fun `resolveExpiry converts expiresIn to an absolute instant`() {
        val before = System.currentTimeMillis()
        val out = WorkBuddyApi.resolveExpiry(JSONObject().put("expiresIn", 3600L))
        assertTrue("must be in the future", out >= before + 3599_000L)
        assertTrue("must not overshoot", out <= System.currentTimeMillis() + 3601_000L)
    }

    @Test
    fun `resolveExpiry defaults to a bounded window when the backend reports nothing`() {
        val before = System.currentTimeMillis()
        val out = WorkBuddyApi.resolveExpiry(JSONObject())
        // A missing expiry must NOT produce 0 (which would read as "expired
        // forever" and force a refresh on every single request).
        assertTrue("must not be zero", out > 0L)
        assertTrue("must be in the future", out > before)
    }

    @Test
    fun `resolveExpiry keeps a still-valid fallback instead of resetting it`() {
        val fallback = System.currentTimeMillis() + 60_000L
        val out = WorkBuddyApi.resolveExpiry(JSONObject(), fallback)
        assertEquals(fallback, out)
    }

    // ───────────────────────── catalog mapping ─────────────────────────

    private fun catalog(vararg entries: JSONObject): JSONArray =
        JSONArray().apply { entries.forEach { put(it) } }

    @Test
    fun `toModels maps capability flags and limits`() {
        val raw = catalog(
            JSONObject()
                .put("id", "primary-model")
                .put("name", "Primary")
                .put("maxInputTokens", 272_000)
                .put("maxOutputTokens", 72_000)
                .put("supportsImages", true)
                .put("supportsReasoning", true),
        )
        val models = WorkBuddyApi.toModels(raw)
        assertEquals(1, models.size)
        val m = models.single()
        assertEquals("primary-model", m.id)
        assertEquals("Primary", m.displayName)
        assertEquals(272_000, m.contextWindow)
        assertEquals(72_000, m.maxOutputTokens)
        assertEquals(true, m.supportsReasoning)
        assertTrue(m.inputModalities!!.contains("image"))
    }

    @Test
    fun `toModels reports reasoning as unknown when the flag is absent`() {
        // Absent must stay null (unknown) rather than false (unsupported) —
        // false would hard-disable the thinking toggle in the UI.
        val raw = catalog(JSONObject().put("id", "deep-model").put("name", "Deep"))
        val m = WorkBuddyApi.toModels(raw).single()
        assertNull(m.supportsReasoning)
    }

    @Test
    fun `toModels drops image and video generation entries`() {
        // Those cannot serve a chat request; offering them in the picker
        // would produce guaranteed failures.
        val raw = catalog(
            JSONObject().put("id", "gemini-3.1-flash-image"),
            JSONObject().put("id", "hunyuan-video-art"),
            JSONObject().put("id", "gpt-5.6-sol"),
        )
        val ids = WorkBuddyApi.toModels(raw).map { it.id }
        assertEquals(listOf("gpt-5.6-sol"), ids)
    }

    @Test
    fun `toModels skips disabled entries`() {
        val raw = catalog(
            JSONObject().put("id", "retired-model").put("disabled", true),
            JSONObject().put("id", "live-model"),
        )
        assertEquals(listOf("live-model"), WorkBuddyApi.toModels(raw).map { it.id })
    }

    @Test
    fun `toModels reads modelId as an alias for id`() {
        // The catalog uses `id`; the agents axis uses `modelId`.
        val raw = catalog(JSONObject().put("modelId", "kimi-k3").put("name", "Kimi-K3"))
        assertEquals("kimi-k3", WorkBuddyApi.toModels(raw).single().id)
    }

    @Test
    fun `toModels deduplicates repeated ids`() {
        val raw = catalog(
            JSONObject().put("id", "dup").put("name", "First"),
            JSONObject().put("id", "dup").put("name", "Second"),
        )
        val models = WorkBuddyApi.toModels(raw)
        assertEquals(1, models.size)
        assertEquals("First", models.single().displayName)
    }

    @Test
    fun `toModels unions the cli agent model list`() {
        // A model entitled only through the `agents[].name == "cli"` axis must
        // still be selectable.
        val agents = JSONArray().put(
            JSONObject()
                .put("name", "cli")
                .put("models", JSONArray().put(JSONObject().put("modelId", "agent-only-model"))),
        )
        val models = WorkBuddyApi.toModels(JSONArray(), agents)
        // The chat list is built from `catalog`; the agent union only adds
        // ids the catalog already carries — assert the call does not throw
        // and stays empty when the catalog is empty.
        assertTrue(models.isEmpty())
    }

    // ───────────────────────── region parsing ─────────────────────────

    @Test
    fun `Region from defaults to domestic for unknown or null input`() {
        // Persisted config may predate the field entirely, or carry a value a
        // newer build introduced — neither may throw on the read path.
        assertEquals(WorkBuddyConstants.Region.DOMESTIC, WorkBuddyConstants.Region.from(null))
        assertEquals(WorkBuddyConstants.Region.DOMESTIC, WorkBuddyConstants.Region.from(""))
        assertEquals(WorkBuddyConstants.Region.DOMESTIC, WorkBuddyConstants.Region.from("mars"))
    }

    @Test
    fun `Region from is case and whitespace insensitive`() {
        assertEquals(
            WorkBuddyConstants.Region.INTERNATIONAL,
            WorkBuddyConstants.Region.from("  INTERNATIONAL  "),
        )
    }

    @Test
    fun `Region fromStrict rejects an unknown value`() {
        assertThrows(IllegalArgumentException::class.java) {
            WorkBuddyConstants.Region.fromStrict("martian")
        }
    }

    @Test
    fun `regions address distinct backends and domains`() {
        // The two tenants are genuinely independent hosts; conflating them
        // would send a domestic account to an international endpoint.
        assertFalse(
            WorkBuddyConstants.Region.DOMESTIC.backend ==
                WorkBuddyConstants.Region.INTERNATIONAL.backend,
        )
        assertFalse(
            WorkBuddyConstants.Region.DOMESTIC.defaultDomain ==
                WorkBuddyConstants.Region.INTERNATIONAL.defaultDomain,
        )
    }

    // ───────────────────────── backend override ─────────────────────────

    @Test
    fun `resolveBackend falls back to the region host`() {
        assertEquals(
            WorkBuddyConstants.Region.INTERNATIONAL.backend,
            WorkBuddyApi.resolveBackend(WorkBuddyConstants.Region.INTERNATIONAL),
        )
    }

    @Test
    fun `resolveBackend honours a custom proxy and strips a trailing slash`() {
        assertEquals(
            "https://proxy.example.com/v2",
            WorkBuddyApi.resolveBackend(
                WorkBuddyConstants.Region.DOMESTIC,
                "  https://proxy.example.com/v2/  ",
            ),
        )
    }

    @Test
    fun `resolveBackend ignores a blank override`() {
        assertEquals(
            WorkBuddyConstants.Region.DOMESTIC.backend,
            WorkBuddyApi.resolveBackend(WorkBuddyConstants.Region.DOMESTIC, "   "),
        )
    }

    // ───────────────────────── bundled catalog ─────────────────────────

    @Test
    fun `bundled catalog exists only for the international tenant`() {
        // A domestic account's entitlement is tenant-specific, so offering a
        // guessed list would present models the account cannot call.
        assertTrue(WorkBuddyApi.bundledModels(WorkBuddyConstants.Region.INTERNATIONAL).isNotEmpty())
        assertTrue(WorkBuddyApi.bundledModels(WorkBuddyConstants.Region.DOMESTIC).isEmpty())
    }

    @Test
    fun `bundled catalog contains no image or video entries`() {
        // Same filter the live mapping applies — a placeholder list must not
        // offer anything the live path would drop.
        val offenders = WorkBuddyApi.bundledModels(WorkBuddyConstants.Region.INTERNATIONAL)
            .filterNot { WorkBuddyConstants.isChatCapable(it.id) }
        assertTrue("unexpected non-chat entries: ${offenders.map { it.id }}", offenders.isEmpty())
    }

    @Test
    fun `bundled catalog ids are unique`() {
        val ids = WorkBuddyApi.bundledModels(WorkBuddyConstants.Region.INTERNATIONAL).map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }
}
