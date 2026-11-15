package com.fengeon.resprite_desktop.ui.editor

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen() {
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
    var strokeStartX by remember { mutableStateOf(-1) }
    var strokeStartY by remember { mutableStateOf(-1) }

    fun bump() { version = System.nanoTime() }

    fun activeCellId(): String = state.ensureCell(state.selectedLayerIndex, state.selectedFrameIndex)

    val openLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)!!.use { RespriteFile.read(it) }
                }
            }
            result.onSuccess { contents ->
                val bitmaps = contents.cells.mapNotNull { (id, bytes) ->
                    runCatching {
                        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }.getOrNull()?.let { id to it }
                }.toMap()
                state.document = contents.document
                state.cellBitmaps.clear()
                state.cellBitmaps.putAll(bitmaps)
                undo.clear()
                bump()
                statusText = "opened ${contents.rootDir}"
            }.onFailure { statusText = "open failed: ${it.message}" }
        }
    }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
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
            statusText = if (ok.isSuccess) "saved" else "save failed: ${ok.exceptionOrNull()?.message}"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Resprite") },
                actions = {
                    IconButton(onClick = { undo.undo(state); bump() }, enabled = undo.canUndo) {
                        Icon(Icons.Filled.Undo, contentDescription = "Undo")
                    }
                    IconButton(onClick = { undo.redo(state); bump() }, enabled = undo.canRedo) {
                        Icon(Icons.Filled.Redo, contentDescription = "Redo")
                    }
                    IconButton(onClick = { openLauncher.launch(arrayOf("*/*")) }) {
                        Icon(Icons.Filled.FolderOpen, contentDescription = "Open")
                    }
                    IconButton(onClick = { saveLauncher.launch("sprite.resprite") }) {
                        Icon(Icons.Filled.Save, contentDescription = "Save")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .background(Color(0xFF202020))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Tool.values().forEach { t ->
                    Button(
                        onClick = { tool = t },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp)
                    ) {
                        Text(if (tool == t) "[${t.name}]" else t.name, color = Color.White)
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

            Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                Column(modifier = Modifier.weight(1f).padding(4.dp)) {
                    val composite = remember(version, state.selectedFrameIndex) { composeLayers(state) }
                    CanvasView(
                        composite = composite,
                        tool = tool,
                        onStrokeStart = { x, y ->
                            strokeStartX = x; strokeStartY = y
                            when (tool) {
                                Tool.Pencil, Tool.Eraser -> {
                                    val cmd = PaintStrokeCmd(
                                        cellId = activeCellId(),
                                        points = listOf(x to y),
                                        color = if (tool == Tool.Eraser) 0 else primary,
                                        eraser = tool == Tool.Eraser,
                                        brushSize = 1
                                    )
                                    undo.execute(state, cmd); bump()
                                }
                                Tool.Fill -> {
                                    undo.execute(state, FillCmd(activeCellId(), x, y, primary)); bump()
                                }
                                Tool.Eyedropper -> {
                                    primary = Eyedropper.sample(state, x, y); bump()
                                }
                                else -> { }
                            }
                        },
                        onStrokeMove = { x0, y0, x1, y1 ->
                            if (tool == Tool.Pencil || tool == Tool.Eraser) {
                                val cmd = PaintStrokeCmd(
                                    cellId = activeCellId(),
                                    points = listOf(x0 to y0, x1 to y1),
                                    color = if (tool == Tool.Eraser) 0 else primary,
                                    eraser = tool == Tool.Eraser,
                                    brushSize = 1
                                )
                                undo.execute(state, cmd); bump()
                            }
                        },
                        onStrokeEnd = { sx, sy, ex, ey ->
                            if (tool == Tool.Move) {
                                val dx = ex - sx
                                val dy = ey - sy
                                if (dx != 0 || dy != 0) {
                                    val cellId = state.cellIdFor(
                                        state.selectedLayerIndex,
                                        state.selectedFrameIndex
                                    )
                                    if (cellId != null) {
                                        undo.execute(state, MoveCellCmd(cellId, dx, dy))
                                        bump()
                                    }
                                }
                            } else {
                                val shape = when (tool) {
                                    Tool.Line -> Shape.Line
                                    Tool.Rect -> Shape.Rect
                                    Tool.Ellipse -> Shape.Ellipse
                                    else -> null
                                }
                                if (shape != null) {
                                    undo.execute(
                                        state,
                                        ShapeCmd(
                                            cellId = activeCellId(),
                                            shape = shape,
                                            x0 = sx, y0 = sy, x1 = ex, y1 = ey,
                                            color = primary,
                                            strokeWidth = 1,
                                            filled = false
                                        )
                                    )
                                    bump()
                                }
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                Column(modifier = Modifier.size(width = 220.dp, height = 0.dp).fillMaxSize().padding(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Layers", color = Color.White, modifier = Modifier.weight(1f))
                        IconButton(onClick = {
                            undo.execute(state, CreateLayerCmd(
                                atIndex = state.document.layerDatas.size,
                                frameCount = state.document.frameDatas.size
                            ))
                            bump()
                        }) { Icon(Icons.Filled.Add, "Add layer") }
                        IconButton(onClick = {
                            if (state.document.layerDatas.size > 1) {
                                undo.execute(state, DeleteLayerCmd(state.selectedLayerIndex))
                                bump()
                            }
                        }) { Icon(Icons.Filled.Delete, "Delete layer") }
                    }
                    Divider()
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        itemsIndexed(state.document.layerDatas) { i, layer ->
                            ListItem(
                                headlineContent = { Text(layer.name, color = Color.White) },
                                trailingContent = { Text(if (layer.contentVisible) "on" else "off", color = Color.White) },
                                modifier = Modifier
                                    .background(
                                        if (i == state.selectedLayerIndex) Color(0xFF404040) else Color.Transparent
                                    )
                                    .clickable {
                                        state.selectedLayerIndex = i
                                        bump()
                                    }
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                    state.document.frameDatas.forEachIndexed { i, _ ->
                        Button(
                            onClick = { state.selectedFrameIndex = i; bump() },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp)
                        ) {
                            Text(if (i == state.selectedFrameIndex) "[$i]" else "$i", color = Color.White)
                        }
                    }
                    IconButton(onClick = {
                        undo.execute(state, CreateFrameCmd(
                            atIndex = state.document.frameDatas.size,
                            layerCount = state.document.layerDatas.size,
                            canvasSize = Size(state.canvasWidth, state.canvasHeight)
                        ))
                        bump()
                    }) { Icon(Icons.Filled.Add, "Add frame") }
                }
                Text(statusText, color = Color(0xFF80CBC4))
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
