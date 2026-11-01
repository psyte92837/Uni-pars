package com.unipars.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.unipars.app.R
import com.unipars.engine.CaptchaPolicy
import com.unipars.engine.FetchSettings
import com.unipars.engine.Trigger
import com.unipars.engine.TriggerType

@Composable
fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp).semantics { heading() },
    )
}

@Composable
fun NumberField(label: String, value: Int, modifier: Modifier = Modifier, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { t ->
            text = t.filter { it.isDigit() }.take(6)
            text.toIntOrNull()?.let(onChange)
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.semantics { stateDescription = label })
    }
}

/** User-editable options; values arrive pre-filled with the AI/probe recommendations. */
@Composable
fun SettingsEditor(settings: FetchSettings, onChange: (FetchSettings) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(stringResource(R.string.set_interval), settings.intervalMinutes, Modifier.weight(1f)) {
                onChange(settings.copy(intervalMinutes = it))
            }
            NumberField(stringResource(R.string.set_jitter), settings.jitterPercent, Modifier.weight(1f)) {
                onChange(settings.copy(jitterPercent = it.coerceIn(0, 50)))
            }
        }
        if (settings.intervalMinutes < FetchSettings.MIN_INTERVAL_MINUTES) {
            Text(stringResource(R.string.set_interval_min), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(stringResource(R.string.set_connect), settings.connectTimeoutSec, Modifier.weight(1f)) { onChange(settings.copy(connectTimeoutSec = it.coerceIn(1, 300))) }
            NumberField(stringResource(R.string.set_read), settings.readTimeoutSec, Modifier.weight(1f)) { onChange(settings.copy(readTimeoutSec = it.coerceIn(1, 300))) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(stringResource(R.string.set_retries), settings.retries, Modifier.weight(1f)) { onChange(settings.copy(retries = it.coerceIn(0, 10))) }
            NumberField(stringResource(R.string.set_backoff), settings.backoffMinutes, Modifier.weight(1f)) { onChange(settings.copy(backoffMinutes = it.coerceIn(1, 1440))) }
        }
        NumberField(stringResource(R.string.set_daily_limit), settings.maxRequestsPerDay, Modifier.fillMaxWidth()) { onChange(settings.copy(maxRequestsPerDay = it)) }
        var quiet by remember(settings.quietHours) { mutableStateOf(settings.quietHours.orEmpty()) }
        OutlinedTextField(
            value = quiet,
            onValueChange = { quiet = it; onChange(settings.copy(quietHours = it.ifBlank { null })) },
            label = { Text(stringResource(R.string.set_quiet)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        var ua by remember(settings.userAgent) { mutableStateOf(settings.userAgent.orEmpty()) }
        OutlinedTextField(
            value = ua,
            onValueChange = { ua = it; onChange(settings.copy(userAgent = it.ifBlank { null })) },
            label = { Text(stringResource(R.string.set_ua)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        SwitchRow(stringResource(R.string.set_wifi), settings.wifiOnly) { onChange(settings.copy(wifiOnly = it)) }
        SwitchRow(stringResource(R.string.set_charging), settings.chargingOnly) { onChange(settings.copy(chargingOnly = it)) }
        SwitchRow(stringResource(R.string.set_robots), settings.respectRobots) { onChange(settings.copy(respectRobots = it)) }
        SwitchRow(stringResource(R.string.set_captcha), settings.captchaPolicy == CaptchaPolicy.ASK_USER) {
            onChange(settings.copy(captchaPolicy = if (it) CaptchaPolicy.ASK_USER else CaptchaPolicy.PAUSE))
        }
    }
}

@Composable
private fun triggerLabel(type: TriggerType): String = stringResource(
    when (type) {
        TriggerType.NEW_ITEM -> R.string.trg_new
        TriggerType.FIELD_CHANGED -> R.string.trg_changed
        TriggerType.ITEM_REMOVED -> R.string.trg_removed
        TriggerType.BELOW -> R.string.trg_below
        TriggerType.ABOVE -> R.string.trg_above
        TriggerType.CONTAINS -> R.string.trg_contains
    },
)

@Composable
private fun <T> Picker(label: String, options: List<T>, title: @Composable (T) -> String, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        OutlinedButton(onClick = { open = true }) { Text(label) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o -> DropdownMenuItem(text = { Text(title(o)) }, onClick = { open = false; onPick(o) }) }
        }
    }
}

/** When to notify. */
@Composable
fun TriggersEditor(triggers: List<Trigger>, fieldNames: List<String>, onChange: (List<Trigger>) -> Unit) {
    val anyField = stringResource(R.string.trg_any_field)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        triggers.forEachIndexed { index, t ->
            Column(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Picker(triggerLabel(t.type), TriggerType.entries.toList(), { triggerLabel(it) }) { type ->
                        onChange(triggers.toMutableList().also { it[index] = t.copy(type = type) })
                    }
                    Spacer(Modifier.width(8.dp))
                    if (t.type != TriggerType.NEW_ITEM && t.type != TriggerType.ITEM_REMOVED) {
                        Picker(t.field ?: anyField, listOf<String?>(null) + fieldNames, { it ?: anyField }) { f ->
                            onChange(triggers.toMutableList().also { it[index] = t.copy(field = f) })
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { onChange(triggers.toMutableList().also { it.removeAt(index) }) }) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_remove))
                    }
                }
                if (t.type == TriggerType.BELOW || t.type == TriggerType.ABOVE || t.type == TriggerType.CONTAINS) {
                    var v by remember(t.value) { mutableStateOf(t.value.orEmpty()) }
                    OutlinedTextField(
                        value = v,
                        onValueChange = { nv -> v = nv; onChange(triggers.toMutableList().also { it[index] = t.copy(value = nv) }) },
                        label = { Text(stringResource(if (t.type == TriggerType.CONTAINS) R.string.trg_text else R.string.trg_threshold)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = if (t.type == TriggerType.CONTAINS) KeyboardType.Text else KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        TextButton(onClick = { onChange(triggers + Trigger(TriggerType.NEW_ITEM)) }) {
            Icon(Icons.Default.Add, contentDescription = null)
            Text(stringResource(R.string.trg_add))
        }
    }
}
