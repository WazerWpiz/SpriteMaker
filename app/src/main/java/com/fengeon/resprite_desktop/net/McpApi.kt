package com.fengeon.resprite_desktop.net

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.fengeon.resprite_desktop.commands.CanvasFlipCmd
import com.fengeon.resprite_desktop.commands.ClearCellCmd
import com.fengeon.resprite_desktop.commands.PixelSetCmd
import com.fengeon.resprite_desktop.commands.CanvasResizeCmd
import com.fengeon.resprite_desktop.commands.CreateFrameCmd
import com.fengeon.resprite_desktop.commands.CreateLayerCmd
import com.fengeon.resprite_desktop.commands.DeleteLayerCmd
import com.fengeon.resprite_desktop.commands.DuplicateLayerCmd
import com.fengeon.resprite_desktop.commands.DeleteFrameCmd
import com.fengeon.resprite_desktop.commands.DuplicateFrameCmd
import com.fengeon.resprite_desktop.commands.SetFramePropertyCmd
import com.fengeon.resprite_desktop.commands.SetFrameRateCmd
import com.fengeon.resprite_desktop.commands.FillCmd
import com.fengeon.resprite_desktop.commands.MoveLayerCmd
import com.fengeon.resprite_desktop.commands.PaintStrokeCmd
import com.fengeon.resprite_desktop.commands.SetLayerPropertyCmd
import com.fengeon.resprite_desktop.commands.Shape
import com.fengeon.resprite_desktop.commands.ShapeCmd
import com.fengeon.resprite_desktop.commands.SpriteFlipCmd
import com.fengeon.resprite_desktop.commands.SpriteResizeCmd
import com.fengeon.resprite_desktop.engine.Compositor
import com.fengeon.resprite_desktop.format.GifEncoder
import com.fengeon.resprite_desktop.engine.RespriteEngine
import com.fengeon.resprite_desktop.model.Size
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Maps HTTP tool calls onto editor commands.
 *
 * Every mutation runs on the UI thread via RespriteEngine.onMain (cell bitmaps are
 * not thread-safe) and is executed as a Command, so it lands on the same undo
 * stack the human UI uses. Pixel coordinates are canvas pixels and are always
 * inclusive of the named pixel: a tool that says x=3,y=7 touches exactly that
 * pixel, never half of it.
 */
object McpApi {

    private val json = Json { ignoreUnknownKeys = true }

    fun handle(body: String): String {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }
            .getOrElse { return error("invalid JSON body") }
        val tool = root["tool"]?.jsonPrimitive?.content ?: return error("missing 'tool'")
        val args = root["args"]?.jsonObject ?: JsonObject(emptyMap())
        return runCatching { dispatch(tool, args) }
            .getOrElse { "{\"ok\":false,\"error\":${JsonPrimitive(it.message ?: it.javaClass.simpleName)}}" }
    }

    /** Tool catalogue, also served by the 'list_tools' call. */
    private val TOOLS = listOf(
        "ping", "help",
        "get_state", "get_canvas", "get_layer_canvas", "get_pixel", "get_palette", "get_canvas_ascii",
        "draw_pixel", "draw_pixels", "draw_line", "draw_rect", "draw_ellipse", "draw_polyline",
        "draw_sprite_from_ascii",
        "fill", "erase_pixel", "erase_pixels", "clear_layer",
        "set_color", "set_secondary_color",
        "add_layer", "delete_layer", "duplicate_layer", "move_layer",
        "set_layer_visible", "set_layer_locked", "set_layer_opacity", "rename_layer",
        "select_layer", "select_frame",
        "add_frame", "duplicate_frame", "delete_frame", "set_frame_duration", "set_frame_rate", "get_frames",
        "flip_h", "flip_v", "resize_canvas",
        "undo", "redo", "export_gif", "export_sheet"
    )

    private fun dispatch(tool: String, a: JsonObject): String = when (tool) {
        "ping" -> "{\"ok\":true,\"result\":\"pong\"}"
        "help" -> buildJsonObject {
            put("ok", true)
            put("result", buildJsonObject {
                put("tools", buildJsonArray { TOOLS.forEach { add(JsonPrimitive(it)) } })
                put("colors", "ARGB ints, e.g. -65536 = opaque red (#FFFF0000). Use 0xFF000000.toInt() style values.")
                put("coords", "canvas pixels, origin top-left, inclusive")
                put(
                    "recommended",
                    "Call get_canvas_ascii first: it returns the canvas as a text grid plus a " +
                        "symbol->colour legend. Edit that grid and send it back with " +
                        "draw_sprite_from_ascii (args: ascii, legend, clear, offset_x, offset_y). " +
                        "This avoids guessing pixel coordinates."
                )
            })
        }.toString()

        // reads
        "get_state" -> getState()
        "get_canvas" -> getCanvas(allLayers = true)
        "get_layer_canvas" -> getCanvas(allLayers = false)
        "get_pixel" -> getPixel(a)
        "get_palette" -> getPalette()
        "get_canvas_ascii" -> getCanvasAscii()

        // drawing
        "draw_pixel" -> drawPixel(a)
        "draw_pixels" -> drawPixels(a)
        "draw_line" -> shape(a, Shape.Line)
        "draw_rect" -> shape(a, Shape.Rect)
        "draw_ellipse" -> shape(a, Shape.Ellipse)
        "draw_polyline" -> polyline(a)
        "draw_sprite_from_ascii" -> drawSpriteFromAscii(a)
        "fill" -> fill(a)
        "erase_pixel" -> erasePixel(a)
        "erase_pixels" -> erasePixels(a)
        "clear_layer" -> clearLayer()

        // colours
        "set_color" -> setColor(a, secondary = false)
        "set_secondary_color" -> setColor(a, secondary = true)

        // layers
        "add_layer" -> addLayer()
        "delete_layer" -> deleteLayer()
        "duplicate_layer" -> duplicateLayer()
        "move_layer" -> moveLayer(a)
        "set_layer_visible" -> setLayerVisible(a)
        "set_layer_locked" -> setLayerLocked(a)
        "set_layer_opacity" -> setLayerOpacity(a)
        "rename_layer" -> renameLayer(a)
        "select_layer" -> selectLayer(a)

        // frames / canvas
        "select_frame" -> selectFrame(a)
        "add_frame" -> addFrame()
        "duplicate_frame" -> duplicateFrame()
        "delete_frame" -> deleteFrame()
        "set_frame_duration" -> setFrameDuration(a)
        "set_frame_rate" -> setFrameRate(a)
        "get_frames" -> getFrames()
        "flip_h" -> flip(a, horizontal = true)
        "flip_v" -> flip(a, horizontal = false)
        "resize_canvas" -> resizeCanvas(a)

        // history
        "undo" -> undo()
        "export_gif" -> exportGif(a)
        "export_sheet" -> exportSheet(a)
        "redo" -> redo()
        else -> error("unknown tool '$tool' (call 'help' for the list)")
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
                put("secondaryColor", s.secondaryColor)
                put("canUndo", RespriteEngine.undo.canUndo)
                put("canRedo", RespriteEngine.undo.canRedo)
                put("layerDetails", buildJsonArray {
                    s.document.layerDatas.forEachIndexed { i, l ->
                        add(buildJsonObject {
                            put("index", i)
                            put("name", l.name)
                            put("visible", l.contentVisible)
                            put("locked", l.isLocked)
                            put("opacity", l.opacity)
                        })
                    }
                })
            })
        }.toString()
    }

    private fun getCanvas(allLayers: Boolean): String = RespriteEngine.onMain {
        val s = RespriteEngine.state
        val bmp = if (allLayers) {
            Compositor.flatten(s)
        } else {
            val id = s.cellIdFor(s.selectedLayerIndex, s.selectedFrameIndex)
            val src = id?.let { s.cellBitmaps[it] }
            val copy = Bitmap.createBitmap(s.canvasWidth.coerceAtLeast(1), s.canvasHeight.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            if (src != null) Canvas(copy).drawBitmap(src, 0f, 0f, null)
            copy
        }
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        val png = android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
        buildJsonObject {
            put("ok", true)
            put("result", buildJsonObject {
                put("format", "png")
                put("encoding", "base64")
                put("width", s.canvasWidth)
                put("height", s.canvasHeight)
                put("data", png)
            })
        }.toString()
    }

    private fun getPixel(a: JsonObject): String {
        val x = a.intArg("x"); val y = a.intArg("y")
        return RespriteEngine.onMain {
            val s = RespriteEngine.state
            val id = s.cellIdFor(s.selectedLayerIndex, s.selectedFrameIndex)
            val bmp = id?.let { s.cellBitmaps[it] }
            val argb = if (bmp != null && x in 0 until bmp.width && y in 0 until bmp.height) bmp.getPixel(x, y) else 0
            buildJsonObject {
                put("ok", true)
                put("result", buildJsonObject {
                    put("x", x); put("y", y)
                    put("argb", argb)
                    put("hex", "#%08X".format(argb))
                    put("alpha", Color.alpha(argb))
                })
            }.toString()
        }
    }

    private fun getPalette(): String = RespriteEngine.onMain {
        val s = RespriteEngine.state
        buildJsonObject {
            put("ok", true)
            put("result", buildJsonObject {
                put("primary", s.primaryColor)
                put("secondary", s.secondaryColor)
            })
        }.toString()
    }

    /**
     * Renders the current frame as an ASCII grid so a text-only model can
     * actually SEE the canvas it is drawing on.
     *
     * One character per pixel, one row per line of output, and a legend mapping
     * every character back to its colour. Transparent pixels are "." so an empty
     * canvas reads as a grid of dots. Anything larger than 64x64 is downsampled
     * with the top-left pixel of each block, so the reply stays a manageable size
     * and tallies with the ASCII sprite format draw_sprite_from_ascii accepts.
     */
    private fun getCanvasAscii(): String = RespriteEngine.onMain {
        val s = RespriteEngine.state
        val w = s.canvasWidth
        val h = s.canvasHeight
        val bmp = Compositor.flatten(s)
        val stepX = (w + 63) / 64
        val stepY = (h + 63) / 64
        val cols = (w + stepX - 1) / stepX
        val rows = (h + stepY - 1) / stepY

        val symbols = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
        val colourToSymbol = LinkedHashMap<Int, Char>()
        val sb = StringBuilder()
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val x = (col * stepX).coerceAtMost(w - 1)
                val y = (row * stepY).coerceAtMost(h - 1)
                val argb = bmp.getPixel(x, y)
                if (Color.alpha(argb) == 0) {
                    sb.append('.')
                } else {
                    val sym = colourToSymbol.getOrPut(argb) {
                        symbols[colourToSymbol.size.coerceAtMost(symbols.length - 1)]
                    }
                    sb.append(sym)
                }
            }
            if (row < rows - 1) sb.append('\n')
        }
        bmp.recycle()
        // Built from the collected map: a finished JsonObject has no put().
        val legend = buildJsonObject {
            put(".", "%08X".format(0))
            colourToSymbol.forEach { (argb, sym) ->
                put(sym.toString(), "%08X".format(argb))
            }
        }
        bmp.recycle()
        buildJsonObject {
            put("ok", true)
            put("result", buildJsonObject {
                put("format", "ascii")
                put("canvasWidth", w)
                put("canvasHeight", h)
                put("gridWidth", cols)
                put("gridHeight", rows)
                put(
                    "meaning",
                    "one character per pixel, each row is one line, '.' = transparent. " +
                        "Legend maps symbol -> AARRGGBB hex. Use this grid as the `ascii` " +
                        "argument of draw_sprite_from_ascii to edit it."
                )
                put("legend", legend)
                put("grid", sb.toString())
            })
        }.toString()
    }

    /**
     * Draws a sprite written as text — the most reliable way for a text-only
     * model to produce pixel art, because coordinates stop being guessed.
     *
     * `ascii` holds one character per pixel with rows separated by newlines.
     * `legend` maps each character to a colour (ARGB int, "#RRGGBB"/"#AARRGGBB"
     * hex, or "r,g,b,a" 0-255). Characters with no legend entry are skipped, so
     * spaces can be used for "leave alone". `offset_x`/`offset_y` place the grid,
     * and `clear` wipes the layer first when true.
     */
    private fun drawSpriteFromAscii(a: JsonObject): String {
        val ascii = a["ascii"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("missing string 'ascii'")
        val clear = a.boolArg("clear") ?: true
        val offsetX = a.optIntArg("offset_x") ?: 0
        val offsetY = a.optIntArg("offset_y") ?: 0
        val legend = HashMap<Char, Int>()
        (a["legend"] as? JsonObject)?.forEach { (ch, value) ->
            if (ch.length == 1) {
                val colour = parseColour(value) ?: return@forEach
                legend[ch[0]] = colour
            }
        }
        return RespriteEngine.onMain {
            val s = RespriteEngine.state
            val cellId = s.ensureCell(s.selectedLayerIndex, s.selectedFrameIndex)
            if (clear) {
                RespriteEngine.undo.execute(s, ClearCellCmd(cellId))
            }
            // One undo entry per distinct colour keeps the existing single-colour
            // command and holds a single snapshot per group.
            val byColour = LinkedHashMap<Int, ArrayList<Pair<Int, Int>>>()
            val lines = ascii.split('\n')
            for (row, line in lines.withIndex()) {
                // String is a CharSequence, not an Iterable<Char>, so it has no
                // withIndex(); index into it directly instead.
                for (col in line.indices) {
                    val ch = line[col]
                    if (ch == '.' || ch == ' ') continue
                    val colour = legend[ch] ?: continue
                    val x = offsetX + col
                    val y = offsetY + row
                    if (x < 0 || y < 0 || x >= s.canvasWidth || y >= s.canvasHeight) continue
                    byColour.getOrPut(colour) { ArrayList() }.add(x to y)
                }
            }
            var painted = 0
            for ((colour, points) in byColour) {
                RespriteEngine.undo.execute(s, PixelSetCmd(cellId, points, colour))
                painted += points.size
            }
            RespriteEngine.touch()
            buildJsonObject {
                put("ok", true)
                put("result", buildJsonObject {
                    put("painted", painted)
                    put("colours", byColour.size)
                    put("rows", lines.size)
                })
            }.toString()
        }
    }

    /** Accepts "#RRGGBB", "#AARRGGBB", "r,g,b,a", or an ARGB int. */
    private fun parseColour(value: JsonElement): Int? {
        val raw = (value as? JsonPrimitive)?.content?.trim() ?: return null
        if (raw.isEmpty()) return null
        if (raw.contains(',')) {
            val parts = raw.split(',').map { it.trim().toIntOrNull() ?: return null }
            if (parts.size < 3) return null
            val r = parts[0].coerceIn(0, 255)
            val g = parts[1].coerceIn(0, 255)
            val b = parts[2].coerceIn(0, 255)
            val al = parts.getOrNull(3)?.coerceIn(0, 255) ?: 255
            return (al shl 24) or (r shl 16) or (g shl 8) or b
        }
        val hex = raw.removePrefix("#")
        if (hex.length == 6) return ("FF$hex").toLongOrNull(16)?.toInt()
        if (hex.length == 8) return hex.toLongOrNull(16)?.toInt()
        return raw.toIntOrNull()
    }

    // ---- drawing ---------------------------------------------------------

    private fun drawPixel(a: JsonObject): String {
        val x = a.intArg("x"); val y = a.intArg("y")
        val color = a.optIntArg("color")
        return RespriteEngine.onMain {
            val s = RespriteEngine.state
            val cellId = s.ensureCell(s.selectedLayerIndex, s.selectedFrameIndex)
            RespriteEngine.undo.execute(s, PaintStrokeCmd(cellId, listOf(x to y), color ?: s.primaryColor))
            RespriteEngine.touch()
            okResult("drawn", "$x,$y")
        }
    }

    private fun drawPixels(a: JsonObject): String {
        val pts = a["points"] as? JsonArray ?: throw IllegalArgumentException("points must be an array of [x,y]")
        val color = a.optIntArg("color")
        return RespriteEngine.onMain {
            val s = RespriteEngine.state
            val cellId = s.ensureCell(s.selectedLayerIndex, s.selectedFrameIndex)
            val ink = color ?: s.primaryColor
            val list = ArrayList<Pair<Int, Int>>(pts.size)
            for (p in pts) {
                val arr = p as? JsonArray ?: continue
                if (arr.size < 2) continue
                list.add(arr[0].jsonPrimitive.int to arr[1].jsonPrimitive.int)
            }
            // One command for the whole batch: a per-pixel command would push one
            // undo entry and hold one snapshot per point.
            RespriteEngine.undo.execute(s, PixelSetCmd(cellId, list, ink))
            RespriteEngine.touch()
            okResult("pixels", list.size.toString())
        }
    }

    private fun shape(a: JsonObject, shape: Shape): String {
        val x0 = a.intArg("x0"); val y0 = a.intArg("y0")
        val x1 = a.intArg("x1"); val y1 = a.intArg("y1")
        val color = a.optIntArg("color")
        val filled = a.boolArg("filled") ?: false
        return RespriteEngine.onMain {
            val s = RespriteEngine.state
            val cellId = s.ensureCell(s.selectedLayerIndex, s.selectedFrameIndex)
            RespriteEngine.undo.execute(s, ShapeCmd(cellId, shape, x0, y0, x1, y1, color ?: s.primaryColor, 1, filled))
            RespriteEngine.touch()
            okResult("shape", shape.name.lowercase())
        }
    }

    private fun polyline(a: JsonObject): String {
        val pts = a["points"] as? JsonArray ?: throw IllegalArgumentException("points must be an array of [x,y]")
        val color = a.optIntArg("color")
        return RespriteEngine.onMain {
            val s = RespriteEngine.state
            val cellId = s.ensureCell(s.selectedLayerIndex, s.selectedFrameIndex)
            val ink = color ?: s.primaryColor
            var segs = 0
            for (i in 0 until pts.size - 1) {
                val p0 = pts[i] as? JsonArray ?: continue
                val p1 = pts[i + 1] as? JsonArray ?: continue
                if (p0.size < 2 || p1.size < 2) continue
                RespriteEngine.undo.execute(s, ShapeCmd(
                    cellId, Shape.Line,
                    p0[0].jsonPrimitive.int, p0[1].jsonPrimitive.int,
                    p1[0].jsonPrimitive.int, p1[1].jsonPrimitive.int,
                    ink, 1, false
                ))
                segs++
            }
            RespriteEngine.touch()
            okResult("segments", segs.toString())
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

    private fun erasePixel(a: JsonObject): String {
        val x = a.intArg("x"); val y = a.intArg("y")
        return RespriteEngine.onMain {
            val s = RespriteEngine.state
            val cellId = s.ensureCell(s.selectedLayerIndex, s.selectedFrameIndex)
            RespriteEngine.undo.execute(s, PaintStrokeCmd(cellId, listOf(x to y), 0, eraser = true))
            RespriteEngine.touch()
            okResult("erased", "$x,$y")
        }
    }

    private fun erasePixels(a: JsonObject): String {
        val pts = a["points"] as? JsonArray ?: throw IllegalArgumentException("points must be an array of [x,y]")
        return RespriteEngine.onMain {
            val s = RespriteEngine.state
            val cellId = s.ensureCell(s.selectedLayerIndex, s.selectedFrameIndex)
            val list = ArrayList<Pair<Int, Int>>(pts.size)
            for (p in pts) {
                val arr = p as? JsonArray ?: continue
                if (arr.size < 2) continue
                list.add(arr[0].jsonPrimitive.int to arr[1].jsonPrimitive.int)
            }
            RespriteEngine.undo.execute(s, PixelSetCmd(cellId, list, 0, eraser = true))
            RespriteEngine.touch()
            okResult("erased", list.size.toString())
        }
    }

    private fun clearLayer(): String = RespriteEngine.onMain {
        val s = RespriteEngine.state
        val id = s.cellIdFor(s.selectedLayerIndex, s.selectedFrameIndex) ?: return@onMain okResult("cleared", "false")
        s.cellBitmaps[id] ?: return@onMain okResult("cleared", "false")
        RespriteEngine.undo.execute(s, ClearCellCmd(id))
        RespriteEngine.touch()
        okResult("cleared", id)
    }

    // ---- colours ---------------------------------------------------------

    private fun setColor(a: JsonObject, secondary: Boolean): String {
        val color = a.intArg("color")
        return RespriteEngine.onMain {
            val s = RespriteEngine.state
            if (secondary) s.secondaryColor = color else s.primaryColor = color
            RespriteEngine.touch()
            okResult(if (secondary) "secondaryColor" else "primaryColor", color.toString())
        }
    }

    // ---- layers ----------------------------------------------------------

    private fun addLayer(): String = RespriteEngine.onMain {
        val s = RespriteEngine.state
        RespriteEngine.undo.execute(s, CreateLayerCmd(s.document.layerDatas.size, s.document.frameDatas.size))
        RespriteEngine.touch()
        okResult("layers", s.document.layerDatas.size.toString())
    }

    private fun deleteLayer(): String = RespriteEngine.onMain {
        val s = RespriteEngine.state
        if (s.document.layerDatas.size <= 1) return@onMain okResult("deleted", "false")
        RespriteEngine.undo.execute(s, DeleteLayerCmd(s.selectedLayerIndex))
        RespriteEngine.touch()
        okResult("deleted", "true")
    }

    private fun duplicateLayer(): String = RespriteEngine.onMain {
        val s = RespriteEngine.state
        RespriteEngine.undo.execute(s, DuplicateLayerCmd(s.selectedLayerIndex))
        RespriteEngine.touch()
        okResult("layers", s.document.layerDatas.size.toString())
    }

    private fun moveLayer(a: JsonObject): String {
        val from = a.intArg("from"); val to = a.intArg("to")
        return RespriteEngine.onMain {
            val s = RespriteEngine.state
            RespriteEngine.undo.execute(s, MoveLayerCmd(from, to))
            RespriteEngine.touch()
            okResult("moved", "$from->$to")
        }
    }

    private fun setLayerVisible(a: JsonObject): String {
        val v = a.boolArg("visible") ?: throw IllegalArgumentException("missing boolean 'visible'")
        return RespriteEngine.onMain {
            val s = RespriteEngine.state
            RespriteEngine.undo.execute(s, SetLayerPropertyCmd(s.selectedLayerIndex) { it.copy(contentVisible = v) })
            RespriteEngine.touch()
            okResult("visible", v.toString())
        }
    }

    private fun setLayerLocked(a: JsonObject): String {
        val v = a.boolArg("locked") ?: throw IllegalArgumentException("missing boolean 'locked'")
        return RespriteEngine.onMain {
            val s = RespriteEngine.state
            RespriteEngine.undo.execute(s, SetLayerPropertyCmd(s.selectedLayerIndex) { it.copy(isLocked = v) })
            RespriteEngine.touch()
            okResult("locked", v.toString())
        }
    }

    private fun setLayerOpacity(a: JsonObject): String {
        val o = a["opacity"]?.jsonPrimitive?.float ?: throw IllegalArgumentException("missing float 'opacity' 0..1")
        val clamped = o.coerceIn(0f, 1f)
        return RespriteEngine.onMain {
            val s = RespriteEngine.state
            RespriteEngine.undo.execute(s, SetLayerPropertyCmd(s.selectedLayerIndex) { it.copy(opacity = clamped) })
            RespriteEngine.touch()
            okResult("opacity", clamped.toString())
        }
    }

    private fun renameLayer(a: JsonObject): String {
        val name = a["name"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("missing 'name'")
        return RespriteEngine.onMain {
            val s = RespriteEngine.state
            RespriteEngine.undo.execute(s, SetLayerPropertyCmd(s.selectedLayerIndex) { it.copy(name = name) })
            RespriteEngine.touch()
            okResult("name", name)
        }
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

    // ---- frames / canvas -------------------------------------------------

    private fun addFrame(): String = RespriteEngine.onMain {
        val s = RespriteEngine.state
        RespriteEngine.undo.execute(s, CreateFrameCmd(s.document.frameDatas.size, s.document.layerDatas.size, Size(s.canvasWidth, s.canvasHeight)))
        RespriteEngine.touch()
        okResult("frames", s.document.frameDatas.size.toString())
    }

    private fun duplicateFrame(): String = RespriteEngine.onMain {
        val s = RespriteEngine.state
        RespriteEngine.undo.execute(s, DuplicateFrameCmd(s.selectedFrameIndex))
        RespriteEngine.touch()
        okResult("frames", s.document.frameDatas.size.toString())
    }

    private fun deleteFrame(): String = RespriteEngine.onMain {
        val s = RespriteEngine.state
        if (s.document.frameDatas.size <= 1) return@onMain okResult("deleted", "false")
        RespriteEngine.undo.execute(s, DeleteFrameCmd(s.selectedFrameIndex))
        RespriteEngine.touch()
        okResult("deleted", "true")
    }

    private fun setFrameDuration(a: JsonObject): String {
        val d = a.intArg("duration")
        require(d in 1..600) { "duration must be 1..600" }
        return RespriteEngine.onMain {
            val s = RespriteEngine.state
            RespriteEngine.undo.execute(s, SetFramePropertyCmd(s.selectedFrameIndex) { it.copy(duration = d) })
            RespriteEngine.touch()
            okResult("duration", d.toString())
        }
    }

    private fun setFrameRate(a: JsonObject): String {
        val fps = a.intArg("fps")
        return RespriteEngine.onMain {
            val s = RespriteEngine.state
            RespriteEngine.undo.execute(s, SetFrameRateCmd(fps))
            RespriteEngine.touch()
            okResult("frameRate", s.document.frameRate.toString())
        }
    }

    private fun getFrames(): String = RespriteEngine.onMain {
        val s = RespriteEngine.state
        buildJsonObject {
            put("ok", true)
            put("result", buildJsonObject {
                put("frameRate", s.document.frameRate)
                put("selected", s.selectedFrameIndex)
                put("frames", buildJsonArray {
                    s.document.frameDatas.forEachIndexed { i, f ->
                        add(buildJsonObject {
                            put("index", i)
                            put("duration", f.duration)
                            put("clipName", f.clipName)
                            put("clipDir", f.clipDir)
                        })
                    }
                })
            })
        }.toString()
    }

    private fun flip(a: JsonObject, horizontal: Boolean): String = RespriteEngine.onMain {
        val s = RespriteEngine.state
        val all = a.boolArg("all_cells") ?: false
        RespriteEngine.undo.execute(s, if (all) CanvasFlipCmd(horizontal) else SpriteFlipCmd(horizontal))
        RespriteEngine.touch()
        okResult("flipped", if (horizontal) "h" else "v")
    }

    private fun resizeCanvas(a: JsonObject): String {
        val w = a.intArg("width"); val h = a.intArg("height")
        require(w in 1..2048 && h in 1..2048) { "size must be 1..2048" }
        return RespriteEngine.onMain {
            val s = RespriteEngine.state
            val all = a.boolArg("all_cells") ?: false
            RespriteEngine.undo.execute(s, if (all) CanvasResizeCmd(Size(w, h)) else SpriteResizeCmd(Size(w, h)))
            RespriteEngine.touch()
            okResult("size", "${w}x$h")
        }
    }

    // ---- history ---------------------------------------------------------

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

    /**
     * Renders every frame, then writes a looping GIF into the app's external
     * files dir where adb and other tools can read it without extra permissions.
     */
    private fun exportGif(a: JsonObject): String {
        val name = (a["name"]?.jsonPrimitive?.content ?: "animation").replace(Regex("[^A-Za-z0-9_-]"), "_")
        return RespriteEngine.onMain {
            val s = RespriteEngine.state
            val w = s.canvasWidth
            val h = s.canvasHeight
            val fps = (a["fps"]?.jsonPrimitive?.int ?: s.document.frameRate).coerceIn(1, 60)
            val frames = ArrayList<IntArray>(s.document.frameDatas.size)
            val delays = ArrayList<Int>(s.document.frameDatas.size)
            for (i in s.document.frameDatas.indices) {
                val bmp = Compositor.flatten(s, i)
                val px = IntArray(w * h)
                bmp.getPixels(px, 0, w, 0, 0, w, h)
                bmp.recycle()
                frames.add(px)
                // FrameData.duration is a frame multiplier; convert to centiseconds.
                val mult = s.document.frameDatas[i].duration.coerceAtLeast(1)
                delays.add(((100 * mult) / fps).coerceAtLeast(2))
            }
            val gif = GifEncoder.encode(w, h, frames, delays)
            val dir = exportDir()
            dir.mkdirs()
            val file = java.io.File(dir, "$name.gif")
            file.writeBytes(gif)
            buildJsonObject {
                put("ok", true)
                put("result", buildJsonObject {
                    put("path", file.absolutePath)
                    put("bytes", gif.size)
                    put("frames", frames.size)
                    put("fps", fps)
                })
            }.toString()
        }
    }

    /**
     * Lays every frame out in a horizontal strip. Easier to inspect than a GIF and
     * what most engines want for sprite-sheet animation.
     */
    private fun exportSheet(a: JsonObject): String {
        val name = (a["name"]?.jsonPrimitive?.content ?: "sheet").replace(Regex("[^A-Za-z0-9_-]"), "_")
        return RespriteEngine.onMain {
            val s = RespriteEngine.state
            val w = s.canvasWidth
            val h = s.canvasHeight
            val count = s.document.frameDatas.size
            val sheet = Bitmap.createBitmap(w * count, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(sheet)
            for (i in 0 until count) {
                val frame = Compositor.flatten(s, i)
                canvas.drawBitmap(frame, (i * w).toFloat(), 0f, null)
                frame.recycle()
            }
            val dir = exportDir()
            dir.mkdirs()
            val file = java.io.File(dir, "$name.png")
            file.outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
            sheet.recycle()
            buildJsonObject {
                put("ok", true)
                put("result", buildJsonObject {
                    put("path", file.absolutePath)
                    put("frames", count)
                    put("width", w * count)
                    put("height", h)
                })
            }.toString()
        }
    }

    /** App-specific external dir: readable by adb and other apps, no permission. */
    private fun exportDir(): java.io.File {
        val ctx = com.fengeon.resprite_desktop.RespriteApp.instance
        val base = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        return java.io.File(base, "exports")
    }

    // ---- helpers ---------------------------------------------------------

    private fun okResult(key: String, value: String): String =
        buildJsonObject {
            put("ok", true)
            put("result", buildJsonObject { put(key, value) })
        }.toString()

    private fun error(message: String): String =
        "{\"ok\":false,\"error\":${JsonPrimitive(message)}}"

    private fun JsonObject.intArg(name: String): Int =
        this[name]?.jsonPrimitive?.int ?: throw IllegalArgumentException("missing int '$name'")

    private fun JsonObject.optIntArg(name: String): Int? =
        this[name]?.jsonPrimitive?.int

    private fun JsonObject.boolArg(name: String): Boolean? =
        this[name]?.jsonPrimitive?.booleanOrNull
}
