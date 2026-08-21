package com.openminis.app.sandbox

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import com.openminis.app.offload.OffloadPermissionManager
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Cross-process bridge between the `:toolservice` process (the sole owner of
 * the PRoot `native_offload` abstract socket) and the main process (which
 * still produces the results of the "heavy" handlers whose dependency graph
 * — Room / BrowserTabPool / ProviderRepository / AccessibilityService /
 * Shizuku binder — cannot be safely replicated inside `:toolservice`).
 *
 * [native-oom construction plan Phase 1, 做法 B]:
 *   - `:toolservice` owns `NativeOffloadServer` + the 13 light android-*
 *     handlers (built there from just `applicationContext`).
 *   - The 6 heavy handlers (`minis-browser-use`, `minis-model-use`,
 *     `minis-sessions-cli`, `minis-config`, `android-a11y-cli`,
 *     `android-shizuku-cli`) stay in the main process, registered into
 *     [NativeOffloadBridgeMain] instead of `NativeOffloadServer`.
 *   - When the guest execs a heavy handler, `:toolservice`'s
 *     [NativeOffloadServer] has a forwarding facade for that name which
 *     calls [NativeOffloadBridgeClient.forward] — one request/response over
 *     the bridge socket. The main process executes the real handler and the
 *     result travels back; the PRoot guest only ever talks to
 *     `:toolservice`'s socket.
 *   - Permission negotiation (ASK_ONCE dialogs live in the main process's
 *     Compose UI): `:toolservice` forwards `PERMISSION_REQUEST` frames and
 *     awaits `PERMISSION_RESPONSE`.
 *
 * Frame format (little-endian, mirrors the native_offload socket framing so
 * the same helpers are reused):
 *
 *   REQUEST      : MAGIC = 0x4E4F4652 ('R' 'F' 'O' 'N' LE) | requestId(i64) |
 *                  handlerName(le32len+utf8) | pid(i32) | argc(i32) |
 *                  argv[](le32len+utf8) | envc(i32) | env[]("k=v") |
 *                  cwd(le32len+utf8) | sessionId(le32len+utf8, ""=null)
 *   RESPONSE     : MAGIC = 0x5350464F ('O' 'F' 'P' 'S' LE) | requestId(i64) |
 *                  exitCode(i32) | output(le32len+utf8)
 *   PERM_REQUEST : MAGIC = 0x504D524F ('O' 'R' 'M' 'P' LE) | requestId(i64) |
 *                  toolName | toolTitle | sessionId
 *   PERM_RESPONSE: MAGIC = 0x50504D52 ('R' 'M' 'P' 'P' LE) | requestId(i64) |
 *                  allowed(i32)  (1=true, 0=false)
 *
 * The main process owns the bridge listening socket `native-offload-bridge`;
 * `:toolservice` connects per call (cheap for our request rate, and it
 * removes any reconnect-state machinery — a connect failure yields a typed
 * error result instead of a silent hang).
 */

// ---- shared LE frame helpers (mirrors NativeOffload.kt, no budget arg --
//      the 4MiB per-string cap in readLEString is the protective bound).

internal fun DataInputStream.readLEInt(): Int {
    val buf = ByteArray(4)
    readFully(buf)
    return ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).int
}

internal fun DataInputStream.readLELong(): Long {
    val buf = ByteArray(8)
    readFully(buf)
    return ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).long
}

internal fun DataInputStream.readLEString(): String {
    val len = readLEInt()
    if (len < 0 || len > 1 shl 20) throw IllegalStateException("bad string len $len")
    if (len == 0) return ""
    val buf = ByteArray(len)
    readFully(buf)
    return String(buf, Charsets.UTF_8)
}

internal fun DataOutputStream.writeLEInt(v: Int) {
    write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
}

internal fun DataOutputStream.writeLELong(v: Long) {
    write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array())
}

internal fun DataOutputStream.writeLEString(s: String) {
    val bytes = s.toByteArray(Charsets.UTF_8)
    writeLEInt(bytes.size)
    if (bytes.isNotEmpty()) write(bytes)
}

object NativeOffloadBridge {

    private const val TAG = "OffloadBridge"

    const val SOCKET_NAME = "native-offload-bridge"

    // REQUEST / RESPONSE
    private const val MAGIC_REQ = 0x4E4F4652
    private const val MAGIC_RSP = 0x5350464F
    // PERM_REQUEST / PERM_RESPONSE
    private const val MAGIC_PERM_REQ = 0x504D524F
    private const val MAGIC_PERM_RSP = 0x50504D52

    private const val VERSION = 1

    /** Heavy handler names that live in the main process via the bridge. */
    val heavyHandlerNames: Set<String> = setOf(
        "minis-browser-use",
        "minis-model-use",
        "minis-sessions-cli",
        "minis-config",
        "android-a11y-cli",
        "android-shizuku-cli",
    )

    /** Light handler names that execute in-process inside `:toolservice`. */
    val lightHandlerNames: Set<String> = OffloadHandlerCatalog.baseHandlerNames.toSet() - heavyHandlerNames

    // -------------------- client (used by :toolservice) --------------------

    object Client {

        private val forwardIds = AtomicLong(1)

        /** Monotonic request id for [forward] + [requestPermission] calls. */
        fun nextRequestId(): Long = forwardIds.getAndIncrement()

        /**
         * A [NativeOffloadHandler] facade that forwards the request to the
         * main-process bridge. Registered in `:toolservice` for every heavy
         * handler name so the guest's execve reaches the real handler in the
         * main process without `:toolservice` needing its dependencies.
         */
        fun forwardingHandler(handlerName: String): NativeOffloadHandler =
            NativeOffloadHandler { request ->
                forward(nextRequestId(), handlerName, request)
                    ?: NativeOffloadResult(
                        1,
                        "native_offload: bridge to main process unavailable for '$handlerName'\n",
                    )
            }

        fun forward(requestId: Long, handlerName: String, request: NativeOffloadRequest): NativeOffloadResult? {
            return try {
                LocalSocket().use { sock ->
                    sock.connect(LocalSocketAddress(SOCKET_NAME))
                    val output = DataOutputStream(sock.outputStream)
                    output.writeLEInt(MAGIC_REQ)
                    output.writeLEInt(VERSION)
                    output.writeLELong(requestId)
                    output.writeLEString(handlerName)
                    output.writeLEInt(request.pid)
                    output.writeLEInt(request.argv.size)
                    request.argv.forEach { output.writeLEString(it) }
                    output.writeLEInt(request.env.size)
                    request.env.forEach { (k, v) -> output.writeLEString("$k=$v") }
                    output.writeLEString(request.cwd)
                    output.writeLEString(request.sessionId ?: "")
                    output.flush()

                    val input = DataInputStream(sock.inputStream)
                    val magic = input.readLEInt()
                    if (magic != MAGIC_RSP) {
                        Log.w(TAG, "forward: unexpected magic 0x${magic.toUInt().toString(16)}")
                        return null
                    }
                    input.readLEInt() // version
                    val rid = input.readLELong()
                    if (rid != requestId) {
                        Log.w(TAG, "forward: request id mismatch got=$rid want=$requestId")
                        return null
                    }
                    val exitCode = input.readLEInt()
                    val outputText = input.readLEString()
                    NativeOffloadResult(exitCode, outputText)
                }
            } catch (e: Exception) {
                Log.w(TAG, "forward '$handlerName' failed: ${e.message}")
                null
            }
        }

        fun requestPermission(requestId: Long, toolName: String, toolTitle: String, sessionId: String): Boolean {
            return try {
                LocalSocket().use { sock ->
                    sock.connect(LocalSocketAddress(SOCKET_NAME))
                    val output = DataOutputStream(sock.outputStream)
                    output.writeLEInt(MAGIC_PERM_REQ)
                    output.writeLEInt(VERSION)
                    output.writeLELong(requestId)
                    output.writeLEString(toolName)
                    output.writeLEString(toolTitle)
                    output.writeLEString(sessionId)
                    output.flush()

                    val input = DataInputStream(sock.inputStream)
                    val magic = input.readLEInt()
                    if (magic != MAGIC_PERM_RSP) return false
                    input.readLEInt() // version
                    val rid = input.readLELong()
                    if (rid != requestId) return false
                    input.readLEInt() == 1
                }
            } catch (e: Exception) {
                Log.w(TAG, "requestPermission '$toolName' failed: ${e.message}")
                false
            }
        }
    }

    // -------------------- main-process server ------------------------------

    /**
     * Main-process side of the bridge. Owns the listening socket and the
     * heavy-handler registry. Started once from MinisApp.onCreate AFTER the
     * repositories the heavy handlers need are constructed. Uses its own
     * bounded executor so a burst of forwarded requests cannot spawn
     * unbounded threads in the main process either.
     */
    object Server {

        @Volatile
        private var serverSocket: LocalServerSocket? = null
        private val handlers = ConcurrentHashMap<String, NativeOffloadHandler>()

        fun register(name: String, handler: NativeOffloadHandler) {
            handlers[name] = handler
            Log.i(TAG, "bridge register '$name' (total=${handlers.size})")
        }

        @Synchronized
        fun start() {
            if (serverSocket != null) return
            val s = try {
                LocalServerSocket(SOCKET_NAME)
            } catch (e: Exception) {
                Log.w(TAG, "bridge bind failed: ${e.message}")
                return
            }
            serverSocket = s
            val exec = ThreadPoolExecutor(
                2, 2,
                60L, TimeUnit.SECONDS,
                ArrayBlockingQueue(16),
                { r -> Thread(r, "offload-bridge-worker").apply { isDaemon = true } },
                ThreadPoolExecutor.AbortPolicy(),
            )
            thread(name = "offload-bridge-accept", isDaemon = true) {
                while (true) {
                    val client = try {
                        s.accept()
                    } catch (e: Exception) {
                        Log.i(TAG, "bridge accept loop terminated: ${e.message}")
                        return@thread
                    }
                    try {
                        exec.execute { handleClient(client) }
                    } catch (_: RejectedExecutionException) {
                        runCatching { client.close() }
                    }
                }
            }
            Log.i(TAG, "bridge listening on abstract socket '$SOCKET_NAME'")
        }

        private fun handleClient(client: LocalSocket) {
            try {
                client.use {
                    val input = DataInputStream(client.inputStream)
                    val magic = input.readLEInt()
                    when (magic) {
                        MAGIC_REQ -> handleForward(input, client)
                        MAGIC_PERM_REQ -> handlePermRequest(input, client)
                        else -> Log.w(TAG, "bridge unknown magic 0x${magic.toUInt().toString(16)}")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "bridge client error: ${e.message}")
            }
        }

        private fun handleForward(input: DataInputStream, client: LocalSocket) {
            input.readLEInt() // version
            val requestId = input.readLELong()
            val handlerName = input.readLEString()
            val pid = input.readLEInt()
            val argc = input.readLEInt()
            val argv = ArrayList<String>(argc)
            repeat(argc) { argv.add(input.readLEString()) }
            val envc = input.readLEInt()
            val env = LinkedHashMap<String, String>(envc)
            repeat(envc) {
                val s = input.readLEString()
                val eq = s.indexOf('=')
                if (eq >= 0) env[s.substring(0, eq)] = s.substring(eq + 1) else env[s] = ""
            }
            val cwd = input.readLEString()
            val sessionId = input.readLEString().ifEmpty { null }

            val handler = handlers[handlerName]
            val result = if (handler == null) {
                NativeOffloadResult(127, "native_offload: no handler for '$handlerName' (bridge)\n")
            } else {
                try {
                    handler.handle(NativeOffloadRequest(pid, argv, env, cwd, sessionId))
                } catch (e: Exception) {
                    Log.w(TAG, "bridge handler '$handlerName' threw: ${e.message}", e)
                    NativeOffloadResult(1, "native_offload: ${e.message}\n")
                }
            }

            // Reuse the shared output cap so a heavy handler can't blow the
            // main process either.
            val (outText, _) = internalTruncateHandlerOutput(result.output)
            val output = DataOutputStream(client.outputStream)
            output.writeLEInt(MAGIC_RSP)
            output.writeLEInt(VERSION)
            output.writeLELong(requestId)
            output.writeLEInt(result.exitCode)
            output.writeLEString(outText)
            output.flush()
        }

        private fun handlePermRequest(input: DataInputStream, client: LocalSocket) {
            input.readLEInt() // version
            val requestId = input.readLELong()
            val toolName = input.readLEString()
            val toolTitle = input.readLEString()
            val sessionId = input.readLEString()

            // The main-process Compose UI observes OffloadPermissionManager.
            // _pendingRequest; runBlocking here mirrors OffloadGate.
            val allowed = kotlinx.coroutines.runBlocking {
                OffloadPermissionManager.checkPermission(toolName, toolTitle, sessionId)
            }
            val output = DataOutputStream(client.outputStream)
            output.writeLEInt(MAGIC_PERM_RSP)
            output.writeLEInt(VERSION)
            output.writeLELong(requestId)
            output.writeLEInt(if (allowed) 1 else 0)
            output.flush()
        }

        @Synchronized
        fun stop() {
            try { serverSocket?.close() } catch (_: Exception) {}
            serverSocket = null
            handlers.clear()
        }
    }
}