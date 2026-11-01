package com.unipars.app.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.unipars.app.net.UserAgents
import com.unipars.engine.EngineJson
import kotlinx.serialization.builtins.serializer

/** Imperative handle to the WebView shown during setup and captcha checks. */
class PageController {
    var webView: WebView? = null
        internal set

    fun setPickMode(enabled: Boolean) {
        webView?.evaluateJavascript("window.__uniparsPick=$enabled;", null)
    }

    /** DOM after JavaScript ran, as the user sees it. */
    fun captureHtml(callback: (html: String?, url: String?) -> Unit) {
        val wv = webView ?: return callback(null, null)
        wv.evaluateJavascript("document.documentElement.outerHTML") { result ->
            val html = runCatching { EngineJson.decodeFromString(String.serializer(), result) }.getOrNull()
            callback(html, wv.url)
        }
    }
}

private class Bridge(
    private val onPick: (String, String) -> Unit,
    private val onJson: (String, String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun pick(path: String, html: String) = main.post { onPick(path, html) }.let { }

    @JavascriptInterface
    fun json(url: String, body: String) = main.post { onJson(url, body) }.let { }
}

/**
 * Hooks fetch/XHR to collect JSON responses (hidden APIs are the most stable data source) and
 * lets the user tap elements to show what they want to monitor.
 */
private val INJECTED_JS = """
(function(){
  if (window.__uniparsInit) return; window.__uniparsInit = true;
  window.__uniparsPick = window.__uniparsPick || false;
  function cssPath(el){
    var parts = [];
    while (el && el.nodeType === 1 && parts.length < 6) {
      var s = el.tagName.toLowerCase();
      if (el.id && !/\d{3,}/.test(el.id)) { parts.unshift(s + '#' + el.id); break; }
      var cls = (el.getAttribute('class') || '').trim().split(/\s+/)
        .filter(function(c){ return c && c.length < 40 && !/\d{3,}/.test(c); }).slice(0, 3);
      if (cls.length) s += '.' + cls.join('.');
      parts.unshift(s);
      el = el.parentElement;
    }
    return parts.join(' > ');
  }
  document.addEventListener('click', function(e){
    if (!window.__uniparsPick) return;
    e.preventDefault(); e.stopPropagation();
    var el = e.target;
    el.style.outline = '3px solid #ff9800';
    UniPars.pick(cssPath(el), el.outerHTML.slice(0, 3000));
  }, true);
  var of = window.fetch;
  if (of) window.fetch = function(){
    return of.apply(this, arguments).then(function(r){
      try {
        var ct = r.headers.get('content-type') || '';
        if (ct.indexOf('json') >= 0) r.clone().text().then(function(t){ UniPars.json(r.url, t.slice(0, 6000)); });
      } catch (x) {}
      return r;
    });
  };
  var oo = XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open = function(m, u){ this.__u = u; return oo.apply(this, arguments); };
  var os = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.send = function(){
    var x = this;
    x.addEventListener('load', function(){
      try {
        var ct = x.getResponseHeader('content-type') || '';
        if (ct.indexOf('json') >= 0 && typeof x.responseText === 'string') UniPars.json(x.responseURL || String(x.__u), x.responseText.slice(0, 6000));
      } catch (e) {}
    });
    return os.apply(this, arguments);
  };
})();
""".trimIndent()

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PageWebView(
    url: String,
    controller: PageController,
    modifier: Modifier = Modifier,
    instrument: Boolean = true,
    onPick: (path: String, html: String) -> Unit = { _, _ -> },
    onJson: (url: String, body: String) -> Unit = { _, _ -> },
    onPageFinished: (String) -> Unit = {},
) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                // Same UA as background HTTP requests, so cookies from checks stay valid.
                settings.userAgentString = UserAgents.default(ctx)
                android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                val documentStart = instrument && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
                if (instrument) {
                    addJavascriptInterface(Bridge(onPick, onJson), "UniPars")
                    if (documentStart) WebViewCompat.addDocumentStartJavaScript(this, INJECTED_JS, setOf("*"))
                }
                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView, u: String?, favicon: Bitmap?) {
                        if (instrument && !documentStart) view.evaluateJavascript(INJECTED_JS, null)
                    }

                    override fun onPageFinished(view: WebView, u: String) {
                        if (instrument && !documentStart) view.evaluateJavascript(INJECTED_JS, null)
                        android.webkit.CookieManager.getInstance().flush()
                        onPageFinished(u)
                    }
                }
                controller.webView = this
                loadUrl(url)
            }
        },
        onRelease = { wv ->
            if (controller.webView === wv) controller.webView = null
            wv.destroy()
        },
    )
}
