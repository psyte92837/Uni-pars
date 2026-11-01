package com.unipars.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtractorTest {

    private val catalog = """
        <html><body><ul>
          <li class="card"><a href="/p/1"><h2>Phone A</h2></a><span class="price">1 299,90 ₽</span></li>
          <li class="card"><a href="/p/2"><h2>Phone B</h2></a><b class="cost">$1,050</b></li>
          <li class="card"><a href="/p/3"><h2>Phone C</h2></a><meta itemprop="price" content="999"></li>
        </ul><a class="next" href="?page=2">next</a></body></html>
    """.trimIndent()

    private val cssExtract = Extract(
        itemSelector = "li.card",
        fields = listOf(
            FieldSpec("title", "h2"),
            FieldSpec("price", ".price", transform = Transform.NUMBER, fallbackSelectors = listOf(".cost", "[itemprop=price]")),
            FieldSpec("url", "a", attr = "href", transform = Transform.ABSOLUTE_URL),
        ),
        key = listOf("url"),
    )

    @Test
    fun cssWithFallbacksAndTransforms() {
        val r = Extractor.extract(cssExtract, catalog, "https://shop.example/catalog", Pagination(".next"))
        assertEquals(3, r.items.size)
        assertEquals("Phone A", r.items[0].fields["title"])
        assertEquals("1299.9", r.items[0].fields["price"])
        assertEquals("1050", r.items[1].fields["price"])
        assertEquals("https://shop.example/p/2", r.items[1].fields["url"])
        assertEquals("https://shop.example/catalog?page=2", r.nextPageUrl)
    }

    @Test
    fun metaContentAttribute() {
        val e = cssExtract.copy(fields = listOf(FieldSpec("price", "[itemprop=price]", attr = "content")), key = emptyList())
        val r = Extractor.extract(e, catalog, "https://shop.example/")
        assertTrue(r.items.any { it.fields["price"] == "999" })
    }

    @Test
    fun keyIsStableWhenOtherFieldsChange() {
        val a = Extractor.extract(cssExtract, catalog, "https://shop.example/catalog")
        val b = Extractor.extract(cssExtract, catalog.replace("1 299,90", "1 199,90"), "https://shop.example/catalog")
        assertEquals(a.items[0].key, b.items[0].key)
    }

    @Test
    fun jsonBodyWithPath() {
        val body = """{"data":{"items":[{"id":7,"name":"X","offer":{"price":"10.5"}},{"id":8,"name":"Y","offer":{"price":12}}],"next":"/api?p=2"}}"""
        val e = Extract(
            type = ExtractType.JSON,
            itemSelector = "$.data.items[*]",
            fields = listOf(FieldSpec("id", "id"), FieldSpec("name", "name"), FieldSpec("price", "offer.price", transform = Transform.NUMBER)),
            key = listOf("id"),
        )
        val r = Extractor.extract(e, body, "https://shop.example/api", Pagination("$.data.next"))
        assertEquals(listOf("X", "Y"), r.items.map { it.fields["name"] })
        assertEquals("12", r.items[1].fields["price"])
        assertEquals("https://shop.example/api?p=2", r.nextPageUrl)
    }

    @Test
    fun jsonLdWithFilter() {
        val html = """<html><head>
            <script type="application/ld+json">{"@type":"BreadcrumbList"}</script>
            <script type="application/ld+json">[{"@type":"Product","name":"Lamp","offers":{"price":"49.00","priceCurrency":"EUR"}}]</script>
            </head><body></body></html>"""
        val e = Extract(
            type = ExtractType.JSON, jsonSource = JsonSource.JSON_LD,
            itemSelector = "$[?(@.@type=='Product')]",
            fields = listOf(FieldSpec("name", "name"), FieldSpec("price", "offers.price", transform = Transform.NUMBER)),
        )
        val r = Extractor.extract(e, html, "https://x.example/")
        assertEquals(1, r.items.size)
        assertEquals("49", r.items[0].fields["price"])
    }

    @Test
    fun nextDataScript() {
        val html = """<html><body><script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":{"list":[{"t":"a"},{"t":"b"}]}}}</script></body></html>"""
        val e = Extract(
            type = ExtractType.JSON, jsonSource = JsonSource.SCRIPT, scriptSelector = "script#__NEXT_DATA__",
            itemSelector = "$..list[*]", fields = listOf(FieldSpec("t", "t")),
        )
        assertEquals(listOf("a", "b"), Extractor.extract(e, html, "https://x.example/").items.map { it.fields["t"] })
    }

    @Test
    fun regexAndErrorsDoNotThrow() {
        val spec = FieldSpec("n", "", regex = "Артикул:\\s*(\\d+)")
        assertEquals("12345", Extractor.postProcess("Артикул: 12345 шт", spec, ""))
        val bad = Extract(type = ExtractType.JSON, itemSelector = "$.a", fields = emptyList())
        val r = Extractor.extract(bad, "not json", "https://x/")
        assertTrue(r.errors.isNotEmpty())
    }

    @Test
    fun numberParsing() {
        assertEquals("1299.9", Extractor.parseNumber("1 299,90 ₽"))
        assertEquals("1299.9", Extractor.parseNumber("$1,299.90"))
        assertEquals("1299", Extractor.parseNumber("1.299 €"))
        assertEquals("12.5", Extractor.parseNumber("12,5"))
        assertEquals("1000000", Extractor.parseNumber("1 000 000"))
        assertNull(Extractor.parseNumber("нет в наличии"))
    }
}
