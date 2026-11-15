package com.fengeon.resprite_desktop.ui.editor

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.min

enum class Tool { Pencil, Eraser, Fill, Line, Rect, Ellipse, Select, Move, Eyedropper, Gradient }

/**
 * Renders the composited sprite with pinch-zoom and two-finger pan.
 *
 * Single finger -> active tool. Two fingers -> transform. A second finger
 * arriving mid-stroke abandons the stroke so transforms never leave stray marks.
 *
 * Layout (scale + offset) is derived from measured container size, never written
 * during the draw pass. Gesture handlers read live values through
 * [rememberUpdatedState] so the pointer coroutine doesn't restart on zoom.
 */
@Composable
fun CanvasView(
    composite: Bitmap,
    tool: Tool,
    onStrokeStart: (x: Int, y: Int) -> Unit,
    onStrokeMove: (x0: Int, y0: Int, x1: Int, y1: Int) -> Unit,
    onStrokeEnd: (startX: Int, startY: Int, endX: Int, endY: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var userScale by remember { mutableStateOf(1f) }
    var userPan by remember { mutableStateOf(Offset.Zero) }

    val baseScale = remember(containerSize, composite) {
        if (containerSize.width == 0 || containerSize.height == 0) 1f
        else min(
            containerSize.width.toFloat() / composite.width,
            containerSize.height.toFloat() / composite.height
        )
    }
    val baseX = (containerSize.width - composite.width * baseScale) / 2f
    val baseY = (containerSize.height - composite.height * baseScale) / 2f
    val effectiveScale = baseScale * userScale

    val scaleState = rememberUpdatedState(effectiveScale)
    val baseXState = rememberUpdatedState(baseX)
    val baseYState = rememberUpdatedState(baseY)
    val panState = rememberUpdatedState(userPan)

    fun toPixel(pos: Offset): Pair<Int, Int> {
        val s = scaleState.value
        if (s <= 0f) return 0 to 0
        val px = ((pos.x - baseXState.value - panState.value.x) / s).toInt()
        val py = ((pos.y - baseYState.value - panState.value.y) / s).toInt()
        return px.coerceIn(0, composite.width - 1) to py.coerceIn(0, composite.height - 1)
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { containerSize = it }
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
                            multi = true
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            if (zoom != 1f) {
                                userScale = (userScale * zoom).coerceIn(0.25f, 32f)
                            }
                            userPan += pan
                            event.changes.forEach { it.consume() }
                        } else if (!multi) {
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change != null && change.pressed) {
                                val moved = (change.position - downPos).getDistance() > 4f
                                if (moved && !started) {
                                    val (px, py) = toPixel(downPos)
                                    startX = px; startY = py; lastX = px; lastY = py
                                    onStrokeStart(px, py)
                                    started = true
                                }
                                if (started) {
                                    val (px, py) = toPixel(change.position)
                                    if (px != lastX || py != lastY) {
                                        onStrokeMove(lastX, lastY, px, py)
                                        lastX = px; lastY = py
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
                            val (px, py) = toPixel(downPos)
                            onStrokeStart(px, py)
                            onStrokeEnd(px, py, px, py)
                        }
                    }
                }
            }
    ) {
        drawImage(
            image = composite.asImageBitmap(),
            dstOffset = IntOffset(
                (baseX + userPan.x).toInt(),
                (baseY + userPan.y).toInt()
            ),
            dstSize = IntSize(
                (composite.width * effectiveScale).toInt(),
                (composite.height * effectiveScale).toInt()
            )
        )
    }
}
