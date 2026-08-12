package com.openminis.app.provider

import com.openminis.app.BuildConfig
import okhttp3.Request
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.mockito.MockedStatic
import org.mockito.Mockito.mockStatic
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.junit.jupiter.MockitoExtension

@ExtendWith(MockitoExtension::class)
class UserAgentOverrideTest {

    private lateinit var mockBuildConfig: MockedStatic<BuildConfig>
    private lateinit var mockBuild: MockedStatic<android.os.Build>
    private lateinit var mockBuildVersion: MockedStatic<android.os.Build.VERSION>

    @BeforeEach
    fun setUp() {
        mockBuildConfig = mockStatic(BuildConfig::class.java)
        mockBuild = mockStatic(android.os.Build::class.java)
        mockBuildVersion = mockStatic(android.os.Build.VERSION::class.java)
    }

    @AfterEach
    fun tearDown() {
        mockBuildConfig.close()
        mockBuild.close()
        mockBuildVersion.close()
    }

    @Test
    fun `applyUserAgentOverride with customUserAgent adds header`() {
        val request = Request.Builder()
            .url("https://example.com")
            .applyUserAgentOverride("CustomAgent/1.0", null)
            .build()

        assertEquals("CustomAgent/1.0", request.header("User-Agent"))
    }

    @Test
    fun `applyUserAgentOverride with customUserAgent trimmed adds header`() {
        val request = Request.Builder()
            .url("https://example.com")
            .applyUserAgentOverride("  CustomAgent/1.0  ", null)
            .build()

        assertEquals("CustomAgent/1.0", request.header("User-Agent"))
    }

    @Test
    fun `applyUserAgentOverride with empty customUserAgent uses defaultUserAgent`() {
        mockBuildConfig.`when`<String> { BuildConfig.VERSION_NAME }.thenReturn("1.0.0")
        mockBuild.`when`<String> { android.os.Build.MODEL }.thenReturn("Pixel 6")
        mockBuildVersion.`when`<String> { android.os.Build.VERSION.RELEASE }.thenReturn("12")

        val request = Request.Builder()
            .url("https://example.com")
            .applyUserAgentOverride("  ", "DefaultAgent/1.0")
            .build()

        assertEquals("DefaultAgent/1.0", request.header("User-Agent"))
    }

    @Test
    fun `applyUserAgentOverride with null customUserAgent uses defaultUserAgent`() {
        val request = Request.Builder()
            .url("https://example.com")
            .applyUserAgentOverride(null, "DefaultAgent/1.0")
            .build()

        assertEquals("DefaultAgent/1.0", request.header("User-Agent"))
    }

    @Test
    fun `applyUserAgentOverride with null customUserAgent and null defaultUserAgent adds no header`() {
        val request = Request.Builder()
            .url("https://example.com")
            .applyUserAgentOverride(null, null)
            .build()

        assertNull(request.header("User-Agent"))
    }

    @Test
    fun `applyUserAgentOverride with empty customUserAgent and null defaultUserAgent adds no header`() {
        val request = Request.Builder()
            .url("https://example.com")
            .applyUserAgentOverride("  ", null)
            .build()

        assertNull(request.header("User-Agent"))
    }

    @Test
    fun `applyUserAgentOverride with customUserAgent overrides defaultUserAgent`() {
        val request = Request.Builder()
            .url("https://example.com")
            .applyUserAgentOverride("CustomAgent/1.0", "DefaultAgent/1.0")
            .build()

        assertEquals("CustomAgent/1.0", request.header("User-Agent"))
    }

    @Test
    fun `MinisUserAgent DEFAULT returns correct format`() {
        mockBuildConfig.`when`<String> { BuildConfig.VERSION_NAME }.thenReturn("2.0.0")
        mockBuild.`when`<String> { android.os.Build.MODEL }.thenReturn("Galaxy S22")
        mockBuildVersion.`when`<String> { android.os.Build.VERSION.RELEASE }.thenReturn("13")

        val default = MinisUserAgent.DEFAULT
        assertEquals("Minis/2.0.0 (Android 13; Galaxy S22)", default)
    }

    @Test
    fun `MinisUserAgent DEFAULT with null release`() {
        mockBuildConfig.`when`<String> { BuildConfig.VERSION_NAME }.thenReturn("1.0.0")
        mockBuild.`when`<String> { android.os.Build.MODEL }.thenReturn("Pixel 5")
        mockBuildVersion.`when`<String> { android.os.Build.VERSION.RELEASE }.thenReturn(null)

        val default = MinisUserAgent.DEFAULT
        assertEquals("Minis/1.0.0 (Android unknown; Pixel 5)", default)
    }

    @Test
    fun `MinisUserAgent DEFAULT with null model`() {
        mockBuildConfig.`when`<String> { BuildConfig.VERSION_NAME }.thenReturn("1.0.0")
        mockBuild.`when`<String> { android.os.Build.MODEL }.thenReturn(null)
        mockBuildVersion.`when`<String> { android.os.Build.VERSION.RELEASE }.thenReturn("11")

        val default = MinisUserAgent.DEFAULT
        assertEquals("Minis/1.0.0 (Android 11; unknown)", default)
    }

    @Test
    fun `MinisUserAgent DEFAULT with empty model`() {
        mockBuildConfig.`when`<String> { BuildConfig.VERSION_NAME }.thenReturn("1.0.0")
        mockBuild.`when`<String> { android.os.Build.MODEL }.thenReturn("  ")
        mockBuildVersion.`when`<String> { android.os.Build.VERSION.RELEASE }.thenReturn("11")

        val default = MinisUserAgent.DEFAULT
        assertEquals("Minis/1.0.0 (Android 11; unknown)", default)
    }

    @Test
    fun `applyUserAgentOverride with defaultUserAgent default value`() {
        mockBuildConfig.`when`<String> { BuildConfig.VERSION_NAME }.thenReturn("1.0.0")
        mockBuild.`when`<String> { android.os.Build.MODEL }.thenReturn("Pixel 6")
        mockBuildVersion.`when`<String> { android.os.Build.VERSION.RELEASE }.thenReturn("12")

        val request = Request.Builder()
            .url("https://example.com")
            .applyUserAgentOverride(null)
            .build()

        assertNotNull(request.header("User-Agent"))
        assertEquals("Minis/1.0.0 (Android 12; Pixel 6)", request.header("User-Agent"))
    }

    @Test
    fun `applyUserAgentOverride returns the same builder`() {
        val builder = Request.Builder().url("https://example.com")
        val result = builder.applyUserAgentOverride("Test", "Default")
        assertEquals(builder, result)
    }
}