package com.fengeon.resprite_desktop.ui.editor

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.fengeon.resprite_desktop.ui.rsColors
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.min

enum class Tool { Pencil, Eraser, Fill, Line, Rect, Ellipse, Select, Move, Eyedropper, Gradient }

private fun screenToPixel(
    pos: Offset, containerSize: IntSize, userScale: Float, userPan: Offset,
    bitmapWidth: Int, bitmapHeight: Int
): Pair<Int, Int> {
    if (containerSize.width == 0 || containerSize.height == 0) return 0 to 0
    val fitScale = min(
        containerSize.width.toFloat() / bitmapWidth,
        containerSize.height.toFloat() / bitmapHeight
    )
    val scale = fitScale * userScale
    if (scale <= 0f) return 0 to 0
    val drawW = bitmapWidth * scale
    val drawH = bitmapHeight * scale
    val originX = (containerSize.width - drawW) / 2f
    val originY = (containerSize.height - drawH) / 2f
    val px = ((pos.x - originX - userPan.x) / scale).toInt()
    val py = ((pos.y - originY - userPan.y) / scale).toInt()
    return px.coerceIn(0, bitmapWidth - 1) to py.coerceIn(0, bitmapHeight - 1)
}

@Composable
fun CanvasView(
    composite: Bitmap,
    tool: Tool,
    onStrokeStart: (x: Int, y: Int) -> Unit,
    onStrokeMove: (x0: Int, y0: Int, x1: Int, y1: Int) -> Unit,
    onStrokeEnd: (startX: Int, startY: Int, endX: Int, endY: Int) -> Unit,
    onZoomChange: (Float) -> Unit = {},
    onStrokeCancel: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val canvasColors = rsColors
    val containerSize = remember { mutableStateOf(IntSize.Zero) }
    val userScale = remember { mutableStateOf(1f) }
    val userPan = remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(userScale.value) { onZoomChange(userScale.value) }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { containerSize.value = it }
            .pointerInput(composite, tool) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val downPos = down.position
                    var multi = false
                    var started = false
                    var startX = -1
                    var startY = -1
                    var lastX = -1
                    var lastY = -1

                    var event = awaitPointerEvent()
                    while (event.changes.any { it.pressed }) {
                        val active = event.changes.count { it.pressed }
                        if (active >= 2) {
                            if (!multi) onStrokeCancel()
                            multi = true
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            if (zoom != 1f) { userScale.value = (userScale.value * zoom).coerceIn(0.25f, 32f); onZoomChange(userScale.value) }
                            userPan.value += pan
                            event.changes.forEach { it.consume() }
                        } else if (!multi) {
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change != null && change.pressed) {
                                val moved = (change.position - downPos).getDistance() > 4f
                                if (moved && !started) {
                                    val p = screenToPixel(downPos, containerSize.value, userScale.value, userPan.value, composite.width, composite.height)
                                    startX = p.first; startY = p.second; lastX = p.first; lastY = p.second
                                    onStrokeStart(p.first, p.second)
                                    started = true
                                }
                                if (started) {
                                    val p = screenToPixel(change.position, containerSize.value, userScale.value, userPan.value, composite.width, composite.height)
                                    if (p.first != lastX || p.second != lastY) {
                                        onStrokeMove(lastX, lastY, p.first, p.second)
                                        lastX = p.first; lastY = p.second
                                    }
                                }
                                change.consume()
                            }
                        }
                        event = awaitPointerEvent()
                    }

                    if (!multi) {
                        if (started) {
                            onStrokeEnd(startX, startY, lastX, lastY)
                        } else {
                            val p = screenToPixel(downPos, containerSize.value, userScale.value, userPan.value, composite.width, composite.height)
                            onStrokeStart(p.first, p.second)
                            onStrokeEnd(p.first, p.second, p.first, p.second)
                        }
                    }
                }
            }
    ) {
        val cs = containerSize.value
        if (cs.width > 0 && cs.height > 0) {
            val fitScale = min(
                cs.width.toFloat() / composite.width,
                cs.height.toFloat() / composite.height
            )
            val scale = fitScale * userScale.value
            val drawW = composite.width * scale
            val drawH = composite.height * scale
            val originX = (cs.width - drawW) / 2f
            val originY = (cs.height - drawH) / 2f
            val finalX = originX + userPan.value.x
            val finalY = originY + userPan.value.y

            // Draw checkerboard background
            val checkSize = 8f * scale
            if (checkSize > 1f) {
                val cols = (drawW / checkSize).toInt()
                val rows = (drawH / checkSize).toInt()
                for (r in 0..rows) {
                    for (c in 0..cols) {
                        val color = if ((r + c) % 2 == 0) canvasColors.checkerDark else canvasColors.checkerLight
                        drawRect(
                            color = color,
                            topLeft = Offset(finalX + c * checkSize, finalY + r * checkSize),
                            size = Size(
                                min(checkSize, finalX + drawW - (finalX + c * checkSize)),
                                min(checkSize, finalY + drawH - (finalY + r * checkSize))
                            )
                        )
                    }
                }
            }

            drawImage(
                image = composite.asImageBitmap(),
                dstOffset = IntOffset(finalX.toInt(), finalY.toInt()),
                dstSize = IntSize(drawW.toInt(), drawH.toInt()),
                filterQuality = FilterQuality.None
            )
        }
    }
}
