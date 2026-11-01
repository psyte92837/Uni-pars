package com.unipars.engine

import kotlinx.serialization.Serializable
import org.jsoup.Jsoup

/** Raw observations made by the app's network layer; analysis below is pure. */
data class ProbeInput(
    val url: String,
    val status: Int,
    val headers: Map<String, String>,
    val body: String,
    val responseTimeMs: Long,
    val robotsTxt: String?,
    /** HTML of the same page after JavaScript ran in a WebView, if available. */
    val renderedBody: String? = null,
    /** JSON responses seen while the page loaded in the WebView (url -> truncated body). */
    val xhrJson: Map<String, String> = emptyMap(),
)

/** Facts measured locally, without AI. */
@Serializable
data class ProbeFacts(
    val status: Int = 0,
    val responseTimeMs: Long = 0,
    val server: String? = null,
    val antibot: List<String> = emptyList(),
    val block: String? = null,
    val robotsAllowed: Boolean? = null,
    val crawlDelaySec: Int? = null,
    val supportsEtag: Boolean = false,
    val supportsLastModified: Boolean = false,
    val hasJsonLd: Boolean = false,
    val jsonLdTypes: List<String> = emptyList(),
    val embeddedJsonScripts: List<String> = emptyList(),
    val feeds: List<String> = emptyList(),
    val jsRequiredLikely: Boolean = false,
    val xhrJsonUrls: List<String> = emptyList(),
    val pageBytes: Int = 0,
)

object SiteProbe {

    private const val USER_AGENT_TOKEN = "unipars"

    fun analyze(input: ProbeInput): ProbeFacts {
        val h = input.headers.mapKeys { it.key.lowercase() }
        val doc = Jsoup.parse(input.renderedBody ?: input.body, input.url)
        val rawDoc = Jsoup.parse(input.body, input.url)
        val verdict = BlockDetector.detect(input.status, input.headers, input.body)

        val jsonLd = doc.select("script[type=application/ld+json]")
        val types = jsonLd.flatMap { Regex("\"@type\"\\s*:\\s*\"([^\"]+)\"").findAll(it.data()).map { m -> m.groupValues[1] } }
            .distinct().take(10)

        val embedded = buildList {
            if (doc.selectFirst("script#__NEXT_DATA__") != null) add("script#__NEXT_DATA__")
            if (doc.selectFirst("script#__NUXT_DATA__") != null) add("script#__NUXT_DATA__")
            doc.select("script:not([src])").forEach {
                val d = it.data()
                if (d.contains("window.__INITIAL_STATE__") || d.contains("window.__NUXT__") || d.contains("window.__APOLLO_STATE__")) {
                    add("inline:" + (Regex("window\\.(__\\w+__)").find(d)?.groupValues?.get(1) ?: "state"))
                }
            }
        }.distinct()

        val feeds = doc.select("link[type=application/rss+xml], link[type=application/atom+xml], link[type=application/feed+json]")
            .map { it.absUrl("href") }.filter { it.isNotBlank() }.distinct()

        // If the server HTML has little text but the rendered DOM has much more, data comes from JS.
        val rawText = rawDoc.body()?.text()?.length ?: 0
        val renderedText = input.renderedBody?.let { Jsoup.parse(it).body()?.text()?.length } ?: rawText
        val spaShell = rawDoc.select("#root, #app, #__next, [data-reactroot]").isNotEmpty() && rawText < 500
        val jsRequired = spaShell || (input.renderedBody != null && renderedText > rawText * 2 + 500)

        val robots = input.robotsTxt?.let { Robots.parse(it) }
        val path = runCatching { java.net.URI(input.url).let { u -> (u.rawPath.ifEmpty { "/" }) + (u.rawQuery?.let { "?$it" } ?: "") } }
            .getOrDefault("/")

        return ProbeFacts(
            status = input.status,
            responseTimeMs = input.responseTimeMs,
            server = h["server"],
            antibot = detectVendors(h, input.body),
            block = verdict.kind.takeIf { it != BlockKind.NONE }?.name,
            robotsAllowed = robots?.isAllowed(USER_AGENT_TOKEN, path),
            crawlDelaySec = robots?.crawlDelay(USER_AGENT_TOKEN),
            supportsEtag = h.containsKey("etag"),
            supportsLastModified = h.containsKey("last-modified"),
            hasJsonLd = jsonLd.isNotEmpty(),
            jsonLdTypes = types,
            embeddedJsonScripts = embedded,
            feeds = feeds,
            jsRequiredLikely = jsRequired,
            xhrJsonUrls = input.xhrJson.keys.take(20),
            pageBytes = input.body.length,
        )
    }

    fun detectVendors(headers: Map<String, String>, body: String): List<String> {
        val h = headers.mapKeys { it.key.lowercase() }
        val cookies = h["set-cookie"].orEmpty()
        val head = body.take(30_000).lowercase()
        return buildList {
            if (h.containsKey("cf-ray") || h["server"]?.contains("cloudflare", true) == true || cookies.contains("__cf_bm")) add("Cloudflare")
            if (h.keys.any { it.startsWith("x-datadome") } || cookies.contains("datadome")) add("DataDome")
            if (cookies.contains("_px") || head.contains("perimeterx")) add("PerimeterX")
            if (cookies.contains("incap_ses") || cookies.contains("visid_incap")) add("Imperva")
            if (cookies.contains("_abck") || cookies.contains("bm_sz")) add("Akamai Bot Manager")
            if (h["server"]?.contains("ddos-guard", true) == true || cookies.contains("__ddg")) add("DDoS-Guard")
            if (head.contains("smartcaptcha") || h["server"]?.contains("qrator", true) == true) add(if (head.contains("smartcaptcha")) "Yandex SmartCaptcha" else "Qrator")
            if (head.contains("g-recaptcha") || head.contains("recaptcha/api.js")) add("reCAPTCHA")
            if (head.contains("hcaptcha")) add("hCaptcha")
        }.distinct()
    }

    /**
     * Conservative defaults from measured facts. The AI may refine them; the user may override.
     */
    fun recommend(facts: ProbeFacts): Pair<FetchSettings, FetchMode> {
        val protected = facts.antibot.any { it != "reCAPTCHA" && it != "hCaptcha" }
        val mode = if (facts.jsRequiredLikely && facts.xhrJsonUrls.isEmpty()) FetchMode.WEBVIEW else FetchMode.HTTP
        var interval = when {
            protected -> 180
            mode == FetchMode.WEBVIEW -> 120
            else -> 60
        }
        facts.crawlDelaySec?.let { delay -> interval = maxOf(interval, (delay / 60) + FetchSettings.MIN_INTERVAL_MINUTES) }
        val slow = facts.responseTimeMs > 3_000
        return FetchSettings(
            intervalMinutes = interval,
            jitterPercent = if (protected) 25 else 15,
            connectTimeoutSec = if (slow) 30 else 15,
            readTimeoutSec = if (slow || mode == FetchMode.WEBVIEW) 60 else 30,
            retries = if (protected) 1 else 2,
            backoffMinutes = if (protected) 120 else 30,
            maxRequestsPerDay = if (protected) 24 else 96,
            respectRobots = true,
            captchaPolicy = CaptchaPolicy.ASK_USER,
        ) to mode
    }
}

/** Small robots.txt parser: groups, Allow/Disallow with `*` and `$`, Crawl-delay. */
class Robots private constructor(private val groups: List<Group>) {

    private data class Group(val agents: List<String>, val rules: List<Pair<Boolean, String>>, val crawlDelay: Int?)

    fun isAllowed(userAgent: String, path: String): Boolean {
        val rules = groupFor(userAgent)?.rules ?: return true
        val match = rules.filter { (_, p) -> p.isNotEmpty() && matches(p, path) }
            .maxWithOrNull(compareBy<Pair<Boolean, String>> { it.second.length }.thenBy { it.first })
        return match?.first ?: true
    }

    fun crawlDelay(userAgent: String): Int? = groupFor(userAgent)?.crawlDelay

    private fun groupFor(ua: String): Group? =
        groups.firstOrNull { g -> g.agents.any { it != "*" && ua.contains(it, ignoreCase = true) } }
            ?: groups.firstOrNull { "*" in it.agents }

    private fun matches(pattern: String, path: String): Boolean {
        val anchored = pattern.endsWith("$")
        val body = pattern.removeSuffix("$")
        val regex = body.split("*").joinToString(".*") { Regex.escape(it) }
        return Regex("^$regex" + if (anchored) "$" else "").containsMatchIn(path)
    }

    companion object {
        fun parse(text: String): Robots {
            val groups = mutableListOf<Group>()
            var agents = mutableListOf<String>()
            var rules = mutableListOf<Pair<Boolean, String>>()
            var delay: Int? = null
            var lastWasAgent = false
            fun flush() {
                if (agents.isNotEmpty()) groups += Group(agents, rules, delay)
                agents = mutableListOf(); rules = mutableListOf(); delay = null
            }
            text.lineSequence().map { it.substringBefore('#').trim() }.filter { it.contains(':') }.forEach { line ->
                val key = line.substringBefore(':').trim().lowercase()
                val value = line.substringAfter(':').trim()
                when (key) {
                    "user-agent" -> {
                        if (!lastWasAgent) flush()
                        agents.add(value.lowercase())
                        lastWasAgent = true
                        return@forEach
                    }
                    "allow" -> rules.add(true to value)
                    "disallow" -> rules.add(false to value)
                    "crawl-delay" -> delay = value.toDoubleOrNull()?.toInt()
                }
                lastWasAgent = false
            }
            flush()
            return Robots(groups)
        }
    }
}
