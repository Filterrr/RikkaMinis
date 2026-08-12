package com.openminis.app.config

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ConfigCollectionTest {

    private lateinit var collection: TestConfigCollection

    @BeforeEach
    fun setUp() {
        collection = TestConfigCollection()
    }

    @Test
    fun `basePath returns configured value`() {
        assertEquals("/test/base", collection.basePath)
    }

    @Test
    fun `displayName returns configured value`() {
        assertEquals("Test Collection", collection.displayName)
    }

    @Test
    fun `description returns configured value`() {
        assertEquals("A test collection", collection.description)
    }

    @Test
    fun `addable returns true by default`() {
        assertTrue(collection.addable)
    }

    @Test
    fun `removable returns true by default`() {
        assertTrue(collection.removable)
    }

    @Test
    fun `risk returns SENSITIVE by default`() {
        assertEquals(ConfigRisk.SENSITIVE, collection.risk)
    }

    @Test
    fun `addPayloadSchema returns Json by default`() {
        assertEquals(ConfigSchema.Json, collection.addPayloadSchema)
    }

    @Test
    fun `childIds returns list of child ids`() {
        collection.addChild("child1")
        collection.addChild("child2")
        assertEquals(listOf("child1", "child2"), collection.childIds())
    }

    @Test
    fun `childIds returns empty list when no children`() {
        assertTrue(collection.childIds().isEmpty())
    }

    @Test
    fun `fields returns fields for given id`() {
        collection.addChild("child1")
        val fields = collection.fields("child1")
        assertNotNull(fields)
        assertEquals(2, fields.size)
    }

    @Test
    fun `fields returns empty list for unknown id`() {
        val fields = collection.fields("unknown")
        assertTrue(fields.isEmpty())
    }

    @Test
    fun `add returns new id and registers child`() {
        val payload = ConfigValue(mapOf("name" to "newItem"))
        val newId = collection.add(payload)
        assertNotNull(newId)
        assertTrue(collection.childIds().contains(newId))
    }

    @Test
    fun `add generates unique ids`() {
        val payload = ConfigValue(mapOf("name" to "item"))
        val id1 = collection.add(payload)
        val id2 = collection.add(payload)
        assertNotEquals(id1, id2)
    }

    @Test
    fun `remove removes existing child`() {
        collection.addChild("child1")
        collection.addChild("child2")
        collection.remove("child1")
        assertFalse(collection.childIds().contains("child1"))
        assertTrue(collection.childIds().contains("child2"))
    }

    @Test
    fun `remove on non-existent id does not throw`() {
        assertDoesNotThrow { collection.remove("nonexistent") }
    }

    @Test
    fun `custom addable override returns false`() {
        val custom = object : TestConfigCollection() {
            override val addable: Boolean = false
        }
        assertFalse(custom.addable)
    }

    @Test
    fun `custom removable override returns false`() {
        val custom = object : TestConfigCollection() {
            override val removable: Boolean = false
        }
        assertFalse(custom.removable)
    }

    @Test
    fun `custom risk override returns SAFE`() {
        val custom = object : TestConfigCollection() {
            override val risk: ConfigRisk = ConfigRisk.SAFE
        }
        assertEquals(ConfigRisk.SAFE, custom.risk)
    }

    @Test
    fun `custom addPayloadSchema override returns Form`() {
        val custom = object : TestConfigCollection() {
            override val addPayloadSchema: ConfigSchema = ConfigSchema.Form
        }
        assertEquals(ConfigSchema.Form, custom.addPayloadSchema)
    }

    private open class TestConfigCollection : ConfigCollection {
        private val children = mutableMapOf<String, List<ConfigField>>()
        private var counter = 0

        override val basePath: String = "/test/base"
        override val displayName: String = "Test Collection"
        override val description: String = "A test collection"

        override fun childIds(): List<String> = children.keys.toList()

        override fun fields(forId: String): List<ConfigField> =
            children[forId] ?: emptyList()

        override fun add(payload: ConfigValue): String {
            val id = "item-${counter++}"
            children[id] = listOf(
                ConfigField("name", "string"),
                ConfigField("value", "string")
            )
            return id
        }

        override fun remove(id: String) {
            children.remove(id)
        }

        fun addChild(id: String) {
            children[id] = listOf(
                ConfigField("name", "string"),
                ConfigField("value", "string")
            )
        }
    }
}