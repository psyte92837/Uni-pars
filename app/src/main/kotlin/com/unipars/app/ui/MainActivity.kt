package com.unipars.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {

    private var pendingRoute by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) pendingRoute = routeFrom(intent)
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
        routeFrom(intent)?.let { pendingRoute = it }
    }

    private fun routeFrom(intent: Intent?): String? {
        intent ?: return null
        intent.getStringExtra(EXTRA_ROUTE)?.let { return it }
        if (intent.action == Intent.ACTION_SEND) {
            val url = extractUrl(intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()) ?: return null
            return "new?url=" + Uri.encode(url)
        }
        return null
    }

    companion object {
        const val EXTRA_ROUTE = "route"

        /** Shared text often looks like "Title https://site/page"; take the first http(s) URL. */
        fun extractUrl(text: String): String? = Regex("https?://\\S+").find(text)?.value?.trimEnd('.', ',', ')', '»', '"')
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
        composable("new?url={url}", arguments = listOf(navArgument("url") { type = NavType.StringType; nullable = true; defaultValue = null })) {
            NewRecipeScreen(onDone = { nav.popBackStack() })
        }
        composable("settings") { SettingsScreen(onBack = { nav.popBackStack() }) }
        composable("recipe/{id}") {
            RecipeDetailScreen(onBack = { nav.popBackStack() }, onCheck = { nav.navigate("check/$it") })
        }
        composable("check/{id}") { entry ->
            CheckScreen(recipeId = entry.arguments?.getString("id").orEmpty(), onDone = { nav.popBackStack() })
        }
    }
}

fun formatTime(ts: Long?): String = ts?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)) } ?: "—"
