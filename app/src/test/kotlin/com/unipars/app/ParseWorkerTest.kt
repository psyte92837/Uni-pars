package com.unipars.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.unipars.app.data.RecipeStatus
import com.unipars.app.work.ParseWorker
import com.unipars.engine.Extract
import com.unipars.engine.FetchResponse
import com.unipars.engine.FieldSpec
import com.unipars.engine.PageFetcher
import com.unipars.engine.Recipe
import com.unipars.engine.Source
import com.unipars.engine.Transform
import com.unipars.engine.Trigger
import com.unipars.engine.TriggerType
import com.unipars.engine.Validation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ParseWorkerTest {

    private val app = ApplicationProvider.getApplicationContext<UniParsApp>()

    private fun page(vararg items: Pair<String, String>) = "<html><body>" +
        items.joinToString("") { (name, price) -> "<div class='p'><a href='/p/$name'>$name</a><b>$price</b></div>" } +
        "</body></html>"

    private val recipe = Recipe(
        id = "r1",
        name = "Test shop",
        source = Source("https://shop.example/list"),
        extract = Extract(
            itemSelector = ".p",
            fields = listOf(FieldSpec("title", "a"), FieldSpec("price", "b", transform = Transform.NUMBER), FieldSpec("url", "a", "href", Transform.ABSOLUTE_URL)),
            key = listOf("url"),
        ),
        validate = Validation(minItems = 1, requiredFields = listOf("title")),
        triggers = listOf(Trigger(TriggerType.NEW_ITEM), Trigger(TriggerType.FIELD_CHANGED, "price")),
    )

    private fun run(html: String): ListenableWorker.Result {
        app.fetcherFactory = { PageFetcher { url, _, _ -> FetchResponse(200, emptyMap(), html, url) } }
        // Skip the polite per-domain gap between the two test runs.
        app.getSharedPreferences("domain_gate", Context.MODE_PRIVATE).edit().clear().commit()
        val worker = TestListenableWorkerBuilder<ParseWorker>(app)
            .setInputData(workDataOf(ParseWorker.KEY_ID to recipe.id, ParseWorker.KEY_MANUAL to true))
            .build()
        return runBlocking { worker.doWork() }
    }

    @Test
    fun baselineThenChanges() = runBlocking {
        app.repository.saveNewRecipe(recipe, null)

        assertEquals(ListenableWorker.Result.success(), run(page("A" to "100", "B" to "200")))
        val afterBaseline = app.db.recipes().get(recipe.id)!!
        assertTrue(afterBaseline.baselineDone)
        assertEquals(2, afterBaseline.itemCount)
        assertEquals(RecipeStatus.ACTIVE, afterBaseline.status)
        assertTrue(app.db.events().pending(recipe.id).isEmpty())

        run(page("A" to "90", "B" to "200", "C" to "300"))
        val all = app.db.events().observe(recipe.id).first()
        assertEquals(setOf("NEW_ITEM", "FIELD_CHANGED"), all.map { it.type }.toSet())
        assertEquals("100" to "90", all.single { it.type == "FIELD_CHANGED" }.let { it.oldValue to it.newValue })
        assertEquals(3, app.db.recipes().get(recipe.id)!!.itemCount)

        run("<html><body><div class='other'></div></body></html>")
        assertEquals(RecipeStatus.BROKEN, app.db.recipes().get(recipe.id)!!.status)
    }

    @Test
    fun captchaPausesRecipe() = runBlocking {
        app.repository.saveNewRecipe(recipe.copy(id = "r2"), null)
        app.fetcherFactory = { PageFetcher { url, _, _ -> FetchResponse(403, mapOf("cf-mitigated" to "challenge"), "<title>Just a moment...</title>", url) } }
        val worker = TestListenableWorkerBuilder<ParseWorker>(app)
            .setInputData(workDataOf(ParseWorker.KEY_ID to "r2", ParseWorker.KEY_MANUAL to true))
            .build()
        worker.doWork()
        assertEquals(RecipeStatus.NEEDS_HUMAN, app.db.recipes().get("r2")!!.status)
    }
}
