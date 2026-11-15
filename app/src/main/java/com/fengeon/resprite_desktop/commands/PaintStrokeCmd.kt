package com.fengeon.resprite_desktop.commands

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode

/**
 * One pencil/eraser/fill stroke against a cell bitmap. Captures a snapshot
 * on first point so undo restores exactly the pixels before the stroke.
 */
class PaintStrokeCmd(
    private val cellId: String,
    private val points: List<Pair<Int, Int>>,
    private val color: Int,
    private val eraser: Boolean = false,
    private val brushSize: Int = 1
) : Command {
    override val label: String = if (eraser) "Eraser" else "Paint"
    private var snapshot: Bitmap? = null

    override fun apply(state: EditorState) {
        val target = state.cellBitmaps[cellId] ?: return
        if (snapshot == null) snapshot = target.copy(Bitmap.Config.ARGB_8888, false)
        val canvas = Canvas(target)
        val paint = Paint().apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = brushSize.toFloat()
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            if (eraser) xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
        if (points.size == 1) {
            val (x, y) = points[0]
            if (eraser) {
                val er = Paint().apply {
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                    style = Paint.Style.FILL
                }
                val r = (brushSize / 2f).coerceAtLeast(0.5f)
                canvas.drawCircle(x.toFloat(), y.toFloat(), r, er)
            } else {
                val b = brushSize.coerceAtLeast(1)
                canvas.drawRect(
                    x.toFloat(), y.toFloat(), (x + b).toFloat(), (y + b).toFloat(),
                    paint.apply { style = Paint.Style.FILL }
                )
            }
        } else {
            for (i in 0 until points.size - 1) {
                val (x0, y0) = points[i]
                val (x1, y1) = points[i + 1]
                canvas.drawLine(x0.toFloat(), y0.toFloat(), x1.toFloat(), y1.toFloat(), paint)
            }
        }
    }

    override fun revert(state: EditorState) {
        val snap = snapshot ?: return
        state.cellBitmaps[cellId] = snap.copy(Bitmap.Config.ARGB_8888, true)
    }
}
