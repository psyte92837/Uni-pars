package com.unipars.app.ui

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unipars.ai.AiProvider
import com.unipars.app.R
import com.unipars.app.UniParsApp
import com.unipars.app.setup.SetupEngine
import com.unipars.app.ui.theme.Palette
import com.unipars.app.ui.theme.ThemeMode
import com.unipars.app.ui.theme.isDark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Provider choice, key with "get key" link, paste and test. Used by onboarding and settings. */
@Composable
fun AiSetupSection(showModel: Boolean = false) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as UniParsApp
    val scope = rememberCoroutineScope()
    var provider by remember { mutableStateOf(app.prefs.provider) }
    var key by remember(provider) { mutableStateOf(app.prefs.apiKey(provider)) }
    var model by remember(provider) { mutableStateOf(app.prefs.model(provider)) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember(provider) { mutableStateOf<Pair<Boolean, String>?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AiProvider.entries.forEach { p ->
            val selected = p == provider
            OutlinedCard(
                onClick = { provider = p; app.prefs.provider = p },
                border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            stringResource(if (p == AiProvider.GEMINI) R.string.provider_gemini_desc else R.string.provider_claude_desc),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (selected) Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(provider.keyUrl))) }) {
                Text(stringResource(R.string.key_get, provider.keyHint))
            }
            OutlinedButton(onClick = {
                val clip = ctx.getSystemService(ClipboardManager::class.java)?.primaryClip
                val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()?.trim()
                if (!text.isNullOrEmpty()) { key = text; app.prefs.setApiKey(provider, text); testResult = null }
            }) { Text(stringResource(R.string.key_paste)) }
        }
        Text(stringResource(R.string.key_link_hint, provider.keyUrl), style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = key,
            onValueChange = { key = it; app.prefs.setApiKey(provider, it); testResult = null },
            label = { Text(stringResource(R.string.settings_key, provider.title)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        if (showModel) {
            OutlinedTextField(
                value = model, onValueChange = { model = it; app.prefs.setModel(provider, it) },
                label = { Text(stringResource(R.string.settings_model, provider.defaultModel)) }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    testing = true
                    scope.launch {
                        val error = withContext(Dispatchers.IO) { SetupEngine(app).testKey(provider, key, app.prefs.model(provider)) }
                        testResult = if (error == null) true to ctx.getString(R.string.key_ok) else false to error
                        testing = false
                    }
                },
                enabled = key.isNotBlank() && !testing,
            ) { Text(stringResource(if (testing) R.string.key_testing else R.string.key_test)) }
            testResult?.let { (ok, msg) ->
                Text(msg, color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
        Text(stringResource(R.string.settings_key_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Light/dark/system, gradient palette and Material You colors; applies instantly. */
@Composable
fun AppearanceSection() {
    val app = LocalContext.current.applicationContext as UniParsApp
    val theme by app.theme.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            ThemeMode.entries.forEachIndexed { i, mode ->
                SegmentedButton(
                    selected = theme.mode == mode,
                    onClick = { app.updateTheme(theme.copy(mode = mode)) },
                    shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size),
                ) { Text(stringResource(mode.title)) }
            }
        }
        val dark = isDark(theme)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
            Palette.entries.forEach { p ->
                val name = stringResource(p.title)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(p.gradient(dark)))
                            .border(if (theme.palette == p && !theme.dynamicColor) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                            .clickable { app.updateTheme(theme.copy(palette = p, dynamicColor = false)) }
                            .semantics { contentDescription = name },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (theme.palette == p && !theme.dynamicColor) Icon(Icons.Default.Check, contentDescription = null, tint = Color.White)
                    }
                    Text(name, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 31) {
            SwitchRow(stringResource(R.string.theme_dynamic), theme.dynamicColor) { app.updateTheme(theme.copy(dynamicColor = it)) }
        }
    }
}

/**
 * Notification kinds + system permission. With [requestImmediately] the permission dialog pops up
 * as soon as the section is shown (onboarding step).
 */
@Composable
fun NotificationsSection(requestImmediately: Boolean = false) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as UniParsApp
    var changes by remember { mutableStateOf(app.prefs.notifyChanges) }
    var actions by remember { mutableStateOf(app.prefs.notifyActions) }
    var appNews by remember { mutableStateOf(app.prefs.notifyApp) }
    var granted by remember { mutableStateOf(app.notifier.canPost()) }
    val request = rememberNotificationPermissionRequest(force = true) { granted = it }
    LaunchedEffect(requestImmediately) { if (requestImmediately && !granted) request() }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SwitchRow(stringResource(R.string.notify_changes), changes) { changes = it; app.prefs.notifyChanges = it }
        SwitchRow(stringResource(R.string.notify_actions), actions) { actions = it; app.prefs.notifyActions = it }
        SwitchRow(stringResource(R.string.notify_app), appNews) { appNews = it; app.prefs.notifyApp = it }
        if (granted) {
            Text(stringResource(R.string.notify_granted), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
        } else {
            Text(stringResource(R.string.notify_denied), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = request) { Text(stringResource(R.string.settings_notifications)) }
                OutlinedButton(onClick = {
                    ctx.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName),
                    )
                }) { Text(stringResource(R.string.notify_system)) }
            }
        }
    }
}

/** Battery restrictions and defaults for new recipes. */
@SuppressLint("BatteryLife")
@Composable
fun BackgroundSection() {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as UniParsApp
    var ignoring by remember { mutableStateOf(isIgnoringBattery(ctx)) }
    var interval by remember { mutableStateOf(app.prefs.defaultIntervalMinutes) }
    var wifi by remember { mutableStateOf(app.prefs.defaultWifiOnly) }
    var research by remember { mutableStateOf(app.prefs.webResearch) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(if (ignoring) R.string.settings_battery_ok else R.string.settings_battery_ask),
            style = MaterialTheme.typography.bodySmall,
        )
        if (!ignoring) {
            Button(onClick = {
                ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
                ignoring = isIgnoringBattery(ctx)
            }) { Text(stringResource(R.string.settings_battery_button)) }
        }
        Text(stringResource(R.string.default_interval), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
        val options = listOf(0, 30, 60, 180, 720)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, v ->
                SegmentedButton(
                    selected = interval == v,
                    onClick = { interval = v; app.prefs.defaultIntervalMinutes = v },
                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                ) {
                    Text(
                        when {
                            v == 0 -> stringResource(R.string.default_interval_ai)
                            v < 60 -> stringResource(R.string.unit_minutes, v)
                            else -> stringResource(R.string.unit_hours, v / 60)
                        },
                        maxLines = 1,
                    )
                }
            }
        }
        SwitchRow(stringResource(R.string.set_wifi), wifi) { wifi = it; app.prefs.defaultWifiOnly = it }
        SwitchRow(stringResource(R.string.settings_research), research) { research = it; app.prefs.webResearch = it }
    }
}

private fun isIgnoringBattery(ctx: Context): Boolean =
    ctx.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(ctx.packageName) == true
