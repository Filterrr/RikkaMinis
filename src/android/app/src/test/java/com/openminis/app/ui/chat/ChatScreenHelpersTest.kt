package com.openminis.app.ui.chat

import android.content.Context
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.jupiter.api.Test

class ChatScreenHelpersTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun createCameraOutputUri_returnsValidUriAndFile() {
        val (uri, file) = createCameraOutputUri(context)
        
        assert(uri != null)
        assert(file.exists())
        assert(uri.path != null)
        assert(uri.path!!.contains("camera-photos"))
        assert(uri.path!!.endsWith(".jpg"))
        
        file.delete()
    }

    @Test
    fun createCameraOutputUri_createsFileInCorrectDirectory() {
        val (uri, file) = createCameraOutputUri(context)
        
        val parentDir = file.parentFile
        assert(parentDir != null)
        assert(parentDir!!.name == "camera-photos")
        assert(parentDir.parentFile == context.filesDir)
        
        file.delete()
    }

    @Test
    fun createCameraOutputUri_generatesUniqueFileNames() {
        val (uri1, file1) = createCameraOutputUri(context)
        val (uri2, file2) = createCameraOutputUri(context)
        
        assert(uri1 != uri2)
        assert(file1 != file2)
        
        file1.delete()
        file2.delete()
    }

    @Test
    fun getFileName_returnsNullForInvalidUri() {
        val invalidUri = Uri.parse("content://invalid/uri")
        val result = getFileName(context, invalidUri)
        
        assert(result == null)
    }

    @Test
    fun getFileName_returnsNullForNonExistentUri() {
        val nonExistentUri = Uri.parse("content://com.openminis.app.fileprovider/nonexistent")
        val result = getFileName(context, nonExistentUri)
        
        assert(result == null)
    }

    @Test
    fun getFileName_returnsCorrectNameForValidUri() {
        val (uri, file) = createCameraOutputUri(context)
        
        val result = getFileName(context, uri)
        
        assert(result != null)
        assert(result == file.name)
        
        file.delete()
    }

    @Test
    fun pendingNonTextSelection_Group_hasCorrectProperties() {
        val group = PendingNonTextSelection.Group(
            groupId = "group1",
            modelDisplayName = "Model A",
            modalityLabel = "Text"
        )
        
        assert(group.groupId == "group1")
        assert(group.modelDisplayName == "Model A")
        assert(group.modalityLabel == "Text")
    }

    @Test
    fun pendingNonTextSelection_GroupEntry_hasCorrectProperties() {
        val groupEntry = PendingNonTextSelection.GroupEntry(
            groupId = "group1",
            entryId = "entry1",
            modelDisplayName = "Model B",
            modalityLabel = "Image"
        )
        
        assert(groupEntry.groupId == "group1")
        assert(groupEntry.entryId == "entry1")
        assert(groupEntry.modelDisplayName == "Model B")
        assert(groupEntry.modalityLabel == "Image")
    }

    @Test
    fun pendingNonTextSelection_Entry_hasCorrectProperties() {
        val entry = PendingNonTextSelection.Entry(
            entryId = "entry1",
            modelDisplayName = "Model C",
            modalityLabel = "Audio"
        )
        
        assert(entry.entryId == "entry1")
        assert(entry.modelDisplayName == "Model C")
        assert(entry.modalityLabel == "Audio")
    }

    @Test
    fun pendingNonTextSelection_sealedClass_hasCorrectSubtypes() {
        val group = PendingNonTextSelection.Group(
            groupId = "g1",
            modelDisplayName = "M1",
            modalityLabel = "L1"
        )
        val groupEntry = PendingNonTextSelection.GroupEntry(
            groupId = "g1",
            entryId = "e1",
            modelDisplayName = "M2",
            modalityLabel = "L2"
        )
        val entry = PendingNonTextSelection.Entry(
            entryId = "e1",
            modelDisplayName = "M3",
            modalityLabel = "L3"
        )
        
        assert(group is PendingNonTextSelection)
        assert(groupEntry is PendingNonTextSelection)
        assert(entry is PendingNonTextSelection)
    }

    @Test
    fun pendingNonTextSelection_Group_differentGroupsAreNotEqual() {
        val group1 = PendingNonTextSelection.Group(
            groupId = "g1",
            modelDisplayName = "M1",
            modalityLabel = "L1"
        )
        val group2 = PendingNonTextSelection.Group(
            groupId = "g2",
            modelDisplayName = "M2",
            modalityLabel = "L2"
        )
        
        assert(group1 != group2)
    }

    @Test
    fun pendingNonTextSelection_GroupEntry_differentEntriesAreNotEqual() {
        val entry1 = PendingNonTextSelection.GroupEntry(
            groupId = "g1",
            entryId = "e1",
            modelDisplayName = "M1",
            modalityLabel = "L1"
        )
        val entry2 = PendingNonTextSelection.GroupEntry(
            groupId = "g1",
            entryId = "e2",
            modelDisplayName = "M2",
            modalityLabel = "L2"
        )
        
        assert(entry1 != entry2)
    }

    @Test
    fun pendingNonTextSelection_Entry_differentEntriesAreNotEqual() {
        val entry1 = PendingNonTextSelection.Entry(
            entryId = "e1",
            modelDisplayName = "M1",
            modalityLabel = "L1"
        )
        val entry2 = PendingNonTextSelection.Entry(
            entryId = "e2",
            modelDisplayName = "M2",
            modalityLabel = "L2"
        )
        
        assert(entry1 != entry2)
    }

    @Test
    fun pendingNonTextSelection_Group_samePropertiesAreEqual() {
        val group1 = PendingNonTextSelection.Group(
            groupId = "g1",
            modelDisplayName = "M1",
            modalityLabel = "L1"
        )
        val group2 = PendingNonTextSelection.Group(
            groupId = "g1",
            modelDisplayName = "M1",
            modalityLabel = "L1"
        )
        
        assert(group1 == group2)
    }

    @Test
    fun pendingNonTextSelection_GroupEntry_samePropertiesAreEqual() {
        val entry1 = PendingNonTextSelection.GroupEntry(
            groupId = "g1",
            entryId = "e1",
            modelDisplayName = "M1",
            modalityLabel = "L1"
        )
        val entry2 = PendingNonTextSelection.GroupEntry(
            groupId = "g1",
            entryId = "e1",
            modelDisplayName = "M1",
            modalityLabel = "L1"
        )
        
        assert(entry1 == entry2)
    }

    @Test
    fun pendingNonTextSelection_Entry_samePropertiesAreEqual() {
        val entry1 = PendingNonTextSelection.Entry(
            entryId = "e1",
            modelDisplayName = "M1",
            modalityLabel = "L1"
        )
        val entry2 = PendingNonTextSelection.Entry(
            entryId = "e1",
            modelDisplayName = "M1",
            modalityLabel = "L1"
        )
        
        assert(entry1 == entry2)
    }
}