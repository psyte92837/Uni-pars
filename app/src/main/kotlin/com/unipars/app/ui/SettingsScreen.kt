package com.unipars.app.ui

import android.app.usage.UsageStatsManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import com.unipars.ai.AiProvider
import com.unipars.app.BuildConfig
import com.unipars.app.R
import com.unipars.app.UniParsApp
import com.unipars.app.ui.theme.AppScaffold
import com.unipars.app.ui.theme.SectionCard
import com.unipars.app.update.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(onBack: () -> Unit, onRerunSetup: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as UniParsApp
    val scope = rememberCoroutineScope()
    var effort by remember { mutableStateOf(app.prefs.effort) }
    var limit by remember { mutableStateOf(app.prefs.monthlyLimitUsd.takeIf { it > 0 }?.toString().orEmpty()) }
    val bucket = remember { standbyBucketName(ctx) }
    val crash = remember { app.crashFile.takeIf { it.exists() }?.readText() }

    AppScaffold(
        title = stringResource(R.string.settings_title),
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) } },
    ) { padding ->
        Column(Modifier.padding(padding).padding(horizontal = 12.dp).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(6.dp))
            SectionCard(stringResource(R.string.settings_ai)) {
                AiSetupSection(showModel = true)
                if (app.prefs.provider == AiProvider.CLAUDE) {
                    Text(stringResource(R.string.settings_effort), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("low" to R.string.effort_low, "medium" to R.string.effort_medium, "high" to R.string.effort_high).forEach { (v, label) ->
                            FilterChip(selected = effort == v, onClick = { effort = v; app.prefs.effort = v }, label = { Text(stringResource(label)) })
                        }
                    }
                }
                OutlinedTextField(
                    value = limit,
                    onValueChange = { v ->
                        limit = v.filter { it.isDigit() || it == '.' }.take(8)
                        app.prefs.monthlyLimitUsd = limit.toDoubleOrNull() ?: 0.0
                    },
                    label = { Text(stringResource(R.string.settings_limit)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Text(
                    stringResource(
                        R.string.settings_spent,
                        "%.4f".format(app.prefs.spentThisMonthUsd), "%.4f".format(app.prefs.spentTotalUsd),
                        app.prefs.spentInputTokens, app.prefs.spentOutputTokens,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            SectionCard(stringResource(R.string.settings_appearance)) { AppearanceSection() }
            SectionCard(stringResource(R.string.settings_notifications_section)) { NotificationsSection() }
            SectionCard(stringResource(R.string.settings_background)) {
                BackgroundSection()
                bucket?.let { Text(stringResource(R.string.settings_bucket, it), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }
                OutlinedButton(onClick = {
                    ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
                }, modifier = Modifier.padding(top = 6.dp)) { Text(stringResource(R.string.settings_app_details)) }
                Text(stringResource(R.string.settings_vendor_note), style = MaterialTheme.typography.bodySmall)
            }
            SectionCard(stringResource(R.string.settings_about)) {
                Text(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
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
                    OutlinedButton(onClick = onRerunSetup) { Text(stringResource(R.string.settings_rerun_setup)) }
                }
                if (crash != null) {
                    OutlinedButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, crash)
                        ctx.startActivity(Intent.createChooser(send, null))
                    }) { Text(stringResource(R.string.settings_share_crash)) }
                }
            }
            Spacer(Modifier.height(24.dp))
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
