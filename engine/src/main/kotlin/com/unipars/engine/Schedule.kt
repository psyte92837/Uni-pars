package com.unipars.engine

import java.time.LocalTime

object Schedule {

    /** Effective interval: never below WorkManager's minimum nor the site's crawl delay. */
    fun effectiveIntervalMinutes(settings: FetchSettings, crawlDelaySec: Int? = null): Int {
        var m = maxOf(settings.intervalMinutes, FetchSettings.MIN_INTERVAL_MINUTES)
        if (settings.maxRequestsPerDay > 0) m = maxOf(m, (24 * 60) / settings.maxRequestsPerDay)
        crawlDelaySec?.let { m = maxOf(m, (it + 59) / 60) }
        return m
    }

    /** "23:00-07:00" — supports windows that cross midnight. */
    fun inQuietHours(quietHours: String?, now: LocalTime): Boolean {
        val spec = quietHours?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val parts = spec.split('-').map { it.trim() }
        if (parts.size != 2) return false
        val from = runCatching { LocalTime.parse(parts[0]) }.getOrNull() ?: return false
        val to = runCatching { LocalTime.parse(parts[1]) }.getOrNull() ?: return false
        return if (from <= to) now >= from && now < to else now >= from || now < to
    }

    /** Exponential backoff for consecutive failures, capped at one day. */
    fun backoffMinutes(settings: FetchSettings, consecutiveFailures: Int): Long {
        if (consecutiveFailures <= 0) return 0
        val factor = 1L shl minOf(consecutiveFailures - 1, 6)
        return minOf(settings.backoffMinutes.toLong() * factor, 24 * 60L)
    }
}
