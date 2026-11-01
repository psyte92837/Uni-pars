package com.unipars.ai

import com.unipars.engine.EngineJson
import com.unipars.engine.Item
import com.unipars.engine.ProbeFacts
import com.unipars.engine.Recipe

internal object Prompts {

    val SETUP_SYSTEM = """
        You configure a mobile web-monitoring app. You do NOT scrape anything yourself: you write a
        declarative recipe that the app executes on its own, in the background, without AI, for weeks.
        So the recipe must be robust to small layout changes.

        How the app executes a recipe:
        - mode "http": a plain HTTPS GET; "webview": the page is rendered with JavaScript first. Prefer
          "http" whenever the data is present in the server HTML or in a JSON endpoint; WebView costs battery.
        - extractType "css": itemSelector is a CSS selector (Jsoup) matching one element per item; each
          field selector is relative to the item ("" = the item itself); attr is "text", "ownText", "html"
          or an attribute name (href, src, content, data-*).
        - extractType "json": itemSelector and field selectors are JSON paths ("$.data.items[*]",
          "offers.price", "$..products[*]", "[?(@.@type=='Product')]"). jsonSource "body" = the response is
          JSON (set url to that endpoint); "jsonld" = all ld+json blocks merged into one array;
          "script" = JSON inside the element matched by scriptSelector (e.g. "script#__NEXT_DATA__").
        - transform: "number" turns "1 299,90 ₽" into 1299.9 (use for prices/quantities you compare);
          "absolute_url" for links.
        - key: field names that identify an item across runs (URL, id, SKU) — never prices or other values
          that change, otherwise changes look like new items.
        - Validation: requiredFields + minItems detect when the site changed and the recipe broke.

        Selector robustness, in order of preference:
        1. Structured data: JSON-LD, microdata (itemprop), embedded state (__NEXT_DATA__), JSON endpoints.
        2. Semantic attributes: data-*, itemprop, aria-label, stable ids.
        3. Readable, stable class names.
        Never use :nth-child, absolute paths from <html>, or hashed/generated classes (css-1x2y3z, sc-abcd,
        _a1b2c, jsx-123). Give 1–2 fallbackSelectors for each important field.

        Settings: propose polite values for this specific site. Never go below 15 minutes. Respect
        Crawl-delay. For sites behind anti-bot protection, be conservative (longer intervals, few retries,
        long backoff). Take into account how often the data plausibly changes.

        Notes: facts about the site that justify the settings. Use confidence "measured" only for the
        probe facts given to you, "sourced" only with a URL from the provided research, otherwise
        "assumed". Do not invent sources.

        Warnings (in Russian): terms of service or robots.txt restricting automated access, an official
        API/RSS that would be better, login required, captchas expected. Never suggest ways to bypass
        protections, captchas or access controls.

        Write name, notes, warnings and explanation in Russian.
    """.trimIndent()

    val RESEARCH_SYSTEM = """
        You research one website so that a monitoring app can poll it politely and reliably.
        Search the web and report briefly, in Russian, as a bullet list where each line ends with the
        source URL in parentheses. Cover: an official API, RSS/Atom feeds or data exports; terms of
        service about automated access; published rate limits; known anti-bot protection (Cloudflare,
        DataDome, Qrator, etc.); how often the relevant data usually updates. If you found nothing for a
        point, say so. Do not describe ways to bypass protections. Do not invent URLs.
    """.trimIndent()

    fun research(domain: String, goal: String, facts: ProbeFacts): String = """
        Site: $domain
        What the user wants to monitor: $goal
        Locally measured facts: ${EngineJson.encodeToString(ProbeFacts.serializer(), facts)}
    """.trimIndent()

    fun setup(input: SetupInput): String = buildString {
        appendLine("URL: ${input.url}")
        appendLine("What to monitor (user's words): ${input.goal}")
        appendLine()
        appendLine("Measured probe facts (confidence \"measured\"):")
        appendLine(EngineJson.encodeToString(ProbeFacts.serializer(), input.facts))
        input.research?.takeIf { it.isNotBlank() }?.let {
            appendLine()
            appendLine("Web research (use for \"sourced\" notes, cite these URLs only):")
            appendLine(it)
        }
        if (input.pickedElements.isNotEmpty()) {
            appendLine()
            appendLine("Elements the user tapped on the page (selector path + outer HTML):")
            input.pickedElements.forEach { appendLine("- ${it.cssPath}\n  ${it.outerHtml.take(1500)}") }
        }
        if (input.xhrJson.isNotEmpty()) {
            appendLine()
            appendLine("JSON responses observed while the page loaded (url → beginning of body):")
            input.xhrJson.entries.take(8).forEach { (u, b) -> appendLine("- $u\n  ${b.take(1500)}") }
        }
        if (input.jsonLd.isNotEmpty()) {
            appendLine()
            appendLine("JSON-LD blocks:")
            input.jsonLd.take(5).forEach { appendLine(it.take(2000)) }
        }
        appendLine()
        appendLine("Cleaned page HTML (scripts removed, repeated siblings collapsed):")
        appendLine(input.cleanedHtml)
    }

    fun retry(problem: String, preview: List<Item>): String = buildString {
        appendLine("The app executed your recipe on the real page and it failed validation: $problem")
        if (preview.isNotEmpty()) {
            appendLine("First extracted items:")
            preview.take(5).forEach { appendLine(it.fields.toString()) }
        }
        appendLine("Return a corrected complete answer in the same format.")
    }

    fun repair(old: Recipe, lastGood: List<Item>, problem: String, input: SetupInput): String = buildString {
        appendLine("This recipe worked before but stopped working: $problem")
        appendLine("Old recipe: ${EngineJson.encodeToString(Recipe.serializer(), old)}")
        appendLine("Items it extracted when it worked (keep the same field names and keys):")
        lastGood.take(5).forEach { appendLine(it.fields.toString()) }
        appendLine()
        append(setup(input))
    }
}
