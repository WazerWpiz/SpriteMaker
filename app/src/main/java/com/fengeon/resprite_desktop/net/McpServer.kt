package com.fengeon.resprite_desktop.net

import android.content.Context
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom

/**
 * Owns the single HTTP server the AI tool client talks to.
 *
 * Security posture:
 *  - by default it binds to 127.0.0.1 only, so nothing outside the device can
 *    connect; every /call still requires the per-session bearer token, so another
 *    app on the same phone cannot silently draw on the canvas;
 *  - the token is regenerated each time the server starts and is shown in the
 *    editor so the user can paste it into their client;
 *  - LAN mode binds to 0.0.0.0 instead, which lets AI clients on the same Wi-Fi
 *    reach the tools (the user has to switch it on explicitly). Because LAN mode
 *    needs a token a client can rely on across restarts, it uses a persistent
 *    token stored on-device rather than the per-session one.
 *
 * LAN mode has no TLS: it is safe on a network you trust and must never be
 * port-forwarded to the public internet. For remote access use a tunnel
 * (SSH / Tailscale / `adb reverse`) and leave this switch off.
 */
object McpServer {

    const val DEFAULT_PORT = 47613

    private const val PREFS = "resprite_mcp"
    private const val KEY_LAN = "lan_mode"
    private const val KEY_LAN_TOKEN = "lan_token"

    @Volatile
    var port: Int = DEFAULT_PORT
        private set

    @Volatile
    var token: String = ""
        private set

    /** True while the server is running in LAN mode (bound to 0.0.0.0). */
    @Volatile
    var lanMode: Boolean = false
        private set

    private var server: MiniHttpServer? = null

    val isRunning: Boolean get() = server?.isRunning == true

    /**
     * @param lan bind to all interfaces so other devices on the same Wi-Fi can
     *   call the API. Off by default — see the class comment for why.
     */
    fun start(context: Context, requestedPort: Int = DEFAULT_PORT, lan: Boolean = false): Result<Int> = runCatching {
        if (isRunning) return@runCatching port
        val t = if (lan) persistentToken(context) else newToken()
        val s = MiniHttpServer(requestedPort, t) { _, _, body -> 200 to McpApi.handle(body) }
        s.start(if (lan) "0.0.0.0" else "127.0.0.1")
        server = s
        token = t
        lanMode = lan
        port = requestedPort
        requestedPort
    }

    fun stop() {
        server?.stop()
        server = null
        token = ""
        lanMode = false
    }

    fun lanEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LAN, false)

    fun setLanEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_LAN, enabled).apply()
    }

    /**
     * Replaces the persistent LAN token. The server keeps running with the old
     * one until it is restarted, so callers restart it right after.
     */
    fun rotateToken(context: Context): String {
        val t = newToken()
        prefs(context).edit().putString(KEY_LAN_TOKEN, t).apply()
        return t
    }

    /**
     * This device's Wi-Fi address, e.g. "192.168.1.8". Returns null when no
     * interface has an IPv4 address yet — the UI then falls back to loopback.
     */
    fun lanAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    }.getOrNull()

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun persistentToken(context: Context): String {
        val existing = prefs(context).getString(KEY_LAN_TOKEN, null)
        if (!existing.isNullOrEmpty()) return existing
        val t = newToken()
        prefs(context).edit().putString(KEY_LAN_TOKEN, t).apply()
        return t
    }

    private fun newToken(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}