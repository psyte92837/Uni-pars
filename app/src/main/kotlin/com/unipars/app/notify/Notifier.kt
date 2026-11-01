package com.unipars.app.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
        nm.createNotificationChannel(NotificationChannel(CH_RESULTS, context.getString(R.string.channel_results), NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CH_ACTION, context.getString(R.string.channel_action), NotificationManager.IMPORTANCE_HIGH).apply {
            description = context.getString(R.string.channel_action_desc)
        })
        nm.createNotificationChannel(NotificationChannel(CH_WORK, context.getString(R.string.channel_work), NotificationManager.IMPORTANCE_MIN))
        nm.createNotificationChannel(NotificationChannel(CH_APP, context.getString(R.string.channel_app), NotificationManager.IMPORTANCE_LOW))
    }

    fun canPost(): Boolean = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun events(recipeId: String, recipeName: String, events: List<EventEntity>) {
        if (events.isEmpty()) return
        val lines = events.map(::describe)
        val title = if (events.size == 1) recipeName else context.getString(R.string.notif_changes_title, recipeName, events.size)
        val style = NotificationCompat.InboxStyle().also { s -> lines.take(7).forEach { s.addLine(it) } }
        if (lines.size > 7) style.setSummaryText(context.getString(R.string.notif_more, lines.size - 7))
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
        val text = context.getString(R.string.notif_check_text, reason)
        val n = NotificationCompat.Builder(context, CH_ACTION)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(context.getString(R.string.notif_check_title, domain))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openIntent("check/$recipeId", ("check" + domain).hashCode()))
            .build()
        post(("check" + domain).hashCode(), n)
    }

    fun broken(recipeId: String, recipeName: String, problem: String) {
        val n = NotificationCompat.Builder(context, CH_ACTION)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(context.getString(R.string.notif_broken_title, recipeName))
            .setContentText(context.getString(R.string.notif_broken_short))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.notif_broken_long, problem)))
            .setAutoCancel(true)
            .setContentIntent(openIntent("recipe/$recipeId", ("broken" + recipeId).hashCode()))
            .build()
        post(("broken" + recipeId).hashCode(), n)
    }

    fun delayed(count: Int) {
        val n = NotificationCompat.Builder(context, CH_APP)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(context.getString(R.string.notif_delayed_title))
            .setContentText(context.getString(R.string.notif_delayed_text, count))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.notif_delayed_text, count)))
            .setAutoCancel(true)
            .setContentIntent(openIntent("settings", ID_DELAYED))
            .build()
        post(ID_DELAYED, n)
    }

    fun update(version: String, url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val n = NotificationCompat.Builder(context, CH_APP)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(context.getString(R.string.notif_update_title, version))
            .setContentText(context.getString(R.string.notif_update_text))
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(context, ID_UPDATE, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .build()
        post(ID_UPDATE, n)
    }

    fun cancelCheck(domain: String) = NotificationManagerCompat.from(context).cancel(("check" + domain).hashCode())

    private fun describe(e: EventEntity): String = when (e.type) {
        "NEW_ITEM" -> context.getString(R.string.ev_new, e.label)
        "ITEM_REMOVED" -> context.getString(R.string.ev_removed, e.label)
        "FIELD_CHANGED" -> context.getString(R.string.ev_changed, e.label, e.field.orEmpty(), e.oldValue ?: "—", e.newValue ?: "—")
        "BELOW" -> context.getString(R.string.ev_below, e.label, e.field.orEmpty(), e.newValue.orEmpty())
        "ABOVE" -> context.getString(R.string.ev_above, e.label, e.field.orEmpty(), e.newValue.orEmpty())
        "CONTAINS" -> context.getString(R.string.ev_contains, e.label)
        else -> e.label
    }

    private fun openIntent(route: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_ROUTE, route)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun post(id: Int, n: android.app.Notification) {
        if (!canPost()) return
        try {
            NotificationManagerCompat.from(context).notify(id, n)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the call.
        }
    }

    companion object {
        const val CH_RESULTS = "results"
        const val CH_ACTION = "action"
        const val CH_WORK = "work"
        const val CH_APP = "app"
        private const val ID_DELAYED = 7101
        private const val ID_UPDATE = 7102
    }
}
