package com.openminis.app.ui.components

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.repository.ProviderRepository
import org.junit.Rule
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class QuickTestSheetTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var mockProviderRepository: ProviderRepository
    private lateinit var mockContext: Context
    private lateinit var testEntry: ModelEntry

    @BeforeEach
    fun setUp() {
        mockProviderRepository = mock()
        mockContext = Mockito.mock(Context::class.java)
        
        val model = LLMModel(
            id = "test-model",
            displayName = "Test Model",
            inputModalities = listOf("text"),
            outputModalities = listOf("text")
        )
        testEntry = ModelEntry(
            id = "test-entry",
            providerInstanceId = "test-provider",
            model = model,
            baseModel = model
        )
        
        whenever(mockProviderRepository.instance("test-provider")).thenReturn(null)
    }

    @Test
    fun quickTestSheet_rendersTitleAndModelInfo() {
        composeTestRule.setContent {
            QuickTestSheet(
                entry = testEntry,
                providerRepository = mockProviderRepository,
                onDismiss = {}
            )
        }

        composeTestRule.onNodeWithText("Quick Test").assertIsDisplayed()
        composeTestRule.onNodeWithText("Test Model").assertIsDisplayed()
        composeTestRule.onNodeWithText("test-model").assertIsDisplayed()
    }

    @Test
    fun quickTestSheet_showsRefreshButton_whenNotRunning() {
        composeTestRule.setContent {
            QuickTestSheet(
                entry = testEntry,
                providerRepository = mockProviderRepository,
                onDismiss = {}
            )
        }

        composeTestRule.onNodeWithContentDescription("Run again").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Run again").assertIsEnabled()
    }

    @Test
    fun quickTestSheet_dismissButton_clickable() {
        var dismissed = false
        
        composeTestRule.setContent {
            QuickTestSheet(
                entry = testEntry,
                providerRepository = mockProviderRepository,
                onDismiss = { dismissed = true }
            )
        }

        composeTestRule.onNodeWithText("Done").performClick()
        assert(dismissed)
    }

    @Test
    fun quickTestSheet_withTextModel_showsTextTest() {
        composeTestRule.setContent {
            QuickTestSheet(
                entry = testEntry,
                providerRepository = mockProviderRepository,
                onDismiss = {}
            )
        }

        composeTestRule.onNodeWithText("Text").assertIsDisplayed()
    }

    @Test
    fun quickTestSheet_withImageModel_showsImageTest() {
        val imageModel = LLMModel(
            id = "image-model",
            displayName = "Image Model",
            inputModalities = listOf("text"),
            outputModalities = listOf("image")
        )
        val imageEntry = ModelEntry(
            id = "image-entry",
            providerInstanceId = "image-provider",
            model = imageModel,
            baseModel = imageModel
        )

        composeTestRule.setContent {
            QuickTestSheet(
                entry = imageEntry,
                providerRepository = mockProviderRepository,
                onDismiss = {}
            )
        }

        composeTestRule.onNodeWithText("Image").assertIsDisplayed()
    }

    @Test
    fun quickTestSheet_withSpeechModel_showsSpeechTest() {
        val speechModel = LLMModel(
            id = "speech-model",
            displayName = "Speech Model",
            inputModalities = listOf("text"),
            outputModalities = listOf("audio")
        )
        val speechEntry = ModelEntry(
            id = "speech-entry",
            providerInstanceId = "speech-provider",
            model = speechModel,
            baseModel = speechModel
        )

        composeTestRule.setContent {
            QuickTestSheet(
                entry = speechEntry,
                providerRepository = mockProviderRepository,
                onDismiss = {}
            )
        }

        composeTestRule.onNodeWithText("Speech").assertIsDisplayed()
    }

    @Test
    fun quickTestSheet_withTranscriptionModel_showsTranscriptionTest() {
        val transcriptionModel = LLMModel(
            id = "transcription-model",
            displayName = "Transcription Model",
            inputModalities = listOf("audio"),
            outputModalities = listOf("text")
        )
        val transcriptionEntry = ModelEntry(
            id = "transcription-entry",
            providerInstanceId = "transcription-provider",
            model = transcriptionModel,
            baseModel = transcriptionModel
        )

        composeTestRule.setContent {
            QuickTestSheet(
                entry = transcriptionEntry,
                providerRepository = mockProviderRepository,
                onDismiss = {}
            )
        }

        composeTestRule.onNodeWithText("Transcription").assertIsDisplayed()
    }

    @Test
    fun quickTestSheet_withMultipleModalities_showsAllTests() {
        val multiModel = LLMModel(
            id = "multi-model",
            displayName = "Multi Model",
            inputModalities = listOf("text", "audio"),
            outputModalities = listOf("text", "image", "audio")
        )
        val multiEntry = ModelEntry(
            id = "multi-entry",
            providerInstanceId = "multi-provider",
            model = multiModel,
            baseModel = multiModel
        )

        composeTestRule.setContent {
            QuickTestSheet(
                entry = multiEntry,
                providerRepository = mockProviderRepository,
                onDismiss = {}
            )
        }

        composeTestRule.onNodeWithText("Text").assertIsDisplayed()
        composeTestRule.onNodeWithText("Image").assertIsDisplayed()
        composeTestRule.onNodeWithText("Speech").assertIsDisplayed()
    }

    @Test
    fun quickTestSheet_defaultParameters_rendersCorrectly() {
        composeTestRule.setContent {
            QuickTestSheet(
                entry = testEntry,
                providerRepository = mockProviderRepository,
                onDismiss = {}
            )
        }

        composeTestRule.onNodeWithText("Quick Test").assertIsDisplayed()
        composeTestRule.onNodeWithText("Test Model").assertIsDisplayed()
        composeTestRule.onNodeWithText("Done").assertIsDisplayed()
    }

    @Test
    fun quickTestSheet_testingState_showsProgressIndicator() {
        composeTestRule.setContent {
            QuickTestSheet(
                entry = testEntry,
                providerRepository = mockProviderRepository,
                onDismiss = {}
            )
        }

        composeTestRule.onNodeWithText("Testing...").assertIsDisplayed()
    }
}