package com.unipars.engine

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Shared JSON configuration for recipes, profiles and stored items. */
val EngineJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    prettyPrint = false
}

/**
 * A recipe is the deterministic result of AI-assisted setup. The background engine
 * executes it without any AI calls.
 */
@Serializable
data class Recipe(
    val id: String,
    val name: String,
    val source: Source,
    val extract: Extract,
    val validate: Validation = Validation(),
    val triggers: List<Trigger> = listOf(Trigger(TriggerType.NEW_ITEM)),
    val settings: FetchSettings = FetchSettings(),
)

@Serializable
enum class FetchMode {
    /** Plain HTTP request; the body is HTML or JSON. */
    @SerialName("http") HTTP,

    /** Render the page in an offscreen WebView (JavaScript-heavy sites). */
    @SerialName("webview") WEBVIEW,
}

@Serializable
data class Source(
    val url: String,
    val mode: FetchMode = FetchMode.HTTP,
    val headers: Map<String, String> = emptyMap(),
    val pagination: Pagination? = null,
)

@Serializable
data class Pagination(
    /** CSS selector of the "next page" link (HTML) or JSON path of the next URL (JSON). */
    val nextSelector: String,
    val maxPages: Int = 3,
)

@Serializable
enum class ExtractType {
    /** CSS selectors over HTML (Jsoup). */
    @SerialName("css") CSS,

    /** JSON paths over a JSON document (see [JsonSource]). */
    @SerialName("json") JSON,
}

@Serializable
enum class JsonSource {
    /** The response body itself is JSON (hidden XHR/API endpoints). */
    @SerialName("body") BODY,

    /** All `<script type="application/ld+json">` blocks, merged into one array. */
    @SerialName("jsonld") JSON_LD,

    /** The text of the script element matched by [Extract.scriptSelector], e.g. `script#__NEXT_DATA__`. */
    @SerialName("script") SCRIPT,
}

@Serializable
data class Extract(
    val type: ExtractType = ExtractType.CSS,
    val jsonSource: JsonSource = JsonSource.BODY,
    val scriptSelector: String? = null,
    /** CSS selector (CSS) or JSON path (JSON) that yields one node per item. */
    val itemSelector: String,
    val fields: List<FieldSpec>,
    /** Field names that identify an item between runs. Empty = all fields. */
    val key: List<String> = emptyList(),
)

@Serializable
data class FieldSpec(
    val name: String,
    /** Relative to the item. Empty string means the item node itself. */
    val selector: String,
    /** `text`, `html`, `ownText`, or an attribute name such as `href`/`content`. Ignored for JSON. */
    val attr: String = "text",
    val transform: Transform = Transform.NONE,
    /** Optional regex; the first group (or whole match) becomes the value. */
    val regex: String? = null,
    val fallbackSelectors: List<String> = emptyList(),
)

@Serializable
enum class Transform {
    @SerialName("none") NONE,
    @SerialName("trim") TRIM,
    @SerialName("number") NUMBER,
    @SerialName("absolute_url") ABSOLUTE_URL,
    @SerialName("lowercase") LOWERCASE,
}

@Serializable
data class Validation(
    val minItems: Int = 1,
    val requiredFields: List<String> = emptyList(),
    /** Share of items in which every required field is non-empty. */
    val minFilledRatio: Double = 0.8,
)

@Serializable
enum class TriggerType {
    @SerialName("new_item") NEW_ITEM,
    @SerialName("field_changed") FIELD_CHANGED,
    @SerialName("item_removed") ITEM_REMOVED,
    @SerialName("below") BELOW,
    @SerialName("above") ABOVE,
    @SerialName("contains") CONTAINS,
}

@Serializable
data class Trigger(
    val type: TriggerType,
    val field: String? = null,
    val value: String? = null,
)

@Serializable
enum class CaptchaPolicy {
    /** Pause the recipe and ask the user to pass the check in a WebView. */
    @SerialName("ask_user") ASK_USER,

    /** Pause silently; the status is visible in the app. */
    @SerialName("pause") PAUSE,
}

/** User-editable options. The AI proposes values during setup; the user has the last word. */
@Serializable
data class FetchSettings(
    val intervalMinutes: Int = 60,
    val jitterPercent: Int = 15,
    val connectTimeoutSec: Int = 15,
    val readTimeoutSec: Int = 30,
    val retries: Int = 2,
    val backoffMinutes: Int = 30,
    val maxRequestsPerDay: Int = 200,
    val respectRobots: Boolean = true,
    val wifiOnly: Boolean = false,
    val chargingOnly: Boolean = false,
    val userAgent: String? = null,
    val captchaPolicy: CaptchaPolicy = CaptchaPolicy.ASK_USER,
    /** "HH:mm-HH:mm"; notifications are held back during this window. */
    val quietHours: String? = null,
) {
    companion object {
        /** WorkManager does not run periodic work more often than this. */
        const val MIN_INTERVAL_MINUTES = 15
    }
}

@Serializable
enum class Confidence {
    /** Measured by local probes. */
    @SerialName("measured") MEASURED,

    /** Found in a cited source (web search). */
    @SerialName("sourced") SOURCED,

    /** Model's assumption; may be outdated. */
    @SerialName("assumed") ASSUMED,
}

@Serializable
data class ProfileNote(
    val text: String,
    val confidence: Confidence,
    val source: String? = null,
)

/** What is known about a site, plus the recommended settings. Cached per domain. */
@Serializable
data class SiteProfile(
    val domain: String,
    val facts: ProbeFacts,
    val recommended: FetchSettings,
    val recommendedMode: FetchMode,
    val notes: List<ProfileNote> = emptyList(),
    val warnings: List<String> = emptyList(),
    val updatedAt: Long = 0,
)

/** One extracted record. */
@Serializable
data class Item(
    val key: String,
    val fields: Map<String, String?>,
)
