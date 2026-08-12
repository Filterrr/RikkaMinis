package com.openminis.app.data

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.openminis.app.data.MountedFoldersStore.Entry
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import android.net.Uri
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule

class MountedFoldersStoreTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val store = MountedFoldersStore(context)

    @Test
    fun testEntryDataClass_defaultValues() {
        val entry = Entry(
            name = "Test",
            sourceDisplayName = "TestSource",
            treeUri = "content://test"
        )
        
        assertNotNull(entry.id)
        assertTrue(entry.createdAt > 0)
        assertTrue(entry.isWritable)
        assertTrue(entry.userAllowWrite)
        assertNull(entry.resolvedHostPath)
        assertTrue(entry.effectiveWritable)
        
        val entry2 = entry.copy(userAllowWrite = false)
        assertFalse(entry2.effectiveWritable)
    }

    @Test
    fun testEntryDataClass_effectiveWritable() {
        val entry = Entry(
            name = "Test",
            sourceDisplayName = "TestSource",
            treeUri = "content://test",
            isWritable = true,
            userAllowWrite = true
        )
        assertTrue(entry.effectiveWritable)
        
        val readOnly = entry.copy(isWritable = false)
        assertFalse(readOnly.effectiveWritable)
        
        val userBlocked = entry.copy(userAllowWrite = false)
        assertFalse(userBlocked.effectiveWritable)
    }

    @Test
    fun testStore_initialState() {
        assertEquals(0, store.entries.value.size)
    }

    @Test
    fun testStore_add_rejectsNullName() = runTest {
        val uri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ATest")
        val result = store.add(uri, "")
        assertNull(result)
        assertEquals(0, store.entries.value.size)
    }

    @Test
    fun testStore_add_rejectsDuplicateName() = runTest {
        val uri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ATest")
        val first = store.add(uri, "Test")
        assertNotNull(first)
        
        val second = store.add(uri, "test")
        assertNull(second)
        assertEquals(1, store.entries.value.size)
    }

    @Test
    fun testStore_add_rejectsMaxMounts() = runTest {
        for (i in 0 until MountedFoldersStore.MAX_MOUNTS) {
            val uri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ATest$i")
            val result = store.add(uri, "Test$i")
            assertNotNull(result)
        }
        
        val uri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ATestOverflow")
        val result = store.add(uri, "TestOverflow")
        assertNull(result)
        assertEquals(MountedFoldersStore.MAX_MOUNTS, store.entries.value.size)
    }

    @Test
    fun testStore_remove() = runTest {
        val uri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ATest")
        val entry = store.add(uri, "Test")
        assertNotNull(entry)
        
        val removed = store.remove(entry.id)
        assertTrue(removed)
        assertEquals(0, store.entries.value.size)
        
        val removeAgain = store.remove(entry.id)
        assertFalse(removeAgain)
    }

    @Test
    fun testStore_rename() = runTest {
        val uri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ATest")
        val entry = store.add(uri, "Test")
        assertNotNull(entry)
        
        val renamed = store.rename(entry.id, "NewName")
        assertTrue(renamed)
        assertEquals("NewName", store.entries.value.first().name)
        
        val renameInvalid = store.rename(entry.id, "")
        assertFalse(renameInvalid)
        
        val renameDuplicate = store.add(uri, "Other")
        assertNotNull(renameDuplicate)
        val renameDuplicateResult = store.rename(entry.id, "Other")
        assertFalse(renameDuplicateResult)
    }

    @Test
    fun testStore_setUserAllowWrite() = runTest {
        val uri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ATest")
        val entry = store.add(uri, "Test")
        assertNotNull(entry)
        
        val changed = store.setUserAllowWrite(entry.id, false)
        assertTrue(changed)
        assertFalse(store.entries.value.first().userAllowWrite)
        
        val noChange = store.setUserAllowWrite(entry.id, false)
        assertFalse(noChange)
    }

    @Test
    fun testStore_probeWritable() {
        val tempDir = java.io.File.createTempFile("test", "dir")
        tempDir.delete()
        tempDir.mkdirs()
        
        val result = store.probeWritable(tempDir.absolutePath)
        assertTrue(result)
        
        tempDir.deleteRecursively()
    }

    @Test
    fun testStore_probeWritable_nonexistent() {
        val result = store.probeWritable("/nonexistent/path")
        assertFalse(result)
    }

    @Test
    fun testStore_sanitizeName() {
        // Test via add with invalid names
        val invalidName = "../test"
        val uri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ATest")
        
        // These should fail due to invalid names
        runTest {
            val result = store.add(uri, invalidName)
            assertNull(result)
        }
    }

    @Test
    fun testSafMountHelper_buildPickerIntent() {
        val intent = SafMountHelper.buildPickerIntent()
        assertEquals(android.content.Intent.ACTION_OPEN_DOCUMENT_TREE, intent.action)
        assertTrue(intent.flags and android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(intent.flags and android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
        assertTrue(intent.flags and android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0)
    }

    @Test
    fun testSafMountHelper_treeDisplayPath() {
        val uri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ATestFolder")
        val path = SafMountHelper.treeDisplayPath(uri)
        assertEquals("primary:TestFolder", path)
    }

    @Test
    fun testComposable_rendering() {
        composeTestRule.setContent {
            Column {
                Text("Test Text", Modifier.testTag("testText"))
                Button(onClick = {}, Modifier.testTag("testButton")) {
                    Text("Click Me")
                }
            }
        }
        
        composeTestRule.onNodeWithTag("testText").assertIsDisplayed()
        composeTestRule.onNodeWithTag("testButton").assertIsDisplayed()
    }

    @Test
    fun testComposable_clickEvent() {
        var clicked = false
        composeTestRule.setContent {
            Column {
                Button(
                    onClick = { clicked = true },
                    Modifier.testTag("clickButton")
                ) {
                    Text("Click")
                }
            }
        }
        
        composeTestRule.onNodeWithTag("clickButton").performClick()
        assertTrue(clicked)
    }

    @Test
    fun testComposable_defaultParameters() {
        composeTestRule.setContent {
            Column {
                Text("Default Text", Modifier.testTag("defaultText"))
                Button(onClick = {}, Modifier.testTag("defaultButton")) {
                    Text("Default Button")
                }
            }
        }
        
        composeTestRule.onNodeWithText("Default Text").assertIsDisplayed()
        composeTestRule.onNodeWithText("Default Button").assertIsDisplayed()
    }

    @Test
    fun testStore_composableIntegration() {
        var displayedEntries = 0
        composeTestRule.setContent {
            val entries = remember { mutableStateOf(store.entries.value) }
            Column {
                Text("Entries: ${entries.value.size}", Modifier.testTag("entryCount"))
                entries.value.forEach { entry ->
                    Text(entry.name, Modifier.testTag("entry_${entry.id}"))
                }
            }
        }
        
        composeTestRule.onNodeWithTag("entryCount").assertIsDisplayed()
        composeTestRule.onNodeWithText("Entries: 0").assertIsDisplayed()
    }
}