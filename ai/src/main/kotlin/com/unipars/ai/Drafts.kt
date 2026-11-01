package com.unipars.ai

import com.unipars.engine.CaptchaPolicy
import com.unipars.engine.Confidence
import com.unipars.engine.Extract
import com.unipars.engine.ExtractType
import com.unipars.engine.FetchMode
import com.unipars.engine.FetchSettings
import com.unipars.engine.FieldSpec
import com.unipars.engine.JsonSource
import com.unipars.engine.Pagination
import com.unipars.engine.ProfileNote
import com.unipars.engine.Recipe
import com.unipars.engine.Source
import com.unipars.engine.Transform
import com.unipars.engine.Trigger
import com.unipars.engine.TriggerType
import com.unipars.engine.Validation
import kotlinx.serialization.Serializable

/**
 * What the model returns. Flat, list-based and fully required so it maps onto a strict
 * structured-output schema ([DraftSchema]); converted into engine types by [toRecipe]/[toSettings].
 */
@Serializable
data class SetupDraft(
    val recipe: RecipeDraft,
    val settings: SettingsDraft,
    val notes: List<NoteDraft>,
    val warnings: List<String>,
    val explanation: String,
)

@Serializable
data class RecipeDraft(
    val name: String,
    val url: String,
    val mode: String,
    val extractType: String,
    val jsonSource: String,
    val scriptSelector: String?,
    val itemSelector: String,
    val fields: List<FieldDraft>,
    val key: List<String>,
    val requiredFields: List<String>,
    val minItems: Int,
    val nextPageSelector: String?,
    val maxPages: Int,
    val triggers: List<TriggerDraft>,
)

@Serializable
data class FieldDraft(
    val name: String,
    val selector: String,
    val attr: String,
    val transform: String,
    val regex: String?,
    val fallbackSelectors: List<String>,
)

@Serializable
data class TriggerDraft(val type: String, val field: String?, val value: String?)

@Serializable
data class SettingsDraft(
    val intervalMinutes: Int,
    val jitterPercent: Int,
    val connectTimeoutSec: Int,
    val readTimeoutSec: Int,
    val retries: Int,
    val backoffMinutes: Int,
    val maxRequestsPerDay: Int,
)

@Serializable
data class NoteDraft(val text: String, val confidence: String, val source: String?)

fun RecipeDraft.toRecipe(id: String, fallbackUrl: String, settings: FetchSettings): Recipe = Recipe(
    id = id,
    name = name.ifBlank { fallbackUrl },
    source = Source(
        url = url.ifBlank { fallbackUrl },
        mode = if (mode == "webview") FetchMode.WEBVIEW else FetchMode.HTTP,
        pagination = nextPageSelector?.takeIf { it.isNotBlank() }?.let { Pagination(it, maxPages.coerceIn(1, 10)) },
    ),
    extract = Extract(
        type = if (extractType == "json") ExtractType.JSON else ExtractType.CSS,
        jsonSource = when (jsonSource) {
            "jsonld" -> JsonSource.JSON_LD
            "script" -> JsonSource.SCRIPT
            else -> JsonSource.BODY
        },
        scriptSelector = scriptSelector?.takeIf { it.isNotBlank() },
        itemSelector = itemSelector,
        fields = fields.map { f ->
            FieldSpec(
                name = f.name,
                selector = f.selector,
                attr = f.attr.ifBlank { "text" },
                transform = when (f.transform) {
                    "number" -> Transform.NUMBER
                    "absolute_url" -> Transform.ABSOLUTE_URL
                    "lowercase" -> Transform.LOWERCASE
                    "trim" -> Transform.TRIM
                    else -> Transform.NONE
                },
                regex = f.regex?.takeIf { it.isNotBlank() },
                fallbackSelectors = f.fallbackSelectors.filter { it.isNotBlank() },
            )
        },
        key = key.filter { k -> fields.any { it.name == k } },
    ),
    validate = Validation(
        minItems = minItems.coerceAtLeast(1),
        requiredFields = requiredFields.filter { r -> fields.any { it.name == r } },
    ),
    triggers = triggers.mapNotNull { t ->
        val type = when (t.type) {
            "new_item" -> TriggerType.NEW_ITEM
            "field_changed" -> TriggerType.FIELD_CHANGED
            "item_removed" -> TriggerType.ITEM_REMOVED
            "below" -> TriggerType.BELOW
            "above" -> TriggerType.ABOVE
            "contains" -> TriggerType.CONTAINS
            else -> return@mapNotNull null
        }
        Trigger(type, t.field?.takeIf { it.isNotBlank() }, t.value?.takeIf { it.isNotBlank() })
    }.ifEmpty { listOf(Trigger(TriggerType.NEW_ITEM)) },
    settings = settings,
)

/** AI suggestions are clamped to safe bounds and never looser than the locally measured baseline. */
fun SettingsDraft.toSettings(baseline: FetchSettings): FetchSettings = baseline.copy(
    intervalMinutes = intervalMinutes.coerceIn(FetchSettings.MIN_INTERVAL_MINUTES, 7 * 24 * 60).coerceAtLeast(baseline.intervalMinutes / 2),
    jitterPercent = jitterPercent.coerceIn(0, 50),
    connectTimeoutSec = connectTimeoutSec.coerceIn(5, 120),
    readTimeoutSec = readTimeoutSec.coerceIn(5, 180),
    retries = retries.coerceIn(0, 5),
    backoffMinutes = backoffMinutes.coerceIn(5, 24 * 60),
    maxRequestsPerDay = maxRequestsPerDay.coerceIn(1, 24 * 60 / FetchSettings.MIN_INTERVAL_MINUTES * 2),
    captchaPolicy = CaptchaPolicy.ASK_USER,
)

fun NoteDraft.toNote(): ProfileNote = ProfileNote(
    text = text,
    confidence = when (confidence) {
        "measured" -> Confidence.MEASURED
        "sourced" -> Confidence.SOURCED
        else -> Confidence.ASSUMED
    },
    // A "sourced" note without a source is downgraded: we never present unsourced claims as facts.
    source = source?.takeIf { it.isNotBlank() },
).let { if (it.confidence == Confidence.SOURCED && it.source == null) it.copy(confidence = Confidence.ASSUMED) else it }

/** Strict JSON schema for [SetupDraft]. */
object DraftSchema {
    private fun str() = mapOf("type" to "string")
    private fun nstr() = mapOf("type" to listOf("string", "null"))
    private fun int() = mapOf("type" to "integer")
    private fun enumOf(vararg v: String) = mapOf("type" to "string", "enum" to v.toList())
    private fun arr(items: Map<String, Any?>) = mapOf("type" to "array", "items" to items)
    private fun obj(vararg props: Pair<String, Map<String, Any?>>) = mapOf(
        "type" to "object",
        "properties" to props.toMap(),
        "required" to props.map { it.first },
        "additionalProperties" to false,
    )

    val schema: Map<String, Any?> = obj(
        "recipe" to obj(
            "name" to str(),
            "url" to str(),
            "mode" to enumOf("http", "webview"),
            "extractType" to enumOf("css", "json"),
            "jsonSource" to enumOf("body", "jsonld", "script"),
            "scriptSelector" to nstr(),
            "itemSelector" to str(),
            "fields" to arr(obj(
                "name" to str(),
                "selector" to str(),
                "attr" to str(),
                "transform" to enumOf("none", "trim", "number", "absolute_url", "lowercase"),
                "regex" to nstr(),
                "fallbackSelectors" to arr(str()),
            )),
            "key" to arr(str()),
            "requiredFields" to arr(str()),
            "minItems" to int(),
            "nextPageSelector" to nstr(),
            "maxPages" to int(),
            "triggers" to arr(obj(
                "type" to enumOf("new_item", "field_changed", "item_removed", "below", "above", "contains"),
                "field" to nstr(),
                "value" to nstr(),
            )),
        ),
        "settings" to obj(
            "intervalMinutes" to int(),
            "jitterPercent" to int(),
            "connectTimeoutSec" to int(),
            "readTimeoutSec" to int(),
            "retries" to int(),
            "backoffMinutes" to int(),
            "maxRequestsPerDay" to int(),
        ),
        "notes" to arr(obj(
            "text" to str(),
            "confidence" to enumOf("measured", "sourced", "assumed"),
            "source" to nstr(),
        )),
        "warnings" to arr(str()),
        "explanation" to str(),
    )
}
