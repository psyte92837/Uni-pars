package com.unipars.ai

import com.unipars.engine.Confidence
import com.unipars.engine.FetchSettings
import com.unipars.engine.ProbeFacts
import com.unipars.engine.Transform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupAssistantTest {

    private val page = """<html><body>
        <div class="product" data-sku="1"><a href="/p/1">Lamp</a><span class="price">1 200 ₽</span></div>
        <div class="product" data-sku="2"><a href="/p/2">Chair</a><span class="price">3 500 ₽</span></div>
        </body></html>"""

    private fun draft(itemSelector: String, interval: Int = 5) = """
        {"recipe":{"name":"Каталог","url":"","mode":"http","extractType":"css","jsonSource":"body","scriptSelector":null,
          "itemSelector":"$itemSelector",
          "fields":[{"name":"title","selector":"a","attr":"text","transform":"trim","regex":null,"fallbackSelectors":[]},
                    {"name":"price","selector":".price","attr":"text","transform":"number","regex":null,"fallbackSelectors":[".cost"]},
                    {"name":"url","selector":"a","attr":"href","transform":"absolute_url","regex":null,"fallbackSelectors":[]}],
          "key":["url","nonexistent"],"requiredFields":["title","price"],"minItems":1,"nextPageSelector":null,"maxPages":1,
          "triggers":[{"type":"below","field":"price","value":"2000"},{"type":"bogus","field":null,"value":null}]},
         "settings":{"intervalMinutes":$interval,"jitterPercent":90,"connectTimeoutSec":1,"readTimeoutSec":30,"retries":9,"backoffMinutes":30,"maxRequestsPerDay":96},
         "notes":[{"text":"Сайт за Cloudflare","confidence":"measured","source":null},
                  {"text":"Есть RSS","confidence":"sourced","source":null}],
         "warnings":[],"explanation":"ok"}
    """.trimIndent()

    private class FakeClient(private val answers: List<String>) : AiClient {
        val requests = mutableListOf<AiRequest>()
        override fun complete(request: AiRequest): AiResponse {
            requests += request
            return AiResponse(answers[requests.size - 1], AiUsage(1000, 200))
        }
    }

    private val input = SetupInput("https://shop.example/catalog", "цены", ProbeFacts(), "<div/>")

    @Test
    fun acceptsWorkingDraftAndClampsSettings() {
        val client = FakeClient(listOf(draft(".product")))
        val r = SetupAssistant(client).createRecipe("id1", input, SetupAssistant.verifierFor(page, input.url))
        assertNull(r.validationProblem)
        assertEquals(1, r.attempts)
        assertEquals(2, r.preview.size)
        assertEquals("1200", r.preview[0].fields["price"])
        assertEquals(listOf("url"), r.recipe.extract.key)
        assertEquals(Transform.NUMBER, r.recipe.extract.fields[1].transform)
        assertEquals(1, r.recipe.triggers.size)
        val s = r.recipe.settings
        assertTrue(s.intervalMinutes >= FetchSettings.MIN_INTERVAL_MINUTES)
        assertEquals(50, s.jitterPercent)
        assertEquals(5, s.connectTimeoutSec)
        assertEquals(5, s.retries)
        // "sourced" without a URL is downgraded.
        assertEquals(Confidence.ASSUMED, r.notes[1].confidence)
        assertTrue(client.requests[0].jsonSchema != null)
    }

    @Test
    fun retriesWithFeedbackWhenRecipeDoesNotMatch() {
        val client = FakeClient(listOf(draft(".nope"), draft(".product")))
        val r = SetupAssistant(client).createRecipe("id1", input, SetupAssistant.verifierFor(page, input.url))
        assertEquals(2, r.attempts)
        assertNull(r.validationProblem)
        val second = client.requests[1].messages
        assertEquals(3, second.size)
        assertTrue(second.last().text.contains("found 0 items"))
        assertEquals(AiUsage(2000, 400), r.usage)
    }

    @Test
    fun reportsProblemAfterMaxAttempts() {
        val client = FakeClient(List(3) { draft(".nope") })
        val r = SetupAssistant(client, maxAttempts = 3).createRecipe("id1", input, SetupAssistant.verifierFor(page, input.url))
        assertEquals(3, r.attempts)
        assertTrue(r.validationProblem!!.contains("found 0 items"))
    }

    @Test
    fun flagsForeignSource() {
        val client = FakeClient(listOf(draft(".product").replace("\"url\":\"\"", "\"url\":\"https://evil.example.net/x\"")))
        val r = SetupAssistant(client).createRecipe("id1", input, SetupAssistant.verifierFor(page, input.url))
        assertEquals("https://evil.example.net/x", r.foreignSource)
        val ok = SetupAssistant(FakeClient(listOf(draft(".product")))).createRecipe("id1", input, SetupAssistant.verifierFor(page, input.url))
        assertNull(ok.foreignSource)
        assertTrue(client.requests[0].messages[0].text.contains("<untrusted_page_data>"))
    }

    @Test(expected = AiException::class)
    fun invalidJson() {
        SetupAssistant(FakeClient(listOf("not json"))).createRecipe("id", input, SetupAssistant.verifierFor(page, input.url))
    }

    @Test
    fun schemaIsStrict() {
        @Suppress("UNCHECKED_CAST")
        val recipe = (DraftSchema.schema["properties"] as Map<String, Map<String, Any?>>)["recipe"]!!
        assertEquals(false, recipe["additionalProperties"])
        assertEquals((recipe["properties"] as Map<*, *>).keys.toList(), recipe["required"])
    }

    @Test
    fun claudeClientBuildsWithoutNetwork() {
        // Construction must not perform I/O (it runs on the UI's background dispatcher only on demand).
        ClaudeClient("sk-test")
    }
}
