package com.fengeon.resprite_desktop.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardActions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fengeon.resprite_desktop.net.AgentConfig
import com.fengeon.resprite_desktop.net.AgentRun
import com.fengeon.resprite_desktop.ui.rsColors

/**
 * The agent prompt panel, opened from the ✨ toolbar button.
 *
 * It is a bottom sheet rather than a full screen so the canvas stays visible
 * above it: the user watches the drawing appear while typing the next command.
 *
 * All run state comes from [AgentRun], so closing this sheet never kills a run
 * in flight and reopening shows the same progress and step log.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentSheet(onDismiss: () -> Unit) {
    val C = rsColors
    val context = androidx.compose.ui.platform.LocalContext.current
    val progress by AgentRun.progress.collectAsState()
    val log by AgentRun.log.collectAsState()
    val history by AgentRun.history.collectAsState()

    var prompt by remember { mutableStateOf("") }
    var configOpen by remember { mutableStateOf(false) }

    // Prompt history is persisted; read it when the sheet is first shown.
    LaunchedEffect(Unit) { AgentRun.loadHistory(context) }

    val configured = remember { AgentConfig.isConfigured(context) }
    val runningNow = progress != null && progress!!.finished.not()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp)
        ) {
            // ---- status line -------------------------------------------------
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            when {
                                progress == null -> MaterialTheme.colorScheme.outlineVariant
                                progress!!.finished && progress!!.success -> C.statusText
                                progress!!.finished -> MaterialTheme.colorScheme.error
                                else -> C.statusText
                            },
                            RoundedCornerShape(4.dp)
                        )
                )
                Text(
                    "AI Agent",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 8.dp)
                )
                val p = progress
                Text(
                    if (p != null && !p.finished) "step ${p.step}/${p.maxSteps}" else "",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp)
                )
                if (runningNow) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        color = C.statusText,
                        modifier = Modifier.padding(start = 8.dp).size(14.dp)
                    )
                }
            }

            // ---- current status text ----------------------------------------
            val p = progress
            if (p != null) {
                Text(
                    p.status,
                    fontSize = 11.sp,
                    color = when {
                        p.finished && !p.success -> MaterialTheme.colorScheme.error
                        p.finished -> C.statusText
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(top = 6.dp)
                )
            }

            // ---- step log ----------------------------------------------------
            if (log.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, C.divider),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) {
                    Text(
                        log.joinToString("\n"),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        lineHeight = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .heightIn(max = 130.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(8.dp)
                    )
                }
            }

            // ---- prompt history chips ---------------------------------------
            if (history.isNotEmpty()) {
                Text(
                    "RECENT PROMPTS",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp)
                )
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                ) {
                    androidx.compose.foundation.lazy.items(history) { item ->
                        val short = if (item.length > 28) item.take(28) + "…" else item
                        Box(
                            modifier = Modifier
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(8.dp)
                                )
                                .border(
                                    1.dp,
                                    MaterialTheme.colorScheme.outlineVariant,
                                    RoundedCornerShape(8.dp)
                                )
                                .clickable { prompt = item }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(short, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }

            // ---- prompt input -------------------------------------------------
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                placeholder = {
                    Text(
                        "Draw a 32×32 pixel tree, use 4 colours",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                minLines = 2,
                maxLines = 5,
                shape = RoundedCornerShape(12.dp),
                keyboardActions = KeyboardActions(onDone = {
                    if (!runningNow && prompt.isNotBlank()) AgentRun.start(context, prompt)
                }),
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
            )

            // ---- actions ------------------------------------------------------
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) {
                Button(
                    onClick = {
                        val text = prompt
                        prompt = ""
                        AgentRun.start(context, text)
                    },
                    enabled = !runningNow && prompt.isNotBlank() && configured,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(18.dp))
                    Text("  Send")
                }
                if (runningNow) {
                    OutlinedButton(
                        onClick = { AgentRun.stop() },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.padding(start = 8.dp)
                    ) {
                        Icon(Icons.Filled.Stop, null, modifier = Modifier.size(18.dp))
                        Text("  Stop")
                    }
                } else {
                    Text(
                        progress?.let { "${it.step}/${it.maxSteps}" } ?: "",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 10.dp)
                    )
                }
            }

            if (!configured) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) {
                    Text(
                        "No API key yet.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { onDismiss() }) { Text("Open settings", fontSize = 11.sp) }
                }
            }

            // ---- inline provider quick-set (falls back to the full sheet) -----
            if (configOpen) {
                var key by remember { mutableStateOf(AgentConfig.apiKey(context)) }
                var model by remember { mutableStateOf(AgentConfig.model(context)) }
                val cfgContext = context
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        OutlinedTextField(
                            value = key,
                            onValueChange = { key = it },
                            label = { Text("API key", fontSize = 11.sp) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = model,
                            onValueChange = { model = it },
                            label = { Text("Model", fontSize = 11.sp) },
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        )
                        Button(
                            onClick = {
                                AgentConfig.save(
                                    cfgContext,
                                    AgentConfig.provider,
                                    key,
                                    model,
                                    AgentConfig.baseUrl(cfgContext),
                                    AgentConfig.maxSteps(cfgContext)
                                )
                                configOpen = false
                            },
                            enabled = key.isNotBlank(),
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        ) { Text("Save") }
                    }
                }
            }

            if (configured) {
                TextButton(
                    onClick = { configOpen = !configOpen },
                    modifier = Modifier.padding(top = 2.dp)
                ) { Text("Change key / model", fontSize = 10.sp) }
            } else {
                TextButton(
                    onClick = { configOpen = true },
                    modifier = Modifier.padding(top = 2.dp)
                ) { Text("Paste API key", fontSize = 10.sp) }
            }
        }
    }
}