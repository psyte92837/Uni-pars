package com.unipars.app.ui

import android.app.Application
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.unipars.ai.SetupResult
import com.unipars.app.R
import com.unipars.app.UniParsApp
import com.unipars.app.data.RecipeStatus
import com.unipars.app.data.Transfer
import com.unipars.app.data.item
import com.unipars.app.data.recipe
import com.unipars.app.setup.SetupEngine
import com.unipars.app.work.Scheduler
import com.unipars.engine.FetchSettings
import com.unipars.engine.Recipe
import com.unipars.engine.Trigger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class RepairState(val running: Boolean = false, val result: SetupResult? = null, val error: String? = null)

class RecipeDetailViewModel(application: Application, handle: SavedStateHandle) : AndroidViewModel(application) {
    private val app = application as UniParsApp
    val id: String = checkNotNull(handle["id"])
    val recipe = app.db.recipes().observe(id)
    val items = app.db.items().observeActive(id)
    val events = app.db.events().observe(id)
    val runs = app.db.runs().observe(id)
    val repair = MutableStateFlow(RepairState())

    fun runNow() = Scheduler.runNow(app, id)

    fun setEnabled(enabled: Boolean) = viewModelScope.launch {
        val e = app.db.recipes().get(id) ?: return@launch
        app.db.recipes().upsert(e.copy(enabled = enabled))
        if (enabled) Scheduler.schedule(app, e.recipe()) else Scheduler.cancel(app, id)
    }

    fun save(recipe: Recipe) = viewModelScope.launch {
        app.repository.updateRecipe(recipe)
        val e = app.db.recipes().get(id) ?: return@launch
        if (e.enabled) Scheduler.schedule(app, recipe, app.repository.profile(e.domain)?.facts?.crawlDelaySec)
    }

    fun delete(onDone: () -> Unit) = viewModelScope.launch {
        Scheduler.cancel(app, id)
        app.db.deleteRecipe(id)
        onDone()
    }

    suspend fun csv(): String = Transfer.itemsCsv(app.db.items().active(id, Int.MAX_VALUE))

    /** Spends tokens only when the user presses the button. */
    fun startRepair() = viewModelScope.launch {
        repair.value = RepairState(running = true)
        try {
            val e = app.db.recipes().get(id) ?: return@launch
            val lastGood = app.db.items().active(id, 5).map { it.item() }
            val engine = SetupEngine(app)
            val result = withContext(Dispatchers.IO) {
                engine.repair(engine.client(), e.recipe(), lastGood, e.lastMessage ?: "recipe stopped matching").also { engine.addUsage(it.usage) }
            }
            repair.value = RepairState(result = result)
        } catch (ex: Exception) {
            repair.value = RepairState(error = ex.message ?: ex.toString())
        }
    }

    fun applyRepair() = viewModelScope.launch {
        val r = repair.value.result ?: return@launch
        app.repository.updateRecipe(r.recipe)
        val e = app.db.recipes().get(id) ?: return@launch
        app.db.recipes().upsert(e.copy(status = RecipeStatus.ACTIVE, consecutiveFailures = 0, lastMessage = app.getString(R.string.msg_repaired), bodyHash = null))
        Scheduler.schedule(app, r.recipe)
        Scheduler.runNow(app, id)
        repair.value = RepairState()
    }

    fun dismissRepair() {
        repair.value = RepairState()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeDetailScreen(onBack: () -> Unit, onCheck: (String) -> Unit, vm: RecipeDetailViewModel = viewModel()) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val entity by vm.recipe.collectAsStateWithLifecycle(initialValue = null)
    val itemList by vm.items.collectAsStateWithLifecycle(initialValue = emptyList())
    val events by vm.events.collectAsStateWithLifecycle(initialValue = emptyList())
    val runs by vm.runs.collectAsStateWithLifecycle(initialValue = emptyList())
    val repair by vm.repair.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    var confirmDelete by remember { mutableStateOf(false) }
    val e = entity

    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { ctx.contentResolver.openOutputStream(uri)!!.use { it.write(vm.csv().toByteArray()) } }.isSuccess
            }
            Toast.makeText(ctx, if (ok) R.string.export_done else R.string.export_failed, Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(e?.name ?: "", maxLines = 1) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) } },
            actions = {
                IconButton(onClick = vm::runNow) { Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.action_run_now)) }
                IconButton(onClick = { csvLauncher.launch("${e?.domain ?: "data"}.csv") }) { Icon(Icons.Default.Share, contentDescription = stringResource(R.string.action_export_csv)) }
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.action_delete)) }
            },
        )
    }) { padding ->
        if (e == null) return@Scaffold
        val recipe = remember(e.json) { runCatching { e.recipe() }.getOrNull() }
        Column(Modifier.padding(padding).fillMaxSize()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(statusText(e.status, e.enabled), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Switch(checked = e.enabled, onCheckedChange = { vm.setEnabled(it) })
                }
                Text(stringResource(R.string.detail_last_run, formatTime(e.lastRunAt), e.itemCount), style = MaterialTheme.typography.bodySmall)
                e.lastMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (e.status == RecipeStatus.NEEDS_HUMAN) {
                    Button(onClick = { onCheck(e.id) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text(stringResource(R.string.detail_pass_check, e.domain)) }
                }
                if (e.status == RecipeStatus.BROKEN) {
                    Button(onClick = { vm.startRepair() }, enabled = !repair.running, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text(stringResource(R.string.detail_repair)) }
                }
            }
            val tabs = listOf(R.string.tab_data, R.string.tab_events, R.string.tab_log, R.string.tab_settings)
            TabRow(selectedTabIndex = tab) {
                tabs.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(stringResource(t)) }) }
            }
            when (tab) {
                0 -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                    items(itemList, key = { it.key }) { ItemCard(it.item()) }
                }
                1 -> LazyColumn(Modifier.fillMaxSize().padding(12.dp)) {
                    items(events, key = { it.id }) { ev ->
                        Column(Modifier.padding(vertical = 6.dp)) {
                            Text(formatTime(ev.ts) + " · " + ev.type.lowercase(), style = MaterialTheme.typography.labelSmall)
                            Text(ev.label + (ev.field?.let { f -> ": $f ${ev.oldValue ?: "—"} → ${ev.newValue ?: "—"}" } ?: ""))
                        }
                    }
                }
                2 -> LazyColumn(Modifier.fillMaxSize().padding(12.dp)) {
                    items(runs, key = { it.id }) { r ->
                        Text(
                            "${formatTime(r.ts)} · ${r.outcome} · ${r.durationMs} ms · ${r.items}" + (r.message?.let { " · $it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                }
                else -> if (recipe != null) RecipeSettingsTab(recipe, onSave = { vm.save(it) }, onCheck = { onCheck(e.id) }, onRepair = { vm.startRepair() })
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_title)) },
            text = { Text(stringResource(R.string.delete_text)) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete(onBack) }) { Text(stringResource(R.string.action_delete)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (repair.running) {
        AlertDialog(onDismissRequest = {}, confirmButton = {}, title = { Text(stringResource(R.string.repair_running)) }, text = { CircularProgressIndicator() })
    }
    repair.error?.let { err ->
        AlertDialog(
            onDismissRequest = vm::dismissRepair,
            confirmButton = { TextButton(onClick = vm::dismissRepair) { Text(stringResource(R.string.action_ok)) } },
            title = { Text(stringResource(R.string.repair_failed)) },
            text = { Text(err) },
        )
    }
    repair.result?.let { r ->
        AlertDialog(
            onDismissRequest = vm::dismissRepair,
            title = { Text(stringResource(if (r.validationProblem == null) R.string.repair_ok else R.string.repair_still_broken)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    r.foreignSource?.let { WarningCard(stringResource(R.string.new_foreign, it)) }
                    r.validationProblem?.let { WarningCard(it) }
                    Text(r.explanation, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    r.preview.take(5).forEach { ItemCard(it) }
                }
            },
            // A repair that moves the source to another site is not applied silently.
            confirmButton = { TextButton(onClick = { vm.applyRepair() }, enabled = r.foreignSource == null) { Text(stringResource(R.string.action_apply)) } },
            dismissButton = { TextButton(onClick = vm::dismissRepair) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun RecipeSettingsTab(recipe: Recipe, onSave: (Recipe) -> Unit, onCheck: () -> Unit, onRepair: () -> Unit) {
    var draft by remember(recipe) { mutableStateOf(recipe) }
    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
        Text(stringResource(R.string.detail_source, recipe.source.url), style = MaterialTheme.typography.bodySmall)
        Text(
            stringResource(R.string.detail_mode, recipe.source.mode.name.lowercase(), recipe.extract.type.name.lowercase(), recipe.extract.fields.joinToString { it.name }),
            style = MaterialTheme.typography.bodySmall,
        )
        SectionTitle(stringResource(R.string.section_triggers))
        TriggersEditor(draft.triggers, draft.extract.fields.map { it.name }) { t: List<Trigger> -> draft = draft.copy(triggers = t) }
        SectionTitle(stringResource(R.string.section_params))
        SettingsEditor(draft.settings) { s: FetchSettings -> draft = draft.copy(settings = s) }
        Spacer(Modifier.height(12.dp))
        Button(onClick = { onSave(draft) }, enabled = draft != recipe, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_save)) }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCheck, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.detail_open_site)) }
            OutlinedButton(onClick = onRepair, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.detail_reconfigure)) }
        }
        Spacer(Modifier.height(32.dp))
    }
}
