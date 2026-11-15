package com.fengeon.resprite_desktop.ui.editor

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.Gradient
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.delay
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fengeon.resprite_desktop.ui.rsColors
import com.fengeon.resprite_desktop.commands.CreateFrameCmd
import com.fengeon.resprite_desktop.commands.CreateLayerCmd
import com.fengeon.resprite_desktop.commands.DeleteFrameCmd
import com.fengeon.resprite_desktop.commands.DeleteLayerCmd
import com.fengeon.resprite_desktop.commands.Eyedropper
import com.fengeon.resprite_desktop.commands.FillCmd
import com.fengeon.resprite_desktop.commands.MoveCellCmd
import com.fengeon.resprite_desktop.commands.PaintStrokeCmd
import com.fengeon.resprite_desktop.engine.Compositor
import com.fengeon.resprite_desktop.engine.RespriteEngine
import com.fengeon.resprite_desktop.net.McpServer
import androidx.compose.material.icons.filled.Settings
import com.fengeon.resprite_desktop.commands.Shape
import com.fengeon.resprite_desktop.commands.ShapeCmd
import com.fengeon.resprite_desktop.format.RespriteFile
import com.fengeon.resprite_desktop.format.GifEncoder
import com.fengeon.resprite_desktop.model.Palette
import com.fengeon.resprite_desktop.model.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

// Tools that have a real handler in onStrokeStart/onStrokeEnd. Tool.Select and
// Tool.Gradient exist in the enum but are not wired yet, so they are hidden from
// the toolbar instead of sitting there dead (see design/editor-redesign.html).
private val TOOLS_WITH_HANDLERS: List<Tool> = listOf(
    Tool.Pencil, Tool.Eraser, Tool.Fill, Tool.Line, Tool.Rect, Tool.Ellipse,
    Tool.Move, Tool.Eyedropper
)

private val toolIcons: Map<Tool, ImageVector> = mapOf(
    Tool.Pencil to Icons.Filled.Edit,
    Tool.Eraser to Icons.Filled.AutoFixHigh,
    Tool.Fill to Icons.Filled.FormatColorFill,
    Tool.Line to Icons.Filled.Timeline,
    Tool.Rect to Icons.Filled.CropSquare,
    Tool.Ellipse to Icons.Filled.Circle,
    Tool.Select to Icons.Filled.SelectAll,
    Tool.Move to Icons.Filled.OpenWith,
    Tool.Eyedropper to Icons.Filled.Colorize,
    Tool.Gradient to Icons.Filled.Gradient
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen() {
    val C = rsColors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Shared with the HTTP tool API: one document, one undo stack. Reading
    // revision subscribes this composable to changes made from either side.
    val revision = RespriteEngine.revision
    val state = RespriteEngine.state
    val undo = RespriteEngine.undo
    var tool by remember { mutableStateOf(Tool.Pencil) }
    // Colours live on the shared state so the HTTP API and the palette never
    // disagree about the active colour. Reading `revision` above means an API
    // set_color() recomposes this screen too.
    // Snapshot values for rendering (the palette swatches). Drawing handlers read
    // state.primaryColor at call time instead, so a colour change is never missed
    // by a lambda captured on an earlier composition.
    val primary = state.primaryColor
    val secondary = state.secondaryColor
    var palette by remember { mutableStateOf(Palette.COMMODORE_64) }
    var statusText by remember { mutableStateOf("ready") }
    var layersOpen by remember { mutableStateOf(false) }
    var mcpOpen by remember { mutableStateOf(false) }
    var zoomPct by remember { mutableStateOf(100) }
    var playing by remember { mutableStateOf(false) }
    val activeStroke = remember { mutableStateOf<PaintStrokeCmd?>(null) }

    // Export modal
    var exportOpen by remember { mutableStateOf(false) }
    var exportStatus by remember { mutableStateOf("") }
    val exportSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    fun bump() { RespriteEngine.touch() }

    // Playback: advances the selected frame at the document's frame rate. Reading
    // state.document.frameRate each tick means a rate change takes effect without
    // restarting playback.
    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        while (true) {
            val fps = state.document.frameRate.coerceIn(1, 60)
            delay((1000L / fps).coerceAtLeast(16L))
            val count = state.document.frameDatas.size
            if (count <= 1) { playing = false; break }
            state.selectedFrameIndex = (state.selectedFrameIndex + 1) % count
            bump()
        }
    }
    fun activeCellId(): String = state.ensureCell(state.selectedLayerIndex, state.selectedFrameIndex)

    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)!!.use { RespriteFile.read(it) } }
            }
            result.onSuccess { contents ->
                val bitmaps = contents.cells.mapNotNull { (id, bytes) ->
                    runCatching { android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                        .getOrNull()?.let { id to it }
                }.toMap()
                RespriteEngine.replaceDocument(contents.document, bitmaps)
                statusText = "opened"
            }.onFailure { statusText = "open failed" }
        }
    }

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val cellsOut = state.cellBitmaps.mapValues { (_, bmp) ->
                val bos = java.io.ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
                bos.toByteArray()
            }
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)!!.use { out ->
                        RespriteFile.write(out, state.document, cellsOut, null, null)
                    }
                }
            }
            statusText = if (ok.isSuccess) "saved" else "save failed"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Resprite", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = C.topBar),
                actions = {
                    // UndoManager keeps its stacks in plain ArrayDeques, so Compose
                    // cannot observe them. Reading `version` here subscribes THIS
                    // lambda to the recomposition that bump() triggers; without it
                    // the bar never recomposed and both buttons stayed disabled.
                    val canUndo = revision.let { undo.canUndo }
                    val canRedo = revision.let { undo.canRedo }
                    IconButton(onClick = { undo.undo(state); bump() }, enabled = canUndo) {
                        Icon(Icons.Filled.Undo, "Undo", tint = if (canUndo) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { undo.redo(state); bump() }, enabled = canRedo) {
                        Icon(Icons.Filled.Redo, "Redo", tint = if (canRedo) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { mcpOpen = true }) {
                        Icon(
                            Icons.Filled.Settings,
                            "AI access",
                            tint = if (McpServer.isRunning) C.statusText else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    IconButton(onClick = { openLauncher.launch(arrayOf("*/*")) }) {
                        Icon(Icons.Filled.FolderOpen, "Open", tint = MaterialTheme.colorScheme.onSurface)
                    }
                    IconButton(onClick = { saveLauncher.launch("sprite.resprite") }) {
                        Icon(Icons.Filled.Save, "Save", tint = MaterialTheme.colorScheme.onSurface)
                    }
                    IconButton(onClick = { exportOpen = true; exportStatus = ""; exportSheetState.show() }) {
                        Icon(Icons.Filled.FileDownload, "Export", tint = MaterialTheme.colorScheme.onSurface)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .background(C.panelDeep)
        ) {
            // Toolbar: icons only
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(C.panel)
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                TOOLS_WITH_HANDLERS.forEach { t ->
                    IconButton(
                        onClick = { tool = t },
                        modifier = Modifier
                            .size(40.dp)
                            .background(
                                if (tool == t) C.toolActiveBg else Color.Transparent,
                                RoundedCornerShape(6.dp)
                            )
                            .border(
                                width = if (tool == t) 2.dp else 0.dp,
                                color = if (tool == t) C.toolActiveBg else Color.Transparent,
                                shape = RoundedCornerShape(6.dp)
                            )
                    ) {
                        Icon(
                            imageVector = toolIcons[t] ?: Icons.Filled.Edit,
                            contentDescription = t.name,
                            tint = if (tool == t) C.toolIconActive else C.toolIcon,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }

            PalettePanel(
                palette = palette,
                primary = primary,
                secondary = secondary,
                onPrimaryChange = { state.primaryColor = it; bump() },
                onSecondaryChange = { state.secondaryColor = it; bump() },
                onPaletteChange = { palette = it; state.primaryColor = it.colors.firstOrNull() ?: primary; bump() }
            )

            Column(modifier = Modifier.fillMaxWidth().weight(1f).padding(4.dp)) {
                    val composite = remember(revision, state.selectedFrameIndex) { Compositor.flatten(state) }
                    CanvasView(
                        composite = composite,
                        tool = tool,
                        onStrokeStart = { x, y ->
                            when (tool) {
                                Tool.Pencil, Tool.Eraser -> {
                                    val cmd = PaintStrokeCmd(activeCellId(), listOf(x to y),
                                        if (tool == Tool.Eraser) 0 else state.primaryColor, tool == Tool.Eraser, 1)
                                    undo.execute(state, cmd)
                                    activeStroke.value = cmd
                                    bump()
                                }
                                Tool.Fill -> { undo.execute(state, FillCmd(activeCellId(), x, y, state.primaryColor)); bump() }
                                Tool.Eyedropper -> { state.primaryColor = Eyedropper.sample(state, x, y); bump() }
                                else -> {}
                            }
                        },
                        onStrokeMove = { x0, y0, x1, y1 ->
                            if (tool == Tool.Pencil || tool == Tool.Eraser) {
                                activeStroke.value?.let { it.extend(state, x1, y1); bump() }
                            }
                        },
                        onStrokeEnd = { sx, sy, ex, ey ->
                            activeStroke.value = null
                            if (tool == Tool.Move) {
                                val dx = ex - sx; val dy = ey - sy
                                if (dx != 0 || dy != 0) {
                                    val id = state.cellIdFor(state.selectedLayerIndex, state.selectedFrameIndex)
                                    if (id != null) { undo.execute(state, MoveCellCmd(id, dx, dy)); bump() }
                                }
                            } else {
                                val shape = when (tool) {
                                    Tool.Line -> Shape.Line; Tool.Rect -> Shape.Rect; Tool.Ellipse -> Shape.Ellipse; else -> null
                                }
                                if (shape != null) {
                                    undo.execute(state, ShapeCmd(activeCellId(), shape, sx, sy, ex, ey, state.primaryColor, 1, false)); bump()
                                }
                            }
                        },
                        onZoomChange = { z -> zoomPct = (z * 100).toInt() },
                        // A second finger turns the gesture into a pinch; no onStrokeEnd
                        // follows, so close the stroke here or it would stay active and
                        // a later move could append to a finished undo entry.
                        onStrokeCancel = { activeStroke.value = null },
                        modifier = Modifier.fillMaxSize()
                    )
                }

            // Frame strip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(C.panel)
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { playing = !playing }, modifier = Modifier.size(36.dp)) {
                    Icon(
                        if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        "Play",
                        tint = if (playing) C.statusText else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp)
                    )
                }
                // LazyRow, not Row: an action set can have hundreds of frames and a
                // plain Row would overflow off-screen with no way to reach them.
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    itemsIndexed(state.document.frameDatas) { i, _ ->
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(
                                    if (i == state.selectedFrameIndex) C.frameSelectedBg else C.frameIdle,
                                    RoundedCornerShape(4.dp)
                                )
                                .clickable { state.selectedFrameIndex = i; playing = false; bump() },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("$i", color = if (i == state.selectedFrameIndex) C.toolIconActive else MaterialTheme.colorScheme.onSurface, fontSize = 12.sp)
                        }
                    }
                }
                IconButton(onClick = {
                    undo.execute(state, CreateFrameCmd(state.document.frameDatas.size, state.document.layerDatas.size, Size(state.canvasWidth, state.canvasHeight))); bump()
                }, modifier = Modifier.size(36.dp)) { Icon(Icons.Filled.Add, "Add frame", tint = MaterialTheme.colorScheme.onSurface) }
                IconButton(onClick = {
                    if (state.document.frameDatas.size > 1) {
                        undo.execute(state, DeleteFrameCmd(state.selectedFrameIndex)); bump()
                    }
                }, modifier = Modifier.size(36.dp)) { Icon(Icons.Filled.Delete, "Delete frame", tint = MaterialTheme.colorScheme.onSurface) }
                IconButton(onClick = { layersOpen = true }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Layers, "Layers", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp))
                }
                Text("${state.canvasWidth}×${state.canvasHeight}  ${zoomPct}%", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                Text(statusText, color = C.statusText, fontSize = 11.sp)
            }
        }
    }

    if (mcpOpen) {
        McpSheet(onDismiss = { mcpOpen = false })
    }

    if (layersOpen) {
        ModalBottomSheet(
            onDismissRequest = { layersOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text("Layers", color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, modifier = Modifier.weight(1f))
                IconButton(onClick = {
                    undo.execute(state, CreateLayerCmd(state.document.layerDatas.size, state.document.frameDatas.size)); bump()
                }, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.Add, "Add layer", tint = MaterialTheme.colorScheme.onSurface) }
                IconButton(onClick = {
                    if (state.document.layerDatas.size > 1) { undo.execute(state, DeleteLayerCmd(state.selectedLayerIndex)); bump() }
                }, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.Delete, "Delete layer", tint = MaterialTheme.colorScheme.onSurface) }
            }
            HorizontalDivider(color = C.divider)
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).padding(bottom = 16.dp)) {
                itemsIndexed(state.document.layerDatas) { i, layer ->
                    ListItem(
                        headlineContent = { Text(layer.name, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp) },
                        modifier = Modifier
                            .background(if (i == state.selectedLayerIndex) C.selectedRow else Color.Transparent)
                            .clickable { state.selectedLayerIndex = i; bump(); layersOpen = false }
                    )
                }
            }
        }
    }

    if (exportOpen) {
        ModalBottomSheet(
            onDismissRequest = { exportOpen = false },
            sheetState = exportSheetState,
            sheetShape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            sheetContentColor = C.panel
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text("Export", color = MaterialTheme.colorScheme.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                HorizontalDivider(color = C.divider, modifier = Modifier.padding(vertical = 8.dp))

                // Current frame PNG
                ListItem(
                    headlineContent = { Text("Current Frame (PNG)", color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp) },
                    supportingContent = { Text("Export the currently selected frame as PNG", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth().background(Color.Transparent).clickable {
                        exportCurrentFramePng(context, state, scope) { msg ->
                            exportStatus = msg
                            exportOpen = false
                        }
                    }
                )
                HorizontalDivider(color = C.divider)

                // All frames as PNG
                ListItem(
                    headlineContent = { Text("All Frames (PNG)", color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp) },
                    supportingContent = { Text("Export each frame as a separate PNG file", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth().background(Color.Transparent).clickable {
                        exportPngFrames(context, state, scope) { msg ->
                            exportStatus = msg
                            exportOpen = false
                        }
                    }
                )
                HorizontalDivider(color = C.divider)

                // Sprite sheet PNG
                ListItem(
                    headlineContent = { Text("Sprite Sheet (PNG)", color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp) },
                    supportingContent = { Text("All frames in a horizontal strip", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth().background(Color.Transparent).clickable {
                        exportPngSheet(context, state, scope) { msg ->
                            exportStatus = msg
                            exportOpen = false
                        }
                    }
                )
                HorizontalDivider(color = C.divider)

                // GIF animation
                ListItem(
                    headlineContent = { Text("Animation (GIF)", color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp) },
                    supportingContent = { Text("Animated GIF with frame delays", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth().background(Color.Transparent).clickable {
                        exportGif(context, state, scope) { msg ->
                            exportStatus = msg
                            exportOpen = false
                        }
                    }
                )

                if (exportStatus.isNotBlank()) {
                    HorizontalDivider(color = C.divider, modifier = Modifier.padding(vertical = 8.dp))
                    Text(exportStatus, color = C.statusText, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            }
        }
    }
}

// ===== Top-level export functions (called from EditorScreen) =====

private fun exportGif(context: android.content.Context, state: com.fengeon.resprite_desktop.commands.EditorState, scope: kotlinx.coroutines.CoroutineScope, onResult: (String) -> Unit) {
    scope.launch {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val w = state.canvasWidth
                val h = state.canvasHeight
                val fps = state.document.frameRate.coerceIn(1, 60)
                val frames = ArrayList<IntArray>(state.document.frameDatas.size)
                val delays = ArrayList<Int>(state.document.frameDatas.size)
                for (i in state.document.frameDatas.indices) {
                    val bmp = Compositor.flatten(state, i)
                    val px = IntArray(w * h)
                    bmp.getPixels(px, 0, w, 0, 0, w, h)
                    bmp.recycle()
                    frames.add(px)
                    val mult = state.document.frameDatas[i].duration.coerceAtLeast(1)
                    delays.add(((100 * mult) / fps).coerceAtLeast(2))
                }
                val gif = GifEncoder.encode(w, h, frames, delays)
                val dir = File(context.getExternalFilesDir(null), "exports")
                dir.mkdirs()
                val file = File(dir, "animation_${System.currentTimeMillis()}.gif")
                FileOutputStream(file).use { it.write(gif) }
                "GIF exported: ${file.name} (${gif.size / 1024} KB, ${frames.size} frames)"
            }
        }
        onResult(result.getOrElse { "Export failed: ${it.message}" })
    }
}

private fun exportPngSheet(context: android.content.Context, state: com.fengeon.resprite_desktop.commands.EditorState, scope: kotlinx.coroutines.CoroutineScope, onResult: (String) -> Unit) {
    scope.launch {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val w = state.canvasWidth
                val h = state.canvasHeight
                val count = state.document.frameDatas.size
                val sheet = Bitmap.createBitmap(w * count, h, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(sheet)
                for (i in 0 until count) {
                    val frame = Compositor.flatten(state, i)
                    canvas.drawBitmap(frame, (i * w).toFloat(), 0f, null)
                    frame.recycle()
                }
                val dir = File(context.getExternalFilesDir(null), "exports")
                dir.mkdirs()
                val file = File(dir, "sheet_${System.currentTimeMillis()}.png")
                FileOutputStream(file).use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
                sheet.recycle()
                "Sprite sheet exported: ${file.name} (${file.length() / 1024} KB, $count frames)"
            }
        }
        onResult(result.getOrElse { "Export failed: ${it.message}" })
    }
}

private fun exportPngFrames(context: android.content.Context, state: com.fengeon.resprite_desktop.commands.EditorState, scope: kotlinx.coroutines.CoroutineScope, onResult: (String) -> Unit) {
    scope.launch {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val w = state.canvasWidth
                val h = state.canvasHeight
                val count = state.document.frameDatas.size
                val dir = File(context.getExternalFilesDir(null), "exports")
                dir.mkdirs()
                for (i in 0 until count) {
                    val frame = Compositor.flatten(state, i)
                    val file = File(dir, "frame_${i}_${System.currentTimeMillis()}.png")
                    FileOutputStream(file).use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    frame.recycle()
                }
                "$count PNG frames exported to exports/ folder"
            }
        }
        onResult(result.getOrElse { "Export failed: ${it.message}" })
    }
}

private fun exportCurrentFramePng(context: android.content.Context, state: com.fengeon.resprite_desktop.commands.EditorState, scope: kotlinx.coroutines.CoroutineScope, onResult: (String) -> Unit) {
    scope.launch {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val w = state.canvasWidth
                val h = state.canvasHeight
                val frame = Compositor.flatten(state, state.selectedFrameIndex)
                val dir = File(context.getExternalFilesDir(null), "exports")
                dir.mkdirs()
                val file = File(dir, "frame_${state.selectedFrameIndex}_${System.currentTimeMillis()}.png")
                FileOutputStream(file).use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
                frame.recycle()
                "Current frame exported: ${file.name}"
            }
        }
        onResult(result.getOrElse { "Export failed: ${it.message}" })
    }
}
