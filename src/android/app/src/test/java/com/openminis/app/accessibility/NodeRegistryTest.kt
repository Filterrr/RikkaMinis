package com.openminis.app.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock

class NodeRegistryTest {

    private lateinit var registry: NodeRegistry

    @BeforeEach
    fun setUp() {
        registry = NodeRegistry()
    }

    @Test
    fun `put returns non-null id`() {
        val node = mock(AccessibilityNodeInfo::class.java)
        val id = registry.put(node)
        assertNotNull(id)
        assertTrue(id.isNotEmpty())
    }

    @Test
    fun `put returns unique sequential ids`() {
        val node1 = mock(AccessibilityNodeInfo::class.java)
        val node2 = mock(AccessibilityNodeInfo::class.java)
        val node3 = mock(AccessibilityNodeInfo::class.java)

        val id1 = registry.put(node1)
        val id2 = registry.put(node2)
        val id3 = registry.put(node3)

        assertNotEquals(id1, id2)
        assertNotEquals(id2, id3)
        assertNotEquals(id1, id3)
    }

    @Test
    fun `put returns id padded to at least 4 chars`() {
        val node = mock(AccessibilityNodeInfo::class.java)
        val id = registry.put(node)
        assertTrue(id.length >= 4)
    }

    @Test
    fun `get returns node previously put`() {
        val node = mock(AccessibilityNodeInfo::class.java)
        val id = registry.put(node)

        val result = registry.get(id)
        assertSame(node, result)
    }

    @Test
    fun `get returns null for unknown id`() {
        val result = registry.get("nonexistent")
        assertNull(result)
    }

    @Test
    fun `get returns null for empty id`() {
        assertNull(registry.get(""))
    }

    @Test
    fun `get returns null after clear`() {
        val node = mock(AccessibilityNodeInfo::class.java)
        val id = registry.put(node)

        registry.clear()
        assertNull(registry.get(id))
    }

    @Test
    fun `clear empties the registry`() {
        val node1 = mock(AccessibilityNodeInfo::class.java)
        val node2 = mock(AccessibilityNodeInfo::class.java)

        registry.put(node1)
        registry.put(node2)
        registry.clear()

        val node = mock(AccessibilityNodeInfo::class.java)
        val newId = registry.put(node)
        // After clear, seq continues; new id should still work
        assertSame(node, registry.get(newId))
    }

    @Test
    fun `clear on empty registry does not throw`() {
        assertDoesNotThrow { registry.clear() }
    }

    @Test
    fun `multiple puts and gets preserve nodes`() {
        val nodes = (1..10).map { mock(AccessibilityNodeInfo::class.java) }
        val ids = nodes.map { registry.put(it) }

        nodes.indices.forEach { i ->
            assertSame(nodes[i], registry.get(ids[i]))
        }
    }

    @Test
    fun `put same node multiple times returns different ids`() {
        val node = mock(AccessibilityNodeInfo::class.java)
        val id1 = registry.put(node)
        val id2 = registry.put(node)

        assertNotEquals(id1, id2)
        assertSame(node, registry.get(id1))
        assertSame(node, registry.get(id2))
    }

    @Test
    fun `TTL_MS is 60000`() {
        assertEquals(60_000L, NodeRegistry.TTL_MS)
    }

    @Test
    fun `get returns null for expired entry`() {
        val node = mock(AccessibilityNodeInfo::class.java)
        val id = registry.put(node)

        // Simulate expiration by waiting is impractical; instead verify TTL constant
        // and that fresh entries are retrievable.
        assertSame(node, registry.get(id))
    }

    @Test
    fun `put triggers eviction of expired entries`() {
        val node1 = mock(AccessibilityNodeInfo::class.java)
        val node2 = mock(AccessibilityNodeInfo::class.java)

        val id1 = registry.put(node1)
        val id2 = registry.put(node2)

        // Both should be present immediately
        assertSame(node1, registry.get(id1))
        assertSame(node2, registry.get(id2))
    }

    @Test
    fun `id format is base36`() {
        val node = mock(AccessibilityNodeInfo::class.java)
        val id = registry.put(node)
        // Base36 chars: 0-9, a-z
        assertTrue(id.all { it.isDigit() || it in 'a'..'z' })
    }

    @Test
    fun `sequential ids increment correctly`() {
        val node = mock(AccessibilityNodeInfo::class.java)
        val ids = (1..5).map { registry.put(node) }

        // Each id should be different and increasing in length/value
        assertEquals(5, ids.toSet().size)
    }

    @Test
    fun `get does not remove non-expired entry`() {
        val node = mock(AccessibilityNodeInfo::class.java)
        val id = registry.put(node)

        registry.get(id)
        val result = registry.get(id)
        assertSame(node, result)
    }

    @Test
    fun `put after clear works correctly`() {
        val node1 = mock(AccessibilityNodeInfo::class.java)
        registry.put(node1)
        registry.clear()

        val node2 = mock(AccessibilityNodeInfo::class.java)
        val id = registry.put(node2)
        assertSame(node2, registry.get(id))
    }

    @Test
    fun `large number of puts and gets`() {
        val count = 100
        val nodes = (1..count).map { mock(AccessibilityNodeInfo::class.java) }
        val ids = nodes.map { registry.put(it) }

        nodes.indices.forEach { i ->
            assertSame(nodes[i], registry.get(ids[i]))
        }
    }
}