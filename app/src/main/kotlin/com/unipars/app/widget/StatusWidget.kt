package com.unipars.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.unipars.app.R
import com.unipars.app.UniParsApp
import com.unipars.app.data.RecipeStatus
import com.unipars.app.ui.MainActivity
import com.unipars.app.ui.formatTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Home-screen summary: how many recipes run and how many need attention. */
class StatusWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        scope.launch {
            try {
                render(context, manager, ids)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        suspend fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, StatusWidget::class.java))
            if (ids.isNotEmpty()) render(context, manager, ids)
        }

        private suspend fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val app = context.applicationContext as UniParsApp
            val recipes = app.db.recipes().all()
            val attention = recipes.count { it.enabled && it.status != RecipeStatus.ACTIVE }
            val last = recipes.mapNotNull { it.lastSuccessAt }.maxOrNull()
            val views = RemoteViews(context.packageName, R.layout.widget_status).apply {
                setTextViewText(R.id.widget_title, context.getString(R.string.app_name))
                setTextViewText(R.id.widget_counts, context.getString(R.string.widget_counts, recipes.count { it.enabled }, attention))
                setTextViewText(R.id.widget_last, context.getString(R.string.widget_last, formatTime(last)))
                val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE))
            }
            manager.updateAppWidget(ids, views)
        }
    }
}
