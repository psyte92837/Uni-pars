package com.unipars.ai

import com.unipars.engine.EngineJson
import com.unipars.engine.ExtractionResult
import com.unipars.engine.Extractor
import com.unipars.engine.FetchMode
import com.unipars.engine.FetchSettings
import com.unipars.engine.Item
import com.unipars.engine.ProbeFacts
import com.unipars.engine.ProfileNote
import com.unipars.engine.Recipe
import com.unipars.engine.SiteProbe
import com.unipars.engine.Validator
import kotlinx.serialization.SerializationException

data class PickedElement(val cssPath: String, val outerHtml: String)

data class SetupInput(
    val url: String,
    val goal: String,
    val facts: ProbeFacts,
    val cleanedHtml: String,
    val jsonLd: List<String> = emptyList(),
    val xhrJson: Map<String, String> = emptyMap(),
    val pickedElements: List<PickedElement> = emptyList(),
    val research: String? = null,
)

data class SetupResult(
    val recipe: Recipe,
    val recommendedMode: FetchMode,
    val notes: List<ProfileNote>,
    val warnings: List<String>,
    val explanation: String,
    val preview: List<Item>,
    /** Non-null when the last attempt still failed local validation; the user decides. */
    val validationProblem: String?,
    val attempts: Int,
    val usage: AiUsage,
)

/**
 * Turns "what the user wants" into a recipe with at most a few model calls. Every draft is executed
 * locally ([verify]) before it is accepted; failures are fed back to the model.
 */
class SetupAssistant(private val client: AiClient, private val maxAttempts: Int = 3) {

    /**
     * Optional web research (server-side web search). Returns plain text with cited URLs, or null
     * if the provider/key does not allow it — setup continues with local facts only.
     */
    fun research(domain: String, goal: String, facts: ProbeFacts): Pair<String?, AiUsage> = try {
        val r = client.complete(AiRequest(
            system = Prompts.RESEARCH_SYSTEM,
            messages = listOf(ChatMessage(Role.USER, Prompts.research(domain, goal, facts))),
            webSearch = true,
            maxTokens = 4_000,
        ))
        r.text to r.usage
    } catch (e: AiException) {
        if (e.kind == AiException.Kind.AUTH) throw e
        null to AiUsage()
    }

    /**
     * @param verify executes a recipe against the page the user is looking at (or a fresh fetch for
     *   JSON endpoints) and returns the extraction result.
     */
    fun createRecipe(id: String, input: SetupInput, verify: (Recipe) -> ExtractionResult): SetupResult =
        loop(id, input, Prompts.setup(input), verify)

    fun repairRecipe(old: Recipe, lastGood: List<Item>, problem: String, input: SetupInput, verify: (Recipe) -> ExtractionResult): SetupResult =
        loop(old.id, input, Prompts.repair(old, lastGood, problem, input), verify, keepSettings = old.settings)

    private fun loop(
        id: String,
        input: SetupInput,
        firstMessage: String,
        verify: (Recipe) -> ExtractionResult,
        keepSettings: FetchSettings? = null,
    ): SetupResult {
        val (baseline, measuredMode) = SiteProbe.recommend(input.facts)
        val messages = mutableListOf(ChatMessage(Role.USER, firstMessage))
        var usage = AiUsage()
        var last: SetupResult? = null

        for (attempt in 1..maxAttempts) {
            val response = client.complete(AiRequest(Prompts.SETUP_SYSTEM, messages.toList(), DraftSchema.schema))
            usage += response.usage
            val draft = try {
                EngineJson.decodeFromString(SetupDraft.serializer(), response.text)
            } catch (e: SerializationException) {
                throw AiException("Модель вернула некорректный JSON: ${e.message}", AiException.Kind.BAD_OUTPUT, e)
            } catch (e: IllegalArgumentException) {
                throw AiException("Модель вернула некорректный JSON: ${e.message}", AiException.Kind.BAD_OUTPUT, e)
            }
            val settings = keepSettings ?: draft.settings.toSettings(baseline)
            val recipe = draft.recipe.toRecipe(id, input.url, settings)

            val extraction = runCatching { verify(recipe) }
                .getOrElse { ExtractionResult(emptyList(), listOf("${it::class.simpleName}: ${it.message}")) }
            val validation = Validator.validate(recipe.validate, extraction)
            val emptyKeys = recipe.extract.key.isNotEmpty() &&
                extraction.items.isNotEmpty() &&
                extraction.items.all { item -> recipe.extract.key.all { item.fields[it].isNullOrBlank() } }
            val problem = when {
                !validation.ok -> validation.message
                emptyKeys -> "key fields ${recipe.extract.key} are empty in every item"
                else -> null
            }
            last = SetupResult(
                recipe = recipe,
                recommendedMode = if (recipe.source.mode == FetchMode.WEBVIEW) FetchMode.WEBVIEW else measuredMode,
                notes = draft.notes.map { it.toNote() },
                warnings = draft.warnings,
                explanation = draft.explanation,
                preview = extraction.items.take(20),
                validationProblem = problem,
                attempts = attempt,
                usage = usage,
            )
            if (problem == null) return last
            messages += ChatMessage(Role.ASSISTANT, response.text)
            messages += ChatMessage(Role.USER, Prompts.retry(problem, extraction.items))
        }
        return last!!
    }

    companion object {
        /** Convenience for verifying against an already fetched page. */
        fun verifierFor(body: String, baseUrl: String): (Recipe) -> ExtractionResult = { r ->
            Extractor.extract(r.extract, body, baseUrl, r.source.pagination)
        }
    }
}
