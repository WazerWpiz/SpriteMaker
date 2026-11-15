package com.fengeon.resprite_desktop.net

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shared, process-wide view of the agent run.
 *
 * The editor toolbar button and the prompt panel both observe this, so:
 *  - closing the panel must not break a run that is still going (the coroutine
 *    lives here, not in a composable that gets disposed);
 *  - the toolbar icon can show "agent is working" without the panel being open;
 *  - Stop works from either place.
 *
 * The last few prompts are persisted so a useful one can be re-run without
 * retyping it.
 */
object AgentRun {

    data class Progress(
        val step: Int,
        val maxSteps: Int,
        val tool: String?,
        val args: String?,
        val status: String,
        val finished: Boolean,
        val success: Boolean
    ) {
        val label: String
            get() = when {
                finished && success -> "Done"
                finished && !success -> "Error"
                tool != null -> "$tool"
                else -> "Thinking…"
            }
    }

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress.asStateFlow()

    private val _history = MutableStateFlow<List<String>>(emptyList())
    val history: StateFlow<List<String>> = _history.asStateFlow()

    /** Rolling log of what the agent did, newest last. Survives panel closing. */
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    @Volatile
    private var cancelled = false

    @Volatile
    private var running = false

    val isRunning: Boolean get() = running

    /** Starts a run. Ignores the call if one is already going. */
    fun start(context: Context, prompt: String) {
        if (running) return
        if (!AgentConfig.isConfigured(context)) {
            _progress.value = Progress(
                step = 0,
                maxSteps = AgentConfig.maxSteps(context),
                tool = null,
                args = null,
                status = "Not configured — open AI access (⚙) to set your API key.",
                finished = true,
                success = false
            )
            return
        }
        running = true
        cancelled = false
        rememberPrompt(context, prompt)
        _log.value = emptyList()
        logLine("> $prompt")
        val max = AgentConfig.maxSteps(context)
        _progress.value = Progress(0, max, null, null, "Thinking…", false, true)
        AgentClient.run(
            context = context,
            userPrompt = prompt,
            isCancelled = { cancelled },
            onStep = { step, tool, args ->
                    val detail = args?.takeIf { it.isNotBlank() && it != "{}" }
                    logLine("step $step/$max — ${tool ?: "thinking"}${detail?.let { "\n  $it" } ?: ""}")
                _progress.value = Progress(
                    step = step,
                    maxSteps = max,
                    tool = tool,
                    args = args,
                    status = tool?.let { "Calling $it" } ?: "Thinking…",
                    finished = false,
                    success = true
                )
            },
            onResult = { result ->
                running = false
                logLine(if (result.success) "done: ${result.text}" else "error: ${result.text}")
                _progress.value = Progress(
                    step = result.steps,
                    maxSteps = max,
                    tool = null,
                    args = null,
                    status = if (result.success) result.text else result.text,
                    finished = true,
                    success = result.success
                )
            }
        )
    }

    /** Asks the loop to stop after the current step. Safe to call when idle. */
    fun stop() {
        cancelled = true
    }

    fun clearProgress() {
        if (!running) _progress.value = null
    }

    // ---- prompt history ----------------------------------------------------

    private const val PREFS_HISTORY = "agent_prompts"

    private fun logLine(line: String) {
        _log.value = (_log.value + line).takeLast(60)
    }

    private fun rememberPrompt(context: Context, prompt: String) {
        val text = prompt.trim()
        if (text.isEmpty()) return
        val existing = history(context)
        val updated = (listOf(text) + existing).distinct().take(8)
        context.getSharedPreferences(PREFS_HISTORY, Context.MODE_PRIVATE)
            .edit()
            .putString("prompts", updated.joinToString("\n"))
            .apply()
        _history.value = updated
    }

    private fun history(context: Context): List<String> {
        if (_history.value.isNotEmpty()) return _history.value
        val stored = context.getSharedPreferences(PREFS_HISTORY, Context.MODE_PRIVATE)
            .getString("prompts", "")
            ?.split('\n')
            ?.filter { it.isNotBlank() }
            ?: emptyList()
        _history.value = stored
        return stored
    }

    /** Prompts that were used before this app process started. */
    fun loadHistory(context: Context): List<String> = history(context)
}