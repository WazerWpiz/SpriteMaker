package com.fengeon.resprite_desktop.net

import kotlinx.serialization.json.JsonPrimitive
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Tiny blocking HTTP/1.1 server bound to loopback only. Deliberately has no
 * dependencies so it works inside the app without pulling in Ktor/Netty.
 *
 * It implements just enough HTTP for a local tool client: one request per
 * connection, Content-Length bodies, and "Connection: close" responses. It is
 * not a general web server and must never be bound to a public interface.
 */
class MiniHttpServer(
    private val port: Int,
    private val token: String,
    private val handler: (method: String, path: String, body: String) -> Pair<Int, String>
) {
    private val running = AtomicBoolean(false)
    private var socket: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(4)

    val isRunning: Boolean get() = running.get()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val server = ServerSocket(port, 8, InetAddress.getByName("127.0.0.1"))
        socket = server
        thread(name = "resprite-http", isDaemon = true) {
            while (running.get()) {
                val client = try {
                    server.accept()
                } catch (e: Exception) {
                    if (running.get()) continue else break
                }
                pool.execute { serve(client) }
            }
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        pool.shutdownNow()
    }

    private fun serve(client: Socket) {
        client.use { c ->
            try {
                c.soTimeout = 10_000
                val input = BufferedInputStream(c.getInputStream())
                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(' ')
                if (parts.size < 3) { writeResponse(c.getOutputStream(), 400, "{\"error\":\"bad request line\"}"); return }
                val method = parts[0]
                val path = parts[1].substringBefore('?')

                var contentLength = 0
                var authorized = false
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    val idx = line.indexOf(':')
                    if (idx <= 0) continue
                    val name = line.substring(0, idx).trim().lowercase()
                    val value = line.substring(idx + 1).trim()
                    when (name) {
                        "content-length" -> contentLength = value.toIntOrNull() ?: 0
                        "authorization" -> authorized = value == "Bearer $token"
                    }
                }

                val body = if (contentLength > 0) {
                    val buf = ByteArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val n = input.read(buf, read, contentLength - read)
                        if (n < 0) break
                        read += n
                    }
                    String(buf, 0, read, Charsets.UTF_8)
                } else ""

                if (path == "/health") {
                    writeResponse(c.getOutputStream(), 200, "{\"ok\":true,\"port\":$port}")
                    return
                }
                if (!authorized) {
                    writeResponse(c.getOutputStream(), 401, "{\"error\":\"missing or bad bearer token\"}")
                    return
                }
                if (method != "POST" || path != "/call") {
                    writeResponse(c.getOutputStream(), 404, "{\"error\":\"use POST /call\"}")
                    return
                }
                val (status, payload) = try {
                    handler(method, path, body)
                } catch (t: Throwable) {
                    500 to ("{\"error\":" + JsonPrimitive(t.message ?: t.javaClass.simpleName).toString() + "}")
                }
                writeResponse(c.getOutputStream(), status, payload)
            } catch (t: Throwable) {
                try {
                    writeResponse(c.getOutputStream(), 500, "{\"error\":" + JsonPrimitive(t.message ?: "server error").toString() + "}")
                } catch (_: Throwable) {}
            }
        }
    }

    private fun readLine(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
            if (sb.length > 8192) return null
        }
        return sb.toString()
    }

    private fun writeResponse(out: OutputStream, status: Int, body: String) {
        val reason = when (status) {
            200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"
            404 -> "Not Found"; 500 -> "Internal Server Error"; else -> "OK"
        }
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ").append(bytes.size).append("\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }
}
