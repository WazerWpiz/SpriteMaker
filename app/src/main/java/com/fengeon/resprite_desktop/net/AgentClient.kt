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

            var step = 0
            while (step < maxSteps) {
                if (isCancelled()) {
                    onResult(Result(false, "Stopped by you at step $step of $maxSteps.", step))
                    return@launch
                }
                step++
                val response = chatOnce(context, history)
                if (!response.success) {
                    onResult(Result(false, "API error: ${response.error}", step))
                    return@launch
                }

                if (response.toolCalls.isNotEmpty()) {
                    // Record the assistant turn that requested the tools, so the
                    // provider sees a coherent conversation on the next call.
                    history.add(
                        buildJsonObject {
                            put("role", "assistant")
                            put("content", "")
                            put("tool_calls", buildJsonArray {
                                for (call in response.toolCalls) {
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
                    for (call in response.toolCalls) {
                        onStep(step, call.name, call.arguments)
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

                val finalText = response.content ?: "No response from model."
                onResult(Result(true, finalText, step))
                return@launch
            }

            onResult(Result(false, "Max steps ($maxSteps) reached without completion.", maxSteps))
        }
    }

    // ---- model I/O ---------------------------------------------------------

    private data class ToolCall(
        val id: String,
        val name: String,
        val arguments: String
    )

    private data class ChatResponse(
        val success: Boolean,
        val error: String = "",
        val content: String? = null,
        val toolCalls: List<ToolCall> = emptyList()
    )

    data class ModelInfo(
        val id: String,
        val contextLength: Int?,
        val supportsTools: Boolean
    )

    data class ModelListResult(
        val success: Boolean,
        val models: List<ModelInfo>,
        val error: String
    )

    /**
     * Lists the models the configured provider exposes (`GET /models`), so the
     * user never has to type a model id and a typo cannot silently break every
     * run. Both OpenRouter and NVIDIA speak the OpenAI wire format, so one
     * implementation covers them; a Custom endpoint gets the same call.
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
                    callback(
                        ModelListResult(false, emptyList(), translateHttpError(code, raw))
                    )
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
                    val id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val ctx = obj["context_length"]?.jsonPrimitive?.intOrNullSafe()
                    // Providers disagree on where support flags live.
                    val supportsObj = obj["supported_parameters"] as? JsonArray
                    val supports = supportsObj?.any {
                        it.jsonPrimitive.contentOrNull() == "tools"
                    } ?: true
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

    private fun kotlinx.serialization.json.JsonPrimitive.intOrNullSafe(): Int? =
        runCatching { int }.getOrNull()

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNull(): String? =
        if (this is JsonPrimitive) content else null

    /** Turns an HTTP status into something a person can act on. */
    private fun translateHttpError(code: Int, raw: String): String {
        // Pull the provider's own message out if it sent a JSON error body.
        val detail = runCatching {
            json.parseToJsonElement(raw).jsonObject["error"]?.let { err ->
                (err as? JsonObject)?.get("message")?.jsonPrimitive?.content
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

    private fun systemMessage(content: String): JsonObject = buildJsonObject {
        put("role", "system")
        put("content", content)
    }

    private fun userMessage(content: String): JsonObject = buildJsonObject {
        put("role", "user")
        put("content", content)
    }

    private fun chatOnce(context: Context, history: List<JsonObject>): ChatResponse {
        val url = "${AgentConfig.baseUrl(context)}/chat/completions"
        val payload = buildJsonObject {
            put("model", AgentConfig.model(context))
            put("messages", buildJsonArray { history.forEach { add(it) } })
            put("stream", false)
            put("tool_choice", "auto")
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

    private data class ToolSchema(
        val name: String,
        val args: Map<String, String>,
        val note: String? = null
    )

    private fun buildSystemPrompt(): String {
        val tools = listOf(
            ToolSchema("ping", mapOf()),
            ToolSchema("get_state", mapOf()),
            ToolSchema("get_canvas_ascii", mapOf(), "RECOMMENDED FIRST — canvas as a text grid + legend"),
            ToolSchema("get_canvas", mapOf("allLayers" to "boolean"), "PNG base64"),
            ToolSchema("draw_sprite_from_ascii", mapOf(
                "ascii" to "string: one char per pixel, rows separated by \\n",
                "legend" to "object char -> colour",
                "clear" to "boolean",
                "offset_x" to "int", "offset_y" to "int"), "BEST way to draw"),
            ToolSchema("draw_pixel", mapOf("x" to "int", "y" to "int", "color" to "int")),
            ToolSchema("draw_pixels", mapOf("points" to "array<int,int>", "color" to "int")),
            ToolSchema("draw_line", mapOf("x0" to "int", "y0" to "int", "x1" to "int", "y1" to "int", "color" to "int", "filled" to "boolean")),
            ToolSchema("draw_rect", mapOf("x0" to "int", "y0" to "int", "x1" to "int", "y1" to "int", "color" to "int", "filled" to "boolean")),
            ToolSchema("draw_ellipse", mapOf("x0" to "int", "y0" to "int", "x1" to "int", "y1" to "int", "color" to "int", "filled" to "boolean")),
            ToolSchema("draw_polyline", mapOf("points" to "array<int,int>", "color" to "int")),
            ToolSchema("fill", mapOf("x" to "int", "y" to "int", "color" to "int")),
            ToolSchema("clear_layer", mapOf()),
            ToolSchema("add_layer", mapOf()),
            ToolSchema("add_frame", mapOf()),
            ToolSchema("undo", mapOf()),
            ToolSchema("export_gif", mapOf("name" to "string", "fps" to "int"))
        )

        val sb = StringBuilder()
        sb.append("You are an AI agent controlling a pixel-art editor called Resprite.\n\n")
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
        sb.append("- When you are done, reply with plain text (no tool call) and a one-line summary.\n\n")
        sb.append("COLOURS on the wire: ARGB ints (-65536 = opaque red, -1 = opaque white, 0 = transparent) ")
        sb.append("OR strings: \"#RRGGBB\", \"#AARRGGBB\", or \"r,g,b,a\" 0-255.\n")
        sb.append("COORDINATES: origin top-left, inclusive, so x=0,y=0 is the first pixel.\n\n")
        sb.append("TOOLS:\n")
        for (t in tools) {
            val argsDesc = if (t.args.isEmpty()) "no arguments" else
                t.args.entries.joinToString(", ") { "${it.key}:${it.value}" }
            val note = t.note?.let { "  // $it" } ?: ""
            sb.append("- ${t.name}($argsDesc)$note\n")
        }
        return sb.toString()
    }
}
