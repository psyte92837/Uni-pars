package com.unipars.app.ui

import android.annotation.SuppressLint
import android.app.usage.UsageStatsManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.unipars.ai.AiProvider
import com.unipars.app.BuildConfig
import com.unipars.app.R
import com.unipars.app.UniParsApp
import com.unipars.app.update.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@SuppressLint("BatteryLife")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as UniParsApp
    val scope = rememberCoroutineScope()
    var provider by remember { mutableStateOf(app.prefs.provider) }
    var key by remember(provider) { mutableStateOf(app.prefs.apiKey(provider)) }
    var model by remember(provider) { mutableStateOf(app.prefs.model(provider)) }
    var effort by remember { mutableStateOf(app.prefs.effort) }
    var research by remember { mutableStateOf(app.prefs.webResearch) }
    var limit by remember { mutableStateOf(app.prefs.monthlyLimitUsd.takeIf { it > 0 }?.toString().orEmpty()) }
    val pm = ctx.getSystemService(PowerManager::class.java)
    val ignoringBattery = remember { pm.isIgnoringBatteryOptimizations(ctx.packageName) }
    val bucket = remember { standbyBucketName(ctx) }
    val crash = remember { app.crashFile.takeIf { it.exists() }?.readText() }
    val requestNotifications = rememberNotificationPermissionRequest()

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.settings_title)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) } },
        )
    }) { padding ->
        Column(Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle(stringResource(R.string.settings_ai))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AiProvider.entries.forEach { p ->
                    FilterChip(selected = provider == p, onClick = { provider = p; app.prefs.provider = p }, label = { Text(p.title) })
                }
            }
            OutlinedTextField(
                value = key, onValueChange = { key = it; app.prefs.setApiKey(provider, it) },
                label = { Text(stringResource(R.string.settings_key, provider.title)) },
                supportingText = { Text(provider.keyHint) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(R.string.settings_key_note), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = model, onValueChange = { model = it; app.prefs.setModel(provider, it) },
                label = { Text(stringResource(R.string.settings_model, provider.defaultModel)) }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (provider == AiProvider.CLAUDE) {
                Text(stringResource(R.string.settings_effort), style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("low" to R.string.effort_low, "medium" to R.string.effort_medium, "high" to R.string.effort_high).forEach { (v, label) ->
                        FilterChip(selected = effort == v, onClick = { effort = v; app.prefs.effort = v }, label = { Text(stringResource(label)) })
                    }
                }
            }
            SwitchRow(stringResource(R.string.settings_research), research) { research = it; app.prefs.webResearch = it }
            OutlinedTextField(
                value = limit,
                onValueChange = { v ->
                    limit = v.filter { it.isDigit() || it == '.' }.take(8)
                    app.prefs.monthlyLimitUsd = limit.toDoubleOrNull() ?: 0.0
                },
                label = { Text(stringResource(R.string.settings_limit)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(
                    R.string.settings_spent,
                    "%.4f".format(app.prefs.spentThisMonthUsd), "%.4f".format(app.prefs.spentTotalUsd),
                    app.prefs.spentInputTokens, app.prefs.spentOutputTokens,
                ),
                style = MaterialTheme.typography.bodySmall,
            )

            SectionTitle(stringResource(R.string.settings_background))
            Text(
                stringResource(if (ignoringBattery) R.string.settings_battery_ok else R.string.settings_battery_ask),
                style = MaterialTheme.typography.bodySmall,
            )
            bucket?.let { Text(stringResource(R.string.settings_bucket, it), style = MaterialTheme.typography.bodySmall) }
            if (!ignoringBattery) {
                Button(onClick = {
                    ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
                }) { Text(stringResource(R.string.settings_battery_button)) }
            }
            if (!app.notifier.canPost()) {
                OutlinedButton(onClick = {
                    app.prefs.notificationsAsked = false
                    requestNotifications()
                }) { Text(stringResource(R.string.settings_notifications)) }
            }
            OutlinedButton(onClick = {
                ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
            }) { Text(stringResource(R.string.settings_app_details)) }
            Text(stringResource(R.string.settings_vendor_note), style = MaterialTheme.typography.bodySmall)

            SectionTitle(stringResource(R.string.settings_about))
            Text(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = {
                scope.launch {
                    val release = withContext(Dispatchers.IO) { runCatching { UpdateChecker(ctx).latestNewer(BuildConfig.VERSION_NAME) } }
                    release.fold(
                        onSuccess = { r ->
                            if (r == null) Toast.makeText(ctx, R.string.update_none, Toast.LENGTH_SHORT).show()
                            else ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.url)))
                        },
                        onFailure = { Toast.makeText(ctx, R.string.update_failed, Toast.LENGTH_SHORT).show() },
                    )
                }
            }) { Text(stringResource(R.string.update_check)) }
            if (crash != null) {
                OutlinedButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, crash)
                    ctx.startActivity(Intent.createChooser(send, null))
                }) { Text(stringResource(R.string.settings_share_crash)) }
            }
        }
    }
}

/** App Standby bucket: in "rare"/"restricted" the system defers background work for hours. */
private fun standbyBucketName(ctx: android.content.Context): String? {
    if (Build.VERSION.SDK_INT < 28) return null
    val usm = ctx.getSystemService(UsageStatsManager::class.java) ?: return null
    return when (usm.appStandbyBucket) {
        UsageStatsManager.STANDBY_BUCKET_ACTIVE -> "active"
        UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> "working set"
        UsageStatsManager.STANDBY_BUCKET_FREQUENT -> "frequent"
        UsageStatsManager.STANDBY_BUCKET_RARE -> "rare"
        45 -> "restricted"
        else -> usm.appStandbyBucket.toString()
    }
}
