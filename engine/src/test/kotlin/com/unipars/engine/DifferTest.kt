package com.unipars.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DifferTest {

    private fun item(k: String, title: String, price: String) = Item(k, mapOf("title" to title, "price" to price))

    @Test
    fun baselineEmitsNothingForNewItems() {
        val d = Differ.diff(null, listOf(item("a", "A", "10")), listOf(Trigger(TriggerType.NEW_ITEM)))
        assertTrue(d.events.isEmpty())
    }

    @Test
    fun newChangedRemoved() {
        val prev = mapOf("a" to item("a", "A", "10"), "b" to item("b", "B", "20"))
        val cur = listOf(item("a", "A", "9"), item("c", "C", "30"))
        val d = Differ.diff(prev, cur, listOf(
            Trigger(TriggerType.NEW_ITEM),
            Trigger(TriggerType.FIELD_CHANGED, "price"),
            Trigger(TriggerType.ITEM_REMOVED),
        ))
        assertEquals(setOf(EventType.NEW_ITEM, EventType.FIELD_CHANGED, EventType.ITEM_REMOVED), d.events.map { it.type }.toSet())
        val change = d.events.single { it.type == EventType.FIELD_CHANGED }
        assertEquals("10" to "9", change.oldValue to change.newValue)
        assertEquals("B", d.events.single { it.type == EventType.ITEM_REMOVED }.label)
    }

    @Test
    fun thresholdFiresOnlyOnCrossing() {
        val t = listOf(Trigger(TriggerType.BELOW, "price", "100"))
        val first = Differ.diff(mapOf("a" to item("a", "A", "120")), listOf(item("a", "A", "95")), t)
        assertEquals(1, first.events.size)
        val again = Differ.diff(mapOf("a" to item("a", "A", "95")), listOf(item("a", "A", "90")), t)
        assertTrue(again.events.isEmpty())
        // On the very first run a matching item is reported, since the user asked "tell me when below 100".
        val baseline = Differ.diff(null, listOf(item("a", "A", "50")), t)
        assertEquals(1, baseline.events.size)
    }

    @Test
    fun containsKeyword() {
        val t = listOf(Trigger(TriggerType.CONTAINS, "title", "iphone"))
        val d = Differ.diff(mapOf("a" to item("a", "Samsung", "1")), listOf(item("a", "Samsung", "1"), item("b", "iPhone 17", "2")), t)
        assertEquals("b", d.events.single().itemKey)
    }
}
