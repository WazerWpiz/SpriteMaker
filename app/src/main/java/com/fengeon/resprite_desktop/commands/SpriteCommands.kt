package com.fengeon.resprite_desktop.commands

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import com.fengeon.resprite_desktop.model.Bounds
import com.fengeon.resprite_desktop.model.CellData
import com.fengeon.resprite_desktop.model.Point
import com.fengeon.resprite_desktop.model.Size
import java.util.UUID

/** Mirrors SpriteFlipCmd, SpriteResizeCmd, SwapSpriteColorCmd. */

class SpriteFlipCmd(
    private val horizontal: Boolean
) : Command {
    override val label: String = if (horizontal) "Flip Sprite H" else "Flip Sprite V"

    private val before = HashMap<String, Bitmap>()

    override fun apply(state: EditorState) {
        val m = Matrix().apply {
            if (horizontal) setScale(-1f, 1f) else setScale(1f, -1f)
        }
        for ((id, bmp) in state.cellBitmaps) {
            before[id] = bmp.copy(Bitmap.Config.ARGB_8888, false)
            val flipped = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
            Canvas(flipped).drawBitmap(bmp, m, Paint(Paint.FILTER_BITMAP_FLAG))
            state.cellBitmaps[id] = flipped
        }
    }

    override fun revert(state: EditorState) {
        for ((id, bmp) in before) state.cellBitmaps[id] = bmp
        before.clear()
    }
}

class SpriteResizeCmd(
    private val newSize: Size
) : Command {
    override val label: String = "Sprite Resize ${newSize.width}x${newSize.height}"

    private var oldCanvas: Size? = null
    private val before = HashMap<String, Bitmap>()

    override fun apply(state: EditorState) {
        oldCanvas = state.document.canvasSize
        for ((id, bmp) in state.cellBitmaps) {
            before[id] = bmp.copy(Bitmap.Config.ARGB_8888, false)
            val scaled = Bitmap.createScaledBitmap(bmp, newSize.width, newSize.height, true)
            state.cellBitmaps[id] = scaled
        }
        state.setCanvasSize(newSize)
    }

    override fun revert(state: EditorState) {
        oldCanvas?.let { state.setCanvasSize(it) }
        for ((id, bmp) in before) state.cellBitmaps[id] = bmp
        before.clear()
    }
}

class CanvasResizeCmd(
    private val newSize: Size
) : Command {
    override val label: String = "Canvas Resize ${newSize.width}x${newSize.height}"

    private var oldCanvas: Size? = null
    private val before = HashMap<String, Bitmap>()

    override fun apply(state: EditorState) {
        oldCanvas = state.document.canvasSize
        for ((id, bmp) in state.cellBitmaps) {
            before[id] = bmp.copy(Bitmap.Config.ARGB_8888, false)
            val out = Bitmap.createBitmap(newSize.width, newSize.height, Bitmap.Config.ARGB_8888)
            Canvas(out).drawBitmap(bmp, 0f, 0f, null)
            state.cellBitmaps[id] = out
        }
        state.setCanvasSize(newSize)
    }

    override fun revert(state: EditorState) {
        oldCanvas?.let { state.setCanvasSize(it) }
        for ((id, bmp) in before) state.cellBitmaps[id] = bmp
        before.clear()
    }
}

class CanvasFlipCmd(private val horizontal: Boolean) : Command {
    override val label: String = "Canvas Flip"
    private val before = HashMap<String, Bitmap>()

    override fun apply(state: EditorState) {
        val m = Matrix().apply { if (horizontal) setScale(-1f, 1f) else setScale(1f, -1f) }
        for ((id, bmp) in state.cellBitmaps) {
            before[id] = bmp.copy(Bitmap.Config.ARGB_8888, false)
            val out = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
            Canvas(out).drawBitmap(bmp, m, Paint(Paint.FILTER_BITMAP_FLAG))
            state.cellBitmaps[id] = out
        }
    }

    override fun revert(state: EditorState) {
        for ((id, bmp) in before) state.cellBitmaps[id] = bmp
        before.clear()
    }
}

/** Adds a blank cell to the layer at the target frame index. */
class CreateCellCmd(
    private val layerIndex: Int,
    private val frameIndex: Int,
    private val size: Size,
    private val origin: Point = Point()
) : Command {
    override val label: String = "Create Cell"
    private var insertedId: String? = null

    override fun apply(state: EditorState) {
        val id = UUID.randomUUID().toString()
        insertedId = id
        state.mutateLayer(layerIndex) { layer ->
            val cells = layer.cells.toMutableList()
            while (cells.size < frameIndex) cells.add(CellData(id = UUID.randomUUID().toString()))
            val cell = CellData(id = id, bounds = Bounds(origin, size))
            if (frameIndex < cells.size) cells[frameIndex] = cell else cells.add(cell)
            layer.copy(cells = cells)
        }
        state.cellBitmaps[id] = Bitmap.createBitmap(
            size.width.coerceAtLeast(1),
            size.height.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
    }

    override fun revert(state: EditorState) {
        val id = insertedId ?: return
        state.cellBitmaps.remove(id)
        state.mutateLayer(layerIndex) { layer ->
            layer.copy(cells = layer.cells.filterNot { it.id == id })
        }
        insertedId = null
    }
}
