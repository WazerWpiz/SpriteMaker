package com.fengeon.resprite_desktop.commands

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.abs

/** Fill/Shape commands. Each captures a snapshot on apply for exact undo. */

class FillCmd(
    private val cellId: String,
    private val x: Int,
    private val y: Int,
    private val newColor: Int,
    private val tolerance: Int = 0
) : Command {
    override val label: String = "Fill"
    private var snapshot: Bitmap? = null

    override fun apply(state: EditorState) {
        val target = state.cellBitmaps[cellId] ?: return
        if (snapshot == null) snapshot = target.copy(Bitmap.Config.ARGB_8888, false)
        floodFill(target, x, y, newColor, tolerance)
    }

    override fun revert(state: EditorState) {
        val snap = snapshot ?: return
        state.cellBitmaps[cellId] = snap.copy(Bitmap.Config.ARGB_8888, true)
    }

    private fun floodFill(bmp: Bitmap, sx: Int, sy: Int, fill: Int, tol: Int) {
        if (sx < 0 || sy < 0 || sx >= bmp.width || sy >= bmp.height) return
        val w = bmp.width; val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        val origin = pixels[sy * w + sx]
        if (origin == fill && tol == 0) return
        val queue = ArrayDeque<Int>()
        queue.addLast(sy * w + sx)
        while (queue.isNotEmpty()) {
            val idx = queue.removeFirst()
            if (idx < 0 || idx >= pixels.size) continue
            if (!colorNear(pixels[idx], origin, tol)) continue
            if (pixels[idx] == fill) continue
            pixels[idx] = fill
            val x0 = idx % w; val y0 = idx / w
            if (x0 > 0) queue.addLast(idx - 1)
            if (x0 < w - 1) queue.addLast(idx + 1)
            if (y0 > 0) queue.addLast(idx - w)
            if (y0 < h - 1) queue.addLast(idx + w)
        }
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
    }

    private fun colorNear(a: Int, b: Int, tol: Int): Boolean {
        if (tol == 0) return a == b
        val da = abs(((a ushr 24) and 0xFF) - ((b ushr 24) and 0xFF))
        val dr = abs(((a ushr 16) and 0xFF) - ((b ushr 16) and 0xFF))
        val dg = abs(((a ushr 8) and 0xFF) - ((b ushr 8) and 0xFF))
        val db = abs((a and 0xFF) - (b and 0xFF))
        return da <= tol && dr <= tol && dg <= tol && db <= tol
    }
}

enum class Shape { Line, Rect, Ellipse }

class ShapeCmd(
    private val cellId: String,
    private val shape: Shape,
    private val x0: Int, private val y0: Int,
    private val x1: Int, private val y1: Int,
    // Named 'ink', not 'color': inside Paint().apply {} the bare name 'color'
    // resolves to Paint.color itself, so `this.color = color` was a self-assign
    // that left every shape at Paint's default black.
    private val ink: Int,
    private val strokeWidth: Int = 1,
    private val filled: Boolean = false
) : Command {
    override val label: String = when (shape) {
        Shape.Line -> "Line"
        Shape.Rect -> "Rect"
        Shape.Ellipse -> "Ellipse"
    }
    private var snapshot: Bitmap? = null

    override fun apply(state: EditorState) {
        val target = state.cellBitmaps[cellId] ?: return
        if (snapshot == null) snapshot = target.copy(Bitmap.Config.ARGB_8888, false)
        val c = Canvas(target)
        val p = Paint().apply {
            this.color = ink
            this.strokeWidth = strokeWidth.toFloat()
            style = if (filled) Paint.Style.FILL else Paint.Style.STROKE
            // Pixel art must be hard-edged. With anti-aliasing on (the Paint
            // default) a curved or diagonal edge lands at partial alpha, so the
            // outline reads as muddy grey and neighbouring pixels bleed. Off, each
            // pixel is either fully painted or untouched.
            isAntiAlias = false
            strokeCap = Paint.Cap.BUTT
            strokeJoin = Paint.Join.MITER
        }
        // Two coordinate conventions meet here.
        //
        // The API is INCLUSIVE: draw_rect(x0=2, x1=8) must paint pixels 2..8.
        // Canvas.drawRect/drawOval take an EXCLUSIVE right/bottom edge, so a filled
        // shape adds 1 to the far edge. Without that, x1 itself was never painted
        // and a one-pixel rect (x0 == x1) painted nothing at all.
        //
        // A stroked shape is drawn as a line/outline, where a 1px stroke centred on
        // an integer coordinate straddles two pixel rows at ~50% alpha each. Stroked
        // shapes are offset by half a pixel so the stroke covers exactly one row at
        // full opacity.
        val far = if (filled) 1f else 0.5f
        val near = if (filled) 0f else 0.5f
        val ax = x0 + near; val ay = y0 + near
        val bx = x1 + far; val by = y1 + far
        when (shape) {
            Shape.Line -> c.drawLine(ax, ay, bx, by, p)
            Shape.Rect -> {
                val l = minOf(ax, bx)
                val t = minOf(ay, by)
                val r = maxOf(ax, bx)
                val b = maxOf(ay, by)
                c.drawRect(l, t, r, b, p)
            }
            Shape.Ellipse -> {
                val l = minOf(ax, bx)
                val t = minOf(ay, by)
                val r = maxOf(ax, bx)
                val b = maxOf(ay, by)
                c.drawOval(RectF(l, t, r, b), p)
            }
        }
    }

    override fun revert(state: EditorState) {
        val snap = snapshot ?: return
        state.cellBitmaps[cellId] = snap.copy(Bitmap.Config.ARGB_8888, true)
    }
}

/** Reads a pixel's color from the composite of visible layers at (x,y). */
object Eyedropper {
    fun sample(state: EditorState, x: Int, y: Int): Int {
        if (x < 0 || y < 0 || x >= state.canvasWidth || y >= state.canvasHeight) return 0
        for (layer in state.document.layerDatas.asReversed()) {
            if (!layer.contentVisible) continue
            val cell = layer.cells.getOrNull(state.selectedFrameIndex) ?: continue
            val bmp = state.cellBitmaps[cell.id] ?: continue
            val cx = x - cell.bounds.origin.x
            val cy = y - cell.bounds.origin.y
            if (cx < 0 || cy < 0 || cx >= bmp.width || cy >= bmp.height) continue
            val px = bmp.getPixel(cx, cy)
            if ((px ushr 24) != 0) return px
        }
        return 0
    }
}
