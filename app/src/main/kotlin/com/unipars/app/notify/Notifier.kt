package com.unipars.app.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.unipars.app.R
import com.unipars.app.data.EventEntity
import com.unipars.app.ui.MainActivity

class Notifier(private val context: Context) {

    fun createChannels() {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_RESULTS, "Изменения на сайтах", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CH_ACTION, "Требуется действие", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Капча, проверка браузера, сломанный рецепт"
        })
    }

    fun events(recipeId: String, recipeName: String, events: List<EventEntity>) {
        if (events.isEmpty()) return
        val lines = events.map(::describe)
        val title = if (events.size == 1) recipeName else "$recipeName: ${events.size} изменений"
        val style = NotificationCompat.InboxStyle().also { s -> lines.take(7).forEach { s.addLine(it) } }
        if (lines.size > 7) style.setSummaryText("и ещё ${lines.size - 7}")
        val n = NotificationCompat.Builder(context, CH_RESULTS)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(lines.first())
            .setStyle(style)
            .setAutoCancel(true)
            .setGroup("results")
            .setContentIntent(openIntent("recipe/$recipeId", recipeId.hashCode()))
            .build()
        post(recipeId.hashCode(), n)
    }

    /** One notification per domain, so several recipes on the same site do not spam. */
    fun needsHuman(recipeId: String, domain: String, reason: String) {
        val n = NotificationCompat.Builder(context, CH_ACTION)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Нужна проверка на $domain")
            .setContentText("Сайт просит пройти проверку ($reason). Нажмите, чтобы открыть её; сбор продолжится автоматически.")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Сайт просит пройти проверку ($reason). Нажмите, чтобы открыть её; сбор продолжится автоматически."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openIntent("check/$recipeId", ("check" + domain).hashCode()))
            .build()
        post(("check" + domain).hashCode(), n)
    }

    fun broken(recipeId: String, recipeName: String, problem: String) {
        val n = NotificationCompat.Builder(context, CH_ACTION)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("«$recipeName» перестал работать")
            .setContentText("Похоже, сайт изменился. Откройте, чтобы починить с ИИ.")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Похоже, сайт изменился: $problem\nОткройте, чтобы починить рецепт с ИИ (тратит токены только по вашему нажатию)."))
            .setAutoCancel(true)
            .setContentIntent(openIntent("recipe/$recipeId", ("broken" + recipeId).hashCode()))
            .build()
        post(("broken" + recipeId).hashCode(), n)
    }

    fun cancelCheck(domain: String) = NotificationManagerCompat.from(context).cancel(("check" + domain).hashCode())

    private fun describe(e: EventEntity): String = when (e.type) {
        "NEW_ITEM" -> "Новое: ${e.label}"
        "ITEM_REMOVED" -> "Пропало: ${e.label}"
        "FIELD_CHANGED" -> "${e.label}: ${e.field} ${e.oldValue ?: "—"} → ${e.newValue ?: "—"}"
        "BELOW" -> "${e.label}: ${e.field} ниже порога — ${e.newValue}"
        "ABOVE" -> "${e.label}: ${e.field} выше порога — ${e.newValue}"
        "CONTAINS" -> "Найдено: ${e.label}"
        else -> e.label
    }

    private fun openIntent(route: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_ROUTE, route)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun post(id: Int, n: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        NotificationManagerCompat.from(context).notify(id, n)
    }

    companion object {
        const val CH_RESULTS = "results"
        const val CH_ACTION = "action"
    }
}
