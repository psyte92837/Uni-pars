package com.unipars.ai

/** Providers the user can pick in settings; each uses the user's own API key. */
enum class AiProvider(val id: String, val title: String, val defaultModel: String, val keyHint: String, val keyUrl: String) {
    GEMINI("gemini", "Google Gemini", GeminiClient.DEFAULT_MODEL, "Google AI Studio", "https://aistudio.google.com/apikey"),
    CLAUDE("claude", "Anthropic Claude", ClaudeClient.DEFAULT_MODEL, "Anthropic Console", "https://console.anthropic.com/settings/keys"),
    ;

    fun create(apiKey: String, model: String?, effort: String): AiClient = when (this) {
        GEMINI -> GeminiClient(apiKey, model?.takeIf { it.isNotBlank() } ?: defaultModel)
        CLAUDE -> ClaudeClient(apiKey, model?.takeIf { it.isNotBlank() } ?: defaultModel, effort)
    }

    companion object {
        val DEFAULT = GEMINI
        fun byId(id: String?): AiProvider = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
