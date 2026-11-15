package com.fengeon.resprite_desktop.commands

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode

/**
 * One pencil/eraser stroke against a cell bitmap. The whole drag is a single
 * Command, so undo removes the entire stroke in one step instead of one segment
 * at a time. A snapshot is captured before the first pixel; every extension
 * redraws from that snapshot so the stroke never double-draws.
 */
class PaintStrokeCmd(
    private val cellId: String,
    initialPoints: List<Pair<Int, Int>>,
    private val color: Int,
    private val eraser: Boolean = false,
    private val brushSize: Int = 1
) : Command {
    override val label: String = if (eraser) "Eraser" else "Paint"
    private var snapshot: Bitmap? = null
    private val points: MutableList<Pair<Int, Int>> = initialPoints.toMutableList()

    override fun apply(state: EditorState) = paint(state, rebuild = false)

    /** Add one more point to an in-progress stroke without a new undo entry. */
    fun extend(state: EditorState, x: Int, y: Int) {
        points.add(x to y)
        paint(state, rebuild = true)
    }

    private fun paint(state: EditorState, rebuild: Boolean) {
        val target = state.cellBitmaps[cellId] ?: return
        val snap = snapshot ?: target.copy(Bitmap.Config.ARGB_8888, false).also { snapshot = it }
        val canvas = Canvas(target)
        if (rebuild) {
            canvas.drawColor(0, PorterDuff.Mode.CLEAR)
            canvas.drawBitmap(snap, 0f, 0f, null)
        }
        if (points.size == 1) {
            val (px, py) = points[0]
            val b = brushSize.coerceAtLeast(1)
            val fill = Paint().apply {
                style = Paint.Style.FILL
                this.color = color
                if (eraser) xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            }
            canvas.drawRect(px.toFloat(), py.toFloat(), (px + b).toFloat(), (py + b).toFloat(), fill)
        } else {
            val stroke = Paint().apply {
                this.color = color
                style = Paint.Style.STROKE
                strokeWidth = brushSize.toFloat()
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                if (eraser) xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            }
            for (i in 0 until points.size - 1) {
                val (x0, y0) = points[i]
                val (x1, y1) = points[i + 1]
                canvas.drawLine(x0 + 0.5f, y0 + 0.5f, x1 + 0.5f, y1 + 0.5f, stroke)
            }
        }
    }

    override fun revert(state: EditorState) {
        val snap = snapshot ?: return
        state.cellBitmaps[cellId] = snap.copy(Bitmap.Config.ARGB_8888, true)
    }
}