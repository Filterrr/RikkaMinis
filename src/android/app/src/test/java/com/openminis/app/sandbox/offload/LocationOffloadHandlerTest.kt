package com.openminis.app.sandbox.offload

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import androidx.core.content.ContextCompat
import com.openminis.app.logging.AppLogger
import com.openminis.app.offload.OffloadPermissionManager
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.*
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

@ExtendWith(MockitoExtension::class)
class LocationOffloadHandlerTest {

    @Mock
    private lateinit var mockContext: Context

    @Mock
    private lateinit var mockLocationManager: LocationManager

    @Mock
    private lateinit var mockGeocoder: Geocoder

    private lateinit var handler: LocationOffloadHandler

    @BeforeEach
    fun setUp() {
        handler = LocationOffloadHandler(mockContext)
    }

    @Test
    fun `handle with help flag returns help text`() {
        val request = NativeOffloadRequest(
            argv = listOf("android-location", "-h"),
            uuid = "test-uuid",
            env = emptyMap(),
            requestId = "test-request-id"
        )

        val result = handler.handle(request)

        assertEquals(0, result.exitCode)
        assertTrue(result.body.contains("Usage:"))
        assertTrue(result.body.contains("android-location"))
    }

    @Test
    fun `handle with help long flag returns help text`() {
        val request = NativeOffloadRequest(
            argv = listOf("android-location", "--help"),
            uuid = "test-uuid",
            env = emptyMap(),
            requestId = "test-request-id"
        )

        val result = handler.handle(request)

        assertEquals(0, result.exitCode)
        assertTrue(result.body.contains("Usage:"))
    }

    @Test
    fun `handle with unknown subcommand returns error`() {
        val request = NativeOffloadRequest(
            argv = listOf("android-location", "unknown"),
            uuid = "test-uuid",
            env = emptyMap(),
            requestId = "test-request-id"
        )

        val result = handler.handle(request)

        assertEquals(2, result.exitCode)
        assertTrue(result.body.contains("unknown subcommand"))
    }

    @Test
    fun `handle with geocode subcommand missing lat returns error`() {
        val request = NativeOffloadRequest(
            argv = listOf("android-location", "geocode", "--lon", "10.0"),
            uuid = "test-uuid",
            env = emptyMap(),
            requestId = "test-request-id"
        )

        val result = handler.handle(request)

        assertEquals(2, result.exitCode)
        assertTrue(result.body.contains("--lat"))
    }

    @Test
    fun `handle with geocode subcommand missing lon returns error`() {
        val request = NativeOffloadRequest(
            argv = listOf("android-location", "geocode", "--lat", "10.0"),
            uuid = "test-uuid",
            env = emptyMap(),
            requestId = "test-request-id"
        )

        val result = handler.handle(request)

        assertEquals(2, result.exitCode)
        assertTrue(result.body.contains("--lon"))
    }

    @Test
    fun `handle with geocode subcommand using positional args`() {
        // Mock Geocoder
        val mockAddrs = listOf(mock<android.location.Address>())
        mockAddrs[0].stub {
            on { maxAddressLineIndex } doReturn 0
            on { getAddressLine(0) } doReturn "Test Address"
        }

        val geocoderStatic = Mockito.mockStatic(Geocoder::class.java)
        geocoderStatic.`when`<Geocoder> { Geocoder(any(), any()) }.thenReturn(mockGeocoder)
        whenever(mockGeocoder.getFromLocation(anyDouble(), anyDouble(), anyInt())).thenReturn(mockAddrs)

        val request = NativeOffloadRequest(
            argv = listOf("android-location", "geocode", "10.0", "20.0"),
            uuid = "test-uuid",
            env = emptyMap(),
            requestId = "test-request-id"
        )

        val result = handler.handle(request)

        assertEquals(0, result.exitCode)
        assertTrue(result.body.contains("Test Address"))
        geocoderStatic.close()
    }

    @Test
    fun `handle with forward subcommand missing address returns error`() {
        val request = NativeOffloadRequest(
            argv = listOf("android-location", "forward"),
            uuid = "test-uuid",
            env = emptyMap(),
            requestId = "test-request-id"
        )

        val result = handler.handle(request)

        assertEquals(2, result.exitCode)
        assertTrue(result.body.contains("--address"))
    }

    @Test
    fun `handle with forward subcommand geocoder not present`() {
        val geocoderStatic = Mockito.mockStatic(Geocoder::class.java)
        geocoderStatic.`when`<Boolean> { Geocoder.isPresent() }.thenReturn(false)

        val request = NativeOffloadRequest(
            argv = listOf("android-location", "forward", "--address", "Test Address"),
            uuid = "test-uuid",
            env = emptyMap(),
            requestId = "test-request-id"
        )

        val result = handler.handle(request)

        assertEquals(1, result.exitCode)
        assertTrue(result.body.contains("geocoder_unavailable"))
        geocoderStatic.close()
    }

    @Test
    fun `handle with forward subcommand success`() {
        val geocoderStatic = Mockito.mockStatic(Geocoder::class.java)
        geocoderStatic.`when`<Boolean> { Geocoder.isPresent() }.thenReturn(true)
        geocoderStatic.`when`<Geocoder> { Geocoder(any(), any()) }.thenReturn(mockGeocoder)

        val mockAddrs = listOf(mock<android.location.Address>())
        mockAddrs[0].stub {
            on { latitude } doReturn 10.0
            on { longitude } doReturn 20.0
            on { maxAddressLineIndex } doReturn 0
            on { getAddressLine(0) } doReturn "Test Address"
        }
        whenever(mockGeocoder.getFromLocationName(anyString(), anyInt())).thenReturn(mockAddrs)

        val request = NativeOffloadRequest(
            argv = listOf("android-location", "forward", "--address", "Test Address"),
            uuid = "test-uuid",
            env = emptyMap(),
            requestId = "test-request-id"
        )

        val result = handler.handle(request)

        assertEquals(0, result.exitCode)
        assertTrue(result.body.contains("latitude"))
        assertTrue(result.body.contains("longitude"))
        geocoderStatic.close()
    }

    @Test
    fun `handle with current subcommand permission denied`() {
        val contextCompatStatic = Mockito.mockStatic(ContextCompat::class.java)
        contextCompatStatic.`when`<Int> {
            ContextCompat.checkSelfPermission(any(), eq(Manifest.permission.ACCESS_FINE_LOCATION))
        }.thenReturn(PackageManager.PERMISSION_DENIED)
        contextCompatStatic.`when`<Int> {
            ContextCompat.checkSelfPermission(any(), eq(Manifest.permission.ACCESS_COARSE_LOCATION))
        }.thenReturn(PackageManager.PERMISSION_DENIED)

        val permissionManagerStatic = Mockito.mockStatic(OffloadPermissionManager::class.java)
        permissionManagerStatic.`when`<Any> {
            OffloadPermissionManager.requestAndroidPermission(any())
        }.thenReturn(OffloadPermissionManager.AndroidPermissionResult.DENIED)
        permissionManagerStatic.`when`<Boolean> {
            OffloadPermissionManager.pollForPermissionGrant(any())
        }.thenReturn(false)
        permissionManagerStatic.`when`<Any> {
            OffloadPermissionManager.requestSettingsGate(any(), any())
        }.thenReturn(OffloadPermissionManager.AndroidPermissionResult.DENIED)

        val request = NativeOffloadRequest(
            argv = listOf("android-location", "current"),
            uuid = "test-uuid",
            env = emptyMap(),
            requestId = "test-request-id"
        )

        val result = handler.handle(request)

        assertEquals(77, result.exitCode)
        assertTrue(result.body.contains("permission_denied"))

        contextCompatStatic.close()
        permissionManagerStatic.close()
    }

    @Test
    fun `handle with current subcommand permission timeout`() {
        val contextCompatStatic = Mockito.mockStatic(ContextCompat::class.java)
        contextCompatStatic.`when`<Int> {
            ContextCompat.checkSelfPermission(any(), eq(Manifest.permission.ACCESS_FINE_LOCATION))
        }.thenReturn(PackageManager.PERMISSION_DENIED)
        contextCompatStatic.`when`<Int> {
            ContextCompat.checkSelfPermission(any(), eq(Manifest.permission.ACCESS_COARSE_LOCATION))
        }.thenReturn(PackageManager.PERMISSION_DENIED)

        val permissionManagerStatic = Mockito.mockStatic(OffloadPermissionManager::class.java)
        permissionManagerStatic.`when`<Any> {
            OffloadPermissionManager.requestAndroidPermission(any())
        }.thenReturn(OffloadPermissionManager.AndroidPermissionResult.TIMEOUT)

        val request = NativeOffloadRequest(
            argv = listOf("android-location", "current"),
            uuid = "test-uuid",
            env = emptyMap(),
            requestId = "test-request-id"
        )

        val result = handler.handle(request)

        assertEquals(77, result.exitCode)
        assertTrue(result.body.contains("timeout"))

        contextCompatStatic.close()
        permissionManagerStatic.close()
    }

    @Test
    fun `handle with current subcommand permission granted`() {
        val contextCompatStatic = Mockito.mockStatic(ContextCompat::class.java)
        contextCompatStatic.`when`<Int> {
            ContextCompat.checkSelfPermission(any(), eq(Manifest.permission.ACCESS_FINE_LOCATION))
        }.thenReturn(PackageManager.PERMISSION_GRANTED)

        whenever(mockContext.getSystemService(Context.LOCATION_SERVICE)).thenReturn(mockLocationManager)
        whenever(mockLocationManager.allProviders).thenReturn(listOf("gps"))
        whenever(mockLocationManager.isProviderEnabled("gps")).thenReturn(true)

        val location = mock<android.location.Location>()
        whenever(location.latitude).thenReturn(10.0)
        whenever(location.longitude).thenReturn(20.0)
        whenever(location.accuracy).thenReturn(10.0f)
        whenever(location.altitude).thenReturn(100.0)
        whenever(location.time).thenReturn(1000L)
        whenever(location.provider).thenReturn("gps")
        whenever(mockLocationManager.getLastKnownLocation(any())).thenReturn(location)

        val request = NativeOffloadRequest(
            argv = listOf("android-location", "current"),
            uuid = "test-uuid",
            env = emptyMap(),
            requestId = "test-request-id"
        )

        val result = handler.handle(request)

        assertEquals(0, result.exitCode)
        assertTrue(result.body.contains("latitude"))
        assertTrue(result.body.contains("longitude"))

        contextCompatStatic.close()
    }

    @Test
    fun `handle with current subcommand location services disabled`() {
        val contextCompatStatic = Mockito.mockStatic(ContextCompat::class.java)
        contextCompatStatic.`when`<Int> {
            ContextCompat.checkSelfPermission(any(), eq(Manifest.permission.ACCESS_FINE_LOCATION))
        }.thenReturn(PackageManager.PERMISSION_GRANTED)

        whenever(mockContext.getSystemService(Context.LOCATION_SERVICE)).thenReturn(mockLocationManager)
        whenever(mockLocationManager.allProviders).thenReturn(emptyList())

        val request = NativeOffloadRequest(
            argv = listOf("android-location", "current"),
            uuid = "test-uuid",
            env = emptyMap(),
            requestId = "test-request-id"
        )

        val result = handler.handle(request)

        assertEquals(1, result.exitCode)
        assertTrue(result.body.contains("location_services_disabled"))

        contextCompatStatic.close()
    }

    @Test
    fun `handle with current subcommand location manager not available`() {
        val contextCompatStatic = Mockito.mockStatic(ContextCompat::class.java)
        contextCompatStatic.`when`<Int> {
            ContextCompat.checkSelfPermission(any(), eq(Manifest.permission.ACCESS_FINE_LOCATION))
        }.thenReturn(PackageManager.PERMISSION_GRANTED)

        whenever(mockContext.getSystemService(Context.LOCATION_SERVICE)).thenReturn(null)

        val request = NativeOffloadRequest(
            argv = listOf("android-location", "current"),
            uuid = "test-uuid",
            env = emptyMap(),
            requestId = "test-request-id"
        )

        val result = handler.handle(request)

        assertEquals(1, result.exitCode)
        assertTrue(result.body.contains("service_unavailable"))

        contextCompatStatic.close()
    }

    @Test
    fun `handle with default subcommand current`() {
        val contextCompatStatic = Mockito.mockStatic(ContextCompat::class.java)
        contextCompatStatic.`when`<Int> {
            ContextCompat.checkSelfPermission(any(), eq(Manifest.permission.ACCESS_FINE_LOCATION))
        }.thenReturn(PackageManager.PERMISSION_GRANTED)

        whenever(mockContext.getSystemService(Context.LOCATION_SERVICE)).thenReturn(mockLocationManager)
        whenever(mockLocationManager.allProviders).thenReturn(listOf("gps"))
        whenever(mockLocationManager.isProviderEnabled("gps")).thenReturn(true)

        val location = mock<android.location.Location>()
        whenever(location.latitude).thenReturn(10.0)
        whenever(location.longitude).thenReturn(20.0)
        whenever(location.accuracy).thenReturn(10.0f)
        whenever(location.altitude).thenReturn(100.0)
        whenever(location.time).thenReturn(1000L)
        whenever(location.provider).thenReturn("gps")
        whenever(mockLocationManager.getLastKnownLocation(any())).thenReturn(location)

        val request = NativeOffloadRequest(
            argv = listOf("android-location"),
            uuid = "test-uuid",
            env = emptyMap(),
            requestId = "test-request-id"
        )

        val result = handler.handle(request)

        assertEquals(0, result.exitCode)
        assertTrue(result.body.contains("latitude"))

        contextCompatStatic.close()
    }

    @Test
    fun `handle throws exception and returns error`() {
        val request = NativeOffloadRequest(
            argv = listOf("android-location", "current", "--timeout", "invalid"),
            uuid = "test-uuid",
            env = emptyMap(),
            requestId = "test-request-id"
        )

        val result = handler.handle(request)

        assertEquals(1, result.exitCode)
        assertTrue(result.body.contains("error"))
    }
}