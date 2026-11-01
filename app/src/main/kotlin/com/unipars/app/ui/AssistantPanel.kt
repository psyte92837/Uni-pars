package com.unipars.app.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.unipars.ai.ChatMessage
import com.unipars.ai.Role
import com.unipars.ai.SiteAssistant
import com.unipars.ai.SiteSuggestion
import com.unipars.app.R
import com.unipars.app.UniParsApp
import com.unipars.app.setup.SetupEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AssistantMode { EXPANDED, COLLAPSED, HIDDEN }

data class AssistantEntry(val fromUser: Boolean, val text: String, val suggestions: List<SiteSuggestion> = emptyList())

data class AssistantState(
    val mode: AssistantMode = AssistantMode.EXPANDED,
    val entries: List<AssistantEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

class AssistantViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as UniParsApp
    private val engine = SetupEngine(application)
    val state = MutableStateFlow(AssistantState(mode = runCatching { AssistantMode.valueOf(app.prefs.assistantMode) }.getOrDefault(AssistantMode.EXPANDED)))

    fun setMode(mode: AssistantMode) {
        app.prefs.assistantMode = mode.name
        state.update { it.copy(mode = mode) }
    }

    fun send(text: String) {
        val q = text.trim()
        if (q.isEmpty() || state.value.loading) return
        val history = state.value.entries.map { ChatMessage(if (it.fromUser) Role.USER else Role.ASSISTANT, it.text) }
        state.update { it.copy(entries = it.entries + AssistantEntry(true, q), loading = true, error = null) }
        viewModelScope.launch {
            try {
                val reply = withContext(Dispatchers.IO) {
                    SiteAssistant(engine.client()).reply(history, q).also { engine.addUsage(it.usage) }
                }
                val text = reply.text.ifBlank { app.getString(if (reply.suggestions.isEmpty()) R.string.assistant_nothing else R.string.assistant_found) }
                state.update { it.copy(entries = it.entries + AssistantEntry(false, text, reply.suggestions), loading = false) }
            } catch (e: Exception) {
                state.update { it.copy(loading = false, error = e.message ?: e.toString()) }
            }
        }
    }

    fun clear() = state.update { it.copy(entries = emptyList(), error = null) }
}

/**
 * Home-screen AI helper. Can be collapsed to a single line or hidden (re-opened from the top bar).
 * [onSetup] opens the usual setup wizard pre-filled with a URL and a monitoring goal.
 */
@Composable
fun AssistantPanel(onSetup: (url: String, goal: String) -> Unit, vm: AssistantViewModel = viewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    if (s.mode == AssistantMode.HIDDEN) return
    val ctx = LocalContext.current
    var input by remember { mutableStateOf("") }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).animateContentSize(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable {
                    vm.setMode(if (s.mode == AssistantMode.EXPANDED) AssistantMode.COLLAPSED else AssistantMode.EXPANDED)
                },
            ) {
                Text(
                    stringResource(R.string.assistant_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                if (s.loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                IconButton(onClick = { vm.setMode(if (s.mode == AssistantMode.EXPANDED) AssistantMode.COLLAPSED else AssistantMode.EXPANDED) }) {
                    Icon(
                        if (s.mode == AssistantMode.EXPANDED) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = stringResource(if (s.mode == AssistantMode.EXPANDED) R.string.assistant_collapse else R.string.assistant_expand),
                    )
                }
                IconButton(onClick = { vm.setMode(AssistantMode.HIDDEN) }) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.assistant_close))
                }
            }
            if (s.mode != AssistantMode.EXPANDED) return@Column

            if (s.entries.isEmpty()) {
                Text(stringResource(R.string.assistant_intro), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                    listOf(R.string.assistant_example_1, R.string.assistant_example_2).forEach { id ->
                        val example = stringResource(id)
                        AssistChip(onClick = { vm.send(example) }, label = { Text(example, maxLines = 1) })
                    }
                }
            }
            s.entries.takeLast(MAX_VISIBLE).forEach { e ->
                Text(
                    e.text,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (e.fromUser) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.padding(top = 6.dp),
                )
                e.suggestions.forEach { sug -> SuggestionCard(sug, onSetup = { onSetup(sug.url, sug.goal) }, onOpen = {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(sug.url)))
                }) }
            }
            s.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text(stringResource(R.string.assistant_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { vm.send(input); input = "" }),
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { vm.send(input); input = "" }, enabled = input.isNotBlank() && !s.loading) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.assistant_send))
                }
            }
            if (s.entries.isNotEmpty()) {
                TextButton(onClick = vm::clear) { Text(stringResource(R.string.assistant_clear)) }
            }
        }
    }
}

@Composable
private fun SuggestionCard(s: SiteSuggestion, onSetup: () -> Unit, onOpen: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Column(Modifier.padding(10.dp)) {
            Text(s.title, style = MaterialTheme.typography.titleSmall)
            Text(s.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, maxLines = 1)
            if (s.reason.isNotBlank()) Text(s.reason, style = MaterialTheme.typography.bodySmall)
            if (s.goal.isNotBlank()) Text(stringResource(R.string.assistant_goal, s.goal), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                Button(onClick = onSetup) { Text(stringResource(R.string.assistant_setup)) }
                TextButton(onClick = onOpen) { Text(stringResource(R.string.assistant_open)) }
            }
        }
    }
}

private const val MAX_VISIBLE = 6
