package com.fengeon.resprite_desktop.net

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import java.io.File
import kotlin.concurrent.thread

/**
 * Runs the bundled cloudflared binary against the loopback tool API.
 *
 * It is a foreground service because the tunnel is useless if Android freezes it
 * the moment the editor goes to the background, and MIUI is aggressive about
 * killing plain background processes.
 *
 * The binary is shipped as jniLibs/arm64-v8a/libcloudflared.so: Android 10+ does
 * not allow executing files from the app's data directories (W^X), while entries
 * in nativeLibraryDir are executable.
 */
class TunnelService : Service() {

    companion object {
        const val ACTION_START = "com.fengeon.resprite_desktop.tunnel.START"
        const val ACTION_STOP = "com.fengeon.resprite_desktop.tunnel.STOP"
        const val EXTRA_NAMED_TOKEN = "named_token"

        private const val CHANNEL_ID = "resprite_tunnel"
        private const val NOTIF_ID = 4711
        private val QUICK_URL = Regex("""https://[a-z0-9-]+\.trycloudflare\.com""")
    }

    private var process: Process? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Cloudflare Tunnel",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            killProcess()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val namedToken = intent?.getStringExtra(EXTRA_NAMED_TOKEN)?.takeIf { it.isNotBlank() }
        startForeground(NOTIF_ID, notification("Starting Cloudflare Tunnel…", null))
        startTunnel(namedToken)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        killProcess()
        // Keep a failure message on screen: stopSelf() from fail() lands here too.
        if (TunnelManager.state.value !is TunnelManager.State.Failed) TunnelManager.onStopped()
        super.onDestroy()
    }

    // ---- tunnel lifecycle -------------------------------------------------

    private fun startTunnel(namedToken: String?) {
        killProcess()
        val bin = File(applicationInfo.nativeLibraryDir, "libcloudflared.so")
        if (!bin.canExecute()) {
            fail("cloudflared is not bundled in this build")
            return
        }
        val command = if (namedToken == null) {
            listOf(
                bin.absolutePath, "tunnel", "--no-autoupdate",
                "--url", "http://127.0.0.1:${McpServer.port}"
            )
        } else {
            listOf(bin.absolutePath, "tunnel", "--no-autoupdate", "run", "--token", namedToken)
        }
        val p = try {
            ProcessBuilder(command).redirectErrorStream(true).start()
        } catch (t: Throwable) {
            fail(t.message ?: "could not start cloudflared")
            return
        }
        process = p
        thread(name = "cloudflared-log", isDaemon = true) {
            runCatching {
                p.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        val url = QUICK_URL.find(line)?.value
                        when {
                            url != null -> {
                                TunnelManager.onRunning(url)
                                notify("Tunnel ready", url)
                            }
                            // Named tunnels print this once the edge accepts them.
                            line.contains("Registered tunnel connection") -> {
                                TunnelManager.onRunning(null)
                                notify("Tunnel connected", null)
                            }
                        }
                    }
                }
            }
            val code = runCatching { p.waitFor() }.getOrDefault(-1)
            // Only report a failure if this process died on its own. A user stop
            // clears `process` first, so a clean shutdown must stay "stopped"
            // instead of showing a bogus exit-code error.
            if (process === p) fail("cloudflared stopped (exit code $code)")
        }
    }

    private fun killProcess() {
        val p = process ?: return
        process = null
        runCatching { p.destroy() }
        runCatching { p.waitFor() }
    }

    private fun fail(message: String) {
        TunnelManager.onFailed(message)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---- notification -----------------------------------------------------

    private fun notify(title: String, body: String?) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(title, body))
    }

    private fun notification(title: String, body: String?): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, com.fengeon.resprite_desktop.MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(open)
            .setOngoing(true)
        if (body != null) builder.setContentText(body)
        return builder.build()
    }
}