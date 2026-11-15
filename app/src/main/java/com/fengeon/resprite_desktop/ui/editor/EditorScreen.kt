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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.Gradient
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Undo
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
import androidx.compose.runtime.rememberCoroutineScope
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
import com.fengeon.resprite_desktop.commands.DeleteLayerCmd
import com.fengeon.resprite_desktop.commands.EditorState
import com.fengeon.resprite_desktop.commands.Eyedropper
import com.fengeon.resprite_desktop.commands.FillCmd
import com.fengeon.resprite_desktop.commands.MoveCellCmd
import com.fengeon.resprite_desktop.commands.PaintStrokeCmd
import com.fengeon.resprite_desktop.commands.Shape
import com.fengeon.resprite_desktop.commands.ShapeCmd
import com.fengeon.resprite_desktop.commands.UndoManager
import com.fengeon.resprite_desktop.format.RespriteFile
import com.fengeon.resprite_desktop.model.Palette
import com.fengeon.resprite_desktop.model.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    val state = remember { EditorState() }
    val undo = remember { UndoManager() }
    var tool by remember { mutableStateOf(Tool.Pencil) }
    var primary by remember { mutableStateOf(0xFF000000L.toInt()) }
    var secondary by remember { mutableStateOf(0xFFFFFFFFL.toInt()) }
    var palette by remember { mutableStateOf(Palette.COMMODORE_64) }
    var version by remember { mutableStateOf(0L) }
    var statusText by remember { mutableStateOf("ready") }
    var layersOpen by remember { mutableStateOf(false) }
    var zoomPct by remember { mutableStateOf(100) }

    fun bump() { version = System.nanoTime() }
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
                state.document = contents.document
                state.cellBitmaps.clear()
                state.cellBitmaps.putAll(bitmaps)
                undo.clear()
                bump()
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
                    IconButton(onClick = { undo.undo(state); bump() }, enabled = undo.canUndo) {
                        Icon(Icons.Filled.Undo, "Undo", tint = if (undo.canUndo) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { undo.redo(state); bump() }, enabled = undo.canRedo) {
                        Icon(Icons.Filled.Redo, "Redo", tint = if (undo.canRedo) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { openLauncher.launch(arrayOf("*/*")) }) {
                        Icon(Icons.Filled.FolderOpen, "Open", tint = MaterialTheme.colorScheme.onSurface)
                    }
                    IconButton(onClick = { saveLauncher.launch("sprite.resprite") }) {
                        Icon(Icons.Filled.Save, "Save", tint = MaterialTheme.colorScheme.onSurface)
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
                                if (tool == t) C.toolActiveBg else Color.Transparent,
                                RoundedCornerShape(6.dp)
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
                onPrimaryChange = { primary = it },
                onSecondaryChange = { secondary = it },
                onPaletteChange = { palette = it; primary = it.colors.firstOrNull() ?: primary }
            )

            Column(modifier = Modifier.fillMaxWidth().weight(1f).padding(4.dp)) {
                    val composite = remember(version, state.selectedFrameIndex) { composeLayers(state) }
                    CanvasView(
                        composite = composite,
                        tool = tool,
                        onStrokeStart = { x, y ->
                            when (tool) {
                                Tool.Pencil, Tool.Eraser -> {
                                    undo.execute(state, PaintStrokeCmd(activeCellId(), listOf(x to y),
                                        if (tool == Tool.Eraser) 0 else primary, tool == Tool.Eraser, 1)); bump()
                                }
                                Tool.Fill -> { undo.execute(state, FillCmd(activeCellId(), x, y, primary)); bump() }
                                Tool.Eyedropper -> { primary = Eyedropper.sample(state, x, y); bump() }
                                else -> {}
                            }
                        },
                        onStrokeMove = { x0, y0, x1, y1 ->
                            if (tool == Tool.Pencil || tool == Tool.Eraser) {
                                undo.execute(state, PaintStrokeCmd(activeCellId(), listOf(x0 to y0, x1 to y1),
                                    if (tool == Tool.Eraser) 0 else primary, tool == Tool.Eraser, 1)); bump()
                            }
                        },
                        onStrokeEnd = { sx, sy, ex, ey ->
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
                                    undo.execute(state, ShapeCmd(activeCellId(), shape, sx, sy, ex, ey, primary, 1, false)); bump()
                                }
                            }
                        },
                        onZoomChange = { z -> zoomPct = (z * 100).toInt() },
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
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                    state.document.frameDatas.forEachIndexed { i, _ ->
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(
                                    if (i == state.selectedFrameIndex) C.frameSelectedBg else C.frameIdle,
                                    RoundedCornerShape(4.dp)
                                )
                                .clickable { state.selectedFrameIndex = i; bump() },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("$i", color = if (i == state.selectedFrameIndex) C.toolIconActive else MaterialTheme.colorScheme.onSurface, fontSize = 12.sp)
                        }
                    }
                    IconButton(onClick = {
                        undo.execute(state, CreateFrameCmd(state.document.frameDatas.size, state.document.layerDatas.size, Size(state.canvasWidth, state.canvasHeight))); bump()
                    }, modifier = Modifier.size(36.dp)) { Icon(Icons.Filled.Add, "Add frame", tint = MaterialTheme.colorScheme.onSurface) }
                }
                IconButton(onClick = { layersOpen = true }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.List, "Layers", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp))
                }
                Text("${state.canvasWidth}×${state.canvasHeight}  ${zoomPct}%", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                Text(statusText, color = C.statusText, fontSize = 11.sp)
            }
        }
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
}

private fun composeLayers(state: EditorState): Bitmap {
    val w = state.canvasWidth
    val h = state.canvasHeight
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(out)
    val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
    for (layer in state.document.layerDatas) {
        if (!layer.contentVisible) continue
        val cell = layer.cells.getOrNull(state.selectedFrameIndex) ?: continue
        val bmp = state.cellBitmaps[cell.id] ?: continue
        paint.alpha = (cell.opacity.coerceIn(0f, 1f) * 255f).toInt()
        c.drawBitmap(bmp, cell.bounds.origin.x.toFloat(), cell.bounds.origin.y.toFloat(), paint)
    }
    return out
}
