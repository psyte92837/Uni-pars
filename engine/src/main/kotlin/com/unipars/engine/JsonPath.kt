package com.unipars.engine

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Minimal JSON path: `$.a.b[0].c`, `items[*]`, `..name` (recursive descent),
 * `['key with spaces']`, and `[?(@.type=='Product')]` equality filters.
 * Covers what recipes need without a reflective dependency.
 */
object JsonPath {

    fun select(root: JsonElement, path: String): List<JsonElement> {
        var current = listOf(root)
        for (step in parse(path)) {
            current = current.flatMap { apply(it, step) }
        }
        return current.filter { it !is JsonNull }
    }

    fun selectFirst(root: JsonElement, path: String): JsonElement? = select(root, path).firstOrNull()

    /** Scalar text value of an element; arrays/objects are returned as JSON. */
    fun text(element: JsonElement?): String? = when (element) {
        null, JsonNull -> null
        is JsonPrimitive -> element.content
        else -> element.toString()
    }

    private sealed interface Step {
        data class Key(val name: String) : Step
        data class Index(val index: Int) : Step
        data object Wildcard : Step
        data class Deep(val name: String) : Step
        data class Filter(val field: String, val value: String) : Step
    }

    private fun apply(node: JsonElement, step: Step): List<JsonElement> = when (step) {
        is Step.Key -> listOfNotNull((node as? JsonObject)?.get(step.name))
        is Step.Index -> (node as? JsonArray)?.let { arr ->
            val i = if (step.index < 0) arr.size + step.index else step.index
            listOfNotNull(arr.getOrNull(i))
        } ?: emptyList()
        Step.Wildcard -> when (node) {
            is JsonArray -> node.toList()
            is JsonObject -> node.values.toList()
            else -> emptyList()
        }
        is Step.Deep -> deep(node, step.name)
        is Step.Filter -> {
            val candidates = if (node is JsonArray) node.toList() else listOf(node)
            candidates.filter { c ->
                val v = (c as? JsonObject)?.get(step.field)
                when (v) {
                    is JsonPrimitive -> v.content == step.value
                    is JsonArray -> v.any { (it as? JsonPrimitive)?.content == step.value }
                    else -> false
                }
            }
        }
    }

    private fun deep(node: JsonElement, name: String): List<JsonElement> {
        val out = mutableListOf<JsonElement>()
        fun walk(n: JsonElement) {
            when (n) {
                is JsonObject -> {
                    if (name == "*") out.addAll(n.values) else n[name]?.let { out.add(it) }
                    n.values.forEach { walk(it) }
                }
                is JsonArray -> n.forEach { walk(it) }
                else -> Unit
            }
        }
        walk(node)
        return out
    }

    private fun parse(path: String): List<Step> {
        val steps = mutableListOf<Step>()
        var s = path.trim()
        if (s.startsWith("$")) s = s.substring(1)
        var i = 0
        fun readName(): String {
            val start = i
            while (i < s.length && s[i] != '.' && s[i] != '[') i++
            return s.substring(start, i)
        }
        while (i < s.length) {
            when {
                s.startsWith("..", i) -> {
                    i += 2
                    steps.add(Step.Deep(readName()))
                }
                s[i] == '.' -> {
                    i++
                    val name = readName()
                    steps.add(if (name == "*") Step.Wildcard else Step.Key(name))
                }
                s[i] == '[' -> {
                    val end = findClosing(s, i)
                    val inner = s.substring(i + 1, end).trim()
                    i = end + 1
                    steps.add(parseBracket(inner))
                }
                else -> {
                    val name = readName()
                    steps.add(if (name == "*") Step.Wildcard else Step.Key(name))
                }
            }
        }
        return steps
    }

    private fun findClosing(s: String, open: Int): Int {
        var quote: Char? = null
        var depth = 0
        for (j in open until s.length) {
            val c = s[j]
            if (quote != null) {
                if (c == quote) quote = null
                continue
            }
            when (c) {
                '\'', '"' -> quote = c
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) return j
                }
            }
        }
        throw IllegalArgumentException("Unclosed '[' in JSON path: $s")
    }

    private fun parseBracket(inner: String): Step {
        if (inner == "*") return Step.Wildcard
        inner.toIntOrNull()?.let { return Step.Index(it) }
        if (inner.startsWith("?")) {
            val m = FILTER.matchEntire(inner)
                ?: throw IllegalArgumentException("Unsupported filter: [$inner]")
            return Step.Filter(m.groupValues[1], m.groupValues[2].ifEmpty { m.groupValues[3] })
        }
        return Step.Key(inner.trim('\'', '"'))
    }

    private val FILTER = Regex("""\?\(\s*@\.([\w@$-]+)\s*==\s*(?:'([^']*)'|"([^"]*)")\s*\)""")
}
