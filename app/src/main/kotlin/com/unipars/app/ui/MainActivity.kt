package com.unipars.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unipars.app.UniParsApp
import com.unipars.app.ui.theme.UniParsTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
        // Gradient headers are dark enough for light status-bar icons in both themes.
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        if (savedInstanceState == null) pendingRoute = routeFrom(intent)
        val app = application as UniParsApp
        setContent {
            val theme by app.theme.collectAsStateWithLifecycle()
            UniParsTheme(theme) {
                val nav = rememberNavController()
                LaunchedEffect(pendingRoute) {
                    pendingRoute?.let { nav.navigate(it); pendingRoute = null }
                }
                AppNavHost(nav, startDestination = if (app.prefs.onboardingDone) "list" else "onboarding")
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
fun AppNavHost(nav: NavHostController, startDestination: String = "list") {
    NavHost(nav, startDestination = startDestination) {
        composable("onboarding") {
            OnboardingScreen(onFinish = {
                // First run: replace onboarding with the list; re-run from Settings: just go back.
                if (nav.previousBackStackEntry != null) nav.popBackStack()
                else nav.navigate("list") { popUpTo("onboarding") { inclusive = true } }
            })
        }
        composable("list") {
            RecipeListScreen(
                onOpen = { nav.navigate("recipe/$it") },
                onNew = { nav.navigate("new") },
                onSetup = { url, goal -> nav.navigate("new?url=" + Uri.encode(url) + "&goal=" + Uri.encode(goal)) },
                onSettings = { nav.navigate("settings") },
            )
        }
        composable(
            "new?url={url}&goal={goal}",
            arguments = listOf(
                navArgument("url") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("goal") { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
        ) {
            NewRecipeScreen(onDone = { nav.popBackStack() })
        }
        composable("settings") { SettingsScreen(onBack = { nav.popBackStack() }, onRerunSetup = { nav.navigate("onboarding") }) }
        composable("recipe/{id}") {
            RecipeDetailScreen(onBack = { nav.popBackStack() }, onCheck = { nav.navigate("check/$it") })
        }
        composable("check/{id}") { entry ->
            CheckScreen(recipeId = entry.arguments?.getString("id").orEmpty(), onDone = { nav.popBackStack() })
        }
    }
}

fun formatTime(ts: Long?): String = ts?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)) } ?: "—"
