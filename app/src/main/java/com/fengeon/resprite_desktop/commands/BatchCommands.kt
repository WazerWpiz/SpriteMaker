package com.fengeon.resprite_desktop.commands

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode

/**
 * Paints many independent pixels as ONE undo step.
 *
 * Drawing each pixel as its own PaintStrokeCmd filled the 200-entry undo stack
 * from a single call (a 64x64 clear alone would push 4096 entries) and held one
 * full-canvas snapshot per pixel. This command snapshots the cell once and paints
 * every point into it.
 *
 * Points are canvas pixels and each one covers exactly that pixel: the rect is
 * [x, x+1) x [y, y+1) with anti-aliasing off, so the result is fully opaque and
 * never bleeds into the neighbouring pixel.
 */
class PixelSetCmd(
    private val cellId: String,
    private val points: List<Pair<Int, Int>>,
    color: Int,
    private val eraser: Boolean = false
) : Command, NoOpAware {

    override val label: String = if (eraser) "Erase pixels" else "Set pixels"
    private val ink: Int = color
    private var snapshot: Bitmap? = null

    override var effective: Boolean = false
        private set

    override fun apply(state: EditorState) {
        effective = false
        val target = state.cellBitmaps[cellId] ?: return
        if (snapshot == null) snapshot = target.copy(Bitmap.Config.ARGB_8888, false)
        val canvas = Canvas(target)
        val paint = Paint().apply {
            style = Paint.Style.FILL
            isAntiAlias = false
            color = ink
            if (eraser) xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
        val w = target.width
        val h = target.height
        for ((x, y) in points) {
            if (x < 0 || y < 0 || x >= w || y >= h) continue
            canvas.drawRect(x.toFloat(), y.toFloat(), (x + 1).toFloat(), (y + 1).toFloat(), paint)
            effective = true
        }
    }

    override fun revert(state: EditorState) {
        val snap = snapshot ?: return
        state.cellBitmaps[cellId] = snap.copy(Bitmap.Config.ARGB_8888, true)
        snapshot = null
    }
}

/** Clears an entire cell as one undo step. */
class ClearCellCmd(private val cellId: String) : Command, NoOpAware {

    override val label: String = "Clear layer"
    private var snapshot: Bitmap? = null

    override var effective: Boolean = false
        private set

    override fun apply(state: EditorState) {
        effective = false
        val target = state.cellBitmaps[cellId] ?: return
        if (snapshot == null) snapshot = target.copy(Bitmap.Config.ARGB_8888, false)
        target.eraseColor(0)
        effective = true
    }

    override fun revert(state: EditorState) {
        val snap = snapshot ?: return
        state.cellBitmaps[cellId] = snap.copy(Bitmap.Config.ARGB_8888, true)
        snapshot = null
    }
}
