package com.unipars.app.work

import android.content.Context
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Serializes requests per site across all recipes and keeps a minimum gap between them, so
 * several recipes on one domain behave like one polite client.
 */
object DomainGate {
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun <T> withDomain(context: Context, domain: String, minGapMs: Long, block: suspend () -> T): T {
        val mutex = locks.getOrPut(domain) { Mutex() }
        return mutex.withLock {
            val prefs = context.getSharedPreferences("domain_gate", Context.MODE_PRIVATE)
            val last = prefs.getLong(domain, 0)
            val wait = last + minGapMs - System.currentTimeMillis()
            if (wait > 0) delay(wait.coerceAtMost(MAX_WAIT_MS))
            try {
                block()
            } finally {
                prefs.edit().putLong(domain, System.currentTimeMillis()).apply()
            }
        }
    }

    /** Default gap between two requests to one site; Crawl-delay raises it. */
    fun gapMs(crawlDelaySec: Int?): Long = maxOf(DEFAULT_GAP_MS, (crawlDelaySec ?: 0) * 1000L)

    private const val DEFAULT_GAP_MS = 20_000L
    private const val MAX_WAIT_MS = 120_000L
}
