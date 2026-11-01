package com.unipars.app.net

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import com.unipars.engine.EngineJson
import com.unipars.engine.FetchResponse
import com.unipars.engine.FetchSettings
import kotlinx.serialization.builtins.serializer
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Renders a page in an offscreen WebView and returns the DOM after JavaScript ran.
 * Used for recipes in "webview" mode; heavier than HTTP, so the probe prefers HTTP when possible.
 */
class WebViewFetcher(context: Context) {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    /** Blocking; must not be called on the main thread. */
    @SuppressLint("SetJavaScriptEnabled")
    fun fetch(url: String, settings: FetchSettings, settleMs: Long = 2_500): FetchResponse {
        check(Looper.myLooper() != Looper.getMainLooper()) { "WebViewFetcher.fetch must run off the main thread" }
        val latch = CountDownLatch(1)
        var html: String? = null
        var finalUrl = url
        var error: String? = null
        var webView: WebView? = null
        val done = AtomicBoolean(false)

        main.post {
            try {
                val wv = WebView(appContext)
                webView = wv
                wv.settings.javaScriptEnabled = true
                wv.settings.domStorageEnabled = true
                wv.settings.userAgentString = settings.userAgent ?: UserAgents.default(appContext)
                wv.settings.blockNetworkImage = true
                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, loadedUrl: String) {
                        // onPageFinished may fire several times (redirects); capture after the page settles.
                        main.removeCallbacksAndMessages(TOKEN)
                        main.postAtTime({
                            if (done.getAndSet(true)) return@postAtTime
                            view.evaluateJavascript("document.documentElement.outerHTML") { result ->
                                html = runCatching { EngineJson.decodeFromString(String.serializer(), result) }.getOrNull()
                                finalUrl = view.url ?: loadedUrl
                                latch.countDown()
                            }
                        }, TOKEN, android.os.SystemClock.uptimeMillis() + settleMs)
                    }

                    override fun onReceivedError(view: WebView, request: android.webkit.WebResourceRequest, err: android.webkit.WebResourceError) {
                        if (request.isForMainFrame && !done.getAndSet(true)) {
                            error = err.description?.toString() ?: "load error"
                            latch.countDown()
                        }
                    }
                }
                wv.loadUrl(url)
            } catch (e: Exception) {
                error = e.message
                latch.countDown()
            }
        }

        val finished = latch.await((settings.connectTimeoutSec + settings.readTimeoutSec + 15).toLong(), TimeUnit.SECONDS)
        main.post {
            main.removeCallbacksAndMessages(TOKEN)
            webView?.apply { stopLoading(); destroy() }
        }
        if (!finished) throw IOException("WebView timeout")
        error?.let { throw IOException(it) }
        // WebView does not expose the HTTP status; the block detector works from the content.
        return FetchResponse(200, emptyMap(), html.orEmpty(), finalUrl)
    }

    private companion object {
        val TOKEN = Any()
    }
}
