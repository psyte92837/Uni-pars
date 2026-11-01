package com.unipars.app.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.unipars.engine.Recipe
import com.unipars.engine.Schedule
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** One unique periodic job per recipe; WorkManager survives reboots and respects Doze. */
object Scheduler {

    private fun periodicName(id: String) = "recipe-$id"
    private fun manualName(id: String) = "recipe-now-$id"

    fun schedule(context: Context, recipe: Recipe, crawlDelaySec: Int? = null, replace: Boolean = true) {
        val s = recipe.settings
        val interval = Schedule.effectiveIntervalMinutes(s, crawlDelaySec).toLong()
        // Flex window spreads runs over the end of the period (jitter), so requests are not clockwork.
        val flex = (interval * s.jitterPercent / 100).coerceIn(5, interval)
        val request = PeriodicWorkRequestBuilder<ParseWorker>(interval, TimeUnit.MINUTES, flex, TimeUnit.MINUTES)
            .setConstraints(constraints(recipe))
            .setInitialDelay(Random.nextLong(0, 2), TimeUnit.MINUTES)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, s.backoffMinutes.toLong().coerceAtLeast(10), TimeUnit.MINUTES)
            .setInputData(workDataOf(ParseWorker.KEY_ID to recipe.id))
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            periodicName(recipe.id),
            if (replace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun runNow(context: Context, recipeId: String) {
        val request = OneTimeWorkRequestBuilder<ParseWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf(ParseWorker.KEY_ID to recipeId, ParseWorker.KEY_MANUAL to true))
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(manualName(recipeId), ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel(context: Context, recipeId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(periodicName(recipeId))
        WorkManager.getInstance(context).cancelUniqueWork(manualName(recipeId))
    }

    private fun constraints(recipe: Recipe): Constraints = Constraints.Builder()
        .setRequiredNetworkType(if (recipe.settings.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .setRequiresCharging(recipe.settings.chargingOnly)
        .setRequiresBatteryNotLow(true)
        .build()

    const val TAG = "unipars"
}
