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
        conn.outputStream.use { it.write(payload.toByteArray()) }

        val body = try {
            conn.let {
                val code = it.responseCode
                if (code >= 400) (it.errorStream?.bufferedReader()?.readText() ?: "HTTP $code")
                else it.inputStream.bufferedReader().readText()
            }
        } catch (e: Exception) {
            return ChatResponse(success = false, error = "network: ${e.message}")
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

    private data class ToolSchema(val name: String, val args: Map<String, String>)

    private fun buildSystemPrompt(): String {
        val tools = listOf(
            ToolSchema("ping", mapOf()),
            ToolSchema("help", mapOf()),
            ToolSchema("get_state", mapOf()),
            ToolSchema("get_canvas", mapOf("allLayers" to "boolean")),
            ToolSchema("get_layer_canvas", mapOf()),
            ToolSchema("get_pixel", mapOf("x" to "int", "y" to "int")),
            ToolSchema("get_palette", mapOf()),
            ToolSchema("draw_pixel", mapOf("x" to "int", "y" to "int", "color" to "int")),
            ToolSchema("draw_pixels", mapOf("points" to "array<int,int>", "color" to "int")),
            ToolSchema("draw_line", mapOf("x0" to "int", "y0" to "int", "x1" to "int", "y1" to "int", "color" to "int", "filled" to "boolean")),
            ToolSchema("draw_rect", mapOf("x0" to "int", "y0" to "int", "x1" to "int", "y1" to "int", "color" to "int", "filled" to "boolean")),
            ToolSchema("draw_ellipse", mapOf("x0" to "int", "y0" to "int", "x1" to "int", "y1" to "int", "color" to "int", "filled" to "boolean")),
            ToolSchema("draw_polyline", mapOf("points" to "array<int,int>", "color" to "int")),
            ToolSchema("fill", mapOf("x" to "int", "y" to "int", "color" to "int")),
            ToolSchema("erase_pixel", mapOf("x" to "int", "y" to "int")),
            ToolSchema("erase_pixels", mapOf("points" to "array<int,int>")),
            ToolSchema("clear_layer", mapOf()),
            ToolSchema("set_color", mapOf("color" to "int")),
            ToolSchema("set_secondary_color", mapOf("color" to "int")),
            ToolSchema("add_layer", mapOf()),
            ToolSchema("delete_layer", mapOf()),
            ToolSchema("duplicate_layer", mapOf()),
            ToolSchema("move_layer", mapOf("from" to "int", "to" to "int")),
            ToolSchema("set_layer_visible", mapOf("visible" to "boolean")),
            ToolSchema("set_layer_locked", mapOf("locked" to "boolean")),
            ToolSchema("set_layer_opacity", mapOf("opacity" to "float")),
            ToolSchema("rename_layer", mapOf("name" to "string")),
            ToolSchema("select_layer", mapOf("index" to "int")),
            ToolSchema("select_frame", mapOf("index" to "int")),
            ToolSchema("add_frame", mapOf()),
            ToolSchema("duplicate_frame", mapOf()),
            ToolSchema("delete_frame", mapOf()),
            ToolSchema("set_frame_duration", mapOf("duration" to "int")),
            ToolSchema("set_frame_rate", mapOf("fps" to "int")),
            ToolSchema("get_frames", mapOf()),
            ToolSchema("flip_h", mapOf("all_cells" to "boolean")),
            ToolSchema("flip_v", mapOf("all_cells" to "boolean")),
            ToolSchema("resize_canvas", mapOf("width" to "int", "height" to "int", "all_cells" to "boolean")),
            ToolSchema("undo", mapOf()),
            ToolSchema("redo", mapOf()),
            ToolSchema("export_gif", mapOf("name" to "string", "fps" to "int")),
            ToolSchema("export_sheet", mapOf("name" to "string"))
        )

        val sb = StringBuilder()
        sb.append("You are an AI agent controlling a pixel art editor called Resprite.\n")
        sb.append("You draw by calling ONE tool at a time. You get the tool result, then decide the next step.\n")
        sb.append("Finish by replying with plain text and no tool call.\n")
        sb.append("Canvas coordinates: origin top-left, inclusive.\n")
        sb.append("Colors are ARGB ints: -65536 = opaque red, -1 = opaque white, 0 = transparent.\n")
        sb.append("Prefer get_state first so you know the canvas size.\n\n")
        sb.append("Available tools:\n")
        for (t in tools) {
            val argsDesc = if (t.args.isEmpty()) {
                "no arguments"
            } else {
                t.args.entries.joinToString(", ") { "${it.key}:${it.value}" }
            }
            sb.append("- ${t.name}($argsDesc)\n")
        }
        return sb.toString()
    }
}