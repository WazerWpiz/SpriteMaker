package com.fengeon.resprite_desktop.commands

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode

/**
 * One pencil/eraser stroke against a cell bitmap. The whole drag is a single
 * Command, so undo removes the entire stroke in one step.
 *
 * Lifecycle:
 *  - apply(): first execute (blank/current cell) or redo (cell already restored
 *    to its pre-stroke pixels by revert()). Captures the snapshot if missing, then
 *    draws the whole stroke. It never clears the cell: other marks must survive.
 *  - extend(): during the drag, draws only the new segment. This keeps a long
 *    stroke linear instead of O(k^2) with a full-canvas clear per move.
 *  - revert(): restores the snapshot and releases it, so commands parked on the
 *    redo stack do not pin one full-canvas bitmap each.
 */
class PaintStrokeCmd(
    private val cellId: String,
    initialPoints: List<Pair<Int, Int>>,
    color: Int,
    private val eraser: Boolean = false,
    private val brushSize: Int = 1
) : Command, NoOpAware {
    override val label: String = if (eraser) "Eraser" else "Paint"

    // Held in a differently-named field so `color = ...` inside Paint().apply {}
    // cannot be confused with the constructor parameter.
    private val ink: Int = color
    private var snapshot: Bitmap? = null
    private val points: MutableList<Pair<Int, Int>> = initialPoints.toMutableList()

    override var effective: Boolean = false
        private set

    override fun apply(state: EditorState) {
        effective = false
        val target = state.cellBitmaps[cellId] ?: return
        ensureSnapshot(target)
        replayStroke(target)
        effective = true
    }

    /** Add one more point to an in-progress stroke without a new undo entry. */
    fun extend(state: EditorState, x: Int, y: Int) {
        val target = state.cellBitmaps[cellId] ?: return
        ensureSnapshot(target)
        val prev = points.lastOrNull()
        points.add(x to y)
        val canvas = Canvas(target)
        if (prev == null) dab(canvas, x, y) else segment(canvas, prev.first, prev.second, x, y)
        effective = true
    }

    private fun ensureSnapshot(target: Bitmap): Bitmap =
        snapshot ?: target.copy(Bitmap.Config.ARGB_8888, false).also { snapshot = it }

    private fun replayStroke(target: Bitmap) {
        if (points.isEmpty()) return
        val canvas = Canvas(target)
        if (points.size == 1) {
            dab(canvas, points[0].first, points[0].second)
            return
        }
        for (i in 0 until points.size - 1) {
            val (x0, y0) = points[i]
            val (x1, y1) = points[i + 1]
            segment(canvas, x0, y0, x1, y1)
        }
    }

    // A single tap covers the brush cell exactly. Squares (not circles) keep pixel
    // art crisp now that anti-aliasing is off, and match the old 1px behaviour when
    // brushSize == 1.
    private fun dab(canvas: Canvas, x: Int, y: Int) {
        val b = brushSize.coerceAtLeast(1)
        val fill = Paint().apply {
            style = Paint.Style.FILL
            color = ink
            if (eraser) xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
        canvas.drawRect(x.toFloat(), y.toFloat(), (x + b).toFloat(), (y + b).toFloat(), fill)
    }

    private fun segment(canvas: Canvas, x0: Int, y0: Int, x1: Int, y1: Int) {
        val stroke = Paint().apply {
            color = ink
            style = Paint.Style.STROKE
            strokeWidth = brushSize.toFloat()
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            if (eraser) xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
        // +0.5 centres the stroke on the pixel instead of straddling its corner.
        canvas.drawLine(x0 + 0.5f, y0 + 0.5f, x1 + 0.5f, y1 + 0.5f, stroke)
    }

    override fun revert(state: EditorState) {
        val snap = snapshot ?: return
        state.cellBitmaps[cellId] = snap.copy(Bitmap.Config.ARGB_8888, true)
        snapshot = null
    }
}
