package com.unipars.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.unipars.ai.ClaudeClient
import com.unipars.app.UniParsApp
import com.unipars.app.data.RecipeStatus

class MainActivity : ComponentActivity() {

    private var pendingRoute by mutableStateOf<String?>(null)

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingRoute = intent?.getStringExtra(EXTRA_ROUTE)
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent {
            AppTheme {
                val nav = rememberNavController()
                LaunchedEffect(pendingRoute) {
                    pendingRoute?.let { nav.navigate(it); pendingRoute = null }
                }
                AppNavHost(nav)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_ROUTE)?.let { pendingRoute = it }
    }

    companion object {
        const val EXTRA_ROUTE = "route"
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun AppNavHost(nav: NavHostController) {
    NavHost(nav, startDestination = "list") {
        composable("list") {
            RecipeListScreen(
                onOpen = { nav.navigate("recipe/$it") },
                onNew = { nav.navigate("new") },
                onSettings = { nav.navigate("settings") },
            )
        }
        composable("new") { NewRecipeScreen(onDone = { nav.popBackStack() }) }
        composable("settings") { SettingsScreen(onBack = { nav.popBackStack() }) }
        composable("recipe/{id}") {
            RecipeDetailScreen(onBack = { nav.popBackStack() }, onCheck = { nav.navigate("check/$it") })
        }
        composable("check/{id}") { entry ->
            CheckScreen(recipeId = entry.arguments?.getString("id").orEmpty(), onDone = { nav.popBackStack() })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeListScreen(onOpen: (String) -> Unit, onNew: () -> Unit, onSettings: () -> Unit) {
    val app = LocalContext.current.applicationContext as UniParsApp
    val recipes by remember { app.db.recipes().observeAll() }.collectAsState(initial = emptyList())
    val hasKey = remember { app.prefs.apiKey.isNotBlank() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Uni-pars") },
                actions = { IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = "Настройки") } },
            )
        },
        floatingActionButton = { FloatingActionButton(onClick = onNew) { Icon(Icons.Default.Add, contentDescription = "Новый рецепт") } },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize().padding(horizontal = 12.dp)) {
            if (!hasKey) item {
                WarningCard("Добавьте API-ключ в настройках: он нужен только для настройки новых рецептов.")
            }
            if (recipes.isEmpty()) item {
                Text(
                    "Пока нет рецептов. Нажмите «+», откройте страницу и покажите, что отслеживать — ИИ один раз составит рецепт, дальше сбор идёт в фоне без токенов.",
                    modifier = Modifier.padding(16.dp),
                )
            }
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
                        Text(
                            "${statusText(r.status, r.enabled)} · ${formatTime(r.lastRunAt)} · записей: ${r.itemCount}",
                            color = color,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

@SuppressLint("BatteryLife")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as UniParsApp
    var key by remember { mutableStateOf(app.prefs.apiKey) }
    var model by remember { mutableStateOf(app.prefs.model) }
    var effort by remember { mutableStateOf(app.prefs.effort) }
    var research by remember { mutableStateOf(app.prefs.webResearch) }
    val pm = ctx.getSystemService(PowerManager::class.java)
    val ignoringBattery = remember { pm.isIgnoringBatteryOptimizations(ctx.packageName) }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Настройки") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад") } },
        )
    }) { padding ->
        Column(Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle("ИИ (Claude, ваш ключ)")
            OutlinedTextField(
                value = key, onValueChange = { key = it; app.prefs.apiKey = it },
                label = { Text("Anthropic API key") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Ключ хранится зашифрованным на устройстве и используется только при настройке и по кнопке «Починить с ИИ».", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = model, onValueChange = { model = it; app.prefs.model = it },
                label = { Text("Модель (по умолчанию ${ClaudeClient.DEFAULT_MODEL})") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Глубина анализа", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("low" to "Быстро", "medium" to "Обычно", "high" to "Тщательно").forEach { (v, label) ->
                    FilterChip(selected = effort == v, onClick = { effort = v; app.prefs.effort = v }, label = { Text(label) })
                }
            }
            SwitchRow("Искать сведения о сайте в интернете", research) { research = it; app.prefs.webResearch = it }
            Text("Потрачено токенов: ${app.prefs.spentInputTokens} входных / ${app.prefs.spentOutputTokens} выходных", style = MaterialTheme.typography.bodySmall)

            SectionTitle("Фоновая работа")
            Text(
                if (ignoringBattery) "Оптимизация батареи для приложения отключена — фоновые проверки не будут откладываться."
                else "Android и оболочки (MIUI, EMUI, One UI) могут откладывать фоновые проверки. Разрешите работу без ограничений батареи.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (!ignoringBattery) {
                Button(onClick = {
                    ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
                }) { Text("Разрешить работу в фоне") }
            }
            OutlinedButton(onClick = {
                ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
            }) { Text("Настройки приложения (автозапуск, уведомления)") }
            Text("Для Xiaomi, Huawei, Samsung и других оболочек: включите автозапуск и снимите ограничения фоновой активности. Инструкции: dontkillmyapp.com", style = MaterialTheme.typography.bodySmall)
        }
    }
}
