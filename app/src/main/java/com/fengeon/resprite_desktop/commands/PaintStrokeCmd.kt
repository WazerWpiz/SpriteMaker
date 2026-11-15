package com.fengeon.resprite_desktop.commands

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode

/**
 * One pencil/eraser stroke against a cell bitmap. The whole drag is a single
 * Command, so undo removes the entire stroke in one step instead of one segment.
 *
 * A snapshot of the cell is captured before the first pixel so undo restores the
 * exact pre-stroke image. extend() draws ONLY the new segment instead of clearing
 * and replaying the whole point history, which kept per-move cost linear (a long
 * drag used to be O(k^2) with a full-canvas clear per move).
 *
 * Memory note: the snapshot is a full-canvas copy held for as long as the command
 * sits on the undo stack. revert() drops it and redo rebuilds it, so only the undo
 * side pays. On large canvases with a deep undo history this is the next thing to
 * shrink (dirty-rect snapshot); tracked as a known limitation.
 */
class PaintStrokeCmd(
    private val cellId: String,
    initialPoints: List<Pair<Int, Int>>,
    private val color: Int,
    private val eraser: Boolean = false,
    private val brushSize: Int = 1
) : Command, NoOpAware {
    override val label: String = if (eraser) "Eraser" else "Paint"
    private var snapshot: Bitmap? = null
    private val points: MutableList<Pair<Int, Int>> = initialPoints.toMutableList()

    /** True once a real bitmap was found and painted; false for a no-op command. */
    var effective: Boolean = false
        private set

    override fun apply(state: EditorState) {
        paint(state, rebuild = true)
        effective = snapshot != null
    }

    /** Add one more point to an in-progress stroke without a new undo entry. */
    fun extend(state: EditorState, x: Int, y: Int) {
        val target = state.cellBitmaps[cellId] ?: return
        ensureSnapshot(target)
        val canvas = Canvas(target)
        val prev = points.lastOrNull()
        points.add(x to y)
        if (prev == null) dab(canvas, x, y) else segment(canvas, prev.first, prev.second, x, y)
        effective = true
    }

    private fun ensureSnapshot(target: Bitmap): Bitmap =
        snapshot ?: target.copy(Bitmap.Config.ARGB_8888, false).also { snapshot = it }

    private fun paint(state: EditorState, rebuild: Boolean) {
        val target = state.cellBitmaps[cellId] ?: return
        val snap = ensureSnapshot(target)
        val canvas = Canvas(target)
        if (rebuild) {
            // Redo path: restore the pre-stroke image, then replay the whole stroke.
            canvas.drawColor(0, PorterDuff.Mode.CLEAR)
            canvas.drawBitmap(snap, 0f, 0f, null)
            when {
                points.isEmpty() -> {}
                points.size == 1 -> dab(canvas, points[0].first, points[0].second)
                else -> for (i in 0 until points.size - 1) {
                    val (x0, y0) = points[i]
                    val (x1, y1) = points[i + 1]
                    segment(canvas, x0, y0, x1, y1)
                }
            }
        }
    }

    // A single tap covers the brush cell exactly. Squares (not circles) keep pixel
    // art crisp now that anti-aliasing is off, and match the old 1px behaviour when
    // brushSize == 1.
    private fun dab(canvas: Canvas, x: Int, y: Int) {
        val b = brushSize.coerceAtLeast(1)
        val fill = Paint().apply {
            style = Paint.Style.FILL
            color = this@PaintStrokeCmd.color
            if (eraser) xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
        canvas.drawRect(x.toFloat(), y.toFloat(), (x + b).toFloat(), (y + b).toFloat(), fill)
    }

    private fun segment(canvas: Canvas, x0: Int, y0: Int, x1: Int, y1: Int) {
        val stroke = Paint().apply {
            color = this@PaintStrokeCmd.color
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
        // Release the full-canvas copy while this command sits on the redo stack;
        // a later redo rebuilds it from the restored image.
        snapshot = null
    }
}
