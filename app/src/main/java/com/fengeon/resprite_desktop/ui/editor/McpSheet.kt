package com.fengeon.resprite_desktop.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fengeon.resprite_desktop.net.McpServer
import com.fengeon.resprite_desktop.ui.rsColors

/** Panel that starts/stops the loopback tool API and shows how to call it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpSheet(onDismiss: () -> Unit) {
    val C = rsColors
    val clipboard = LocalClipboardManager.current
    // Re-read after start/stop so the UI reflects the real server state.
    var running by remember { mutableStateOf(McpServer.isRunning) }
    var error by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text("AI tool access", style = MaterialTheme.typography.titleMedium)
            Text(
                "A local HTTP endpoint other programs on this device can use to draw. " +
                    "Bound to 127.0.0.1 and protected by a token.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = {
                    error = null
                    if (running) {
                        McpServer.stop()
                        running = false
                    } else {
                        McpServer.start().onSuccess { running = true }.onFailure { error = it.message }
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
                val endpoint = "http://127.0.0.1:${McpServer.port}/call"
                Text("Endpoint", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp))
                CopyRow(endpoint) { clipboard.setText(AnnotatedString(endpoint)) }

                Text("Bearer token", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
                CopyRow(McpServer.token) { clipboard.setText(AnnotatedString(McpServer.token)) }

                Text(
                    "POST JSON: {\"tool\":\"draw_line\",\"args\":{\"x0\":0,\"y0\":0,\"x1\":32,\"y1\":32}}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp)
                )
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
