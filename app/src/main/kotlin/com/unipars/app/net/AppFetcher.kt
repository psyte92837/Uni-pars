package com.unipars.app.net

import android.content.Context
import com.unipars.engine.CacheValidators
import com.unipars.engine.FetchMode
import com.unipars.engine.FetchResponse
import com.unipars.engine.PageFetcher
import com.unipars.engine.Recipe

/** Picks HTTP or WebView according to the recipe. */
class AppFetcher(context: Context) : PageFetcher {
    private val http = HttpFetcher(context)
    private val webView = WebViewFetcher(context)

    override fun fetch(url: String, recipe: Recipe, validators: CacheValidators?): FetchResponse =
        when (recipe.source.mode) {
            FetchMode.HTTP -> http.fetch(url, recipe.settings, recipe.source.headers, validators)
            FetchMode.WEBVIEW -> webView.fetch(url, recipe.settings)
        }
}
