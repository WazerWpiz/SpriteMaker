package com.fengeon.resprite_desktop.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import com.fengeon.resprite_desktop.commands.EditorState

/** Flattens the visible layers of [state] into one bitmap. Shared by the UI
 *  preview and the HTTP API so both always show exactly the same pixels. */
object Compositor {

    fun flatten(state: EditorState, frameIndex: Int = state.selectedFrameIndex): Bitmap {
        val w = state.canvasWidth.coerceAtLeast(1)
        val h = state.canvasHeight.coerceAtLeast(1)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        for (layer in state.document.layerDatas) {
            if (!layer.contentVisible) continue
            val cell = layer.cells.getOrNull(frameIndex) ?: continue
            val bmp = state.cellBitmaps[cell.id] ?: continue
            // Layer opacity and cell opacity both contribute: a layer at 50% with a
            // fully opaque cell must render at 50%. Reading only cell.opacity made
            // set_layer_opacity() a no-op that still reported success.
            val effective = (layer.opacity * cell.opacity).coerceIn(0f, 1f)
            paint.alpha = (effective * 255f).toInt()
            canvas.drawBitmap(bmp, cell.bounds.origin.x.toFloat(), cell.bounds.origin.y.toFloat(), paint)
        }
        return out
    }

    fun toPngBase64(state: EditorState): String {
        val bmp = flatten(state)
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
    }
}
