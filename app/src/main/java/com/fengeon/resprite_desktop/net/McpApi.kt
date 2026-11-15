package com.fengeon.resprite_desktop.net

import com.fengeon.resprite_desktop.commands.CreateFrameCmd
import com.fengeon.resprite_desktop.commands.CreateLayerCmd
import com.fengeon.resprite_desktop.commands.FillCmd
import com.fengeon.resprite_desktop.commands.PaintStrokeCmd
import com.fengeon.resprite_desktop.commands.Shape
import com.fengeon.resprite_desktop.commands.ShapeCmd
import com.fengeon.resprite_desktop.engine.Compositor
import com.fengeon.resprite_desktop.engine.RespriteEngine
import com.fengeon.resprite_desktop.model.Size
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Maps HTTP tool calls onto editor commands. Every mutation is funnelled through
 * RespriteEngine.onMain so bitmaps are only ever touched on the UI thread, and
 * every call is executed as a Command so it lands on the same undo stack the
 * human UI uses (an AI edit can be undone with the Undo button).
 */
object McpApi {

    private val json = Json { ignoreUnknownKeys = true }

    fun handle(body: String): String {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }
            .getOrElse { return error("invalid JSON body") }
        val tool = root["tool"]?.jsonPrimitive?.content ?: return error("missing 'tool'")
        val args = root["args"]?.jsonObject ?: JsonObject(emptyMap())
        return runCatching { dispatch(tool, args) }
            .getOrElse { "{\"ok\":false,\"error\":${JsonPrimitive(it.message ?: it.javaClass.simpleName).toString()}}" }
    }

    private fun dispatch(tool: String, a: JsonObject): String = when (tool) {
        "ping" -> "{\"ok\":true,\"result\":\"pong\"}"
        "get_state" -> getState()
        "get_canvas" -> getCanvas()
        "set_color" -> setColor(a)
        "draw_pixel" -> drawPixel(a)
        "draw_line" -> shape(a, Shape.Line)
        "draw_rect" -> shape(a, Shape.Rect)
        "draw_ellipse" -> shape(a, Shape.Ellipse)
        "fill" -> fill(a)
        "add_layer" -> addLayer()
        "add_frame" -> addFrame()
        "select_layer" -> selectLayer(a)
        "select_frame" -> selectFrame(a)
        "undo" -> undo()
        "redo" -> redo()
        else -> error("unknown tool '$tool'")
    }

    // ---- reads -----------------------------------------------------------

    private fun getState(): String = RespriteEngine.onMain {
        val s = RespriteEngine.state
        buildJsonObject {
            put("ok", true)
            put("result", buildJsonObject {
                put("width", s.canvasWidth)
                put("height", s.canvasHeight)
                put("layers", s.document.layerDatas.size)
                put("frames", s.document.frameDatas.size)
                put("selectedLayer", s.selectedLayerIndex)
                put("selectedFrame", s.selectedFrameIndex)
                put("primaryColor", s.primaryColor)
                put("canUndo", RespriteEngine.undo.canUndo)
                put("canRedo", RespriteEngine.undo.canRedo)
                putJsonArray("layerNames") {
                    s.document.layerDatas.forEach { add(kotlinx.serialization.json.JsonPrimitive(it.name)) }
                }
            })
        }.toString()
    }

    private fun getCanvas(): String = RespriteEngine.onMain {
        val png = Compositor.toPngBase64(RespriteEngine.state)
        buildJsonObject {
            put("ok", true)
            put("result", buildJsonObject {
                put("format", "png")
                put("encoding", "base64")
                put("data", png)
            })
        }.toString()
    }

    // ---- mutations -------------------------------------------------------

    private fun setColor(a: JsonObject): String {
        val color = a.intArg("color")
        return RespriteEngine.onMain {
            RespriteEngine.state.primaryColor = color
            RespriteEngine.touch()
            okResult("primaryColor", color.toString())
        }
    }

    private fun drawPixel(a: JsonObject): String {
        val x = a.intArg("x")
        val y = a.intArg("y")
        val color = a.optIntArg("color")
        return RespriteEngine.onMain {
            val s = RespriteEngine.state
            val cellId = s.ensureCell(s.selectedLayerIndex, s.selectedFrameIndex)
            val cmd = PaintStrokeCmd(cellId, listOf(x to y), color ?: s.primaryColor)
            RespriteEngine.undo.execute(s, cmd)
            RespriteEngine.touch()
            okResult("drawn", "$x,$y")
        }
    }

    private fun shape(a: JsonObject, shape: Shape): String {
        val x0 = a.intArg("x0"); val y0 = a.intArg("y0")
        val x1 = a.intArg("x1"); val y1 = a.intArg("y1")
        val color = a.optIntArg("color")
        val filled = a["filled"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
        return RespriteEngine.onMain {
            val s = RespriteEngine.state
            val cellId = s.ensureCell(s.selectedLayerIndex, s.selectedFrameIndex)
            val cmd = ShapeCmd(cellId, shape, x0, y0, x1, y1, color ?: s.primaryColor, 1, filled)
            RespriteEngine.undo.execute(s, cmd)
            RespriteEngine.touch()
            okResult("shape", shape.name.lowercase())
        }
    }

    private fun fill(a: JsonObject): String {
        val x = a.intArg("x"); val y = a.intArg("y")
        val color = a.optIntArg("color")
        return RespriteEngine.onMain {
            val s = RespriteEngine.state
            val cellId = s.ensureCell(s.selectedLayerIndex, s.selectedFrameIndex)
            RespriteEngine.undo.execute(s, FillCmd(cellId, x, y, color ?: s.primaryColor))
            RespriteEngine.touch()
            okResult("filled", "$x,$y")
        }
    }

    private fun addLayer(): String = RespriteEngine.onMain {
        val s = RespriteEngine.state
        RespriteEngine.undo.execute(s, CreateLayerCmd(s.document.layerDatas.size, s.document.frameDatas.size))
        RespriteEngine.touch()
        okResult("layers", s.document.layerDatas.size.toString())
    }

    private fun addFrame(): String = RespriteEngine.onMain {
        val s = RespriteEngine.state
        RespriteEngine.undo.execute(
            s,
            CreateFrameCmd(s.document.frameDatas.size, s.document.layerDatas.size, Size(s.canvasWidth, s.canvasHeight))
        )
        RespriteEngine.touch()
        okResult("frames", s.document.frameDatas.size.toString())
    }

    private fun selectLayer(a: JsonObject): String {
        val index = a.intArg("index")
        return RespriteEngine.onMain {
            val s = RespriteEngine.state
            require(index in s.document.layerDatas.indices) { "layer index out of range" }
            s.selectedLayerIndex = index
            RespriteEngine.touch()
            okResult("selectedLayer", index.toString())
        }
    }

    private fun selectFrame(a: JsonObject): String {
        val index = a.intArg("index")
        return RespriteEngine.onMain {
            val s = RespriteEngine.state
            require(index in s.document.frameDatas.indices) { "frame index out of range" }
            s.selectedFrameIndex = index
            RespriteEngine.touch()
            okResult("selectedFrame", index.toString())
        }
    }

    private fun undo(): String = RespriteEngine.onMain {
        val s = RespriteEngine.state
        if (!RespriteEngine.undo.canUndo) return@onMain okResult("undone", "false")
        RespriteEngine.undo.undo(s)
        RespriteEngine.touch()
        okResult("undone", "true")
    }

    private fun redo(): String = RespriteEngine.onMain {
        val s = RespriteEngine.state
        if (!RespriteEngine.undo.canRedo) return@onMain okResult("redone", "false")
        RespriteEngine.undo.redo(s)
        RespriteEngine.touch()
        okResult("redone", "true")
    }

    // ---- helpers ---------------------------------------------------------

    private fun okResult(key: String, value: String): String =
        buildJsonObject {
            put("ok", true)
            put("result", buildJsonObject { put(key, value) })
        }.toString()

    private fun error(message: String): String =
        "{\"ok\":false,\"error\":${JsonPrimitive(message).toString()}}"

    private fun JsonObject.intArg(name: String): Int =
        this[name]?.jsonPrimitive?.int ?: throw IllegalArgumentException("missing int '$name'")

    private fun JsonObject.optIntArg(name: String): Int? =
        this[name]?.jsonPrimitive?.int
}
