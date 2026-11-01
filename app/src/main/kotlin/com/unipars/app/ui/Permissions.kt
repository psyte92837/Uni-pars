package com.unipars.app.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.unipars.app.UniParsApp

/**
 * Returns a function that asks for POST_NOTIFICATIONS. Without [force] it asks only once
 * (used after saving a recipe); onboarding and settings pass [force] = true.
 */
@Composable
fun rememberNotificationPermissionRequest(force: Boolean = false, onResult: (Boolean) -> Unit = {}): () -> Unit {
    val app = LocalContext.current.applicationContext as UniParsApp
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onResult(it) }
    return {
        if (Build.VERSION.SDK_INT >= 33 && !app.notifier.canPost() && (force || !app.prefs.notificationsAsked)) {
            app.prefs.notificationsAsked = true
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            onResult(app.notifier.canPost())
        }
    }
}
