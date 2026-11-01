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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.unipars.ai.AiUsage
import com.unipars.ai.PickedElement
import com.unipars.ai.SetupResult
import com.unipars.app.UniParsApp
import com.unipars.app.setup.PageCapture
import com.unipars.app.setup.SetupEngine
import com.unipars.app.work.Scheduler
import com.unipars.engine.Confidence
import com.unipars.engine.FetchSettings
import com.unipars.engine.Item
import com.unipars.engine.ProbeFacts
import com.unipars.engine.Recipe
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
    val progress: String = "",
    val error: String? = null,
    val result: SetupResult? = null,
    val facts: ProbeFacts? = null,
    val recipe: Recipe? = null,
    val usage: AiUsage = AiUsage(),
    val saved: Boolean = false,
)

class NewRecipeViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as UniParsApp
    private val engine = SetupEngine(application)
    val state = MutableStateFlow(NewRecipeState())

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

    fun onPick(path: String, html: String) = state.update { s ->
        if (s.picked.any { it.cssPath == path }) s else s.copy(picked = s.picked + PickedElement(path, html))
    }

    fun clearPicked() = state.update { it.copy(picked = emptyList()) }

    fun onJson(url: String, body: String) = state.update { s ->
        if (s.xhr.size >= 30 || url in s.xhr) s else s.copy(xhr = s.xhr + (url to body))
    }

    fun analyze(renderedHtml: String?, currentUrl: String?) {
        val s = state.value
        if (s.goal.isBlank()) {
            state.update { it.copy(error = "Опишите, что нужно отслеживать") }
            return
        }
        val capture = PageCapture(currentUrl ?: s.url, renderedHtml, s.xhr, s.picked)
        state.update { it.copy(step = SetupStep.WORKING, error = null, progress = "Проверяю сайт (без ИИ)…") }
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val client = engine.client()
                    val probe = engine.probe(capture)
                    state.update { it.copy(facts = probe.facts, progress = "Ищу сведения о сайте…") }
                    val (research, researchUsage) = engine.research(client, capture.url, s.goal, probe.facts)
                    state.update { it.copy(progress = "ИИ составляет рецепт и проверяет его на странице…") }
                    val input = engine.buildInput(capture, s.goal, probe, research)
                    val r = engine.create(client, UUID.randomUUID().toString(), input, capture, probe)
                    engine.addUsage(r.usage + researchUsage)
                    r to (r.usage + researchUsage)
                }
                state.update { it.copy(step = SetupStep.RESULT, result = result.first, recipe = result.first.recipe, usage = result.second) }
            } catch (e: Exception) {
                state.update { it.copy(step = SetupStep.PAGE, error = e.message ?: e.toString()) }
            }
        }
    }

    fun editRecipe(transform: (Recipe) -> Recipe) = state.update { s -> s.copy(recipe = s.recipe?.let(transform)) }

    fun save() {
        val s = state.value
        val recipe = s.recipe ?: return
        val result = s.result ?: return
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
    val s by vm.state.collectAsState()
    androidx.compose.runtime.LaunchedEffect(s.saved) { if (s.saved) onDone() }
    val controller = remember { PageController() }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(when (s.step) { SetupStep.INPUT -> "Новый рецепт"; SetupStep.PAGE -> "Покажите, что парсить"; SetupStep.WORKING -> "Анализ"; SetupStep.RESULT -> "Проверьте рецепт" }) },
            navigationIcon = {
                IconButton(onClick = { if (s.step == SetupStep.INPUT) onDone() else vm.back() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
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
                    Text(s.progress)
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
            value = s.url, onValueChange = vm::setUrl, label = { Text("Адрес страницы") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = s.goal, onValueChange = vm::setGoal, minLines = 3,
            label = { Text("Что отслеживать") },
            placeholder = { Text("Например: название, цена и ссылка каждого товара; сообщить, если цена ниже 50 000") },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "На следующем шаге страница откроется во встроенном браузере. Нажмите «Выбор элементов» и коснитесь нужных блоков — так ИИ точнее поймёт задачу. Токены тратятся только на этом этапе; дальше сбор идёт без ИИ.",
            style = MaterialTheme.typography.bodySmall,
        )
        Button(onClick = vm::openPage, enabled = s.url.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Открыть страницу") }
    }
}

@Composable
private fun PageStep(s: NewRecipeState, vm: NewRecipeViewModel, controller: PageController) {
    var pickMode by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = pickMode, onClick = { pickMode = !pickMode; controller.setPickMode(pickMode) }, label = { Text("Выбор элементов") })
            if (s.picked.isNotEmpty()) {
                FilterChip(selected = true, onClick = vm::clearPicked, label = { Text("Выбрано: ${s.picked.size} ✕") })
            }
            Spacer(Modifier.weight(1f))
            Button(onClick = { controller.captureHtml { html, url -> vm.analyze(html, url) } }) { Text("Анализировать") }
        }
        s.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(8.dp)) }
        if (s.xhr.isNotEmpty()) {
            Text("Найдено JSON-ответов: ${s.xhr.size}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 8.dp))
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
        OutlinedTextField(value = recipe.name, onValueChange = { n -> vm.editRecipe { it.copy(name = n) } }, label = { Text("Название") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Text(result.explanation, style = MaterialTheme.typography.bodyMedium)
        Text(
            "Способ: ${recipe.source.mode.name.lowercase()}, ${recipe.extract.type.name.lowercase()} · попыток ИИ: ${result.attempts} · токены: ${s.usage.inputTokens}/${s.usage.outputTokens}",
            style = MaterialTheme.typography.bodySmall,
        )
        result.validationProblem?.let {
            WarningCard("Рецепт не прошёл проверку на странице: $it. Можно сохранить, но вероятно он не будет работать — лучше выбрать элементы точнее и повторить анализ.")
        }
        result.warnings.forEach { WarningCard(it) }

        SectionTitle("Что извлечено (${result.preview.size})")
        if (result.preview.isEmpty()) Text("Ничего не найдено")
        result.preview.take(10).forEach { ItemCard(it) }

        SectionTitle("О сайте")
        result.notes.forEach { n ->
            val badge = when (n.confidence) { Confidence.MEASURED -> "измерено"; Confidence.SOURCED -> "источник"; Confidence.ASSUMED -> "предположение ИИ" }
            Text("• [$badge] ${n.text}" + (n.source?.let { " ($it)" } ?: ""), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp))
        }

        SectionTitle("Уведомлять, когда")
        TriggersEditor(recipe.triggers, recipe.extract.fields.map { it.name }) { t: List<Trigger> -> vm.editRecipe { it.copy(triggers = t) } }

        SectionTitle("Настройки (рекомендованы, можно менять)")
        SettingsEditor(recipe.settings) { st: FetchSettings -> vm.editRecipe { it.copy(settings = st) } }

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = vm::back, modifier = Modifier.weight(1f)) { Text("Назад к странице") }
            Button(onClick = vm::save, modifier = Modifier.weight(1f)) { Text("Сохранить и запустить") }
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
