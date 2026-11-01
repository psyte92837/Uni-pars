package com.unipars.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiClientTest {

    private val server = MockWebServer().apply { start() }
    private val client = GeminiClient("k-test", baseUrl = server.url("/v1beta").toString().trimEnd('/'))

    @After fun tearDown() = server.shutdown()

    private fun ok(text: String, extra: String = "") = MockResponse().setBody(
        """{"candidates":[{"content":{"role":"model","parts":[{"text":"thinking...","thought":true},{"text":${Json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(text))}}]},"finishReason":"STOP"$extra}],
            "usageMetadata":{"promptTokenCount":120,"candidatesTokenCount":30,"thoughtsTokenCount":10}}"""
    )

    @Test
    fun structuredRequestAndResponse() {
        server.enqueue(ok("```json\n{\"a\":1}\n```"))
        val r = client.complete(AiRequest("sys", listOf(ChatMessage(Role.USER, "hi"), ChatMessage(Role.ASSISTANT, "x"), ChatMessage(Role.USER, "again")), DraftSchema.schema))
        assertEquals("{\"a\":1}", r.text)
        assertEquals(AiUsage(120, 40), r.usage)

        val rec = server.takeRequest()
        assertEquals("/v1beta/models/gemini-3.5-flash-lite:generateContent", rec.path)
        assertEquals("k-test", rec.getHeader("x-goog-api-key"))
        val body = Json.parseToJsonElement(rec.body.readUtf8()).jsonObject
        val gen = body["generationConfig"]!!.jsonObject
        assertEquals("application/json", gen["responseMimeType"]!!.jsonPrimitive.content)
        assertTrue(gen.containsKey("responseJsonSchema"))
        assertEquals("model", (body["contents"] as kotlinx.serialization.json.JsonArray)[1].jsonObject["role"]!!.jsonPrimitive.content)
        assertFalse(body.containsKey("tools"))
    }

    @Test
    fun fallsBackToSchemaInPromptWhenRejected() {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"Invalid JSON payload received. Unknown name \"responseJsonSchema\""}}"""))
        server.enqueue(ok("{}"))
        client.complete(AiRequest("sys", listOf(ChatMessage(Role.USER, "hi")), DraftSchema.schema))
        server.takeRequest()
        val second = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertFalse(second["generationConfig"]!!.jsonObject.containsKey("responseJsonSchema"))
        assertTrue(second.toString().contains("conforms to this JSON schema"))
    }

    @Test
    fun researchAppendsGroundingSources() {
        server.enqueue(ok("- есть RSS", ""","groundingMetadata":{"groundingChunks":[{"web":{"uri":"https://example.com/rss","title":"example.com"}}]}"""))
        val r = client.complete(AiRequest("sys", listOf(ChatMessage(Role.USER, "q")), webSearch = true))
        assertTrue(r.text.contains("example.com — https://example.com/rss"))
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("google_search"))
    }

    @Test
    fun errorsAreClassified() {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"API key not valid. Please pass a valid API key."}}"""))
        val e = runCatching { client.complete(AiRequest("s", listOf(ChatMessage(Role.USER, "q")))) }.exceptionOrNull() as AiException
        assertEquals(AiException.Kind.AUTH, e.kind)

        server.enqueue(MockResponse().setBody("""{"promptFeedback":{"blockReason":"SAFETY"}}"""))
        val e2 = runCatching { client.complete(AiRequest("s", listOf(ChatMessage(Role.USER, "q")))) }.exceptionOrNull() as AiException
        assertEquals(AiException.Kind.REFUSAL, e2.kind)
    }

    @Test
    fun providerDefaults() {
        assertEquals(AiProvider.GEMINI, AiProvider.DEFAULT)
        assertEquals("gemini-3.5-flash-lite", AiProvider.byId(null).defaultModel)
        assertEquals(AiProvider.CLAUDE, AiProvider.byId("claude"))
    }
}
