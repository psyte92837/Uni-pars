package com.unipars.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class RunnerTest {

    private fun page(n: Int, next: Boolean) = """<html><body>
        ${(1..3).joinToString("") { "<div class='it'><a href='/i/$n-$it'>Item $n-$it</a><span class='p'>${n * 10 + it}</span></div>" }}
        ${if (next) "<a class='next' href='/list?page=${n + 1}'>›</a>" else ""}
        </body></html>"""

    private val recipe = Recipe(
        id = "r1", name = "test",
        source = Source("https://s.example/list", pagination = Pagination(".next", maxPages = 3)),
        extract = Extract(
            itemSelector = ".it",
            fields = listOf(FieldSpec("title", "a"), FieldSpec("price", ".p", transform = Transform.NUMBER), FieldSpec("url", "a", "href", Transform.ABSOLUTE_URL)),
            key = listOf("url"),
        ),
        validate = Validation(minItems = 3, requiredFields = listOf("title", "price")),
    )

    private val fetcher = PageFetcher { url, _, validators ->
        if (validators?.etag == "\"v1\"") return@PageFetcher FetchResponse(304, emptyMap(), "", url, notModified = true)
        val n = Regex("page=(\\d+)").find(url)?.groupValues?.get(1)?.toInt() ?: 1
        FetchResponse(200, mapOf("ETag" to "\"v1\""), page(n, next = n < 5), url)
    }

    @Test
    fun paginatesUpToMaxPages() {
        val out = RecipeRunner(fetcher).run(recipe, null, null) as RunOutcome.Success
        assertEquals(3, out.pages)
        assertEquals(9, out.items.size)
        assertEquals("\"v1\"", out.validators.etag)
    }

    @Test
    fun notModified() {
        val out = RecipeRunner(fetcher).run(recipe, emptyMap(), CacheValidators(etag = "\"v1\""))
        assertTrue(out is RunOutcome.NotModified)
    }

    @Test
    fun brokenWhenLayoutChanges() {
        val changed = PageFetcher { url, _, _ -> FetchResponse(200, emptyMap(), "<html><body><div class='new'>x</div></body></html>", url) }
        val out = RecipeRunner(changed).run(recipe, emptyMap(), null)
        assertTrue(out is RunOutcome.Broken)
    }

    @Test
    fun blocked() {
        val cf = PageFetcher { url, _, _ -> FetchResponse(403, mapOf("cf-mitigated" to "challenge"), "<title>Just a moment...</title>", url) }
        val out = RecipeRunner(cf).run(recipe, null, null) as RunOutcome.Blocked
        assertEquals(BlockKind.CHALLENGE, out.verdict.kind)
    }

    @Test
    fun scheduleHelpers() {
        assertTrue(Schedule.inQuietHours("23:00-07:00", LocalTime.of(2, 0)))
        assertTrue(!Schedule.inQuietHours("23:00-07:00", LocalTime.of(12, 0)))
        assertEquals(15, Schedule.effectiveIntervalMinutes(FetchSettings(intervalMinutes = 5, maxRequestsPerDay = 0)))
        assertEquals(120L, Schedule.backoffMinutes(FetchSettings(backoffMinutes = 30), 3))
    }

    @Test
    fun cleanerShrinksAndKeepsSelectors() {
        val html = "<html><head><style>.a{}</style></head><body><script>var x=1</script>" +
            (1..50).joinToString("") { "<div class='card' onclick='x()'><span itemprop='name'>N$it</span></div>" } + "</body></html>"
        val c = HtmlCleaner.clean(html, "https://x/")
        assertTrue(c.html.contains("itemprop=\"name\""))
        assertTrue(!c.html.contains("onclick"))
        assertTrue(!c.html.contains("var x"))
        assertTrue(c.html.contains("more similar"))
        assertEquals(3, Regex("class=\"card\"").findAll(c.html).count())
    }
}
