package com.unipars.ai

enum class Role { USER, ASSISTANT }

data class ChatMessage(val role: Role, val text: String)

data class AiRequest(
    val system: String,
    val messages: List<ChatMessage>,
    /** JSON schema for structured output; null for free text. */
    val jsonSchema: Map<String, Any?>? = null,
    /** Let the model search the web (server-side tool). */
    val webSearch: Boolean = false,
    val maxTokens: Long = 16_000,
)

data class AiUsage(val inputTokens: Long = 0, val outputTokens: Long = 0) {
    operator fun plus(o: AiUsage) = AiUsage(inputTokens + o.inputTokens, outputTokens + o.outputTokens)
}

data class AiResponse(val text: String, val usage: AiUsage)

class AiException(message: String, val kind: Kind, cause: Throwable? = null) : Exception(message, cause) {
    enum class Kind { AUTH, RATE_LIMIT, REFUSAL, NETWORK, BAD_OUTPUT, OTHER }
}

/** Provider-neutral interface so other providers can be added next to [ClaudeClient]. */
interface AiClient {
    /** Blocking; call from a background dispatcher. */
    fun complete(request: AiRequest): AiResponse
}
