package com.fengeon.resprite_desktop.net

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * In-app AI agent: run a multi-step loop against the LLM, executing every tool
 * call the model returns until it either finishes or hits the step ceiling.
 *
 * Flow (one agent run):
 *   1. Build the system prompt that lists all available MCP tools.
 *   2. Send system + user prompt to the provider (OpenAI-compatible API).
 *   3. If the model asks for a tool -> execute it through McpApi.handle() and
 *      feed the result back; repeat.
 *   4. If the model answers with plain text -> that is the final answer.
 *
 * Everything runs on Dispatchers.IO; tool execution itself hops back to the UI
 * thread via RespriteEngine.onMain(), the same path the HTTP server uses.
 * JSON uses kotlinx.serialization (already a dependency of this project).
 */
object AgentClient {

    private val json = Json { ignoreUnknownKeys = true }

    data class Result(
        val success: Boolean,
        val text: String,
        val steps: Int
    )

    /** Start an agent run with the given user prompt. */
    fun run(
        context: Context,
        userPrompt: String,
        isCancelled: () -> Boolean = { false },
        onStep: (step: Int, tool: String?, args: String?) -> Unit,
        /** Called with whatever the model wrote, so the log can show it. */
        onReply: (String) -> Unit = {},
        onResult: (Result) -> Unit
    ) {
        if (!AgentConfig.isConfigured(context)) {
            onResult(Result(false, "Agent not configured: missing API key / base URL.", 0))
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            val maxSteps = AgentConfig.maxSteps(context)
            // Conversation history. A mutable list of messages, serialised to a
            // JsonArray on every request (kotlinx JsonArray is immutable).
            val history = mutableListOf(systemMessage(buildSystemPrompt()), userMessage(userPrompt))
            // How many tools the model actually asked for across the whole run.
            var toolsUsed = 0

            var step = 0
            while (step < maxSteps) {
                if (isCancelled()) {
                    onResult(Result(false, "Stopped by you at step $step of $maxSteps.", step))
                    return@launch
                }
                val response = chatOnce(context, history)
                if (!response.success) {
                    // A failed request does not consume a step, so the counter the
                    // user sees only ever counts real turns.
                    onResult(Result(false, "API error: ${response.error}", step))
                    return@launch
                }
                // Counted only once the reply actually arrived.
                step++

                val textReply = response.content?.trim().orEmpty()
                if (textReply.isNotEmpty()) {
                    onReply(textReply)
                    history.add(assistantMessage(textReply))
                }

                // Prefer the provider's own tool calls; fall back to a tool call
                // the model wrote out as plain text (some models do that).
                val calls = response.toolCalls.ifEmpty { parseTextToolCalls(textReply) }
                val writtenAsText = response.toolCalls.isEmpty() && calls.isNotEmpty()

                if (calls.isNotEmpty()) {
                    toolsUsed += calls.size
                    // Record the assistant turn that requested the tools, so the
                    // provider sees a coherent conversation on the next call.
                    if (textReply.isEmpty()) {
                        history.add(
                            buildJsonObject {
                                put("role", "assistant")
                                put("content", "")
                                put("tool_calls", buildJsonArray {
                                    for (call in calls) {
                                        add(buildJsonObject {
                                            put("id", call.id)
                                            put("type", "function")
                                            put("function", buildJsonObject {
                                                put("name", call.name)
                                                put("arguments", call.arguments)
                                            })
                                        })
                                    }
                                })
                            }
                        )
                    }
                    for (call in calls) {
                        onStep(step, call.name, call.arguments)
                        if (writtenAsText && call == calls.first()) {
                            onReply("(parsed a tool call the model wrote as text)")
                        }
                        val result = executeTool(call)
                        history.add(
                            buildJsonObject {
                                put("role", "tool")
                                put("tool_call_id", call.id)
                                put("content", result)
                            }
                        )
                    }
                    continue
                }

                val finalText = textReply.ifEmpty { "No response from model." }
                val hint = if (toolsUsed == 0) {
                    "\n\nThe model answered with text only and never called a drawing tool.\n" +
                        "It probably does not support tool calling — try meta/llama-3.3-70b-instruct, " +
                        "nvidia/llama-3.1-nemotron-70b-instruct, or gpt-4o-mini."
                } else {
                    ""
                }
                onResult(Result(true, finalText + hint, step))
                return@launch
            }

            onResult(
                Result(
                    false,
                    "Reached the $maxSteps step limit without finishing. Raise max steps, " +
                        "or ask for something smaller.",
                    maxSteps
                )
            )
        }
    }

    /**
     * Recovers tool calls a model wrote as text instead of using the API's tool
     * mechanism. Handles a bare object, a JSON array, a markdown ```json fence,
     * and both the OpenAI shape ({name, arguments}) and this project's own
     * {"tool": ..., "args": {...}} shape.
     */
    private fun parseTextToolCalls(text: String): List<ToolCall> {
        if (text.isBlank()) return emptyList()
        val cleaned = text.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```")
            .trim()
        // Only worth trying when it at least looks like a tool payload.
        if (!cleaned.contains("draw") && !cleaned.contains("tool") && !cleaned.contains("get_")) {
            return emptyList()
        }
        val parsed = runCatching { json.parseToJsonElement(cleaned) }.getOrNull()
            ?: return emptyList()
        val items = when (parsed) {
            is JsonArray -> parsed
            is JsonObject -> buildJsonArray { add(parsed) }
            else -> return emptyList()
        }
        val out = mutableListOf<ToolCall>()
        var n = 0
        for (el in items) {
            val obj = el as? JsonObject ?: continue
            // {"tool": "...", "args": {...}} — this project's own wire format.
            val toolName = obj["tool"]?.jsonPrimitive?.content
            if (toolName != null) {
                val argsObj = obj["args"].let { it as? JsonObject } ?: JsonObject(emptyMap())
                out.add(ToolCall("text_${n++}", toolName, argsObj.toString()))
                continue
            }
            // {"name": "...", "arguments": {...} | "json-string"} — OpenAI shape.
            val fnName = obj["name"]?.jsonPrimitive?.content ?: continue
            val argNode = obj["arguments"]
            val argString = when (argNode) {
                is JsonObject -> argNode.toString()
                is JsonPrimitive -> argNode.content
                else -> "{}"
            }
            out.add(ToolCall("text_${n++}", fnName, argString))
        }
        return out
    }

    // ---- model I/O ---------------------------------------------------------

    private data class ChatResponse(
        val success: Boolean,
        val error: String = "",
        val content: String? = null,
        val toolCalls: List<ToolCall> = emptyList()
    )

    private data class ToolCall(
        val id: String,
        val name: String,
        val arguments: String
    )

    private fun systemMessage(content: String): JsonObject = buildJsonObject {
        put("role", "system")
        put("content", content)
    }

    private fun userMessage(content: String): JsonObject = buildJsonObject {
        put("role", "user")
        put("content", content)
    }

    private fun assistantMessage(content: String): JsonObject = buildJsonObject {
        put("role", "assistant")
        put("content", content)
    }

    private fun toolMessage(toolCallId: String, content: String): JsonObject = buildJsonObject {
        put("role", "tool")
        put("tool_call_id", toolCallId)
        put("content", content)
    }

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNull(): String? =
        if (this is JsonPrimitive) content else null

    private fun kotlinx.serialization.json.JsonPrimitive.intOrNullSafe(): Int? =
        runCatching { int }.getOrNull()

    /** Turns an HTTP status into something a person can act on. */
    private fun translateHttpError(code: Int, raw: String): String {
        // Pull the provider's own message out if it sent a JSON error body.
        val detail = runCatching {
            json.parseToJsonElement(raw).jsonObject["error"]?.let { err ->
                (err as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull()
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }
        val hint = when (code) {
            401 -> "API key rejected (401) — check the key, or switch provider"
            403 -> "Provider refused the request (403) — key may lack access/credits"
            404 -> "Model not found (404) — check the model name, or tap Load models"
            408, 504 -> "Provider timed out — try again or lower max steps"
            429 -> "Rate limited — wait a moment and retry"
            in 500..599 -> "Provider error ($code) — server side, retry shortly"
            else -> "HTTP $code"
        }
        return if (detail != null) "$hint\n$detail" else hint
    }

    private fun translateException(e: Exception): String = when (e) {
        is java.net.SocketTimeoutException ->
            "Timeout — the provider did not answer in time"
        is java.net.UnknownHostException ->
            "Cannot reach the provider (DNS/offline) — check the network"
        is java.net.ConnectException ->
            "Connection refused — check the base URL"
        is java.io.IOException ->
            "Network error: ${e.message ?: "I/O failure"}"
        else -> "${e.javaClass.simpleName}: ${e.message}"
    }

    // ---- model catalogue ---------------------------------------------------

    data class ModelInfo(
        val id: String,
        val contextLength: Int?,
        /** null = provider did not say, which is the honest answer for some. */
        val supportsTools: Boolean?
    )

    data class ModelListResult(
        val success: Boolean,
        val models: List<ModelInfo>,
        val error: String
    )

    /**
     * Lists the models the configured provider exposes (`GET /models`), so the
     * user never has to type a model id. All three providers speak the OpenAI
     * wire format, so one implementation covers them.
     */
    fun fetchModels(context: Context, callback: (ModelListResult) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            val url = "${AgentConfig.baseUrl(context)}/models"
            val conn = try {
                java.net.URI(url).toURL().openConnection() as java.net.HttpURLConnection
            } catch (e: Exception) {
                callback(ModelListResult(false, emptyList(), translateException(e)))
                return@launch
            }
            conn.requestMethod = "GET"
            conn.setRequestProperty("Content-Type", "application/json")
            val key = AgentConfig.apiKey(context)
            if (key.isNotBlank()) {
                conn.setRequestProperty("Authorization", "Bearer $key")
            }
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            val body = try {
                val code = conn.responseCode
                if (code >= 400) {
                    val raw = conn.errorStream?.bufferedReader()?.readText() ?: ""
                    callback(ModelListResult(false, emptyList(), translateHttpError(code, raw)))
                    return@launch
                }
                conn.inputStream.bufferedReader().readText()
            } catch (e: Exception) {
                callback(ModelListResult(false, emptyList(), translateException(e)))
                return@launch
            } finally {
                conn.disconnect()
            }

            val models = runCatching {
                val data = json.parseToJsonElement(body).jsonObject["data"] as? JsonArray
                data?.mapNotNull { el ->
                    val obj = el.jsonObject
                    val id = obj["id"]?.jsonPrimitive?.contentOrNull() ?: return@mapNotNull null
                    val ctx = obj["context_length"]?.jsonPrimitive?.intOrNullSafe()
                    // Several providers (NVIDIA among them) do not send
                    // supported_parameters at all. Unknown, not "yes".
                    val supports = (obj["supported_parameters"] as? JsonArray)
                        ?.any { it.jsonPrimitive.contentOrNull() == "tools" }
                    ModelInfo(id, ctx, supports)
                } ?: emptyList()
            }.getOrElse { emptyList() }

            if (models.isEmpty()) {
                callback(ModelListResult(false, emptyList(), "Provider returned no models"))
            } else {
                callback(ModelListResult(true, models.sortedBy { it.id }, ""))
            }
        }
    }

    /**
     * Sends the shortest possible real request so the user gets a green/red
     * answer without guessing whether their key works.
     */
    fun testConnection(context: Context, callback: (Result) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            val history = mutableListOf(userMessage("Reply with the single word: ok"))
            val response = chatOnce(context, history)
            if (response.success) {
                callback(Result(true, "Connected. Model answered: ${response.content ?: "ok"}", 1))
            } else {
                callback(Result(false, response.error, 1))
            }
        }
    }

    private data class ArgSpec(
        val name: String,
        val desc: String,
        /** OpenAI JSON-schema types; more than one means "any of". */
        val jsonTypes: List<String>,
        val required: Boolean = true,
        /** Element type for arrays — some providers reject a bare "array". */
        val itemsType: String? = null
    )

    private data class ToolSchema(
        val name: String,
        val args: List<ArgSpec>,
        val note: String? = null
    ) {
        fun promptArgs(): String =
            if (args.isEmpty()) "no arguments"
            else args.joinToString(", ") { a ->
                "${a.name}${if (a.required) "" else "?"}: ${a.desc}"
            }
    }

    private fun iArg(name: String, desc: String = name, required: Boolean = true) =
        ArgSpec(name, desc, listOf("integer"), required)

    // A colour may arrive as an ARGB int, "#RRGGBB", or "r,g,b,a", so the schema
    // has to accept both or strict providers reject the call.
    private fun colourArg(name: String = "color", required: Boolean = true) =
        ArgSpec(name, "ARGB int, \"#RRGGBB\", or \"r,g,b,a\"", listOf("integer", "string"), required)

    private fun bArg(name: String = "filled", required: Boolean = true) =
        ArgSpec(name, "boolean", listOf("boolean"), required)

    private fun sArg(name: String, desc: String = name, required: Boolean = true) =
        ArgSpec(name, desc, listOf("string"), required)

    private fun aArg(name: String, desc: String, required: Boolean = true) =
        ArgSpec(name, desc, listOf("array"), required, itemsType = "integer")

    private fun oArg(name: String, desc: String, required: Boolean = true) =
        ArgSpec(name, desc, listOf("object"), required)

    /**
     * The tool catalogue, in one place: used for BOTH the system prompt (compact
     * prose for models that read it) and the OpenAI `tools` array below (JSON
     * schemas the provider needs to enable function calling).
     */
    private fun toolSchemas(): List<ToolSchema> = listOf(
        ToolSchema(
            "get_canvas_ascii",
            listOf(bArg("allLayers", required = false)),
            "CALL THIS FIRST — canvas as a text grid plus a colour legend. " +
                "Symbols are stable for a colour across calls."
        ),
        ToolSchema(
            "get_state",
            emptyList(),
            "canvas size, selected layer/frame, layer and frame counts"
        ),
        ToolSchema(
            "draw_sprite_from_ascii",
            listOf(
                sArg("ascii", "one character per pixel, rows separated by newlines"),
                oArg("legend", "object mapping character -> colour"),
                bArg("clear", "wipe the target cell first", required = false),
                iArg("offset_x", "grid placement", required = false),
                iArg("offset_y", "grid placement", required = false),
                iArg("layer", "draw into this layer instead of the selected one", required = false),
                iArg("frame", "draw into this frame instead of the selected one", required = false)
            ),
            "BEST way to draw — whole sprite in one call. Pass layer/frame when " +
                "working on an animation so the drawing lands on the right cell."
        ),
        ToolSchema(
            "draw_rect",
            listOf(
                iArg("x0"), iArg("y0"), iArg("x1"), iArg("y1"),
                colourArg(required = false), bArg(required = false)
            )
        ),
        ToolSchema(
            "draw_ellipse",
            listOf(
                iArg("x0"), iArg("y0"), iArg("x1"), iArg("y1"),
                colourArg(required = false), bArg(required = false)
            )
        ),
        ToolSchema(
            "draw_line",
            listOf(iArg("x0"), iArg("y0"), iArg("x1"), iArg("y1"), colourArg(required = false))
        ),
        ToolSchema(
            "draw_pixels",
            listOf(
                aArg("points", "array of [x, y] pairs"),
                colourArg(required = false)
            )
        ),
        ToolSchema(
            "draw_pixel",
            listOf(iArg("x"), iArg("y"), colourArg(required = false))
        ),
        ToolSchema(
            "fill",
            listOf(iArg("x"), iArg("y"), colourArg(required = false))
        ),
        ToolSchema("clear_layer", emptyList()),
        ToolSchema("add_layer", emptyList()),
        ToolSchema(
            "add_frame",
            emptyList(),
            "append a new blank frame; it becomes the selected one"
        ),
        ToolSchema(
            "duplicate_frame",
            emptyList(),
            "copy the selected frame, so the next one starts identical"
        ),
        ToolSchema("undo", emptyList()),
        ToolSchema("get_frames", emptyList()),
        ToolSchema("select_frame", listOf(iArg("index"))),
        ToolSchema("export_gif", listOf(sArg("name"), iArg("fps", required = false)))
    )

    /** Serialises the catalogue into the OpenAI `tools` array. */
    private fun toolsPayload(): JsonArray = buildJsonArray {
        for (t in toolSchemas()) {
            add(buildJsonObject {
                put("type", "function")
                put("function", buildJsonObject {
                    put("name", t.name)
                    put(
                        "description",
                        t.note ?: "Pixel-art editor tool. See the system prompt for the " +
                            "coordinate and colour conventions."
                    )
                    put("parameters", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            for (a in t.args) {
                                put(a.name, buildJsonObject {
                                    if (a.jsonTypes.size == 1) put("type", a.jsonTypes.first())
                                    else put("type", buildJsonArray { a.jsonTypes.forEach { add(JsonPrimitive(it)) } })
                                    put("description", a.desc)
                                    // Arrays without `items` are rejected by several providers.
                                    a.itemsType?.let { put("items", buildJsonObject { put("type", it) }) }
                                })
                            }
                        })
                        put(
                            "required",
                            buildJsonArray { t.args.filter { it.required }.forEach { add(JsonPrimitive(it.name)) } }
                        )
                    })
                })
            })
        }
    }

    private fun chatOnce(context: Context, history: List<JsonObject>): ChatResponse {
        val url = "${AgentConfig.baseUrl(context)}/chat/completions"
        val payload = buildJsonObject {
            put("model", AgentConfig.model(context))
            put("messages", buildJsonArray { history.forEach { add(it) } })
            put("stream", false)
            put("tool_choice", "auto")
            // The provider needs the tool schemas to allow function calling at
            // all: without this array many backends silently answer with text
            // even though tool_choice is "auto".
            put("tools", toolsPayload())
        }.toString()

        // HttpURLConnection is abstract: openConnection() gives the real impl.
        val conn = java.net.URI(url).toURL().openConnection() as java.net.HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        val key = AgentConfig.apiKey(context)
        if (key.isNotBlank()) {
            conn.setRequestProperty("Authorization", "Bearer $key")
        }
        conn.setDoOutput(true)
        // A reply can take a while (long prompts + ASCII grids), but it must not
        // hang forever on a dead network.
        conn.connectTimeout = 30_000
        conn.readTimeout = 180_000
        conn.outputStream.use { it.write(payload.toByteArray()) }

        val body = try {
            val code = conn.responseCode
            val raw = if (code >= 400) {
                conn.errorStream?.bufferedReader()?.readText() ?: ""
            } else {
                conn.inputStream.bufferedReader().readText()
            }
            if (code >= 400) return ChatResponse(
                success = false,
                error = translateHttpError(code, raw)
            )
            raw
        } catch (e: Exception) {
            return ChatResponse(success = false, error = translateException(e))
        } finally {
            conn.disconnect()
        }

        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return ChatResponse(success = false, error = "bad JSON response")
        val errorObj = root["error"]?.jsonObject
        if (errorObj != null) {
            val msg = errorObj["message"]?.jsonPrimitive?.content ?: "provider error"
            return ChatResponse(success = false, error = msg)
        }

        val choice = (root["choices"] as? JsonArray)?.firstOrNull()?.jsonObject
            ?: return ChatResponse(success = false, error = "no choices in response")
        val message = choice["message"]?.jsonObject
            ?: return ChatResponse(success = false, error = "no message in response")

        val toolCalls = mutableListOf<ToolCall>()
        (message["tool_calls"] as? JsonArray)?.forEach { el ->
            val tc = el.jsonObject
            val id = tc["id"]?.jsonPrimitive?.content ?: return@forEach
            val fn = tc["function"]?.jsonObject ?: return@forEach
            val name = fn["name"]?.jsonPrimitive?.content ?: return@forEach
            val args = fn["arguments"]?.jsonPrimitive?.content ?: "{}"
            toolCalls.add(ToolCall(id, name, args))
        }

        val content = message["content"]?.jsonPrimitive?.content
        return ChatResponse(success = true, content = content, toolCalls = toolCalls)
    }

    /** Execute one tool call through the same dispatcher the HTTP server uses. */
    private fun executeTool(call: ToolCall): String {
        val args = runCatching {
            json.parseToJsonElement(call.arguments).jsonObject
        }.getOrElse { JsonObject(emptyMap()) }
        val body = buildJsonObject {
            put("tool", call.name)
            put("args", args)
        }.toString()
        return runCatching { McpApi.handle(body) }
            .getOrElse { "{\"ok\":false,\"error\":${JsonPrimitive(it.message ?: "tool failed")}}" }
    }

    // ---- prompt ------------------------------------------------------------

    private fun buildSystemPrompt(): String {
        val sb = StringBuilder()
        sb.append("You are an AI agent controlling a pixel-art editor called Resprite.\n\n")
        sb.append("YOU MUST ACT, NOT EXPLAIN.\n")
        sb.append("If the user asks you to draw or change anything, call a drawing tool in your " +
            "VERY FIRST reply. Never answer with instructions, a plan, or a code listing instead " +
            "of a tool call — that is treated as a failure.\n\n")
        sb.append("HOW TO DRAW (important):\n")
        sb.append("You cannot see the canvas, and guessing pixel coordinates produces bad art. ")
        sb.append("Use the ASCII grid workflow instead:\n")
        sb.append("1. Call get_canvas_ascii. You get a grid of characters (one char = one pixel, ")
        sb.append("one row per line) plus a legend mapping each character to an AARRGGBB colour. ")
        sb.append("'.' means transparent.\n")
        sb.append("2. Edit that grid in your head: place the right character wherever a pixel should ")
        sb.append("change. You may also invent NEW characters for new colours, and add them to the legend.\n")
        sb.append("3. Send the whole edited grid back with draw_sprite_from_ascii ")
        sb.append("(ascii = the grid text, legend = char->colour map). Off-screen characters are ignored, ")
        sb.append("so spaces/dots mean \"leave transparent\".\n")
        sb.append("4. Call get_canvas_ascii again to verify your result and fix mistakes.\n\n")
        sb.append("RULES:\n")
        sb.append("- Draw the whole sprite in ONE draw_sprite_from_ascii call. Do not place pixels one at a time.\n")
        sb.append("- Keep the legend small (3-8 colours) and reuse characters for the same colour.\n")
        sb.append("- Plan the art first: silhouette, then flat colours, then shading/highlights.\n")
        sb.append("- You may call more than one tool in a single reply when they are independent.\n")
        sb.append("- Only when the drawing really is complete do you reply with plain text " +
            "(no tool call) and a one-line summary.\n\n")
        sb.append("ANIMATION (when the user asks for more than one frame):\n")
        sb.append("- NEVER redraw a frame from memory. Fixup-only keeps shapes aligned.\n")
        sb.append("- Correct sequence per frame: get_state, duplicate_frame, " +
            "get_canvas_ascii, then draw_sprite_from_ascii with ONLY the pixels that move.\n")
        sb.append("- Keep the legend byte-for-byte identical between frames: the same character " +
            "must mean the same colour in every frame, or colours shift between them.\n")
        sb.append("- Pass the frame index explicitly (frame=N) so the drawing cannot land on the " +
            "wrong cell if the user touches the frame strip.\n")
        sb.append("- Draw the SAME size every frame and keep the subject anchored to the same " +
            "origin; shift it by whole pixels only.\n\n")
        sb.append("COLOURS on the wire: ARGB ints (-65536 = opaque red, -1 = opaque white, 0 = transparent) ")
        sb.append("OR strings: \"#RRGGBB\", \"#AARRGGBB\", or \"r,g,b,a\" 0-255.\n")
        sb.append("COORDINATES: origin top-left, inclusive, so x=0,y=0 is the first pixel.\n\n")
        sb.append("TOOLS (these are also declared to the API as callable functions):\n")
        for (t in toolSchemas()) {
            val note = t.note?.let { "  // $it" } ?: ""
            sb.append("- ${t.name}(${t.promptArgs()})$note\n")
        }
        return sb.toString()
    }
}
