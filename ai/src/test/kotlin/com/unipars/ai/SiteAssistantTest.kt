package com.unipars.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SiteAssistantTest {

    private class FakeClient(private val answers: List<String>) : AiClient {
        val requests = mutableListOf<AiRequest>()
        override fun complete(request: AiRequest): AiResponse {
            requests += request
            return AiResponse(answers[requests.size - 1], AiUsage(100, 10))
        }
    }

    @Test
    fun helpAnswersWithoutSearch() {
        val client = FakeClient(listOf("""{"intent":"help","message":"Откройте настройки…","searchQuery":null,"suggestions":[]}"""))
        val r = SiteAssistant(client).reply(emptyList(), "Как пройти капчу?")
        assertEquals("Откройте настройки…", r.text)
        assertTrue(r.suggestions.isEmpty())
        assertEquals(1, client.requests.size)
        assertTrue(client.requests.none { it.webSearch })
    }

    @Test
    fun siteGivenReturnsSuggestion() {
        val client = FakeClient(listOf(
            """{"intent":"site_given","message":"Можно следить за ценами","searchQuery":null,
               "suggestions":[{"url":"https://shop.example/catalog","title":"Каталог","reason":"список товаров","goal":"название и цена"},
                              {"url":"javascript:alert(1)","title":"x","reason":"x","goal":"x"}]}""",
        ))
        val r = SiteAssistant(client).reply(emptyList(), "shop.example")
        assertEquals(listOf("https://shop.example/catalog"), r.suggestions.map { it.url })
    }

    @Test
    fun findSitesSearchesAndDropsInventedHosts() {
        val client = FakeClient(listOf(
            """{"intent":"find_sites","message":"","searchQuery":"вакансии Kotlin удалённо","suggestions":[]}""",
            "https://jobs.example/kotlin — вакансии\nИсточники:\n- jobs.example — https://grounding/redirect/1",
            """{"message":"Нашёл 1 сайт","suggestions":[
                 {"url":"https://jobs.example/kotlin?remote=1","title":"Jobs","reason":"r","goal":"g"},
                 {"url":"https://jobs.example/other","title":"dup","reason":"r","goal":"g"},
                 {"url":"https://invented.example/","title":"Fake","reason":"r","goal":"g"}]}""",
        ))
        val r = SiteAssistant(client).reply(listOf(ChatMessage(Role.USER, "привет"), ChatMessage(Role.ASSISTANT, "здравствуйте")), "ищу работу Kotlin удалённо")
        assertEquals(listOf("https://jobs.example/kotlin?remote=1"), r.suggestions.map { it.url })
        assertEquals(3, client.requests.size)
        assertTrue(client.requests[1].webSearch)
        assertTrue(client.requests[1].messages.single().text.contains("вакансии Kotlin удалённо"))
        assertEquals(AiUsage(300, 30), r.usage)
        // History is passed to the planning call.
        assertEquals(3, client.requests[0].messages.size)
    }
}
