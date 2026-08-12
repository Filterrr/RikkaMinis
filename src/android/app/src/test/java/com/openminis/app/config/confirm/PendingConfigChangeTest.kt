package com.openminis.app.config.confirm

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.Assertions.*

class PendingConfigChangeTest {

    @Test
    fun `PendingConfigChangeItem should have default id and isApproved`() {
        val item = PendingConfigChangeItem(
            displayName = "Test",
            path = "/test",
            oldDisplay = "old",
            newDisplay = "new",
            verb = "change",
            risk = ConfigRisk.LOW
        )
        assertAll(
            { assertNotNull(item.id) },
            { assertTrue(item.isApproved) },
            { assertEquals("Test", item.displayName) },
            { assertEquals("/test", item.path) },
            { assertEquals("old", item.oldDisplay) },
            { assertEquals("new", item.newDisplay) },
            { assertEquals("change", item.verb) },
            { assertEquals(ConfigRisk.LOW, item.risk) }
        )
    }

    @Test
    fun `PendingConfigChangeItem should accept custom id and isApproved`() {
        val item = PendingConfigChangeItem(
            id = "custom-id",
            displayName = "Custom",
            path = "/custom",
            oldDisplay = "old",
            newDisplay = "new",
            verb = "delete",
            risk = ConfigRisk.HIGH,
            isApproved = false
        )
        assertAll(
            { assertEquals("custom-id", item.id) },
            { assertFalse(item.isApproved) },
            { assertEquals("Custom", item.displayName) },
            { assertEquals("/custom", item.path) },
            { assertEquals("old", item.oldDisplay) },
            { assertEquals("new", item.newDisplay) },
            { assertEquals("delete", item.verb) },
            { assertEquals(ConfigRisk.HIGH, item.risk) }
        )
    }

    @Test
    fun `PendingConfigChangeItem copy should work correctly`() {
        val original = PendingConfigChangeItem(
            displayName = "Original",
            path = "/orig",
            oldDisplay = "old",
            newDisplay = "new",
            verb = "update",
            risk = ConfigRisk.MEDIUM
        )
        val copied = original.copy(displayName = "Copied")
        assertAll(
            { assertEquals(original.id, copied.id) },
            { assertEquals("Copied", copied.displayName) },
            { assertEquals(original.path, copied.path) },
            { assertEquals(original.oldDisplay, copied.oldDisplay) },
            { assertEquals(original.newDisplay, copied.newDisplay) },
            { assertEquals(original.verb, copied.verb) },
            { assertEquals(original.risk, copied.risk) },
            { assertEquals(original.isApproved, copied.isApproved) }
        )
    }

    @Test
    fun `PendingConfigChange should have default id and provided items and caption`() {
        val item = PendingConfigChangeItem(
            displayName = "Item1",
            path = "/a",
            oldDisplay = "old",
            newDisplay = "new",
            verb = "add",
            risk = ConfigRisk.LOW
        )
        val change = PendingConfigChange(
            items = listOf(item),
            caption = "Test caption"
        )
        assertAll(
            { assertNotNull(change.id) },
            { assertEquals(1, change.items.size) },
            { assertEquals(item, change.items[0]) },
            { assertEquals("Test caption", change.caption) }
        )
    }

    @Test
    fun `PendingConfigChange should allow null caption`() {
        val item = PendingConfigChangeItem(
            displayName = "Item2",
            path = "/b",
            oldDisplay = "old",
            newDisplay = "new",
            verb = "remove",
            risk = ConfigRisk.HIGH
        )
        val change = PendingConfigChange(
            items = listOf(item),
            caption = null
        )
        assertNull(change.caption)
    }

    @Test
    fun `ConfirmOutcome Approved should hold items`() {
        val item = PendingConfigChangeItem(
            displayName = "Approve me",
            path = "/approve",
            oldDisplay = "old",
            newDisplay = "new",
            verb = "modify",
            risk = ConfigRisk.LOW
        )
        val outcome = ConfirmOutcome.Approved(items = listOf(item))
        assertAll(
            { assertTrue(outcome is ConfirmOutcome.Approved) },
            { assertEquals(1, (outcome as ConfirmOutcome.Approved).items.size) },
            { assertEquals(item, (outcome as ConfirmOutcome.Approved).items[0]) }
        )
    }

    @Test
    fun `ConfirmOutcome Rejected should be singleton`() {
        val outcome1 = ConfirmOutcome.Rejected
        val outcome2 = ConfirmOutcome.Rejected
        assertSame(outcome1, outcome2)
    }

    @Test
    fun `ConfirmOutcome TimedOut should be singleton`() {
        val outcome1 = ConfirmOutcome.TimedOut
        val outcome2 = ConfirmOutcome.TimedOut
        assertSame(outcome1, outcome2)
    }

    @Test
    fun `PendingConfigChangeItem equals and hashCode should work`() {
        val item1 = PendingConfigChangeItem(
            id = "same-id",
            displayName = "X",
            path = "/x",
            oldDisplay = "old",
            newDisplay = "new",
            verb = "verb",
            risk = ConfigRisk.LOW,
            isApproved = true
        )
        val item2 = item1.copy()
        assertEquals(item1, item2)
        assertEquals(item1.hashCode(), item2.hashCode())
    }

    @Test
    fun `PendingConfigChange equals and hashCode should work`() {
        val item = PendingConfigChangeItem(
            displayName = "Test",
            path = "/t",
            oldDisplay = "old",
            newDisplay = "new",
            verb = "verb",
            risk = ConfigRisk.LOW
        )
        val change1 = PendingConfigChange(
            id = "change-id",
            items = listOf(item),
            caption = "cap"
        )
        val change2 = change1.copy()
        assertEquals(change1, change2)
        assertEquals(change1.hashCode(), change2.hashCode())
    }
}