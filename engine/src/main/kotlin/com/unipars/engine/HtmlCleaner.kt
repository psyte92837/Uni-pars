package com.unipars.engine

import org.jsoup.Jsoup
import org.jsoup.nodes.Comment
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * Shrinks a page before it is sent to the model during setup: removes scripts, styles and
 * noise, keeps structure and attributes useful for selectors, and collapses long lists of
 * similar siblings to a few examples. Typically 100–500K tokens of raw HTML become 3–15K.
 */
object HtmlCleaner {

    private val dropTags = listOf("script", "style", "noscript", "svg", "iframe", "canvas", "video", "audio", "picture source", "template", "meta:not([itemprop])", "link:not([itemprop])")
    private val keepAttrs = setOf("id", "class", "href", "src", "alt", "title", "name", "content", "itemprop", "itemtype", "itemscope", "datetime", "value", "type", "role", "aria-label", "rel")

    data class Cleaned(val html: String, val jsonLd: List<String>, val originalChars: Int)

    fun clean(html: String, baseUrl: String, maxChars: Int = 60_000, keepSiblings: Int = 3): Cleaned {
        val doc = Jsoup.parse(html, baseUrl)
        val jsonLd = doc.select("script[type=application/ld+json]").map { it.data().trim().take(4_000) }
        val root: Element = doc.body() ?: doc
        dropTags.forEach { root.select(it).remove() }
        doc.select("header nav, footer").remove()
        removeComments(root)
        root.allElements.forEach { el ->
            val attrs = el.attributes().asList().map { it.key }
            attrs.forEach { key ->
                val keep = key in keepAttrs || key.startsWith("data-") && key.length < 30
                if (!keep) el.removeAttr(key)
                else {
                    val v = el.attr(key)
                    if (v.startsWith("data:") || v.length > 200) el.attr(key, v.take(80) + "…")
                }
            }
        }
        collapseRepeats(root, keepSiblings)
        truncateText(root)
        var out = root.html().replace(Regex("\\n\\s*\\n+"), "\n")
        if (out.length > maxChars) out = out.take(maxChars) + "\n<!-- truncated -->"
        return Cleaned(out, jsonLd, html.length)
    }

    /** Keeps the first [keep] of a run of siblings with the same tag+class and notes how many were dropped. */
    private fun collapseRepeats(el: Element, keep: Int) {
        val children = el.children().toList()
        var i = 0
        while (i < children.size) {
            val sig = signature(children[i])
            var j = i + 1
            while (j < children.size && signature(children[j]) == sig) j++
            val run = j - i
            if (run > keep + 1) {
                for (k in i + keep until j) children[k].remove()
                children[i + keep - 1].after(Comment(" …${run - keep} more similar <${sig}> "))
            }
            i = j
        }
        el.children().forEach { collapseRepeats(it, keep) }
    }

    private fun signature(e: Element) = e.tagName() + "." + e.classNames().sorted().joinToString(".")

    private fun truncateText(el: Element) {
        el.textNodes().forEach { t: TextNode ->
            val s = t.text()
            if (s.length > 300) t.text(s.take(300) + "…")
        }
        el.children().forEach { truncateText(it) }
    }

    private fun removeComments(node: Node) {
        var i = 0
        while (i < node.childNodeSize()) {
            val child = node.childNode(i)
            if (child is Comment) child.remove() else { removeComments(child); i++ }
        }
    }
}
