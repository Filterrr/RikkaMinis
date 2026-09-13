package com.openminis.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-key-affinity] Tests for the sticky-credential persistence format.
 *
 * The prefs blob encodes `"entryId:index"` pairs, and entry ids are composite
 * `"{instanceId}/{modelId}"` where the modelId may ITSELF contain colons
 * (OpenRouter-style `model:variant`). The encoding is only safe because the
 * index is the appended final segment and decoding splits on the LAST colon —
 * these tests pin exactly that contract, because a first-colon split would
 * silently corrupt memory for every colon-carrying model and the failure mode
 * (wrong slot, missing_api_key) is far from its cause.
 */
class StickyKeyEncodingTest {

    @Test fun roundTrip_plainIds() {
        val map = mapOf("entry-a" to 0, "entry-b" to 2)
        assertEquals(map, decodeStickyKeyEntries(encodeStickyKeyEntries(map)))
    }

    @Test fun roundTrip_colonBearingEntryIds_splitOnLastColon() {
        // The load-bearing case: composite ids with colons inside.
        val map = mapOf("inst1/deepseek:chat" to 1, "inst2/model:variant:extra" to 3)
        assertEquals(map, decodeStickyKeyEntries(encodeStickyKeyEntries(map)))
    }

    @Test fun roundTrip_slashIds() {
        val map = mapOf("inst/model-id" to 5)
        assertEquals(map, decodeStickyKeyEntries(encodeStickyKeyEntries(map)))
    }

    @Test fun malformedEntriesAreDroppedNotFatal() {
        val raw = setOf(
            "good:1",
            "nocolon",
            ":3",               // empty id
            "bad:idx",          // non-numeric index
            "neg:-1",           // negative index — no slot addresses below 0
            "ok:0",
        )
        val decoded = decodeStickyKeyEntries(raw)
        assertEquals(mapOf("good" to 1, "ok" to 0), decoded)
    }

    @Test fun nullStoreDecodesToEmpty() {
        assertTrue(decodeStickyKeyEntries(null).isEmpty())
    }

    @Test fun emptyKeyOrNegativeIndexIsNotEncoded() {
        // Writing those would produce entries decode() must drop anyway;
        // filtering at encode keeps the persisted set canonical.
        val encoded = encodeStickyKeyEntries(mapOf("" to 1, "ok" to 2, "bad" to -1))
        assertEquals(setOf("ok:2"), encoded)
    }

    @Test fun emptyMapEncodesToEmptySet() {
        // The property path removes the prefs key entirely on an empty set,
        // so "cleared memory" and "never had memory" converge in storage.
        assertTrue(encodeStickyKeyEntries(emptyMap()).isEmpty())
    }
}
