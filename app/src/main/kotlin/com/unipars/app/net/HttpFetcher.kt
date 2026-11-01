package com.unipars.app.net

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import com.unipars.engine.CacheValidators
import com.unipars.engine.FetchResponse
import com.unipars.engine.FetchSettings
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Shares cookies with WebView, so a captcha or login passed by the user in the app's WebView
 * also applies to background HTTP requests.
 */
class WebViewCookieJar : CookieJar {
    private val manager get() = CookieManager.getInstance()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        cookies.forEach { manager.setCookie(url.toString(), it.toString()) }
        manager.flush()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> =
        manager.getCookie(url.toString())?.split(';')?.mapNotNull { Cookie.parse(url, it.trim()) }.orEmpty()
}

object UserAgents {
    @Volatile private var cached: String? = null

    /** The WebView user agent; challenge cookies are usually bound to it. */
    fun default(context: Context): String =
        cached ?: runCatching { WebSettings.getDefaultUserAgent(context) }.getOrDefault("Mozilla/5.0 (Linux; Android)").also { cached = it }
}

class HttpFetcher(private val context: Context) {

    private val base: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(WebViewCookieJar())
        .followRedirects(true)
        .build()

    /** Blocking; call from a background thread. */
    fun fetch(url: String, settings: FetchSettings, headers: Map<String, String> = emptyMap(), validators: CacheValidators? = null): FetchResponse {
        val client = base.newBuilder()
            .connectTimeout(settings.connectTimeoutSec.toLong(), TimeUnit.SECONDS)
            .readTimeout(settings.readTimeoutSec.toLong(), TimeUnit.SECONDS)
            .callTimeout((settings.connectTimeoutSec + settings.readTimeoutSec + 10).toLong(), TimeUnit.SECONDS)
            .build()
        val builder = Request.Builder().url(url)
            .header("User-Agent", settings.userAgent ?: UserAgents.default(context))
            .header("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
            .header("Accept-Language", Locale.getDefault().toLanguageTag() + ",en;q=0.7")
        headers.forEach { (k, v) -> builder.header(k, v) }
        validators?.etag?.let { builder.header("If-None-Match", it) }
        validators?.lastModified?.let { builder.header("If-Modified-Since", it) }
        val request = builder.build()

        var lastError: IOException? = null
        repeat(settings.retries.coerceAtLeast(0) + 1) { attempt ->
            if (attempt > 0) Thread.sleep(2_000L * attempt)
            try {
                client.newCall(request).execute().use { resp ->
                    if (resp.code == 304) {
                        return FetchResponse(304, emptyMap(), "", resp.request.url.toString(), notModified = true)
                    }
                    // Retry only transient server errors; 4xx (incl. 403/429) go to the block detector.
                    if (resp.code in 500..599 && resp.code != 503 && attempt < settings.retries) {
                        lastError = IOException("HTTP ${resp.code}")
                        return@repeat
                    }
                    val body = resp.body?.let { b ->
                        val source = b.source()
                        source.request(MAX_BYTES)
                        val bytes = source.buffer.readByteArray(minOf(source.buffer.size, MAX_BYTES))
                        String(bytes, b.contentType()?.charset() ?: Charsets.UTF_8)
                    }.orEmpty()
                    val hs = resp.headers.names().associateWith { resp.headers.values(it).joinToString("; ") }
                    return FetchResponse(resp.code, hs, body, resp.request.url.toString())
                }
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw lastError ?: IOException("request failed")
    }

    companion object {
        private const val MAX_BYTES = 8L * 1024 * 1024
    }
}
