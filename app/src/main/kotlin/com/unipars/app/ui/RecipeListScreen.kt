package com.unipars.app.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unipars.app.R
import com.unipars.app.UniParsApp
import com.unipars.app.data.RecipeStatus
import com.unipars.app.data.Transfer
import com.unipars.app.data.recipe
import com.unipars.app.work.Scheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun statusText(status: RecipeStatus, enabled: Boolean): String = stringResource(
    when {
        !enabled -> R.string.status_paused
        status == RecipeStatus.ACTIVE -> R.string.status_active
        status == RecipeStatus.NEEDS_HUMAN -> R.string.status_needs_human
        status == RecipeStatus.BROKEN -> R.string.status_broken
        else -> R.string.status_error
    },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeListScreen(onOpen: (String) -> Unit, onNew: () -> Unit, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as UniParsApp
    val scope = rememberCoroutineScope()
    val recipes by remember { app.db.recipes().observeAll() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val hasKey = remember { app.prefs.hasKey }
    var menu by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val json = Transfer.exportRecipes(app.db.recipes().all().mapNotNull { runCatching { it.recipe() }.getOrNull() })
                    ctx.contentResolver.openOutputStream(uri)!!.use { it.write(json.toByteArray()) }
                }.isSuccess
            }
            Toast.makeText(ctx, if (ok) R.string.export_done else R.string.export_failed, Toast.LENGTH_SHORT).show()
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val imported = withContext(Dispatchers.IO) {
                runCatching {
                    val json = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
                    Transfer.importRecipes(json).onEach { r ->
                        app.repository.saveNewRecipe(r, null)
                        Scheduler.schedule(app, r)
                    }.size
                }.getOrNull()
            }
            val msg = if (imported != null) ctx.getString(R.string.import_done, imported) else ctx.getString(R.string.import_failed)
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings_title)) }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.action_more)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.export_recipes)) }, onClick = {
                            menu = false
                            exportLauncher.launch("uni-pars-recipes.json")
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.import_recipes)) }, onClick = {
                            menu = false
                            importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                        })
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNew) { Icon(Icons.Default.Add, contentDescription = stringResource(R.string.new_title_input)) }
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize().padding(horizontal = 12.dp)) {
            if (!hasKey) item { WarningCard(stringResource(R.string.list_no_key)) }
            if (recipes.isEmpty()) item { Text(stringResource(R.string.list_empty), modifier = Modifier.padding(16.dp)) }
            items(recipes, key = { it.id }) { r ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onOpen(r.id) }) {
                    Column(Modifier.padding(12.dp)) {
                        Text(r.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        Text(r.domain, style = MaterialTheme.typography.bodySmall)
                        val color = when {
                            !r.enabled -> MaterialTheme.colorScheme.outline
                            r.status == RecipeStatus.ACTIVE -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.error
                        }
                        Row {
                            Text(
                                stringResource(R.string.list_row, statusText(r.status, r.enabled), formatTime(r.lastRunAt), r.itemCount),
                                color = color,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
}
