package com.unipars.engine

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.security.MessageDigest

data class ExtractionResult(
    val items: List<Item>,
    val errors: List<String> = emptyList(),
    /** Next page URL, if the recipe has pagination and the page links to one. */
    val nextPageUrl: String? = null,
)

/** Applies a recipe's [Extract] block to a fetched document. Pure and deterministic. */
object Extractor {

    fun extract(extract: Extract, body: String, baseUrl: String, pagination: Pagination? = null): ExtractionResult =
        try {
            when (extract.type) {
                ExtractType.CSS -> extractCss(extract, Jsoup.parse(body, baseUrl), pagination)
                ExtractType.JSON -> extractJson(extract, body, baseUrl, pagination)
            }
        } catch (e: Exception) {
            ExtractionResult(emptyList(), listOf("${e::class.simpleName}: ${e.message}"))
        }

    private fun extractCss(extract: Extract, doc: Document, pagination: Pagination?): ExtractionResult {
        val nodes = doc.select(extract.itemSelector)
        val items = nodes.map { node ->
            val fields = extract.fields.associate { spec -> spec.name to cssValue(node, spec, doc.location()) }
            Item(itemKey(extract, fields), fields)
        }
        val next = pagination?.let { p ->
            doc.selectFirst(p.nextSelector)?.absUrl("href")?.takeIf { it.isNotBlank() }
        }
        return ExtractionResult(dedupe(items), nextPageUrl = next)
    }

    private fun cssValue(item: Element, spec: FieldSpec, baseUrl: String): String? {
        for (selector in listOf(spec.selector) + spec.fallbackSelectors) {
            val el = if (selector.isBlank()) item else item.selectFirst(selector) ?: continue
            val raw = when (spec.attr) {
                "text" -> el.text()
                "ownText" -> el.ownText()
                "html" -> el.html()
                else -> if (spec.transform == Transform.ABSOLUTE_URL) el.absUrl(spec.attr) else el.attr(spec.attr)
            }
            val value = postProcess(raw, spec, baseUrl)
            if (!value.isNullOrBlank()) return value
        }
        return null
    }

    private fun extractJson(extract: Extract, body: String, baseUrl: String, pagination: Pagination?): ExtractionResult {
        val root: JsonElement = when (extract.jsonSource) {
            JsonSource.BODY -> EngineJson.parseToJsonElement(body)
            JsonSource.JSON_LD -> {
                val blocks = Jsoup.parse(body).select("script[type=application/ld+json]").mapNotNull {
                    runCatching { EngineJson.parseToJsonElement(it.data()) }.getOrNull()
                }
                // Flatten top-level arrays so `[*]` and `..` work uniformly.
                JsonArray(blocks.flatMap { if (it is JsonArray) it.toList() else listOf(it) })
            }
            JsonSource.SCRIPT -> {
                val selector = extract.scriptSelector ?: error("scriptSelector is required for jsonSource=script")
                val script = Jsoup.parse(body).selectFirst(selector) ?: error("Script not found: $selector")
                EngineJson.parseToJsonElement(script.data().ifBlank { script.text() })
            }
        }
        val nodes = JsonPath.select(root, extract.itemSelector)
        val items = nodes.map { node ->
            val fields = extract.fields.associate { spec -> spec.name to jsonValue(node, spec, baseUrl) }
            Item(itemKey(extract, fields), fields)
        }
        val next = pagination?.let { JsonPath.text(JsonPath.selectFirst(root, it.nextSelector)) }
            ?.let { resolveUrl(baseUrl, it) }
        return ExtractionResult(dedupe(items), nextPageUrl = next)
    }

    private fun jsonValue(node: JsonElement, spec: FieldSpec, baseUrl: String): String? {
        for (selector in listOf(spec.selector) + spec.fallbackSelectors) {
            val el = if (selector.isBlank()) node else JsonPath.selectFirst(node, selector) ?: continue
            val value = postProcess(JsonPath.text(el), spec, baseUrl)
            if (!value.isNullOrBlank()) return value
        }
        return null
    }

    internal fun postProcess(raw: String?, spec: FieldSpec, baseUrl: String): String? {
        var v = raw?.trim() ?: return null
        spec.regex?.let { pattern ->
            // Model-written regexes run with a time budget so a pathological one cannot hang a run.
            v = Safety.regexFind(pattern, v) ?: return null
        }
        return when (spec.transform) {
            Transform.NONE, Transform.TRIM -> v
            Transform.NUMBER -> parseNumber(v)
            Transform.ABSOLUTE_URL -> resolveUrl(baseUrl, v)
            Transform.LOWERCASE -> v.lowercase()
        }
    }

    /** "1 299,90 ₽" -> "1299.90", "$1,299.90" -> "1299.90". */
    fun parseNumber(text: String): String? {
        val cleaned = text.replace(Regex("[\\s\\u00A0\\u202F]"), "")
        val m = Regex("-?\\d[\\d.,]*").find(cleaned) ?: return null
        var n = m.value.trimEnd('.', ',')
        val lastDot = n.lastIndexOf('.')
        val lastComma = n.lastIndexOf(',')
        n = when {
            lastDot >= 0 && lastComma >= 0 -> {
                val dec = if (lastDot > lastComma) '.' else ','
                val thou = if (dec == '.') ',' else '.'
                n.replace(thou.toString(), "").replace(dec, '.')
            }
            lastComma >= 0 -> {
                // "1,299" (thousands) vs "12,5" (decimal): three digits after the only comma means thousands.
                val parts = n.split(',')
                if (parts.size > 2 || parts.last().length == 3) n.replace(",", "") else n.replace(',', '.')
            }
            lastDot >= 0 -> {
                // Same rule for a lone dot: "1.299" is European thousands; "12.50" is a decimal.
                val parts = n.split('.')
                if (parts.size > 2 || parts.last().length == 3) n.replace(".", "") else n
            }
            else -> n
        }
        return n.toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString()
    }

    fun resolveUrl(base: String, href: String): String =
        runCatching { java.net.URI(base).resolve(href.trim()).toString() }.getOrDefault(href)

    private fun itemKey(extract: Extract, fields: Map<String, String?>): String {
        val keyFields = extract.key.ifEmpty { fields.keys.sorted() }
        val raw = keyFields.joinToString("\u0001") { fields[it].orEmpty() }
        return sha1(raw)
    }

    private fun dedupe(items: List<Item>): List<Item> = items.distinctBy { it.key }

    fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
