package com.unipars.app.ui

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.unipars.ai.AiUsage
import com.unipars.ai.PickedElement
import com.unipars.ai.SetupResult
import com.unipars.app.R
import com.unipars.app.UniParsApp
import com.unipars.app.setup.PageCapture
import com.unipars.app.setup.SetupEngine
import com.unipars.app.work.Scheduler
import com.unipars.engine.Confidence
import com.unipars.engine.FetchSettings
import com.unipars.engine.Item
import com.unipars.engine.ProbeFacts
import com.unipars.engine.Recipe
import com.unipars.engine.Safety
import com.unipars.engine.Trigger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

enum class SetupStep { INPUT, PAGE, WORKING, RESULT }

data class NewRecipeState(
    val step: SetupStep = SetupStep.INPUT,
    val url: String = "",
    val goal: String = "",
    val picked: List<PickedElement> = emptyList(),
    val xhr: Map<String, String> = emptyMap(),
    val progress: Int = 0,
    val error: String? = null,
    val result: SetupResult? = null,
    val facts: ProbeFacts? = null,
    val recipe: Recipe? = null,
    val usage: AiUsage = AiUsage(),
    val costUsd: Double? = null,
    val foreignConfirmed: Boolean = false,
    val saved: Boolean = false,
)

class NewRecipeViewModel(application: Application, handle: SavedStateHandle) : AndroidViewModel(application) {
    private val app = application as UniParsApp
    private val engine = SetupEngine(application)
    val state = MutableStateFlow(NewRecipeState(url = handle.get<String>("url").orEmpty(), goal = handle.get<String>("goal").orEmpty()))

    fun setUrl(v: String) = state.update { it.copy(url = v) }
    fun setGoal(v: String) = state.update { it.copy(goal = v) }

    fun openPage() {
        val url = state.value.url.trim().let { if (it.startsWith("http")) it else "https://$it" }
        state.update { it.copy(url = url, step = SetupStep.PAGE, error = null, picked = emptyList(), xhr = emptyMap()) }
    }

    fun back() = state.update {
        when (it.step) {
            SetupStep.RESULT, SetupStep.WORKING -> it.copy(step = SetupStep.PAGE, error = null)
            else -> it.copy(step = SetupStep.INPUT, error = null)
        }
    }

    // Everything arriving through the JS bridge is untrusted page content: size-limited here.
    fun onPick(path: String, html: String) = state.update { s ->
        if (s.picked.size >= 20 || s.picked.any { it.cssPath == path }) s
        else s.copy(picked = s.picked + PickedElement(Safety.clip(path, 500), Safety.clip(html, 3_000)))
    }

    fun clearPicked() = state.update { it.copy(picked = emptyList()) }

    fun onJson(url: String, body: String) = state.update { s ->
        if (s.xhr.size >= 30 || url in s.xhr) s
        else s.copy(xhr = s.xhr + (Safety.clip(url, 1_000) to Safety.maskPersonalData(Safety.clip(body, 6_000))))
    }

    fun analyze(renderedHtml: String?, currentUrl: String?) {
        val s = state.value
        if (s.goal.isBlank()) {
            state.update { it.copy(error = app.getString(R.string.err_goal_empty)) }
            return
        }
        val capture = PageCapture(currentUrl ?: s.url, renderedHtml, s.xhr, s.picked)
        state.update { it.copy(step = SetupStep.WORKING, error = null, progress = R.string.progress_probe) }
        viewModelScope.launch {
            try {
                val (result, usage) = withContext(Dispatchers.IO) {
                    val client = engine.client()
                    val probe = engine.probe(capture)
                    state.update { it.copy(facts = probe.facts, progress = R.string.progress_research) }
                    val (research, researchUsage) = engine.research(client, capture.url, s.goal, probe.facts)
                    state.update { it.copy(progress = R.string.progress_ai) }
                    val input = engine.buildInput(capture, s.goal, probe, research)
                    val r = engine.create(client, UUID.randomUUID().toString(), input, capture, probe)
                    val total = r.usage + researchUsage
                    engine.addUsage(total)
                    r to total
                }
                state.update {
                    it.copy(
                        step = SetupStep.RESULT, result = result, recipe = result.recipe, usage = usage,
                        costUsd = engine.estimateUsd(usage), foreignConfirmed = false,
                    )
                }
            } catch (e: Exception) {
                state.update { it.copy(step = SetupStep.PAGE, error = e.message ?: e.toString()) }
            }
        }
    }

    fun editRecipe(transform: (Recipe) -> Recipe) = state.update { s -> s.copy(recipe = s.recipe?.let(transform)) }

    fun confirmForeign(v: Boolean) = state.update { it.copy(foreignConfirmed = v) }

    fun save() {
        val s = state.value
        val recipe = s.recipe ?: return
        val result = s.result ?: return
        if (result.foreignSource != null && !s.foreignConfirmed) return
        viewModelScope.launch {
            val profile = s.facts?.let { engine.profile(recipe.source.url, it, result) }
            app.repository.saveNewRecipe(recipe, profile)
            Scheduler.schedule(app, recipe, profile?.facts?.crawlDelaySec)
            Scheduler.runNow(app, recipe.id)
            state.update { it.copy(saved = true) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewRecipeScreen(onDone: () -> Unit, vm: NewRecipeViewModel = viewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val requestNotifications = rememberNotificationPermissionRequest()
    LaunchedEffect(s.saved) {
        if (s.saved) {
            // Ask for notifications in context: right after the first recipe that will send them.
            requestNotifications()
            onDone()
        }
    }
    val controller = remember { PageController() }
    val title = stringResource(
        when (s.step) {
            SetupStep.INPUT -> R.string.new_title_input
            SetupStep.PAGE -> R.string.new_title_page
            SetupStep.WORKING -> R.string.new_title_working
            SetupStep.RESULT -> R.string.new_title_result
        },
    )
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(title) },
            navigationIcon = {
                IconButton(onClick = { if (s.step == SetupStep.INPUT) onDone() else vm.back() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
            },
        )
    }) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (s.step) {
                SetupStep.INPUT -> InputStep(s, vm)
                SetupStep.PAGE -> PageStep(s, vm, controller)
                SetupStep.WORKING -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    if (s.progress != 0) Text(stringResource(s.progress))
                }
                SetupStep.RESULT -> ResultStep(s, vm)
            }
        }
    }
}

@Composable
private fun InputStep(s: NewRecipeState, vm: NewRecipeViewModel) {
    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = s.url, onValueChange = vm::setUrl, label = { Text(stringResource(R.string.new_url)) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = s.goal, onValueChange = vm::setGoal, minLines = 3,
            label = { Text(stringResource(R.string.new_goal)) },
            placeholder = { Text(stringResource(R.string.new_goal_hint)) },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(stringResource(R.string.new_explain), style = MaterialTheme.typography.bodySmall)
        Button(onClick = vm::openPage, enabled = s.url.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.new_open)) }
    }
}

@Composable
private fun PageStep(s: NewRecipeState, vm: NewRecipeViewModel, controller: PageController) {
    var pickMode by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = pickMode, onClick = { pickMode = !pickMode; controller.setPickMode(pickMode) }, label = { Text(stringResource(R.string.new_pick)) })
            if (s.picked.isNotEmpty()) {
                FilterChip(selected = true, onClick = vm::clearPicked, label = { Text(stringResource(R.string.new_picked, s.picked.size)) })
            }
            Spacer(Modifier.weight(1f))
            Button(onClick = { controller.captureHtml { html, url -> vm.analyze(html, url) } }) { Text(stringResource(R.string.new_analyze)) }
        }
        s.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(8.dp)) }
        if (s.xhr.isNotEmpty()) {
            Text(stringResource(R.string.new_json_found, s.xhr.size), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 8.dp))
        }
        PageWebView(
            url = s.url,
            controller = controller,
            modifier = Modifier.fillMaxSize(),
            onPick = vm::onPick,
            onJson = vm::onJson,
            onPageFinished = { controller.setPickMode(pickMode) },
        )
    }
}

@Composable
private fun ResultStep(s: NewRecipeState, vm: NewRecipeViewModel) {
    val result = s.result ?: return
    val recipe = s.recipe ?: return
    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
        OutlinedTextField(value = recipe.name, onValueChange = { n -> vm.editRecipe { it.copy(name = n) } }, label = { Text(stringResource(R.string.new_name)) }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Text(result.explanation, style = MaterialTheme.typography.bodyMedium)
        val cost = s.costUsd?.let { "≈ $" + "%.4f".format(it) } ?: "—"
        Text(
            stringResource(R.string.new_stats, recipe.source.mode.name.lowercase(), recipe.extract.type.name.lowercase(), result.attempts, s.usage.inputTokens, s.usage.outputTokens, cost),
            style = MaterialTheme.typography.bodySmall,
        )
        result.foreignSource?.let { foreign ->
            WarningCard(stringResource(R.string.new_foreign, foreign))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = s.foreignConfirmed, onCheckedChange = vm::confirmForeign)
                Text(stringResource(R.string.new_foreign_confirm), style = MaterialTheme.typography.bodySmall)
            }
        }
        result.validationProblem?.let { WarningCard(stringResource(R.string.new_validation, it)) }
        result.warnings.forEach { WarningCard(it) }

        SectionTitle(stringResource(R.string.new_preview, result.preview.size))
        if (result.preview.isEmpty()) Text(stringResource(R.string.new_preview_empty))
        result.preview.take(10).forEach { ItemCard(it) }

        SectionTitle(stringResource(R.string.new_about_site))
        result.notes.forEach { n ->
            val badge = stringResource(
                when (n.confidence) {
                    Confidence.MEASURED -> R.string.conf_measured
                    Confidence.SOURCED -> R.string.conf_sourced
                    Confidence.ASSUMED -> R.string.conf_assumed
                },
            )
            Text("• [$badge] ${n.text}" + (n.source?.let { " ($it)" } ?: ""), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp))
        }

        SectionTitle(stringResource(R.string.section_triggers))
        TriggersEditor(recipe.triggers, recipe.extract.fields.map { it.name }) { t: List<Trigger> -> vm.editRecipe { it.copy(triggers = t) } }

        SectionTitle(stringResource(R.string.new_settings))
        SettingsEditor(recipe.settings) { st: FetchSettings -> vm.editRecipe { it.copy(settings = st) } }

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = vm::back, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.new_back_to_page)) }
            Button(
                onClick = vm::save,
                enabled = result.foreignSource == null || s.foreignConfirmed,
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.new_save)) }
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
fun WarningCard(text: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) { Text(text, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer) }
}

@Composable
fun ItemCard(item: Item) {
    Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Column(Modifier.padding(10.dp)) {
            item.fields.forEach { (k, v) ->
                Row {
                    Text("$k: ", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                    Text(v ?: "—", style = MaterialTheme.typography.bodySmall, maxLines = 2)
                }
            }
        }
    }
}
