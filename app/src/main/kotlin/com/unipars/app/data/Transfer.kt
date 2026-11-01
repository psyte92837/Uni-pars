package com.unipars.app.data

import com.unipars.engine.EngineJson
import com.unipars.engine.Recipe
import kotlinx.serialization.Serializable
import java.util.UUID

/** Portable file format for sharing recipes between devices (no collected data, no keys). */
@Serializable
data class RecipeBundle(val format: String = FORMAT, val version: Int = 1, val recipes: List<Recipe>) {
    companion object {
        const val FORMAT = "uni-pars-recipes"
    }
}

object Transfer {

    fun exportRecipes(recipes: List<Recipe>): String =
        EngineJson.encodeToString(RecipeBundle.serializer(), RecipeBundle(recipes = recipes))

    /** Parses a bundle and gives every recipe a fresh id so imports never overwrite existing ones. */
    fun importRecipes(json: String): List<Recipe> {
        val bundle = EngineJson.decodeFromString(RecipeBundle.serializer(), json)
        require(bundle.format == RecipeBundle.FORMAT) { "not a Uni-pars recipe file" }
        return bundle.recipes.map { it.copy(id = UUID.randomUUID().toString()) }
    }

    /** CSV (RFC 4180) of the collected items; columns = union of field names. */
    fun itemsCsv(items: List<ItemEntity>): String {
        val parsed = items.map { it to it.item() }
        val columns = parsed.flatMap { it.second.fields.keys }.distinct()
        val sb = StringBuilder()
        sb.appendLine((columns + listOf("first_seen", "last_seen")).joinToString(",") { csv(it) })
        val iso = java.time.format.DateTimeFormatter.ISO_INSTANT
        parsed.forEach { (e, item) ->
            val row = columns.map { item.fields[it].orEmpty() } +
                listOf(iso.format(java.time.Instant.ofEpochMilli(e.firstSeen)), iso.format(java.time.Instant.ofEpochMilli(e.lastSeen)))
            sb.appendLine(row.joinToString(",") { csv(it) })
        }
        return sb.toString()
    }

    private fun csv(v: String): String =
        if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + v.replace("\"", "\"\"") + "\"" else v
}
