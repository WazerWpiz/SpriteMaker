package com.fengeon.resprite_desktop.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import com.fengeon.resprite_desktop.net.AgentClient
import com.fengeon.resprite_desktop.net.AgentConfig
import com.fengeon.resprite_desktop.net.McpServer
import com.fengeon.resprite_desktop.net.TunnelManager
import com.fengeon.resprite_desktop.ui.rsColors

/** Panel that starts/stops the tool API and shows how to call it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpSheet(onDismiss: () -> Unit) {
    val C = rsColors
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    // Re-read after start/stop so the UI reflects the real server state.
    var running by remember { mutableStateOf(McpServer.isRunning) }
    var error by remember { mutableStateOf<String?>(null) }
    // LAN mode is a device-local preference so an AI client can keep using one
    // stable token across restarts instead of reading a new one each time.
    var lanOn by remember { mutableStateOf(McpServer.lanEnabled(context)) }
    var shownToken by remember { mutableStateOf(McpServer.token) }
    val tunnel by TunnelManager.state.collectAsState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text("AI tool access", style = MaterialTheme.typography.titleMedium)
            Text(
                "An HTTP endpoint other programs can use to draw. " +
                    "On this device only by default; LAN mode also opens it to devices on the same Wi-Fi.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "LAN mode (other devices on my Wi-Fi)",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "Off = this phone only. On = any device on your network can reach it.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = lanOn,
                    onCheckedChange = { next ->
                        lanOn = next
                        McpServer.setLanEnabled(context, next)
                        // The bind address only takes effect on start, so re-bind
                        // right away if the server is already running.
                        if (McpServer.isRunning) {
                            McpServer.stop()
                            McpServer.start(context, lan = next)
                                .onSuccess { shownToken = McpServer.token }
                                .onFailure { error = it.message; running = false }
                        }
                    }
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = {
                    error = null
                    if (running) {
                        McpServer.stop()
                        running = false
                        shownToken = ""
                    } else {
                        McpServer.start(context, lan = lanOn)
                            .onSuccess { running = true; shownToken = McpServer.token }
                            .onFailure { error = it.message }
                    }
                }) {
                    Icon(
                        if (running) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(if (running) "  Stop server" else "  Start server")
                }
                Text(
                    if (running) "running" else "stopped",
                    color = if (running) C.statusText else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(start = 12.dp)
                )
            }

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            }

            if (running) {
                val host = if (McpServer.lanMode) McpServer.lanAddress() ?: "127.0.0.1" else "127.0.0.1"
                val endpoint = "http://$host:${McpServer.port}/call"
                Text("Endpoint", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp))
                CopyRow(endpoint) { clipboard.setText(AnnotatedString(endpoint)) }

                Text("Bearer token", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
                CopyRow(shownToken) { clipboard.setText(AnnotatedString(shownToken)) }

                if (McpServer.lanMode) {
                    Text(
                        "Plain HTTP — no encryption. Only use it on a network you trust, " +
                            "and never port-forward this to the internet; use a tunnel " +
                            "(SSH / Tailscale / adb reverse) for remote access instead.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 10.dp)
                    )
                    TextButton(
                        onClick = {
                            // The running server keeps the old token until restarted.
                            McpServer.stop()
                            shownToken = McpServer.rotateToken(context)
                            McpServer.start(context, lan = true)
                        },
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.padding(top = 4.dp)
                    ) { Text("New token", fontSize = 12.sp) }
                }

                Text(
                    "POST JSON: {\"tool\":\"draw_line\",\"args\":{\"x0\":0,\"y0\":0,\"x1\":32,\"y1\":32}}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }

            // ---- public access through Cloudflare Tunnel -------------------
            HorizontalDivider(color = C.divider, modifier = Modifier.padding(top = 16.dp))
            Text(
                "Public access (Cloudflare Tunnel)",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 12.dp)
            )
            Text(
                "Publishes the API over HTTPS at a *.trycloudflare.com URL so an AI client on any " +
                    "network can reach it, without exposing this device on the LAN. Needs the " +
                    "server above to be running.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
            )
            val tunnelBusy = tunnel is TunnelManager.State.Running ||
                tunnel === TunnelManager.State.Starting
            // The button needs the HTTP server to be up: a tunnel publishes the
            // port, it cannot start it. Saying so beats a greyed-out button.
            if (!running && !tunnelBusy) {
                Text(
                    "Start the server above first — the tunnel just publishes that port.",
                    fontSize = 11.sp,
                    color = C.statusText,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = {
                        if (tunnelBusy) TunnelManager.stop(context)
                        else TunnelManager.start(context, namedToken = null)
                    },
                    enabled = running || tunnelBusy
                ) {
                    Text(if (tunnelBusy) "  Stop tunnel" else "  Start tunnel")
                }
                Text(
                    when (val s = tunnel) {
                        TunnelManager.State.Stopped -> "stopped"
                        TunnelManager.State.Starting -> "starting…"
                        is TunnelManager.State.Running -> "public"
                        is TunnelManager.State.Failed -> "failed"
                    },
                    color = if (tunnel is TunnelManager.State.Running) C.statusText
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(start = 12.dp)
                )
            }
            when (val s = tunnel) {
                is TunnelManager.State.Running -> {
                    val publicEndpoint = s.url?.let { "$it/call" }
                        ?: "(hostname is configured in your Cloudflare dashboard)"
                    Text(
                        "Public endpoint",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp)
                    )
                    CopyRow(publicEndpoint) { clipboard.setText(AnnotatedString(publicEndpoint)) }
                    Text(
                        "Anyone with this URL plus the bearer token can draw on your canvas. " +
                            "Stop the tunnel when you are done — the URL dies with it.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                is TunnelManager.State.Failed -> Text(
                    s.message,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 6.dp)
                )
                else -> {}
            }

            // ---- AI Agent (runs on this device) ------------------------------
            HorizontalDivider(color = C.divider, modifier = Modifier.padding(top = 16.dp))

            Text(
                "AI Agent",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 12.dp)
            )
            Text(
                "Runs on this phone. It calls a provider with your own key, then draws " +
                    "through the local tool API — no remote server needed.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 12.dp)
            )

            val agentConfig = AgentConfig
            // Load the persisted provider first, otherwise the chip row would
            // show OpenRouter after a restart while the prefs say NVIDIA.
            AgentConfig.load(context)
            var agentProvider by remember { mutableStateOf(agentConfig.provider) }
            var agentApiKey by remember { mutableStateOf(agentConfig.apiKey(context)) }
            var agentKeyVisible by remember { mutableStateOf(false) }
            var agentModel by remember { mutableStateOf(agentConfig.model(context)) }
            var agentBaseUrl by remember { mutableStateOf(agentConfig.baseUrl(context)) }
            var agentMaxSteps by remember { mutableStateOf(agentConfig.maxSteps(context)) }
            var agentConfigOk by remember { mutableStateOf(agentConfig.isConfigured(context)) }
            var agentTestText by remember { mutableStateOf<String?>(null) }
            var agentTestOk by remember { mutableStateOf(true) }
            var agentBusy by remember { mutableStateOf(false) }
            var agentModels by remember { mutableStateOf<List<AgentClient.ModelInfo>?>(null) }
            var agentModelPickerOpen by remember { mutableStateOf(false) }

            val agentProviders = listOf(
                AgentConfig.Provider.OPENROUTER,
                AgentConfig.Provider.NVIDIA,
                AgentConfig.Provider.CUSTOM
            )

            fun agentValid(): Boolean =
                if (agentProvider == AgentConfig.PROVIDER_CUSTOM) agentBaseUrl.isNotBlank()
                else agentApiKey.isNotBlank()

            fun saveAgentConfig() {
                agentConfig.save(
                    context,
                    agentProvider,
                    agentApiKey,
                    agentModel,
                    agentBaseUrl,
                    agentMaxSteps
                )
                agentConfigOk = agentConfig.isConfigured(context)
            }

            fun testConnection() {
                saveAgentConfig()
                agentBusy = true
                agentTestText = "Testing…"
                agentTestOk = true
                AgentClient.testConnection(context) { result ->
                    agentBusy = false
                    agentTestOk = result.success
                    agentTestText = if (result.success) {
                        "OK — ${result.text}"
                    } else {
                        result.text
                    }
                }
            }

            fun loadModels() {
                saveAgentConfig()
                agentBusy = true
                agentTestText = "Loading models…"
                agentTestOk = true
                AgentClient.fetchModels(context) { result ->
                    agentBusy = false
                    if (result.success) {
                        agentModels = result.models
                        agentModelPickerOpen = true
                        agentTestText = null
                        // Remember a model that can actually call tools.
                        val current = agentModel
                        val stillThere = result.models.any { it.id == current }
                        if (!stillThere) {
                            val pickable = result.models.firstOrNull { it.supportsTools } ?: result.models.first()
                            agentModel = pickable.id
                        }
                    } else {
                        agentTestOk = false
                        agentTestText = result.error
                    }
                }
            }

            // Every agent control lives inside one rounded surface so the sheet
            // reads as labelled groups instead of loose rows.
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {

                    Text(
                        "PROVIDER",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 1.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                    ) {
                        agentProviders.forEach { p ->
                            val selected = p.id == agentProvider
                            val shape = RoundedCornerShape(10.dp)
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(shape)
                                    .background(
                                        if (selected) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surface
                                    )
                                    .border(
                                        width = if (selected) 1.5.dp else 1.dp,
                                        color = if (selected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outlineVariant,
                                        shape = shape
                                    )
                                    .clickable {
                                        agentProvider = p.id
                                        if (p.id != AgentConfig.PROVIDER_CUSTOM) {
                                            agentModel = p.defaultModel
                                            agentBaseUrl = agentConfig.baseUrl(context, p.id)
                                        }
                                        saveAgentConfig()
                                    }
                                    .padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    p.label.substringBefore(' '),
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                    else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(top = 14.dp)
                    ) {
                        Text(
                            "API KEY",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 1.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            if (agentConfigOk) "saved" else "not set",
                            fontSize = 10.sp,
                            color = if (agentConfigOk) C.statusText
                            else MaterialTheme.colorScheme.error
                        )
                    }
                    OutlinedTextField(
                        value = agentApiKey,
                        onValueChange = { agentApiKey = it },
                        placeholder = { Text("Paste your key", fontSize = 12.sp) },
                        singleLine = true,
                        visualTransformation =
                        if (agentKeyVisible) VisualTransformation.None
                        else PasswordVisualTransformation(),
                        trailingIcon = {
                            TextButton(onClick = { agentKeyVisible = !agentKeyVisible }) {
                                Text(if (agentKeyVisible) "Hide" else "Show", fontSize = 10.sp)
                            }
                        },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                    )

                    Text(
                        if (agentProvider == AgentConfig.PROVIDER_CUSTOM) "BASE URL" else "MODEL",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 1.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                    OutlinedTextField(
                        value = if (agentProvider == AgentConfig.PROVIDER_CUSTOM) agentBaseUrl else agentModel,
                        onValueChange = {
                            if (agentProvider == AgentConfig.PROVIDER_CUSTOM) agentBaseUrl = it
                            else agentModel = it
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        ),
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                    )

                    Text(
                        "MAX STEPS  ·  $agentMaxSteps  (suggested 25–60)",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 1.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                    Slider(
                        value = agentMaxSteps.toFloat(),
                        onValueChange = { agentMaxSteps = it.roundToInt() },
                        valueRange = 1f..40f,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Button(
                        onClick = { saveAgentConfig() },
                        enabled = agentValid(),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                    ) { Text("Save configuration") }
                }
            }

            // ---- prompt moved to the editor ---------------------------------
            Text(
                "Prompt & run now live in the editor: tap ✨ in the toolbar (next to the " +
                    "eyedropper) for a prompt panel with the canvas still visible above it.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 14.dp)
            )

            // ---- connection test + model list ----------------------------------
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
            ) {
                Button(
                    onClick = { testConnection() },
                    enabled = agentValid() && !agentBusy,
                    shape = RoundedCornerShape(10.dp)
                ) { Text("Test connection") }
                Button(
                    onClick = { loadModels() },
                    enabled = agentValid() && !agentBusy,
                    shape = RoundedCornerShape(10.dp)
                ) { Text("Load models") }
            }

            if (agentBusy) {
                androidx.compose.material3.CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.padding(top = 10.dp).size(18.dp)
                )
            }

            agentTestText?.let { text ->
                Text(
                    text,
                    fontSize = 11.sp,
                    color = if (agentTestOk) C.statusText else MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            agentModels?.let { list ->
                Text(
                    "MODEL  ·  ${list.size} available",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp)
                )
                Button(
                    onClick = { agentModelPickerOpen = !agentModelPickerOpen },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                ) {
                    Text(
                        (agentModel.substringAfter('/')) + if (agentModelPickerOpen) "  ▴" else "  ▾",
                        fontSize = 11.sp,
                        maxLines = 1
                    )
                }
                if (agentModelPickerOpen) {
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, C.divider),
                        modifier = Modifier.fillMaxWidth().heightIn(max = 210.dp)
                            .padding(top = 6.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .verticalScroll(rememberScrollState())
                                .padding(vertical = 4.dp)
                        ) {
                            list.forEach { m ->
                                val ctx = m.contextLength?.let { " · ${it / 1024}K ctx" } ?: ""
                                val tools = if (m.supportsTools) "" else " · no tools"
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            agentModel = m.id
                                            agentModelPickerOpen = false
                                        }
                                        .padding(horizontal = 10.dp, vertical = 8.dp)
                                ) {
                                    Text(
                                        m.id,
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = if (m.id == agentModel) C.statusText
                                        else MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        "$ctx$tools",
                                        fontSize = 9.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}


@Composable
private fun CopyRow(value: String, onCopy: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp)
    ) {
        Text(
            value,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f).background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp)
        )
        IconButton(onClick = onCopy, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.ContentCopy, "Copy", modifier = Modifier.size(16.dp))
        }
    }
}