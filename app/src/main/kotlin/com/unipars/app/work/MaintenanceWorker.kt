package com.unipars.app.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.unipars.app.BuildConfig
import com.unipars.app.UniParsApp
import com.unipars.app.data.recipe
import com.unipars.app.net.HttpFetcher
import com.unipars.app.update.UpdateChecker
import com.unipars.app.widget.StatusWidget
import com.unipars.engine.FetchSettings
import com.unipars.engine.Robots
import com.unipars.engine.Schedule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Daily housekeeping: prunes old data, re-reads robots.txt, warns when the system delays checks,
 * and looks for app updates.
 */
class MaintenanceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val app get() = applicationContext as UniParsApp

    override suspend fun doWork(): Result {
        val now = System.currentTimeMillis()
        val cutoff = now - RETENTION_DAYS * DAY
        app.db.items().deleteInactiveBefore(cutoff)
        app.db.events().deleteBefore(cutoff)

        recheckRobots(now)
        warnAboutDelays(now)
        if (now - app.prefs.lastUpdateCheck > 3 * DAY) {
            app.prefs.lastUpdateCheck = now
            withContext(Dispatchers.IO) { runCatching { UpdateChecker(applicationContext).latestNewer(BuildConfig.VERSION_NAME) } }
                .getOrNull()?.let { release ->
                    if (app.prefs.notifiedUpdate != release.version) {
                        app.prefs.notifiedUpdate = release.version
                        app.notifier.update(release.version, release.url)
                    }
                }
        }
        StatusWidget.updateAll(applicationContext)
        return Result.success()
    }

    private suspend fun recheckRobots(now: Long) {
        val http = HttpFetcher(applicationContext)
        val cache = mutableMapOf<String, Robots?>()
        val settings = FetchSettings(connectTimeoutSec = 15, readTimeoutSec = 20, retries = 0)
        for (e in app.db.recipes().all()) {
            if (e.robotsCheckedAt != null && now - e.robotsCheckedAt < ROBOTS_EVERY_DAYS * DAY) continue
            val url = runCatching { e.recipe().source.url }.getOrNull() ?: continue
            val uri = runCatching { java.net.URI(url) }.getOrNull() ?: continue
            val origin = "${uri.scheme}://${uri.authority}"
            val robots = cache.getOrPut(origin) {
                withContext(Dispatchers.IO) {
                    runCatching { http.fetch("$origin/robots.txt", settings).takeIf { it.status == 200 }?.body?.let(Robots::parse) }.getOrNull()
                }
            }
            val path = (uri.rawPath ?: "/").ifEmpty { "/" } + (uri.rawQuery?.let { "?$it" } ?: "")
            app.db.recipes().upsert(e.copy(robotsAllowed = robots?.isAllowed("unipars", path) ?: true, robotsCheckedAt = now))
        }
    }

    /** Standby buckets and vendor battery savers can defer work for hours; tell the user once a day. */
    private suspend fun warnAboutDelays(now: Long) {
        val late = app.db.recipes().all().filter { e ->
            if (!e.enabled || e.lastRunAt == null) return@filter false
            val interval = runCatching { Schedule.effectiveIntervalMinutes(e.recipe().settings) }.getOrDefault(60)
            now - e.lastRunAt > maxOf(3L * interval * 60_000, 6 * 3_600_000L)
        }
        if (late.isNotEmpty()) app.notifier.delayed(late.size)
    }

    companion object {
        private const val DAY = 24 * 3_600_000L
        private const val RETENTION_DAYS = 90L
        private const val ROBOTS_EVERY_DAYS = 3L

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<MaintenanceWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("maintenance", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
