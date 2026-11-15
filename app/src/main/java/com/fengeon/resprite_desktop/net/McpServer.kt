package com.fengeon.resprite_desktop.net

import java.security.SecureRandom

/**
 * Owns the single loopback HTTP server the AI tool client talks to.
 *
 * Security posture:
 *  - binds to 127.0.0.1 only, so nothing outside the device can connect;
 *  - every /call requires the per-session bearer token, so another app on the
 *    same phone cannot silently draw on the canvas;
 *  - the token is regenerated each time the server starts and is shown in the
 *    editor so the user can paste it into their client.
 */
object McpServer {

    const val DEFAULT_PORT = 47613

    @Volatile
    var port: Int = DEFAULT_PORT
        private set

    @Volatile
    var token: String = ""
        private set

    private var server: MiniHttpServer? = null

    val isRunning: Boolean get() = server?.isRunning == true

    fun start(requestedPort: Int = DEFAULT_PORT): Result<Int> = runCatching {
        if (isRunning) return@runCatching port
        val t = newToken()
        val s = MiniHttpServer(requestedPort, t) { _, _, body -> 200 to McpApi.handle(body) }
        s.start()
        server = s
        token = t
        port = requestedPort
        requestedPort
    }

    fun stop() {
        server?.stop()
        server = null
        token = ""
    }

    private fun newToken(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
