package com.unipars.engine

/** Validators for conditional GET, stored between runs. */
data class CacheValidators(val etag: String? = null, val lastModified: String? = null, val bodyHash: String? = null)

data class FetchResponse(
    val status: Int,
    val headers: Map<String, String>,
    val body: String,
    val finalUrl: String,
    val notModified: Boolean = false,
)

/** Platform-specific network access (OkHttp or WebView on Android, fakes in tests). */
fun interface PageFetcher {
    fun fetch(url: String, recipe: Recipe, validators: CacheValidators?): FetchResponse
}

sealed interface RunOutcome {
    /** The page did not change since the last run (304 or same body hash). */
    data class NotModified(val validators: CacheValidators) : RunOutcome

    data class Blocked(val verdict: BlockVerdict, val url: String) : RunOutcome

    /** The recipe no longer matches the page. The last good state is kept. */
    data class Broken(val validation: ValidationResult, val extraction: ExtractionResult, val pageSample: String) : RunOutcome

    data class Success(
        val items: List<Item>,
        val diff: DiffResult,
        val validators: CacheValidators,
        val pages: Int,
    ) : RunOutcome

    data class Failed(val error: String) : RunOutcome
}

/**
 * Executes one background run of a recipe: fetch → detect blocks → extract (with pagination)
 * → validate → diff. No AI involved.
 */
class RecipeRunner(private val fetcher: PageFetcher) {

    fun run(recipe: Recipe, previous: Map<String, Item>?, validators: CacheValidators?): RunOutcome {
        val first = try {
            fetcher.fetch(recipe.source.url, recipe, validators)
        } catch (e: Exception) {
            return RunOutcome.Failed("${e::class.simpleName}: ${e.message}")
        }
        if (first.notModified) return RunOutcome.NotModified(validators ?: CacheValidators())

        val bodyHash = Extractor.sha1(first.body)
        val newValidators = CacheValidators(
            etag = header(first.headers, "etag"),
            lastModified = header(first.headers, "last-modified"),
            bodyHash = bodyHash,
        )
        // Same content as last time and we already have state: nothing to do.
        if (previous != null && validators?.bodyHash == bodyHash) return RunOutcome.NotModified(newValidators)

        val verdict = BlockDetector.detect(first.status, first.headers, first.body, first.finalUrl)
        if (verdict.kind != BlockKind.NONE) return RunOutcome.Blocked(verdict, first.finalUrl)
        if (first.status !in 200..299) return RunOutcome.Failed("HTTP ${first.status}")

        val all = mutableListOf<Item>()
        val errors = mutableListOf<String>()
        var page = first
        var pages = 0
        val maxPages = recipe.source.pagination?.maxPages?.coerceIn(1, 20) ?: 1
        val seen = mutableSetOf<String>()
        while (true) {
            pages++
            seen += page.finalUrl
            val result = Extractor.extract(recipe.extract, page.body, page.finalUrl, recipe.source.pagination)
            all += result.items
            errors += result.errors
            val next = result.nextPageUrl
            if (next == null || pages >= maxPages || next in seen) break
            page = try {
                fetcher.fetch(next, recipe, null)
            } catch (e: Exception) {
                errors += "page $pages: ${e.message}"
                break
            }
            val v = BlockDetector.detect(page.status, page.headers, page.body, page.finalUrl)
            if (v.kind != BlockKind.NONE || page.status !in 200..299) break
        }

        val extraction = ExtractionResult(all.distinctBy { it.key }, errors)
        val validation = Validator.validate(recipe.validate, extraction)
        if (!validation.ok) {
            return RunOutcome.Broken(validation, extraction, first.body.take(200_000))
        }
        val diff = Differ.diff(previous, extraction.items, recipe.triggers)
        return RunOutcome.Success(extraction.items, diff, newValidators, pages)
    }

    private fun header(headers: Map<String, String>, name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}
