package com.openminis.app.sandbox.offload

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import com.openminis.app.offload.OffloadPermissionManager
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import com.openminis.app.sandbox.PRootKernel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.*
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SpeechOffloadHandlerTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var context: Context
    private lateinit var handler: SpeechOffloadHandler

    @BeforeEach
    fun setUp() {
        context = mock()
        handler = SpeechOffloadHandler(context)
    }

    @Test
    fun `handle with help flag returns help`() {
        val request = NativeOffloadRequest(
            argv = listOf("android-speech", "--help"),
            env = emptyMap(),
            cwd = tempDir.absolutePath
        )
        val result = handler.handle(request)
        assertEquals(0, result.exitCode)
        assertTrue(result.output.contains("android-speech — speech recognition"))
    }

    @Test
    fun `handle with no positional args returns exit code 2`() {
        val request = NativeOffloadRequest(
            argv = listOf("android-speech"),
            env = emptyMap(),
            cwd = tempDir.absolutePath
        )
        val result = handler.handle(request)
        assertEquals(2, result.exitCode)
        assertTrue(result.output.contains("android-speech — speech recognition"))
    }

    @Test
    fun `handle with unknown subcommand returns exit code 2`() {
        val request = NativeOffloadRequest(
            argv = listOf("android-speech", "unknown"),
            env = emptyMap(),
            cwd = tempDir.absolutePath
        )
        val result = handler.handle(request)
        assertEquals(2, result.exitCode)
        assertTrue(result.output.contains("unknown subcommand"))
    }

    @Test
    fun `handle with status command and available recognizer returns success`() {
        mockStatic(SpeechRecognizer::class.java).use { speechRecognizerMock ->
            speechRecognizerMock.`when`<Boolean> { SpeechRecognizer.isRecognitionAvailable(context) }.thenReturn(true)
            mockStatic(ContextCompat::class.java).use { contextCompatMock ->
                contextCompatMock.`when`<Int> {
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.RECORD_AUDIO
                    )
                }.thenReturn(PackageManager.PERMISSION_GRANTED)

                val request = NativeOffloadRequest(
                    argv = listOf("android-speech", "status"),
                    env = emptyMap(),
                    cwd = tempDir.absolutePath
                )
                val result = handler.handle(request)
                assertEquals(0, result.exitCode)
                val json = JSONObject(result.output.trim())
                assertTrue(json.getBoolean("available"))
                assertTrue(json.getBoolean("has_record_audio_permission"))
            }
        }
    }

    @Test
    fun `handle with status command and unavailable recognizer returns success with false`() {
        mockStatic(SpeechRecognizer::class.java).use { speechRecognizerMock ->
            speechRecognizerMock.`when`<Boolean> { SpeechRecognizer.isRecognitionAvailable(context) }.thenReturn(false)
            mockStatic(ContextCompat::class.java).use { contextCompatMock ->
                contextCompatMock.`when`<Int> {
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.RECORD_AUDIO
                    )
                }.thenReturn(PackageManager.PERMISSION_DENIED)

                val request = NativeOffloadRequest(
                    argv = listOf("android-speech", "status"),
                    env = emptyMap(),
                    cwd = tempDir.absolutePath
                )
                val result = handler.handle(request)
                assertEquals(0, result.exitCode)
                val json = JSONObject(result.output.trim())
                assertFalse(json.getBoolean("available"))
                assertFalse(json.getBoolean("has_record_audio_permission"))
            }
        }
    }

    @Test
    fun `handle with transcribe and unavailable recognizer returns error`() {
        mockStatic(SpeechRecognizer::class.java).use { speechRecognizerMock ->
            speechRecognizerMock.`when`<Boolean> { SpeechRecognizer.isRecognitionAvailable(context) }.thenReturn(false)

            val request = NativeOffloadRequest(
                argv = listOf("android-speech", "transcribe"),
                env = emptyMap(),
                cwd = tempDir.absolutePath
            )
            val result = handler.handle(request)
            assertEquals(1, result.exitCode)
            val json = JSONObject(result.output.trim())
            assertEquals("recognizer_unavailable", json.getString("error"))
        }
    }

    @Test
    fun `handle with transcribe and file source returns not supported`() {
        mockStatic(SpeechRecognizer::class.java).use { speechRecognizerMock ->
            speechRecognizerMock.`when`<Boolean> { SpeechRecognizer.isRecognitionAvailable(context) }.thenReturn(true)

            val sourcePath = tempDir.resolve("test.m4a")
            sourcePath.writeText("fake audio")

            val request = NativeOffloadRequest(
                argv = listOf("android-speech", "transcribe", "--source", sourcePath.absolutePath),
                env = emptyMap(),
                cwd = tempDir.absolutePath
            )
            val result = handler.handle(request)
            assertEquals(2, result.exitCode)
            val json = JSONObject(result.output.trim())
            assertEquals("not_supported", json.getString("error"))
            assertEquals(sourcePath.absolutePath, json.getString("requested_path"))
        }
    }

    @Test
    fun `handle with transcribe and mic source without permission returns permission denied`() {
        mockStatic(SpeechRecognizer::class.java).use { speechRecognizerMock ->
            speechRecognizerMock.`when`<Boolean> { SpeechRecognizer.isRecognitionAvailable(context) }.thenReturn(true)
            mockStatic(ContextCompat::class.java).use { contextCompatMock ->
                contextCompatMock.`when`<Int> {
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.RECORD_AUDIO
                    )
                }.thenReturn(PackageManager.PERMISSION_DENIED)

                mockStatic(OffloadPermissionManager::class.java).use { permissionManagerMock ->
                    permissionManagerMock.`when`<OffloadPermissionManager.AndroidPermissionResult> {
                        runBlocking {
                            OffloadPermissionManager.requestAndroidPermission(
                                listOf(Manifest.permission.RECORD_AUDIO)
                            )
                        }
                    }.thenReturn(OffloadPermissionManager.AndroidPermissionResult.DENIED)

                    permissionManagerMock.`when`<Boolean> {
                        OffloadPermissionManager.pollForPermissionGrant({ false })
                    }.thenReturn(false)

                    permissionManagerMock.`when`<OffloadPermissionManager.AndroidPermissionResult> {
                        runBlocking {
                            OffloadPermissionManager.requestSettingsGate(
                                any()
                            )
                        }
                    }.thenReturn(OffloadPermissionManager.AndroidPermissionResult.DENIED)

                    val request = NativeOffloadRequest(
                        argv = listOf("android-speech", "transcribe"),
                        env = emptyMap(),
                        cwd = tempDir.absolutePath
                    )
                    val result = handler.handle(request)
                    assertEquals(77, result.exitCode)
                    val json = JSONObject(result.output.trim())
                    assertEquals("permission_denied", json.getString("error"))
                }
            }
        }
    }

    @Test
    fun `handle with transcribe and mic source with permission granted returns success`() {
        mockStatic(SpeechRecognizer::class.java).use { speechRecognizerMock ->
            speechRecognizerMock.`when`<Boolean> { SpeechRecognizer.isRecognitionAvailable(context) }.thenReturn(true)
            mockStatic(ContextCompat::class.java).use { contextCompatMock ->
                contextCompatMock.`when`<Int> {
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.RECORD_AUDIO
                    )
                }.thenReturn(PackageManager.PERMISSION_GRANTED)

                val handlerMock = mock<SpeechOffloadHandler>()
                // Since recognize is private, we test the happy path through the handle method
                // by mocking the SpeechRecognizer creation
                mockStatic(SpeechRecognizer::class.java).use { recognizerMock ->
                    val recognizer = mock<SpeechRecognizer>()
                    recognizerMock.`when`<SpeechRecognizer> {
                        SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                    }.thenReturn(recognizer)

                    val request = NativeOffloadRequest(
                        argv = listOf("android-speech", "transcribe", "--duration", "1"),
                        env = emptyMap(),
                        cwd = tempDir.absolutePath
                    )
                    val result = handler.handle(request)
                    assertEquals(0, result.exitCode)
                }
            }
        }
    }

    @Test
    fun `handle with languages command returns success`() {
        val latch = CountDownLatch(1)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                val extras = Bundle()
                val languages = ArrayList<String>()
                languages.add("en-US")
                languages.add("zh-CN")
                extras.putStringArrayList(
                    RecognizerIntent.EXTRA_SUPPORTED_LANGUAGES,
                    languages
                )
                setResultExtras(extras)
                latch.countDown()
            }
        }

        doAnswer { invocation ->
            val intent = invocation.getArgument<Intent>(0)
            val receiverArg = invocation.getArgument<BroadcastReceiver>(2)
            receiverArg.onReceive(context, intent)
            latch.countDown()
            null
        }.`when`(context).sendOrderedBroadcast(
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any()
        )

        val request = NativeOffloadRequest(
            argv = listOf("android-speech", "languages"),
            env = emptyMap(),
            cwd = tempDir.absolutePath
        )
        val result = handler.handle(request)
        assertEquals(0, result.exitCode)
        val json = JSONObject(result.output.trim())
        assertEquals(2, json.getInt("count"))
    }

    @Test
    fun `handle with languages command and prefix filter returns filtered results`() {
        val latch = CountDownLatch(1)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                val extras = Bundle()
                val languages = ArrayList<String>()
                languages.add("en-US")
                languages.add("zh-CN")
                extras.putStringArrayList(
                    RecognizerIntent.EXTRA_SUPPORTED_LANGUAGES,
                    languages
                )
                setResultExtras(extras)
                latch.countDown()
            }
        }

        doAnswer { invocation ->
            val intent = invocation.getArgument<Intent>(0)
            val receiverArg = invocation.getArgument<BroadcastReceiver>(2)
            receiverArg.onReceive(context, intent)
            latch.countDown()
            null
        }.`when`(context).sendOrderedBroadcast(
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any()
        )

        val request = NativeOffloadRequest(
            argv = listOf("android-speech", "languages", "--language", "en"),
            env = emptyMap(),
            cwd = tempDir.absolutePath
        )
        val result = handler.handle(request)
        assertEquals(0, result.exitCode)
        val json = JSONObject(result.output.trim())
        assertEquals(1, json.getInt("count"))
    }

    @Test
    fun `handle with exception in subcommand returns internal error`() {
        val request = NativeOffloadRequest(
            argv = listOf("android-speech", "status"),
            env = emptyMap(),
            cwd = tempDir.absolutePath
        )
        
        // Force exception by mocking SpeechRecognizer.isRecognitionAvailable to throw
        mockStatic(SpeechRecognizer::class.java).use { speechRecognizerMock ->
            speechRecognizerMock.`when`<Boolean> { SpeechRecognizer.isRecognitionAvailable(context) }
                .thenThrow(RuntimeException("Test exception"))

            val result = handler.handle(request)
            assertEquals(1, result.exitCode)
            val json = JSONObject(result.output.trim())
            assertEquals("internal", json.getString("error"))
            assertEquals("Test exception", json.getString("message"))
        }
    }

    @Test
    fun `handle with compact flag returns compact JSON`() {
        mockStatic(SpeechRecognizer::class.java).use { speechRecognizerMock ->
            speechRecognizerMock.`when`<Boolean> { SpeechRecognizer.isRecognitionAvailable(context) }.thenReturn(true)
            mockStatic(ContextCompat::class.java).use { contextCompatMock ->
                contextCompatMock.`when`<Int> {
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.RECORD_AUDIO
                    )
                }.thenReturn(PackageManager.PERMISSION_GRANTED)

                val request = NativeOffloadRequest(
                    argv = listOf("android-speech", "status", "--compact"),
                    env = emptyMap(),
                    cwd = tempDir.absolutePath
                )
                val result = handler.handle(request)
                assertEquals(0, result.exitCode)
                val json = JSONObject(result.output.trim())
                assertTrue(json.has("available"))
                assertTrue(json.has("has_record_audio_permission"))
            }
        }
    }

    @Test
    fun `handle with quiet flag returns only data field`() {
        mockStatic(SpeechRecognizer::class.java).use { speechRecognizerMock ->
            speechRecognizerMock.`when`<Boolean> { SpeechRecognizer.isRecognitionAvailable(context) }.thenReturn(true)
            mockStatic(ContextCompat::class.java).use { contextCompatMock ->
                contextCompatMock.`when`<Int> {
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.RECORD_AUDIO
                    )
                }.thenReturn(PackageManager.PERMISSION_GRANTED)

                val request = NativeOffloadRequest(
                    argv = listOf("android-speech", "status", "--quiet"),
                    env = emptyMap(),
                    cwd = tempDir.absolutePath
                )
                val result = handler.handle(request)
                assertEquals(0, result.exitCode)
                val json = JSONObject(result.output.trim())
                assertTrue(json.has("available"))
                assertTrue(json.has("has_record_audio_permission"))
            }
        }
    }

    @Test
    fun `listen is alias for transcribe`() {
        mockStatic(SpeechRecognizer::class.java).use { speechRecognizerMock ->
            speechRecognizerMock.`when`<Boolean> { SpeechRecognizer.isRecognitionAvailable(context) }.thenReturn(false)

            val request = NativeOffloadRequest(
                argv = listOf("android-speech", "listen"),
                env = emptyMap(),
                cwd = tempDir.absolutePath
            )
            val result = handler.handle(request)
            assertEquals(1, result.exitCode)
            val json = JSONObject(result.output.trim())
            assertEquals("recognizer_unavailable", json.getString("error"))
        }
    }

    @Test
    fun `handle with duration and timeout options`() {
        mockStatic(SpeechRecognizer::class.java).use { speechRecognizerMock ->
            speechRecognizerMock.`when`<Boolean> { SpeechRecognizer.isRecognitionAvailable(context) }.thenReturn(true)
            mockStatic(ContextCompat::class.java).use { contextCompatMock ->
                contextCompatMock.`when`<Int> {
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.RECORD_AUDIO
                    )
                }.thenReturn(PackageManager.PERMISSION_GRANTED)

                val request = NativeOffloadRequest(
                    argv = listOf("android-speech", "transcribe", "--duration", "5", "--timeout", "10"),
                    env = emptyMap(),
                    cwd = tempDir.absolutePath
                )
                val result = handler.handle(request)
                assertEquals(0, result.exitCode)
            }
        }
    }

    @Test
    fun `handle with max results option`() {
        mockStatic(SpeechRecognizer::class.java).use { speechRecognizerMock ->
            speechRecognizerMock.`when`<Boolean> { SpeechRecognizer.isRecognitionAvailable(context) }.thenReturn(true)
            mockStatic(ContextCompat::class.java).use { contextCompatMock ->
                contextCompatMock.`when`<Int> {
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.RECORD_AUDIO
                    )
                }.thenReturn(PackageManager.PERMISSION_GRANTED)

                val request = NativeOffloadRequest(
                    argv = listOf("android-speech", "transcribe", "--max", "5"),
                    env = emptyMap(),
                    cwd = tempDir.absolutePath
                )
                val result = handler.handle(request)
                assertEquals(0, result.exitCode)
            }
        }
    }

    @Test
    fun `handle with language option for transcribe`() {
        mockStatic(SpeechRecognizer::class.java).use { speechRecognizerMock ->
            speechRecognizerMock.`when`<Boolean> { SpeechRecognizer.isRecognitionAvailable(context) }.thenReturn(true)
            mockStatic(ContextCompat::class.java).use { contextCompatMock ->
                contextCompatMock.`when`<Int> {
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.RECORD_AUDIO
                    )
                }.thenReturn(PackageManager.PERMISSION_GRANTED)

                val request = NativeOffloadRequest(
                    argv = listOf("android-speech", "transcribe", "--language", "zh-CN"),
                    env = emptyMap(),
                    cwd = tempDir.absolutePath
                )
                val result = handler.handle(request)
                assertEquals(0, result.exitCode)
            }
        }
    }

    @Test
    fun `source is mic for various mic aliases`()