package com.fengeon.resprite_desktop.net

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * In-app AI agent: run a multi-step loop against the LLM, executing every tool
 * call the model returns until it either finishes or hits the step ceiling.
 *
 * Flow (one agent run):
 *   1. Build the system prompt that lists all available MCP tools (from McpApi).
 *   2. Send user prompt + tool schema to the LLM.
 *   3. If model answers with a tool call → execute it via McpApi.handle(),
 *      append the tool result, repeat.
 *   4. If model answers with text (no tool call) → that's the final answer.
 *   5. Stop when maxSteps is reached or model signals "done".
 *
 * The whole loop runs on Dispatchers.IO so the UI never freezes. Tool execution
 * itself hops back to the main thread via RespriteEngine.onMain() (the same
 * path the HTTP server uses), so it's fully thread-safe.
 *
 * Result is delivered through a callback so the caller (the Compose sheet)
 * can show progress and the final message.
 */
object AgentClient {

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
        // Must be configured first.
        if (!AgentConfig.isConfigured(context)) {
            onResult(Result(false, "Agent not configured: missing API key / base URL.", 0))
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            val maxSteps = AgentConfig.maxSteps(context)
            val systemPrompt = buildSystemPrompt()
            val messages = mutableListOf<JsonObject>()
                .apply { add(systemMessage(systemPrompt)) }
                .apply { add(userMessage(userPrompt)) }

            var step = 0
            while (step < maxSteps) {
                step++
                val response = chatOnce(context, messages)
                if (!response.success) {
                    onResult(Result(false, "API error: ${response.content ?: "unknown"}", step))
                    return@launch
                }

                // If the model returned tool calls, execute them one by one
                if (response.toolCalls.isNotEmpty()) {
                    for (toolCall in response.toolCalls) {
                        onStep(step, toolCall.name, toolCall.arguments)
                        val toolResult = executeTool(toolCall)
                        // Append tool result as a tool message
                        messages.add(toolMessage(toolCall.id, toolResult))
                    }
                    // Continue the loop to let the model respond to tool results
                    continue
                }

                // Model answered with text (no tool call) — this is the final answer
                val finalText = response.content ?: "No response from model."
                onResult(Result(true, finalText, step))
                return@launch
            }

            onResult(Result(false, "Max steps ($maxSteps) reached without completion.", maxSteps))
        }
    }

    // ---- internal ----------------------------------------------------------

    private data class ChatResponse(
        val success: Boolean,
        val content: String?,
        val toolCalls: List<ToolCall>
    )

    private data class ToolCall(
        val id: String,
        val name: String,
        val arguments: String
    )

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
        sb.append("You are an AI agent controlling a pixel art editor (Resprite). ")
        sb.append("You can draw, edit layers/frames, and export art by calling tools. ")
        sb.append("Canvas coordinates: origin top-left, inclusive. ")
        sb.append("Colors are ARGB ints (e.g. -65536 = opaque red, -1 = opaque white, 0 = transparent). ")
        sb.append("When the user asks you to do something, decide which tool(s) to call. ")
        sb.append("Call ONE tool at a time. After each tool result, you will be told the outcome. ")
        sb.append("When the task is complete, answer with plain text (no tool call). ")
        sb.append("Available tools:\n")
        for (t in tools) {
            val argsDesc = if (t.args.isEmpty()) "no args" else t.args.entries.joinToString(", ") { "${it.key}: ${it.value}" }
            sb.append("- ${t.name}($argsDesc)\n")
        }
        return sb.toString()
    }

    private data class ToolSchema(val name: String, val args: Map<String, String>)

    private fun systemMessage(content: String): JsonObject = JsonObject().apply {
        add("role", JsonPrimitive("system"))
        add("content", JsonPrimitive(content))
    }

    private fun userMessage(content: String): JsonObject = JsonObject().apply {
        add("role", JsonPrimitive("user"))
        add("content", JsonPrimitive(content))
    }

    private fun assistantMessage(content: String): JsonObject = JsonObject().apply {
        add("role", JsonPrimitive("assistant"))
        add("content", JsonPrimitive(content))
    }

    private fun toolMessage(toolCallId: String, content: String): JsonObject = JsonObject().apply {
        add("role", JsonPrimitive("tool"))
        add("tool_call_id", JsonPrimitive(toolCallId))
        add("content", JsonPrimitive(content))
    }

    /** Call the LLM once with the current message history. */
    private fun chatOnce(context: Context, messages: List<JsonObject>): ChatResponse {
        val gson = Gson()
        val url = "${AgentConfig.baseUrl(context)}/chat/completions"
        val model = AgentConfig.model(context)

        val payload = JsonObject().apply {
            add("model", JsonPrimitive(model))
            add("messages", gson.toJsonTree(messages))
            add("stream", JsonPrimitive(false))
            add("tool_choice", JsonPrimitive("auto"))
        }

        val jsonStr = gson.toJson(payload)

        // HTTP POST
        val conn = java.net.HttpURLConnection(java.net.URI(url).toURL())
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        val key = AgentConfig.apiKey(context)
        if (key.isNotBlank()) {
            conn.setRequestProperty("Authorization", "Bearer $key")
        }
        conn.setDoOutput(true)
        conn.outputStream.use { it.write(jsonStr.toByteArray()) }

        val responseCode = conn.responseCode
        val responseText = if (responseCode >= 400) {
            conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $responseCode"
        } else {
            conn.inputStream.bufferedReader().readText()
        }
        conn.disconnect()

        if (responseCode >= 400) {
            return ChatResponse(false, "HTTP $responseCode: $responseText", emptyList())
        }

        // Parse response
        val respObj = gson.fromJson(responseText, JsonObject::class.java)
        val choices = respObj["choices"]?.asJsonArray
        if (choices == null || choices.isEmpty()) {
            return ChatResponse(false, "No choices in response", emptyList())
        }
        val choice = choices[0].asJsonObject
        val message = choice["message"]?.asJsonObject
        if (message == null) {
            return ChatResponse(false, "No message in choice", emptyList())
        }

        // Extract tool calls (OpenAI format)
        val toolCalls = mutableListOf<ToolCall>()
        val toolCallsArray = message["tool_calls"]?.asJsonArray
        if (toolCallsArray != null) {
            for (tc in toolCallsArray) {
                val tcObj = tc.asJsonObject
                val id = tcObj["id"]?.asString ?: ""
                val fn = tcObj["function"]?.asJsonObject
                if (fn != null) {
                    val name = fn["name"]?.asString ?: ""
                    val args = fn["arguments"]?.asString ?: "{}"
                    toolCalls.add(ToolCall(id, name, args))
                }
            }
        }

        val content = message["content"]?.asString

        return ChatResponse(true, content, toolCalls)
    }

    /** Execute a single tool call via the local McpApi and return the JSON string. */
    private fun executeTool(toolCall: ToolCall): String {
        // Build the body exactly like the HTTP server does
        val body = buildJsonObject {
            put("tool", toolCall.name)
            // Parse the arguments JSON string into JsonObject
            val argsJson = Json { ignoreUnknownKeys = true }.parseToJsonElement(toolCall.arguments).jsonObject
            put("args", argsJson)
        }.toString()
        return com.fengeon.resprite_desktop.net.McpApi.handle(body)
    }
}