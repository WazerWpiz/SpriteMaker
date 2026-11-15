package com.fengeon.resprite_desktop.commands

import android.graphics.Bitmap
import com.fengeon.resprite_desktop.model.CellData
import com.fengeon.resprite_desktop.model.FrameData
import com.fengeon.resprite_desktop.model.LayerData
import java.util.UUID

/** Mirrors CreateLayerCmd, DeleteLayerCmd, DuplicateLayerCmd, MoveAndSelectLayerCmd, SetLayerPropertyCmd, ToggleInheritCmd. */

class CreateLayerCmd(
    private val atIndex: Int,
    private val frameCount: Int
) : Command {
    override val label: String = "Create Layer"
    private var insertedId: String? = null

    override fun apply(state: EditorState) {
        val id = UUID.randomUUID().toString()
        insertedId = id
        val cells = (0 until frameCount).map { CellData(id = UUID.randomUUID().toString()) }
        val layer = LayerData(id = id, name = "Layer ${state.document.layerDatas.size + 1}", cells = cells)
        val layers = state.document.layerDatas.toMutableList()
        val idx = atIndex.coerceIn(0, layers.size)
        layers.add(idx, layer)
        state.document = state.document.copy(layerDatas = layers)
        for (c in cells) {
            state.cellBitmaps[c.id] = Bitmap.createBitmap(
                state.canvasWidth.coerceAtLeast(1),
                state.canvasHeight.coerceAtLeast(1),
                Bitmap.Config.ARGB_8888
            )
        }
        state.selectedLayerIndex = idx
    }

    override fun revert(state: EditorState) {
        val id = insertedId ?: return
        val layer = state.document.layerDatas.firstOrNull { it.id == id } ?: return
        for (c in layer.cells) state.cellBitmaps.remove(c.id)
        state.document = state.document.copy(
            layerDatas = state.document.layerDatas.filterNot { it.id == id }
        )
        state.selectedLayerIndex = state.selectedLayerIndex.coerceIn(0, (state.document.layerDatas.size - 1).coerceAtLeast(0))
        insertedId = null
    }
}

class DeleteLayerCmd(private val index: Int) : Command {
    override val label: String = "Delete Layer"
    private var removed: LayerData? = null
    private val removedBitmaps = HashMap<String, Bitmap>()

    override fun apply(state: EditorState) {
        val layers = state.document.layerDatas.toMutableList()
        if (index !in layers.indices) return
        val layer = layers.removeAt(index)
        removed = layer
        for (c in layer.cells) {
            state.cellBitmaps.remove(c.id)?.let { removedBitmaps[c.id] = it }
        }
        state.document = state.document.copy(layerDatas = layers)
        state.selectedLayerIndex = state.selectedLayerIndex.coerceIn(0, (layers.size - 1).coerceAtLeast(0))
    }

    override fun revert(state: EditorState) {
        val layer = removed ?: return
        val layers = state.document.layerDatas.toMutableList()
        layers.add(index.coerceIn(0, layers.size), layer)
        state.document = state.document.copy(layerDatas = layers)
        for ((id, bmp) in removedBitmaps) state.cellBitmaps[id] = bmp
        removedBitmaps.clear()
        state.selectedLayerIndex = index
    }
}

class DuplicateLayerCmd(private val index: Int) : Command {
    override val label: String = "Duplicate Layer"
    private var insertedId: String? = null
    private val insertedBitmaps = HashMap<String, Bitmap>()

    override fun apply(state: EditorState) {
        val source = state.document.layerDatas.getOrNull(index) ?: return
        val newId = UUID.randomUUID().toString()
        insertedId = newId
        val newCells = source.cells.map { c ->
            val nid = UUID.randomUUID().toString()
            state.cellBitmaps[c.id]?.let { bmp ->
                insertedBitmaps[nid] = bmp.copy(Bitmap.Config.ARGB_8888, true)
            }
            CellData(id = nid, bounds = c.bounds, opacity = c.opacity, inherit = c.inherit)
        }
        val dup = source.copy(id = newId, name = source.name + " copy", cells = newCells)
        val layers = state.document.layerDatas.toMutableList()
        val at = (index + 1).coerceIn(0, layers.size)
        layers.add(at, dup)
        state.document = state.document.copy(layerDatas = layers)
        for ((id, bmp) in insertedBitmaps) state.cellBitmaps[id] = bmp
        state.selectedLayerIndex = at
    }

    override fun revert(state: EditorState) {
        val id = insertedId ?: return
        state.document = state.document.copy(
            layerDatas = state.document.layerDatas.filterNot { it.id == id }
        )
        for (id2 in insertedBitmaps.keys) state.cellBitmaps.remove(id2)
        insertedBitmaps.clear()
        state.selectedLayerIndex = state.selectedLayerIndex.coerceIn(0, (state.document.layerDatas.size - 1).coerceAtLeast(0))
        insertedId = null
    }
}

class MoveLayerCmd(
    private val from: Int,
    private val to: Int
) : Command {
    override val label: String = "Move Layer"

    override fun apply(state: EditorState) {
        val layers = state.document.layerDatas.toMutableList()
        if (from !in layers.indices) return
        val l = layers.removeAt(from)
        layers.add(to.coerceIn(0, layers.size), l)
        state.document = state.document.copy(layerDatas = layers)
        state.selectedLayerIndex = to.coerceIn(0, layers.size - 1)
    }

    override fun revert(state: EditorState) {
        val layers = state.document.layerDatas.toMutableList()
        if (to !in layers.indices) return
        val l = layers.removeAt(to)
        layers.add(from.coerceIn(0, layers.size), l)
        state.document = state.document.copy(layerDatas = layers)
        state.selectedLayerIndex = from.coerceIn(0, layers.size - 1)
    }
}

class SetLayerPropertyCmd(
    private val index: Int,
    private val transform: (LayerData) -> LayerData
) : Command {
    override val label: String = "Set Layer Property"
    private var previous: LayerData? = null

    override fun apply(state: EditorState) {
        previous = state.document.layerDatas.getOrNull(index) ?: return
        state.mutateLayer(index, transform)
    }

    override fun revert(state: EditorState) {
        previous?.let { state.mutateLayer(index) { _ -> it } }
    }
}

// ---- Frames ----

class CreateFrameCmd(
    private val atIndex: Int,
    private val layerCount: Int,
    private val canvasSize: com.fengeon.resprite_desktop.model.Size
) : Command {
    override val label: String = "Add Frame"
    private var insertedId: String? = null
    private val insertedCellIds = ArrayList<String>()

    override fun apply(state: EditorState) {
        val id = UUID.randomUUID().toString()
        insertedId = id
        val frames = state.document.frameDatas.toMutableList()
        val idx = atIndex.coerceIn(0, frames.size)
        frames.add(idx, FrameData(id = id))
        state.document = state.document.copy(frameDatas = frames, frameCount = frames.size)

        val layers = state.document.layerDatas.toMutableList()
        for (li in 0 until layerCount.coerceAtMost(layers.size)) {
            val layer = layers[li]
            val cell = CellData(id = UUID.randomUUID().toString())
            insertedCellIds.add(cell.id)
            val cells = layer.cells.toMutableList()
            cells.add(idx.coerceIn(0, cells.size), cell)
            layers[li] = layer.copy(cells = cells)
            state.cellBitmaps[cell.id] = Bitmap.createBitmap(
                canvasSize.width.coerceAtLeast(1),
                canvasSize.height.coerceAtLeast(1),
                Bitmap.Config.ARGB_8888
            )
        }
        state.document = state.document.copy(layerDatas = layers)
        state.selectedFrameIndex = idx
    }

    override fun revert(state: EditorState) {
        val id = insertedId ?: return
        val frames = state.document.frameDatas.toMutableList()
        val at = frames.indexOfFirst { it.id == id }
        if (at >= 0) frames.removeAt(at)
        state.document = state.document.copy(frameDatas = frames, frameCount = frames.size)
        val layers = state.document.layerDatas.map { l ->
            l.copy(cells = l.cells.filterNot { it.id in insertedCellIds })
        }
        state.document = state.document.copy(layerDatas = layers)
        for (cid in insertedCellIds) state.cellBitmaps.remove(cid)
        insertedCellIds.clear()
        state.selectedFrameIndex = state.selectedFrameIndex.coerceIn(0, (frames.size - 1).coerceAtLeast(0))
        insertedId = null
    }
}

class DeleteFrameCmd(private val index: Int) : Command {
    override val label: String = "Delete Frame"
    private var removedFrame: FrameData? = null
    private val removedCells = HashMap<Int, CellData>()
    private val removedBitmaps = HashMap<String, Bitmap>()

    override fun apply(state: EditorState) {
        if (state.document.frameDatas.size <= 1) return
        val frames = state.document.frameDatas.toMutableList()
        if (index !in frames.indices) return
        removedFrame = frames.removeAt(index)
        state.document = state.document.copy(frameDatas = frames, frameCount = frames.size)

        val layers = state.document.layerDatas.toMutableList()
        for (li in layers.indices) {
            val layer = layers[li]
            if (index < layer.cells.size) {
                val cells = layer.cells.toMutableList()
                val c = cells.removeAt(index)
                removedCells[li] = c
                state.cellBitmaps.remove(c.id)?.let { removedBitmaps[c.id] = it }
                layers[li] = layer.copy(cells = cells)
            }
        }
        state.document = state.document.copy(layerDatas = layers)
        state.selectedFrameIndex = state.selectedFrameIndex.coerceIn(0, frames.size - 1)
    }

    override fun revert(state: EditorState) {
        val f = removedFrame ?: return
        val frames = state.document.frameDatas.toMutableList()
        frames.add(index.coerceIn(0, frames.size), f)
        state.document = state.document.copy(frameDatas = frames, frameCount = frames.size)

        val layers = state.document.layerDatas.toMutableList()
        for ((li, c) in removedCells) {
            if (li < layers.size) {
                val cells = layers[li].cells.toMutableList()
                cells.add(index.coerceIn(0, cells.size), c)
                layers[li] = layers[li].copy(cells = cells)
            }
        }
        state.document = state.document.copy(layerDatas = layers)
        for ((id, bmp) in removedBitmaps) state.cellBitmaps[id] = bmp
        removedBitmaps.clear()
        state.selectedFrameIndex = index.coerceIn(0, frames.size - 1)
    }
}

class DuplicateFrameCmd(private val index: Int) : Command {
    override val label: String = "Duplicate Frame"
    private var insertedId: String? = null
    private val insertedCellIds = ArrayList<String>()
    private val insertedBitmaps = HashMap<String, Bitmap>()

    override fun apply(state: EditorState) {
        val src = state.document.frameDatas.getOrNull(index) ?: return
        val id = UUID.randomUUID().toString()
        insertedId = id
        val frames = state.document.frameDatas.toMutableList()
        frames.add(index + 1, src.copy(id = id))
        state.document = state.document.copy(frameDatas = frames, frameCount = frames.size)

        val layers = state.document.layerDatas.toMutableList()
        for (li in layers.indices) {
            val layer = layers[li]
            val srcCell = layer.cells.getOrNull(index) ?: continue
            val nid = UUID.randomUUID().toString()
            insertedCellIds.add(nid)
            state.cellBitmaps[srcCell.id]?.let { insertedBitmaps[nid] = it.copy(Bitmap.Config.ARGB_8888, true) }
            val cells = layer.cells.toMutableList()
            cells.add((index + 1).coerceIn(0, cells.size), srcCell.copy(id = nid))
            layers[li] = layer.copy(cells = cells)
        }
        state.document = state.document.copy(layerDatas = layers)
        for ((cid, bmp) in insertedBitmaps) state.cellBitmaps[cid] = bmp
        state.selectedFrameIndex = index + 1
    }

    override fun revert(state: EditorState) {
        val id = insertedId ?: return
        state.document = state.document.copy(
            frameDatas = state.document.frameDatas.filterNot { it.id == id },
            frameCount = state.document.frameDatas.size - 1
        )
        val layers = state.document.layerDatas.map { l ->
            l.copy(cells = l.cells.filterNot { it.id in insertedCellIds })
        }
        state.document = state.document.copy(layerDatas = layers)
        for (cid in insertedCellIds) state.cellBitmaps.remove(cid)
        insertedCellIds.clear()
        insertedBitmaps.clear()
        state.selectedFrameIndex = state.selectedFrameIndex.coerceIn(0, (state.document.frameDatas.size - 1).coerceAtLeast(0))
        insertedId = null
    }
}
