package com.fengeon.resprite_desktop.commands

import android.graphics.Bitmap
import com.fengeon.resprite_desktop.model.CellData
import com.fengeon.resprite_desktop.model.Document
import com.fengeon.resprite_desktop.model.FrameData
import com.fengeon.resprite_desktop.model.LayerData
import com.fengeon.resprite_desktop.model.PaletteData
import com.fengeon.resprite_desktop.model.Size
import java.util.UUID

/**
 * Live editor state. The Document model is the serializable view; this class
 * carries the mutable Bitmaps keyed by cell id, plus the selected layer/frame.
 * Commands read/write here, never directly to the document file.
 */
class EditorState(
    doc: Document = defaultDocument(),
    cells: Map<String, Bitmap> = emptyMap()
) {
    var document: Document = doc
    val cellBitmaps: MutableMap<String, Bitmap> = LinkedHashMap(cells)

    var selectedLayerIndex: Int = 0
    var selectedFrameIndex: Int = 0
    var primaryColor: Int = 0xFF000000.toInt()
    var secondaryColor: Int = 0xFFFFFFFF.toInt()

    val canvasWidth: Int get() = document.canvasSize.width
    val canvasHeight: Int get() = document.canvasSize.height

    fun cellIdFor(layerIndex: Int, frameIndex: Int): String? {
        val layer = document.layerDatas.getOrNull(layerIndex) ?: return null
        return layer.cells.getOrNull(frameIndex)?.id
    }

    fun bitmapFor(layerIndex: Int, frameIndex: Int): Bitmap? {
        val id = cellIdFor(layerIndex, frameIndex) ?: return null
        return cellBitmaps[id]
    }

    fun ensureCell(layerIndex: Int, frameIndex: Int): String {
        require(layerIndex in document.layerDatas.indices) { "no layer at index $layerIndex" }
        require(frameIndex >= 0) { "frame index must be >= 0" }
        // Re-read the live document each iteration: mutateLayer() replaces the
        // layer list, so a captured LayerData snapshot would never update.
        var guard = 0
        while (document.layerDatas[layerIndex].cells.size <= frameIndex) {
            if (guard++ > MAX_PADDED_CELLS) {
                error("ensureCell would pad more than $MAX_PADDED_CELLS cells")
            }
            val newCell = CellData(id = UUID.randomUUID().toString())
            mutateLayer(layerIndex) { it.copy(cells = it.cells + newCell) }
        }
        val layer = document.layerDatas[layerIndex]
        val cell = layer.cells[frameIndex]
        ensureBitmap(cell)
        return cell.id
    }

    /**
     * Guarantees a backing bitmap exists for [cell]. ensureCell() only proves the
     * cell is declared in the document; nothing created its pixels, so on a fresh
     * document every paint command found no bitmap and silently did nothing. A new
     * cell gets a canvas-sized bitmap and its bounds are stretched to match, which
     * is what the original Resprite layout does for a full-canvas cell.
     */
    fun ensureBitmap(cell: CellData): Bitmap {
        cellBitmaps[cell.id]?.let { return it }
        val w = canvasWidth.coerceAtLeast(1)
        val h = canvasHeight.coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        cellBitmaps[cell.id] = bmp
        if (cell.bounds.size.width <= 0 || cell.bounds.size.height <= 0) {
            val layers = document.layerDatas.toMutableList()
            val idx = layers.indexOfFirst { l -> l.cells.any { it.id == cell.id } }
            if (idx >= 0) {
                val cells = layers[idx].cells.toMutableList()
                val ci = cells.indexOfFirst { it.id == cell.id }
                if (ci >= 0) {
                    cells[ci] = cells[ci].copy(bounds = cells[ci].bounds.copy(size = Size(w, h)))
                    layers[idx] = layers[idx].copy(cells = cells)
                    document = document.copy(layerDatas = layers)
                }
            }
        }
        return bmp
    }

    fun mutateLayer(index: Int, fn: (LayerData) -> LayerData) {
        val layers = document.layerDatas.toMutableList()
        layers[index] = fn(layers[index])
        document = document.copy(layerDatas = layers)
    }

    fun mutateFrame(index: Int, fn: (FrameData) -> FrameData) {
        val frames = document.frameDatas.toMutableList()
        frames[index] = fn(frames[index])
        document = document.copy(frameDatas = frames)
    }

    fun setCanvasSize(size: Size) {
        document = document.copy(canvasSize = size)
    }

    fun setPalette(palette: PaletteData) {
        document = document.copy(paletteData = palette)
    }

    companion object {
        /** Hard ceiling on implicit cell padding, so a corrupt frame count can't hang the UI. */
        private const val MAX_PADDED_CELLS = 10_000

        fun defaultDocument(size: Size = Size(64, 64)): Document {
            val layer = LayerData(id = UUID.randomUUID().toString(), name = "Layer")
            val frame = FrameData(id = UUID.randomUUID().toString())
            val cell = CellData(id = UUID.randomUUID().toString())
            return Document(
                canvasSize = size,
                frameCount = 1,
                frameDatas = listOf(frame),
                layerDatas = listOf(layer.copy(cells = listOf(cell)))
            )
        }
    }
}
