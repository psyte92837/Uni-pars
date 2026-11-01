package com.unipars.app.update

import android.content.Context
import com.unipars.app.BuildConfig
import com.unipars.app.net.HttpFetcher
import com.unipars.engine.FetchSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class Release(val version: String, val url: String)

/** APKs are distributed through GitHub Releases; this checks for a newer tag. */
class UpdateChecker(private val context: Context) {

    /** Blocking. Returns the latest release if it is newer than [current], else null. */
    fun latestNewer(current: String): Release? {
        val r = HttpFetcher(context).fetch(
            "https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest",
            FetchSettings(connectTimeoutSec = 15, readTimeoutSec = 20, retries = 0, userAgent = "Uni-pars-app"),
            mapOf("Accept" to "application/vnd.github+json"),
        )
        if (r.status != 200) return null
        val o = Json.parseToJsonElement(r.body).jsonObject
        val tag = o["tag_name"]?.jsonPrimitive?.content ?: return null
        val url = o["html_url"]?.jsonPrimitive?.content ?: return null
        val version = tag.removePrefix("v")
        return if (isNewer(version, current)) Release(version, url) else null
    }

    companion object {
        /** Numeric comparison of dotted versions; "-dev" builds count as older than the same release. */
        fun isNewer(candidate: String, current: String): Boolean {
            fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
            val a = parts(candidate)
            val b = parts(current)
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return current.contains('-') && !candidate.contains('-')
        }
    }
}
