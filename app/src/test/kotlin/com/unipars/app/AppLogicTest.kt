package com.unipars.app

import com.unipars.app.data.ItemEntity
import com.unipars.app.data.Transfer
import com.unipars.app.ui.MainActivity
import com.unipars.app.update.UpdateChecker
import com.unipars.engine.Extract
import com.unipars.engine.FieldSpec
import com.unipars.engine.Recipe
import com.unipars.engine.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLogicTest {

    @Test
    fun recipesRoundTripWithNewIds() {
        val r = Recipe("id-1", "Shop", Source("https://x.example/"), Extract(itemSelector = ".a", fields = listOf(FieldSpec("t", "b"))))
        val imported = Transfer.importRecipes(Transfer.exportRecipes(listOf(r)))
        assertEquals(1, imported.size)
        assertNotEquals("id-1", imported[0].id)
        assertEquals(r.copy(id = imported[0].id), imported[0])
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsForeignFiles() {
        Transfer.importRecipes("""{"format":"something-else","recipes":[]}""")
    }

    @Test
    fun csvEscaping() {
        val csv = Transfer.itemsCsv(listOf(ItemEntity("r", "k", """{"title":"A, \"quoted\"","price":"10"}""", 0, 0)))
        val lines = csv.trim().lines()
        assertEquals("title,price,first_seen,last_seen", lines[0])
        assertTrue(lines[1].startsWith("\"A, \"\"quoted\"\"\",10,"))
    }

    @Test
    fun versions() {
        assertTrue(UpdateChecker.isNewer("0.2.0", "0.1.9"))
        assertTrue(UpdateChecker.isNewer("1.0.0", "1.0.0-dev"))
        assertFalse(UpdateChecker.isNewer("1.0.0", "1.0.0"))
        assertFalse(UpdateChecker.isNewer("0.0.5", "0.1.0"))
    }

    @Test
    fun sharedUrl() {
        assertEquals("https://shop.example/p?id=1", MainActivity.extractUrl("Look at this https://shop.example/p?id=1."))
        assertEquals(null, MainActivity.extractUrl("no link"))
    }
}
