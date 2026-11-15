package com.fengeon.resprite_desktop.net

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Public access to the loopback tool API through a Cloudflare Tunnel.
 *
 * The editor keeps listening on 127.0.0.1 only. A bundled cloudflared process
 * (see [TunnelService]) publishes that port at an HTTPS *.trycloudflare.com URL,
 * so the bearer token crosses the internet encrypted instead of in cleartext like
 * LAN mode does.
 */
object TunnelManager {

    sealed interface State {
        object Stopped : State
        object Starting : State

        /** [url] is null for a named tunnel: the hostname lives in the dashboard. */
        data class Running(val url: String?) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Stopped)
    val state: StateFlow<State> = _state.asStateFlow()

    /** @param namedToken token from a Cloudflare dashboard tunnel, or null/blank
     *  for a throwaway quick tunnel whose URL is generated on the fly. */
    fun start(context: Context, namedToken: String?) {
        _state.value = State.Starting
        val intent = Intent(context, TunnelService::class.java)
            .setAction(TunnelService.ACTION_START)
            .putExtra(TunnelService.EXTRA_NAMED_TOKEN, namedToken)
        context.startForegroundService(intent)
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, TunnelService::class.java))
        _state.value = State.Stopped
    }

    // ---- called by TunnelService -----------------------------------------

    internal fun onRunning(url: String?) {
        _state.value = State.Running(url)
    }

    internal fun onFailed(message: String) {
        _state.value = State.Failed(message)
    }

    internal fun onStopped() {
        _state.value = State.Stopped
    }
}