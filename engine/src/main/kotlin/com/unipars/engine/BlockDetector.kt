package com.unipars.engine

enum class BlockKind {
    NONE,

    /** A visible CAPTCHA (reCAPTCHA, hCaptcha, Turnstile, Yandex SmartCaptcha...). */
    CAPTCHA,

    /** "Checking your browser" interstitial (Cloudflare, DDoS-Guard, DataDome...). */
    CHALLENGE,

    /** HTTP 429 or an explicit rate-limit page. */
    RATE_LIMITED,

    /** Redirected to a login page. */
    LOGIN_REQUIRED,

    /** 403 without a recognizable challenge. */
    FORBIDDEN,
}

data class BlockVerdict(val kind: BlockKind, val vendor: String? = null, val reason: String = "") {
    /** Whether a human can resolve it in a WebView. */
    val needsHuman: Boolean get() = kind == BlockKind.CAPTCHA || kind == BlockKind.CHALLENGE || kind == BlockKind.LOGIN_REQUIRED
}

/** Recognizes anti-bot pages so the engine pauses instead of hammering the site. */
object BlockDetector {

    private val captchaMarkers = listOf(
        "g-recaptcha" to "reCAPTCHA",
        "www.google.com/recaptcha" to "reCAPTCHA",
        "h-captcha" to "hCaptcha",
        "hcaptcha.com/1/api.js" to "hCaptcha",
        "challenges.cloudflare.com/turnstile" to "Cloudflare Turnstile",
        "cf-turnstile" to "Cloudflare Turnstile",
        "smartcaptcha.yandexcloud.net" to "Yandex SmartCaptcha",
        "showcaptcha" to "Yandex captcha",
        "captcha-delivery.com" to "DataDome",
    )

    private val challengeMarkers = listOf(
        "cf-browser-verification" to "Cloudflare",
        "cf_chl_opt" to "Cloudflare",
        "/cdn-cgi/challenge-platform/" to "Cloudflare",
        "just a moment..." to "Cloudflare",
        "checking your browser" to "Generic",
        "проверка браузера" to "Generic",
        "ddos-guard" to "DDoS-Guard",
        "_incapsula_resource" to "Imperva",
        "px-captcha" to "PerimeterX",
        "_pxhd" to "PerimeterX",
        "akamai bot manager" to "Akamai",
    )

    fun detect(status: Int, headers: Map<String, String>, body: String, finalUrl: String? = null): BlockVerdict {
        val h = headers.mapKeys { it.key.lowercase() }
        // Real content pages can mention these strings deep in scripts; only inspect the head of the page.
        val head = body.take(30_000).lowercase()

        if (status == 429) return BlockVerdict(BlockKind.RATE_LIMITED, reason = "HTTP 429")

        captchaMarkers.firstOrNull { head.contains(it.first) }?.let {
            // A page dominated by a captcha widget is a block; a content page with a captcha-protected form is not.
            if (status >= 400 || looksLikeInterstitial(head) || visibleTextLength(body) < 1_500) {
                return BlockVerdict(BlockKind.CAPTCHA, it.second, "captcha marker '${it.first}'")
            }
        }
        if (h["cf-mitigated"] == "challenge") return BlockVerdict(BlockKind.CHALLENGE, "Cloudflare", "cf-mitigated header")
        if (status == 403 || status == 503) {
            challengeMarkers.firstOrNull { head.contains(it.first) }?.let {
                return BlockVerdict(BlockKind.CHALLENGE, it.second, "HTTP $status + '${it.first}'")
            }
        }
        if (looksLikeInterstitial(head)) {
            challengeMarkers.firstOrNull { head.contains(it.first) }?.let {
                return BlockVerdict(BlockKind.CHALLENGE, it.second, "interstitial '${it.first}'")
            }
        }
        if (finalUrl != null && LOGIN_URL.containsMatchIn(finalUrl)) {
            return BlockVerdict(BlockKind.LOGIN_REQUIRED, reason = "redirected to $finalUrl")
        }
        if (status == 401) return BlockVerdict(BlockKind.LOGIN_REQUIRED, reason = "HTTP 401")
        if (status == 403) return BlockVerdict(BlockKind.FORBIDDEN, reason = "HTTP 403")
        return BlockVerdict(BlockKind.NONE)
    }

    private fun looksLikeInterstitial(head: String): Boolean {
        val title = Regex("<title[^>]*>(.*?)</title>", RegexOption.DOT_MATCHES_ALL).find(head)?.groupValues?.get(1).orEmpty()
        return title.contains("just a moment") || title.contains("attention required") ||
            title.contains("captcha") || title.contains("access denied") || title.contains("проверка") ||
            title.contains("are you a robot") || title.contains("вы не робот")
    }

    private fun visibleTextLength(body: String): Int =
        runCatching { org.jsoup.Jsoup.parse(body).body()?.text()?.length ?: 0 }.getOrDefault(0)

    private val LOGIN_URL = Regex("(/login|/signin|/sign-in|/auth|/account/login|passport\\.)", RegexOption.IGNORE_CASE)
}
