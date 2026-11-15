package com.fengeon.resprite_desktop.net

import android.content.Context

/**
 * Which LLM provider the in-app agent talks to, and how.
 *
 * The API key is stored only in this app's private SharedPreferences
 * (shared_prefs/resprite_agent.xml), never in a file another app can read and
 * never committed to git — the user pastes it once in the AI sheet.
 *
 * All three endpoints speak the OpenAI chat-completions wire format, so a single
 * request/response builder covers them: the model answers with a JSON tool call,
 * and we feed its result back for the next step (the agent loop in
 * [AgentClient]).
 */
object AgentConfig {

    const val PREFS = "resprite_agent"
    private const val KEY_PROVIDER = "provider"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_MODEL = "model"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_MAX_STEPS = "max_steps"

    /** Provider ids as shown in the sheet's dropdown. */
    const val PROVIDER_OPENROUTER = "openrouter"
    const val PROVIDER_NVIDIA = "nvidia"
    const val PROVIDER_CUSTOM = "custom"

    data class Provider(val id: String, val label: String, val defaultModel: String) {
        companion object {
            val OPENROUTER = Provider(PROVIDER_OPENROUTER, "OpenRouter", "nvidia/nemotron-3.5-lightning:free")
            val NVIDIA = Provider(PROVIDER_NVIDIA, "NVIDIA NIM", "nvidia/llama-3.1-nemotron-70b-instruct")
            val CUSTOM = Provider(PROVIDER_CUSTOM, "Custom (OpenAI-compatible)", "")
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var provider: String = PROVIDER_OPENROUTER

    /** Base URL of the chat-completions endpoint (without the path). */
    fun baseUrl(context: Context, providerId: String = provider): String = when (providerId) {
        PROVIDER_OPENROUTER -> "https://openrouter.ai/api/v1"
        PROVIDER_NVIDIA -> "https://integrate.api.nvidia.com/v1"
        // A custom provider lets the user point at any OpenAI-compatible server —
        // a local llama.cpp, LM Studio, vLLM or a self-hosted gateway — which is
        // the key to "no external service" if they run the model themselves.
        PROVIDER_CUSTOM -> prefs(context).getString(KEY_BASE_URL, "http://127.0.0.1:8080/v1") ?: ""
        else -> ""
    }

    fun apiKey(context: Context): String =
        prefs(context).getString(KEY_API_KEY, "") ?: ""

    fun model(context: Context): String =
        prefs(context).getString(KEY_MODEL, providerDefaultModel()) ?: providerDefaultModel()

    /**
     * How many tool round-trips the agent may take before it must stop. Each step
     * is one model request plus one executed tool, so 25 is a generous ceiling
     * that still keeps the run bounded.
     */
    fun maxSteps(context: Context): Int =
        prefs(context).getInt(KEY_MAX_STEPS, 25)

    private fun providerDefaultModel(): String = when (provider) {
        PROVIDER_NVIDIA -> Provider.NVIDIA.defaultModel
        else -> Provider.OPENROUTER.defaultModel
    }

    fun save(
        context: Context,
        providerId: String,
        apiKey: String,
        model: String,
        baseUrl: String,
        maxSteps: Int
    ) {
        provider = providerId
        prefs(context).edit()
            .putString(KEY_PROVIDER, providerId)
            .putString(KEY_API_KEY, apiKey)
            .putString(KEY_MODEL, model)
            .putString(KEY_BASE_URL, baseUrl)
            .putInt(KEY_MAX_STEPS, maxSteps.coerceIn(1, 60))
            .apply()
    }

    /** True when the user has entered everything needed to make a call. */
    fun isConfigured(context: Context): Boolean {
        val key = apiKey(context)
        val custom = baseUrl(context)
        return when (provider) {
            // Custom endpoints may be local and unauthenticated.
            PROVIDER_CUSTOM -> custom.isNotBlank()
            else -> key.isNotBlank()
        }
    }
}