package com.unipars.engine

import kotlinx.serialization.Serializable

@Serializable
enum class EventType { NEW_ITEM, FIELD_CHANGED, ITEM_REMOVED, BELOW, ABOVE, CONTAINS }

@Serializable
data class ChangeEvent(
    val type: EventType,
    val itemKey: String,
    val field: String? = null,
    val oldValue: String? = null,
    val newValue: String? = null,
    /** Human-readable label of the item (first non-empty field). */
    val label: String,
)

data class DiffResult(
    /** Events that matched the recipe's triggers. */
    val events: List<ChangeEvent>,
    val added: List<Item>,
    val changed: List<Item>,
    val removedKeys: List<String>,
)

/**
 * Compares the current run with the stored state and keeps only the events the user asked for.
 * The first run ([previous] == null) establishes a baseline and emits no new/changed/removed events;
 * threshold and keyword triggers still fire, because they describe the current state.
 */
object Differ {

    fun diff(previous: Map<String, Item>?, current: List<Item>, triggers: List<Trigger>): DiffResult {
        val baseline = previous == null
        val prev = previous.orEmpty()
        val curByKey = current.associateBy { it.key }

        val added = if (baseline) emptyList() else current.filter { it.key !in prev }
        val changed = current.filter { c -> prev[c.key]?.let { it.fields != c.fields } == true }
        val removed = if (baseline) emptyList() else prev.keys.filter { it !in curByKey }

        val events = mutableListOf<ChangeEvent>()
        for (t in triggers) {
            when (t.type) {
                TriggerType.NEW_ITEM -> added.forEach { events += ChangeEvent(EventType.NEW_ITEM, it.key, label = label(it)) }
                TriggerType.ITEM_REMOVED -> removed.forEach { key ->
                    events += ChangeEvent(EventType.ITEM_REMOVED, key, label = label(prev.getValue(key)))
                }
                TriggerType.FIELD_CHANGED -> changed.forEach { c ->
                    val old = prev.getValue(c.key)
                    val fields = t.field?.let { listOf(it) } ?: c.fields.keys.toList()
                    fields.filter { old.fields[it] != c.fields[it] }.forEach { f ->
                        events += ChangeEvent(EventType.FIELD_CHANGED, c.key, f, old.fields[f], c.fields[f], label(c))
                    }
                }
                TriggerType.BELOW, TriggerType.ABOVE -> {
                    val field = t.field ?: continue
                    val limit = t.value?.toBigDecimalOrNull() ?: continue
                    current.forEach { c ->
                        val now = c.fields[field]?.toBigDecimalOrNull() ?: return@forEach
                        val before = prev[c.key]?.fields?.get(field)?.toBigDecimalOrNull()
                        val hit = if (t.type == TriggerType.BELOW) now < limit else now > limit
                        val wasHit = before != null && (if (t.type == TriggerType.BELOW) before < limit else before > limit)
                        // Fire on crossing the threshold, not on every run while it stays crossed.
                        if (hit && !wasHit) {
                            val type = if (t.type == TriggerType.BELOW) EventType.BELOW else EventType.ABOVE
                            events += ChangeEvent(type, c.key, field, before?.toPlainString(), now.toPlainString(), label(c))
                        }
                    }
                }
                TriggerType.CONTAINS -> {
                    val needle = t.value?.lowercase()?.takeIf { it.isNotBlank() } ?: continue
                    current.forEach { c ->
                        val fields = t.field?.let { listOf(it) } ?: c.fields.keys.toList()
                        val match = fields.firstOrNull { c.fields[it]?.lowercase()?.contains(needle) == true }
                        val matchedBefore = prev[c.key]?.let { p -> fields.any { p.fields[it]?.lowercase()?.contains(needle) == true } } == true
                        if (match != null && !matchedBefore) {
                            events += ChangeEvent(EventType.CONTAINS, c.key, match, null, c.fields[match], label(c))
                        }
                    }
                }
            }
        }
        return DiffResult(events.distinct(), added, changed, removed)
    }

    fun label(item: Item): String =
        item.fields.values.firstOrNull { !it.isNullOrBlank() }?.take(80) ?: item.key.take(8)
}
