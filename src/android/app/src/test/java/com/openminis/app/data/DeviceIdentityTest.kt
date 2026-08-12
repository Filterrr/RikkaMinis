package com.openminis.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.openminis.app.util.EncryptedPrefsFactory
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Rule
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
@ExtendWith(::class)
class DeviceIdentityTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var mockPrefs: SharedPreferences
    private lateinit var mockEditor: SharedPreferences.Editor

    @BeforeEach
    fun setUp() {
        mockkObject(EncryptedPrefsFactory)
        context = ApplicationProvider.getApplicationContext()
        mockPrefs = mockk(relaxed = true)
        mockEditor = mockk(relaxed = true)
        every { EncryptedPrefsFactory.safeCreate(any(), any()) } returns mockPrefs
        every { mockPrefs.edit() } returns mockEditor
        every { mockPrefs.getString(any(), any()) } returns null
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun deviceId_rendersCorrectly() {
        composeTestRule.setContent {
            androidx.compose.material3.Text(text = DeviceIdentity.deviceId(context))
        }
        composeTestRule.onNodeWithText(DeviceIdentity.deviceId(context)).assertExists()
    }

    @Test
    fun deviceId_defaultsToNewUuid() {
        val id = DeviceIdentity.deviceId(context)
        assert(id.isNotEmpty())
        assert(id.matches(Regex("^[a-f0-9-]+$")))
    }

    @Test
    fun deviceId_cachesValue() {
        val first = DeviceIdentity.deviceId(context)
        val second = DeviceIdentity.deviceId(context)
        assert(first == second)
    }

    @Test
    fun deviceName_rendersCorrectly() {
        composeTestRule.setContent {
            androidx.compose.material3.Text(text = DeviceIdentity.deviceName(context))
        }
        composeTestRule.onNodeWithText(DeviceIdentity.deviceName(context)).assertExists()
    }

    @Test
    fun deviceName_containsShortId() {
        val shortId = DeviceIdentity.deviceId(context).takeLast(4).uppercase()
        val name = DeviceIdentity.deviceName(context)
        assert(name.contains(shortId))
    }

    @Test
    fun deviceName_containsManufacturer() {
        val name = DeviceIdentity.deviceName(context)
        assert(name.contains(Build.MANUFACTURER.replaceFirstChar { it.titlecase() }))
    }

    @Test
    fun osVersion_rendersCorrectly() {
        composeTestRule.setContent {
            androidx.compose.material3.Text(text = DeviceIdentity.osVersion())
        }
        composeTestRule.onNodeWithText(DeviceIdentity.osVersion()).assertExists()
    }

    @Test
    fun osVersion_containsAndroid() {
        val version = DeviceIdentity.osVersion()
        assert(version.startsWith("Android"))
    }

    @Test
    fun osVersion_containsReleaseNumber() {
        val version = DeviceIdentity.osVersion()
        assert(version.contains(Build.VERSION.RELEASE))
    }

    @Test
    fun zoneName_rendersCorrectly() {
        composeTestRule.setContent {
            androidx.compose.material3.Text(text = DeviceIdentity.zoneName(context))
        }
        composeTestRule.onNodeWithText(DeviceIdentity.zoneName(context)).assertExists()
    }

    @Test
    fun zoneName_startsWithDevicePrefix() {
        val zone = DeviceIdentity.zoneName(context)
        assert(zone.startsWith("device-"))
    }

    @Test
    fun zoneName_containsDeviceId() {
        val deviceId = DeviceIdentity.deviceId(context)
        val zone = DeviceIdentity.zoneName(context)
        assert(zone.contains(deviceId))
    }

    @Test
    fun clickOnDeviceName_doesNotCrash() {
        composeTestRule.setContent {
            androidx.compose.material3.Button(onClick = { DeviceIdentity.deviceName(context) }) {
                androidx.compose.material3.Text(text = DeviceIdentity.deviceName(context))
            }
        }
        composeTestRule.onNodeWithText(DeviceIdentity.deviceName(context)).performClick()
    }

    @Test
    fun clickOnOsVersion_doesNotCrash() {
        composeTestRule.setContent {
            androidx.compose.material3.Button(onClick = { DeviceIdentity.osVersion() }) {
                androidx.compose.material3.Text(text = DeviceIdentity.osVersion())
            }
        }
        composeTestRule.onNodeWithText(DeviceIdentity.osVersion()).performClick()
    }
}