package com.unipars.app.data

import androidx.room.withTransaction
import com.unipars.engine.ChangeEvent
import com.unipars.engine.EngineJson
import com.unipars.engine.Item
import com.unipars.engine.Recipe
import com.unipars.engine.SiteProfile
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer

private val fieldsSerializer = MapSerializer(String.serializer(), String.serializer().nullable)

fun RecipeEntity.recipe(): Recipe = EngineJson.decodeFromString(Recipe.serializer(), json)

fun Recipe.toJson(): String = EngineJson.encodeToString(Recipe.serializer(), this)

fun ItemEntity.item(): Item = Item(key, EngineJson.decodeFromString(fieldsSerializer, fieldsJson))

fun domainOf(url: String): String =
    runCatching { java.net.URI(url).host?.removePrefix("www.") }.getOrNull() ?: url

class Repository(val db: AppDatabase) {

    suspend fun saveNewRecipe(recipe: Recipe, profile: SiteProfile?) {
        val now = System.currentTimeMillis()
        db.withTransaction {
            val existing = db.recipes().get(recipe.id)
            db.recipes().upsert(
                existing?.copy(name = recipe.name, json = recipe.toJson(), status = RecipeStatus.ACTIVE, consecutiveFailures = 0, lastMessage = null)
                    ?: RecipeEntity(recipe.id, recipe.name, domainOf(recipe.source.url), recipe.toJson(), createdAt = now),
            )
            profile?.let { db.profiles().upsert(ProfileEntity(it.domain, EngineJson.encodeToString(SiteProfile.serializer(), it), now)) }
        }
    }

    suspend fun updateRecipe(recipe: Recipe) {
        val e = db.recipes().get(recipe.id) ?: return
        db.recipes().upsert(e.copy(name = recipe.name, json = recipe.toJson()))
    }

    suspend fun profile(domain: String): SiteProfile? =
        db.profiles().get(domain)?.let { runCatching { EngineJson.decodeFromString(SiteProfile.serializer(), it.json) }.getOrNull() }

    /** Previous state for diffing; null before the first successful run (baseline). */
    suspend fun previousItems(entity: RecipeEntity): Map<String, Item>? =
        if (!entity.baselineDone) null else db.items().active(entity.id).associate { it.key to it.item() }

    suspend fun storeRun(recipeId: String, items: List<Item>, removedKeys: List<String>, events: List<ChangeEvent>, now: Long) {
        db.withTransaction {
            val existing = db.items().all(recipeId).associateBy { it.key }
            db.items().insertAll(items.map { item ->
                ItemEntity(
                    recipeId = recipeId,
                    key = item.key,
                    fieldsJson = EngineJson.encodeToString(fieldsSerializer, item.fields),
                    firstSeen = existing[item.key]?.firstSeen ?: now,
                    lastSeen = now,
                    active = true,
                )
            })
            if (removedKeys.isNotEmpty()) removedKeys.chunked(500).forEach { db.items().deactivate(recipeId, it) }
            if (events.isNotEmpty()) {
                db.events().insertAll(events.map {
                    EventEntity(
                        recipeId = recipeId, ts = now, type = it.type.name, label = it.label,
                        field = it.field, oldValue = it.oldValue, newValue = it.newValue,
                    )
                })
            }
        }
    }
}
