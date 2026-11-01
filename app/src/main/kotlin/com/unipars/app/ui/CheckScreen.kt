package com.unipars.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.unipars.app.UniParsApp
import com.unipars.app.data.RecipeEntity
import com.unipars.app.data.RecipeStatus
import com.unipars.app.data.recipe
import com.unipars.app.work.Scheduler
import com.unipars.engine.BlockDetector
import com.unipars.engine.BlockKind
import kotlinx.coroutines.launch

/**
 * Human-in-the-loop: the user passes a captcha / browser check (or logs in) in a WebView.
 * Cookies are shared with background HTTP requests, so collection resumes afterwards.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckScreen(recipeId: String, onDone: () -> Unit) {
    val app = LocalContext.current.applicationContext as UniParsApp
    val scope = rememberCoroutineScope()
    var entity by remember { mutableStateOf<RecipeEntity?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val controller = remember { PageController() }
    LaunchedEffect(recipeId) { entity = app.db.recipes().get(recipeId) }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Проверка: ${entity?.domain ?: ""}") },
            navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад") } },
        )
    }) { padding ->
        val e = entity ?: return@Scaffold
        val url = remember(e.json) { runCatching { e.recipe().source.url }.getOrDefault("https://${e.domain}") }
        Column(Modifier.padding(padding).fillMaxSize()) {
            Text(
                "Пройдите проверку или войдите на сайт, затем нажмите «Готово». Приложение не решает капчи само.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(12.dp),
            )
            message?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 12.dp)) }
            Button(
                onClick = {
                    controller.captureHtml { html, finalUrl ->
                        val verdict = BlockDetector.detect(200, emptyMap(), html.orEmpty(), finalUrl)
                        if (verdict.kind == BlockKind.NONE || verdict.kind == BlockKind.FORBIDDEN) {
                            scope.launch {
                                val fresh = app.db.recipes().get(recipeId) ?: return@launch
                                app.db.recipes().upsert(fresh.copy(status = RecipeStatus.ACTIVE, consecutiveFailures = 0, lastMessage = "Проверка пройдена"))
                                app.notifier.cancelCheck(fresh.domain)
                                Scheduler.runNow(app, recipeId)
                                onDone()
                            }
                        } else {
                            message = "Похоже, проверка ещё не пройдена (${verdict.vendor ?: verdict.kind.name.lowercase()})."
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            ) { Text("Готово") }
            PageWebView(url = url, controller = controller, instrument = false, modifier = Modifier.fillMaxSize().padding(top = 8.dp))
        }
    }
}
