package com.unipars.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Google Gemini via the REST `generateContent` endpoint, with the user's own API key.
 * Default provider; only used during setup and on explicit repair.
 */
class GeminiClient(
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val baseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
    private val http: OkHttpClient = defaultHttp,
) : AiClient {

    /** Set after the API rejects `responseJsonSchema`; then the schema travels in the prompt instead. */
    @Volatile private var schemaInPrompt = false

    override fun complete(request: AiRequest): AiResponse {
        return try {
            send(request, schemaInPrompt)
        } catch (e: SchemaRejected) {
            schemaInPrompt = true
            send(request, true)
        }
    }

    private class SchemaRejected : Exception()

    private fun send(request: AiRequest, schemaInPromptMode: Boolean): AiResponse {
        val body = buildBody(request, schemaInPromptMode).toString()
        val httpRequest = Request.Builder()
            .url("$baseUrl/models/$model:generateContent")
            .header("x-goog-api-key", apiKey)
            .post(body.toRequestBody(JSON))
            .build()

        var lastError: Exception? = null
        repeat(3) { attempt ->
            if (attempt > 0) Thread.sleep(1_500L * attempt)
            val (code, text) = try {
                http.newCall(httpRequest).execute().use { it.code to it.body?.string().orEmpty() }
            } catch (e: IOException) {
                lastError = AiException("Нет связи с Gemini API: ${e.message}", AiException.Kind.NETWORK, e)
                return@repeat
            }
            when {
                code in 200..299 -> return parse(text, request)
                code == 429 -> throw AiException("Превышен лимит запросов Gemini API, попробуйте позже", AiException.Kind.RATE_LIMIT)
                code == 401 || code == 403 || (code == 400 && text.contains("API key", ignoreCase = true)) ->
                    throw AiException("Неверный ключ Gemini API или нет доступа к модели $model", AiException.Kind.AUTH)
                code == 400 && !schemaInPromptMode && request.jsonSchema != null &&
                    (text.contains("responseJsonSchema") || text.contains("response_json_schema")) -> throw SchemaRejected()
                code == 404 -> throw AiException("Модель $model не найдена. Проверьте название в настройках.", AiException.Kind.OTHER)
                code >= 500 -> lastError = AiException("Ошибка сервера Gemini ($code)", AiException.Kind.OTHER)
                else -> throw AiException("Ошибка Gemini API ($code): ${errorMessage(text)}", AiException.Kind.OTHER)
            }
        }
        throw lastError ?: AiException("Gemini API недоступен", AiException.Kind.NETWORK)
    }

    internal fun buildBody(request: AiRequest, schemaInPromptMode: Boolean): JsonObject = buildJsonObject {
        val system = if (request.jsonSchema != null && schemaInPromptMode) {
            request.system + "\n\nAnswer with a single JSON object (no markdown) that conforms to this JSON schema:\n" +
                toJson(request.jsonSchema)
        } else request.system
        putJsonObject("systemInstruction") { putJsonArray("parts") { add(buildJsonObject { put("text", system) }) } }
        putJsonArray("contents") {
            request.messages.forEach { m ->
                add(buildJsonObject {
                    put("role", if (m.role == Role.USER) "user" else "model")
                    putJsonArray("parts") { add(buildJsonObject { put("text", m.text) }) }
                })
            }
        }
        putJsonObject("generationConfig") {
            put("maxOutputTokens", request.maxTokens)
            if (request.jsonSchema != null) {
                put("responseMimeType", "application/json")
                if (!schemaInPromptMode) put("responseJsonSchema", toJson(request.jsonSchema))
            }
        }
        if (request.webSearch) {
            putJsonArray("tools") { add(buildJsonObject { putJsonObject("google_search") {} }) }
        }
    }

    internal fun parse(text: String, request: AiRequest): AiResponse {
        val root = runCatching { Json.parseToJsonElement(text).jsonObject }
            .getOrElse { throw AiException("Непонятный ответ Gemini API", AiException.Kind.BAD_OUTPUT, it) }
        val usage = (root["usageMetadata"] as? JsonObject)?.let { u ->
            val output = (u["candidatesTokenCount"]?.jsonPrimitive?.longOrNull ?: 0) +
                (u["thoughtsTokenCount"]?.jsonPrimitive?.longOrNull ?: 0)
            AiUsage(u["promptTokenCount"]?.jsonPrimitive?.longOrNull ?: 0, output)
        } ?: AiUsage()

        (root["promptFeedback"] as? JsonObject)?.get("blockReason")?.jsonPrimitive?.contentOrNull?.let {
            throw AiException("Gemini отклонил запрос ($it)", AiException.Kind.REFUSAL)
        }
        val candidate = (root["candidates"] as? JsonArray)?.firstOrNull()?.jsonObject
            ?: throw AiException("Gemini не вернул ответа", AiException.Kind.BAD_OUTPUT)
        val finish = candidate["finishReason"]?.jsonPrimitive?.contentOrNull
        val parts = (candidate["content"] as? JsonObject)?.get("parts")?.jsonArray.orEmpty()
        val answer = parts.mapNotNull { p ->
            val o = p.jsonObject
            if (o["thought"]?.jsonPrimitive?.booleanOrNull == true) null else o["text"]?.jsonPrimitive?.contentOrNull
        }.joinToString("")

        if (finish in REFUSALS && answer.isBlank()) throw AiException("Gemini отказался отвечать ($finish)", AiException.Kind.REFUSAL)
        if (finish == "MAX_TOKENS" && request.jsonSchema != null) {
            throw AiException("Ответ модели обрезан по лимиту токенов", AiException.Kind.BAD_OUTPUT)
        }

        val sources = (candidate["groundingMetadata"] as? JsonObject)?.get("groundingChunks")?.jsonArray.orEmpty()
            .mapNotNull { (it.jsonObject["web"] as? JsonObject) }
            .mapNotNull { w ->
                val uri = w["uri"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val title = w["title"]?.jsonPrimitive?.contentOrNull
                if (title != null) "$title — $uri" else uri
            }
            .distinct()
        val full = if (sources.isEmpty() || request.jsonSchema != null) answer
        else answer + "\n\nИсточники:\n" + sources.joinToString("\n") { "- $it" }
        val cleaned = if (request.jsonSchema != null) stripFences(full) else full
        return AiResponse(cleaned, usage)
    }

    private fun errorMessage(body: String): String =
        runCatching {
            Json.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
        }.getOrNull() ?: body.take(300)

    companion object {
        const val DEFAULT_MODEL = "gemini-3.5-flash-lite"
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val REFUSALS = setOf("SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII")

        private val defaultHttp = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .callTimeout(6, TimeUnit.MINUTES)
            .build()

        internal fun stripFences(s: String): String {
            val t = s.trim()
            if (!t.startsWith("```")) return t
            return t.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        }

        /** Converts the Kotlin map schema ([DraftSchema]) to a JSON element. */
        internal fun toJson(value: Any?): JsonElement = when (value) {
            null -> JsonNull
            is JsonElement -> value
            is String -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to toJson(v) })
            is Iterable<*> -> buildJsonArray { value.forEach { add(toJson(it)) } }
            else -> JsonPrimitive(value.toString())
        }
    }
}
