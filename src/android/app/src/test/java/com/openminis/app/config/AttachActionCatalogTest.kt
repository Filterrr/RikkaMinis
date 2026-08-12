package com.openminis.app.config

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class AttachActionCatalogTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // Test Composable that displays all actions
    @Composable
    fun AttachActionList(
        actions: List<AttachActionCatalog.Spec> = AttachActionCatalog.ALL,
        onClick: (AttachActionCatalog.Spec) -> Unit = {}
    ) {
        actions.forEach { spec ->
            Button(
                onClick = { onClick(spec) },
                modifier = Modifier.testTag("action_${spec.key}")
            ) {
                Icon(spec.icon, contentDescription = spec.key)
                Text(spec.key)
            }
        }
    }

    // Test Composable that displays a single action
    @Composable
    fun SingleActionDisplay(
        spec: AttachActionCatalog.Spec = AttachActionCatalog.spec(AttachActionCatalog.CHOOSE_PHOTOS)!!,
        onClick: () -> Unit = {}
    ) {
        Button(
            onClick = onClick,
            modifier = Modifier.testTag("single_action")
        ) {
            Icon(spec.icon, contentDescription = spec.key)
            Text(spec.key)
        }
    }

    @Test
    fun `test catalog constants`() {
        assertEquals("attach_choose_photos", AttachActionCatalog.CHOOSE_PHOTOS)
        assertEquals("attach_add_file", AttachActionCatalog.ADD_FILE)
        assertEquals("attach_take_photo", AttachActionCatalog.TAKE_PHOTO)
    }

    @Test
    fun `test default order`() {
        assertEquals(3, AttachActionCatalog.DEFAULT_ORDER.size)
        assertEquals(AttachActionCatalog.CHOOSE_PHOTOS, AttachActionCatalog.DEFAULT_ORDER[0])
        assertEquals(AttachActionCatalog.ADD_FILE, AttachActionCatalog.DEFAULT_ORDER[1])
        assertEquals(AttachActionCatalog.TAKE_PHOTO, AttachActionCatalog.DEFAULT_ORDER[2])
    }

    @Test
    fun `test all actions list`() {
        assertEquals(3, AttachActionCatalog.ALL.size)
        
        val choosePhotos = AttachActionCatalog.ALL[0]
        assertEquals(AttachActionCatalog.CHOOSE_PHOTOS, choosePhotos.key)
        assertEquals(Icons.Default.PhotoLibrary, choosePhotos.icon)
        
        val addFile = AttachActionCatalog.ALL[1]
        assertEquals(AttachActionCatalog.ADD_FILE, addFile.key)
        assertEquals(Icons.Default.Description, addFile.icon)
        
        val takePhoto = AttachActionCatalog.ALL[2]
        assertEquals(AttachActionCatalog.TAKE_PHOTO, takePhoto.key)
        assertEquals(Icons.Default.CameraAlt, takePhoto.icon)
    }

    @Test
    fun `test spec function returns correct spec`() {
        val choosePhotosSpec = AttachActionCatalog.spec(AttachActionCatalog.CHOOSE_PHOTOS)
        assertNotNull(choosePhotosSpec)
        assertEquals(AttachActionCatalog.CHOOSE_PHOTOS, choosePhotosSpec?.key)
        assertEquals(Icons.Default.PhotoLibrary, choosePhotosSpec?.icon)

        val addFileSpec = AttachActionCatalog.spec(AttachActionCatalog.ADD_FILE)
        assertNotNull(addFileSpec)
        assertEquals(AttachActionCatalog.ADD_FILE, addFileSpec?.key)
        assertEquals(Icons.Default.Description, addFileSpec?.icon)

        val takePhotoSpec = AttachActionCatalog.spec(AttachActionCatalog.TAKE_PHOTO)
        assertNotNull(takePhotoSpec)
        assertEquals(AttachActionCatalog.TAKE_PHOTO, takePhotoSpec?.key)
        assertEquals(Icons.Default.CameraAlt, takePhotoSpec?.icon)
    }

    @Test
    fun `test spec function returns null for invalid key`() {
        assertNull(AttachActionCatalog.spec("invalid_key"))
    }

    @Test
    fun `test action list renders all items`() {
        composeTestRule.setContent {
            AttachActionList()
        }

        composeTestRule.onNodeWithTag("action_${AttachActionCatalog.CHOOSE_PHOTOS}").assertIsDisplayed()
        composeTestRule.onNodeWithTag("action_${AttachActionCatalog.ADD_FILE}").assertIsDisplayed()
        composeTestRule.onNodeWithTag("action_${AttachActionCatalog.TAKE_PHOTO}").assertIsDisplayed()
        
        composeTestRule.onAllNodesWithText(AttachActionCatalog.CHOOSE_PHOTOS).assertCountEquals(1)
        composeTestRule.onAllNodesWithText(AttachActionCatalog.ADD_FILE).assertCountEquals(1)
        composeTestRule.onAllNodesWithText(AttachActionCatalog.TAKE_PHOTO).assertCountEquals(1)
    }

    @Test
    fun `test action list click events`() {
        var clickedSpec: AttachActionCatalog.Spec? = null
        
        composeTestRule.setContent {
            AttachActionList(
                onClick = { spec -> clickedSpec = spec }
            )
        }

        composeTestRule.onNodeWithTag("action_${AttachActionCatalog.CHOOSE_PHOTOS}").performClick()
        assertEquals(AttachActionCatalog.CHOOSE_PHOTOS, clickedSpec?.key)

        composeTestRule.onNodeWithTag("action_${AttachActionCatalog.ADD_FILE}").performClick()
        assertEquals(AttachActionCatalog.ADD_FILE, clickedSpec?.key)

        composeTestRule.onNodeWithTag("action_${AttachActionCatalog.TAKE_PHOTO}").performClick()
        assertEquals(AttachActionCatalog.TAKE_PHOTO, clickedSpec?.key)
    }

    @Test
    fun `test action list with custom actions`() {
        val customActions = listOf(
            AttachActionCatalog.Spec("custom_action_1", R.string.chat_attach_choose_photos_videos, Icons.Default.PhotoLibrary),
            AttachActionCatalog.Spec("custom_action_2", R.string.chat_attach_add_file, Icons.Default.Description)
        )

        composeTestRule.setContent {
            AttachActionList(actions = customActions)
        }

        composeTestRule.onNodeWithTag("action_custom_action_1").assertIsDisplayed()
        composeTestRule.onNodeWithTag("action_custom_action_2").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("custom_action_1").assertCountEquals(1)
        composeTestRule.onAllNodesWithText("custom_action_2").assertCountEquals(1)
    }

    @Test
    fun `test single action display with default parameter`() {
        composeTestRule.setContent {
            SingleActionDisplay()
        }

        composeTestRule.onNodeWithTag("single_action").assertIsDisplayed()
        composeTestRule.onNodeWithText(AttachActionCatalog.CHOOSE_PHOTOS).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(AttachActionCatalog.CHOOSE_PHOTOS).assertIsDisplayed()
    }

    @Test
    fun `test single action display with custom spec`() {
        val customSpec = AttachActionCatalog.Spec("custom_spec", R.string.chat_attach_take_photo, Icons.Default.CameraAlt)

        composeTestRule.setContent {
            SingleActionDisplay(spec = customSpec)
        }

        composeTestRule.onNodeWithTag("single_action").assertIsDisplayed()
        composeTestRule.onNodeWithText("custom_spec").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("custom_spec").assertIsDisplayed()
    }

    @Test
    fun `test single action display click event`() {
        var clickCount = 0

        composeTestRule.setContent {
            SingleActionDisplay(
                onClick = { clickCount++ }
            )
        }

        composeTestRule.onNodeWithTag("single_action").performClick()
        composeTestRule.onNodeWithTag("single_action").performClick()
        
        assertEquals(2, clickCount)
    }

    @Test
    fun `test action list empty state`() {
        composeTestRule.setContent {
            AttachActionList(actions = emptyList())
        }

        composeTestRule.onAllNodes(hasTestTag("action_")).assertCountEquals(0)
    }

    @Test
    fun `test spec data class properties`() {
        val spec = AttachActionCatalog.Spec(
            key = "test_key",
            titleRes = R.string.chat_attach_choose_photos_videos,
            icon = Icons.Default.PhotoLibrary
        )

        assertEquals("test_key", spec.key)
        assertEquals(R.string.chat_attach_choose_photos_videos, spec.titleRes)
        assertEquals(Icons.Default.PhotoLibrary, spec.icon)
    }
}