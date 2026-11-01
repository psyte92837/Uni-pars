package com.unipars.app.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.unipars.app.UniParsApp

/**
 * Returns a function that asks for POST_NOTIFICATIONS once, at a moment where the user understands
 * why (after creating a recipe), instead of at app start.
 */
@Composable
fun rememberNotificationPermissionRequest(): () -> Unit {
    val app = LocalContext.current.applicationContext as UniParsApp
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    return {
        if (Build.VERSION.SDK_INT >= 33 && !app.notifier.canPost() && !app.prefs.notificationsAsked) {
            app.prefs.notificationsAsked = true
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
