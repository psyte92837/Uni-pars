package com.unipars.app.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.unipars.app.UniParsApp
import com.unipars.app.data.RecipeEntity
import com.unipars.app.data.RecipeStatus
import com.unipars.app.data.RunEntity
import com.unipars.app.data.recipe
import com.unipars.app.net.AppFetcher
import com.unipars.engine.CacheValidators
import com.unipars.engine.CaptchaPolicy
import com.unipars.engine.RecipeRunner
import com.unipars.engine.RunOutcome
import com.unipars.engine.Schedule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime

/**
 * One background run of one recipe. No AI here: fetch → extract → validate → diff → notify.
 */
class ParseWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val app get() = applicationContext as UniParsApp

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ID) ?: return Result.failure()
        val manual = inputData.getBoolean(KEY_MANUAL, false)
        val dao = app.db.recipes()
        val entity = dao.get(id) ?: run {
            Scheduler.cancel(applicationContext, id)
            return Result.success()
        }
        if (!manual) {
            if (!entity.enabled) return Result.success()
            // Waiting for the user to pass a check: do not touch the site until they do.
            if (entity.status == RecipeStatus.NEEDS_HUMAN) return Result.success()
        }
        val recipe = runCatching { entity.recipe() }.getOrElse {
            dao.upsert(entity.copy(status = RecipeStatus.BROKEN, lastMessage = "Рецепт повреждён: ${it.message}"))
            return Result.success()
        }
        val now = System.currentTimeMillis()
        if (!manual) {
            val wait = Schedule.backoffMinutes(recipe.settings, entity.consecutiveFailures) * 60_000
            if (entity.lastRunAt != null && now < entity.lastRunAt + wait) return Result.success()
        }
        val today = LocalDate.now().toString()
        val requestsToday = if (entity.requestsDay == today) entity.requestsToday else 0
        if (!manual && recipe.settings.maxRequestsPerDay in 1..requestsToday) return Result.success()

        if (recipe.settings.respectRobots && app.repository.profile(entity.domain)?.facts?.robotsAllowed == false) {
            dao.upsert(entity.copy(status = RecipeStatus.ERROR, lastRunAt = now, lastMessage = "robots.txt запрещает эту страницу; отключите «Соблюдать robots.txt», если уверены"))
            return Result.success()
        }

        val previous = app.repository.previousItems(entity)
        val validators = CacheValidators(entity.etag, entity.lastModified, entity.bodyHash)
        val started = System.currentTimeMillis()
        val outcome = withContext(Dispatchers.IO) {
            RecipeRunner(AppFetcher(applicationContext)).run(recipe, previous, validators)
        }
        val duration = System.currentTimeMillis() - started
        val base = entity.copy(lastRunAt = now, requestsDay = today, requestsToday = requestsToday + 1)

        when (outcome) {
            is RunOutcome.NotModified -> {
                dao.upsert(base.copy(
                    status = RecipeStatus.ACTIVE, consecutiveFailures = 0, lastSuccessAt = now,
                    etag = outcome.validators.etag ?: entity.etag,
                    lastModified = outcome.validators.lastModified ?: entity.lastModified,
                    bodyHash = outcome.validators.bodyHash ?: entity.bodyHash,
                    lastMessage = "Без изменений",
                ))
                log(id, now, "not_modified", null, duration, entity.itemCount)
            }
            is RunOutcome.Success -> {
                app.repository.storeRun(id, outcome.items, outcome.diff.removedKeys, outcome.diff.events, now)
                dao.upsert(base.copy(
                    status = RecipeStatus.ACTIVE, consecutiveFailures = 0, lastSuccessAt = now,
                    etag = outcome.validators.etag, lastModified = outcome.validators.lastModified, bodyHash = outcome.validators.bodyHash,
                    itemCount = outcome.items.size, baselineDone = true,
                    lastMessage = if (entity.baselineDone) "Изменений: ${outcome.diff.events.size}" else "Базовая линия: ${outcome.items.size} записей",
                ))
                log(id, now, "ok", "страниц: ${outcome.pages}, событий: ${outcome.diff.events.size}", duration, outcome.items.size)
                notifyPending(base, recipe.settings.quietHours)
            }
            is RunOutcome.Blocked -> {
                val v = outcome.verdict
                val reason = listOfNotNull(v.vendor, v.kind.name.lowercase()).joinToString(" ")
                if (v.needsHuman) {
                    dao.upsert(base.copy(status = RecipeStatus.NEEDS_HUMAN, lastMessage = "Нужна проверка: $reason"))
                    if (recipe.settings.captchaPolicy == CaptchaPolicy.ASK_USER) {
                        app.notifier.needsHuman(id, entity.domain, reason)
                    }
                } else {
                    dao.upsert(base.copy(status = RecipeStatus.ERROR, consecutiveFailures = entity.consecutiveFailures + 1, lastMessage = "Сайт ограничил доступ: ${v.reason}"))
                }
                log(id, now, "blocked", reason, duration, 0)
            }
            is RunOutcome.Broken -> {
                val wasBroken = entity.status == RecipeStatus.BROKEN
                dao.upsert(base.copy(status = RecipeStatus.BROKEN, consecutiveFailures = entity.consecutiveFailures + 1, lastMessage = outcome.validation.message))
                if (!wasBroken) app.notifier.broken(id, entity.name, outcome.validation.message)
                log(id, now, "broken", outcome.validation.message, duration, outcome.extraction.items.size)
            }
            is RunOutcome.Failed -> {
                dao.upsert(base.copy(status = RecipeStatus.ERROR, consecutiveFailures = entity.consecutiveFailures + 1, lastMessage = outcome.error))
                log(id, now, "error", outcome.error, duration, 0)
            }
        }
        app.db.runs().trim(id)
        return Result.success()
    }

    /** Sends events not yet notified, unless we are inside the quiet hours (they wait for the next run). */
    private suspend fun notifyPending(entity: RecipeEntity, quietHours: String?) {
        if (Schedule.inQuietHours(quietHours, LocalTime.now())) return
        val pending = app.db.events().pending(entity.id)
        if (pending.isEmpty()) return
        app.notifier.events(entity.id, entity.name, pending)
        app.db.events().markNotified(entity.id)
    }

    private suspend fun log(id: String, ts: Long, outcome: String, message: String?, duration: Long, items: Int) {
        app.db.runs().insert(RunEntity(recipeId = id, ts = ts, outcome = outcome, message = message, durationMs = duration, items = items))
    }

    companion object {
        const val KEY_ID = "recipe_id"
        const val KEY_MANUAL = "manual"
    }
}
