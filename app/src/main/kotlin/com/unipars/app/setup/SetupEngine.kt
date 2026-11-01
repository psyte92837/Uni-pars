package com.unipars.app.setup

import android.content.Context
import com.unipars.ai.AiClient
import com.unipars.ai.AiUsage
import com.unipars.ai.Pricing
import com.unipars.app.R
import com.unipars.ai.PickedElement
import com.unipars.ai.SetupAssistant
import com.unipars.ai.SetupInput
import com.unipars.ai.SetupResult
import com.unipars.app.UniParsApp
import com.unipars.app.data.domainOf
import com.unipars.app.net.HttpFetcher
import com.unipars.app.net.WebViewFetcher
import com.unipars.engine.ExtractType
import com.unipars.engine.ExtractionResult
import com.unipars.engine.Extractor
import com.unipars.engine.FetchMode
import com.unipars.engine.FetchResponse
import com.unipars.engine.FetchSettings
import com.unipars.engine.HtmlCleaner
import com.unipars.engine.Item
import com.unipars.engine.JsonSource
import com.unipars.engine.ProbeFacts
import com.unipars.engine.ProbeInput
import com.unipars.engine.Recipe
import com.unipars.engine.SiteProbe
import com.unipars.engine.SiteProfile

/** What the user's WebView session captured while they looked at the page. */
data class PageCapture(
    val url: String,
    val renderedHtml: String?,
    val xhrJson: Map<String, String>,
    val picked: List<PickedElement>,
)

data class ProbeResult(val facts: ProbeFacts, val raw: FetchResponse?, val rawError: String?)

/**
 * Setup pipeline: local probe (no AI) → optional web research → recipe generation with local
 * verification. This is the only place that spends tokens, besides an explicit repair.
 */
class SetupEngine(private val context: Context) {

    private val app = context.applicationContext as UniParsApp
    private val http = HttpFetcher(context)
    private val probeSettings = FetchSettings(connectTimeoutSec = 15, readTimeoutSec = 30, retries = 0)

    val provider get() = app.prefs.provider
    val model get() = app.prefs.model(provider)

    /** Throws with a user-facing message if there is no key or the monthly budget is used up. */
    fun client(): AiClient {
        val key = app.prefs.apiKey(provider)
        require(key.isNotBlank()) { app.getString(R.string.err_no_key, provider.title) }
        val limit = app.prefs.monthlyLimitUsd
        require(limit <= 0 || app.prefs.spentThisMonthUsd < limit) {
            app.getString(R.string.err_budget, "%.2f".format(app.prefs.spentThisMonthUsd), "%.2f".format(limit))
        }
        return provider.create(key, model, app.prefs.effort)
    }

    /** Sends a tiny request to check that a key works; returns null on success or a user-facing error. */
    fun testKey(provider: com.unipars.ai.AiProvider, key: String, model: String): String? = try {
        provider.create(key.trim(), model, "low").complete(
            com.unipars.ai.AiRequest("Reply with the single word OK.", listOf(com.unipars.ai.ChatMessage(com.unipars.ai.Role.USER, "ping")), maxTokens = 256),
        )
        null
    } catch (e: Exception) {
        e.message ?: e.toString()
    }

    /** Approximate cost in USD, or null for models without a known price. */
    fun estimateUsd(usage: AiUsage): Double? = Pricing.estimateUsd(model, usage)

    /** Blocking. */
    fun probe(capture: PageCapture): ProbeResult {
        val started = System.currentTimeMillis()
        val raw = runCatching { http.fetch(capture.url, probeSettings) }
        val elapsed = System.currentTimeMillis() - started
        val robots = runCatching {
            val u = java.net.URI(capture.url)
            http.fetch("${u.scheme}://${u.authority}/robots.txt", probeSettings).takeIf { it.status == 200 }?.body
        }.getOrNull()
        val r = raw.getOrNull()
        val facts = SiteProbe.analyze(ProbeInput(
            url = capture.url,
            status = r?.status ?: 0,
            headers = r?.headers.orEmpty(),
            body = r?.body.orEmpty(),
            responseTimeMs = elapsed,
            robotsTxt = robots,
            renderedBody = capture.renderedHtml,
            xhrJson = capture.xhrJson,
        ))
        return ProbeResult(facts, r, raw.exceptionOrNull()?.message)
    }

    fun research(client: AiClient, url: String, goal: String, facts: ProbeFacts): Pair<String?, AiUsage> =
        if (!app.prefs.webResearch) null to AiUsage() else SetupAssistant(client).research(domainOf(url), goal, facts)

    fun buildInput(capture: PageCapture, goal: String, probe: ProbeResult, research: String?): SetupInput {
        // Prefer the rendered DOM for understanding the page; the verifier still checks the raw HTML for http mode.
        val html = capture.renderedHtml ?: probe.raw?.body.orEmpty()
        val cleaned = HtmlCleaner.clean(html, capture.url)
        return SetupInput(
            url = capture.url,
            goal = goal,
            facts = probe.facts,
            cleanedHtml = cleaned.html,
            jsonLd = cleaned.jsonLd,
            xhrJson = capture.xhrJson,
            pickedElements = capture.picked,
            research = research,
        )
    }

    /**
     * Executes a draft exactly as the background worker would: http-mode recipes are checked on the raw
     * server response (not the rendered DOM), JSON endpoints are fetched, webview recipes use the rendered page.
     */
    fun verifier(capture: PageCapture, probe: ProbeResult): (Recipe) -> ExtractionResult {
        val cache = mutableMapOf<String, FetchResponse>()
        probe.raw?.let { cache[capture.url] = it }
        return { recipe ->
            val url = recipe.source.url
            val body: String = when {
                recipe.source.mode == FetchMode.WEBVIEW && url == capture.url && capture.renderedHtml != null -> capture.renderedHtml
                recipe.source.mode == FetchMode.WEBVIEW -> WebViewFetcher(context).fetch(url, recipe.settings).body
                else -> cache.getOrPut(url) { http.fetch(url, recipe.settings, recipe.source.headers) }.body
            }
            val needsJson = recipe.extract.type == ExtractType.JSON && recipe.extract.jsonSource == JsonSource.BODY
            if (needsJson && body.trimStart().startsWith("<")) {
                ExtractionResult(emptyList(), listOf("expected a JSON response from $url but got HTML"))
            } else {
                Extractor.extract(recipe.extract, body, url, recipe.source.pagination)
            }
        }
    }

    fun create(client: AiClient, id: String, input: SetupInput, capture: PageCapture, probe: ProbeResult): SetupResult =
        SetupAssistant(client).createRecipe(id, input, verifier(capture, probe))

    fun repair(client: AiClient, old: Recipe, lastGood: List<Item>, problem: String): SetupResult {
        val url = old.source.url
        val probe = probe(PageCapture(url, null, emptyMap(), emptyList()))
        val rendered = if (old.source.mode == FetchMode.WEBVIEW) runCatching { WebViewFetcher(context).fetch(url, old.settings).body }.getOrNull() else null
        val capture = PageCapture(url, rendered, emptyMap(), emptyList())
        val input = buildInput(capture, old.name, probe, null)
        return SetupAssistant(client).repairRecipe(old, lastGood, problem, input, verifier(capture, probe))
    }

    fun profile(url: String, facts: ProbeFacts, result: SetupResult): SiteProfile = SiteProfile(
        domain = domainOf(url),
        facts = facts,
        recommended = result.recipe.settings,
        recommendedMode = result.recommendedMode,
        notes = result.notes,
        warnings = result.warnings,
        updatedAt = System.currentTimeMillis(),
    )

    fun addUsage(usage: AiUsage) {
        app.prefs.spentInputTokens += usage.inputTokens
        app.prefs.spentOutputTokens += usage.outputTokens
        estimateUsd(usage)?.let { app.prefs.addSpend(it) }
    }
}
