package com.unipars.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionTest {

    @Test
    fun cloudflareChallenge() {
        val body = "<html><head><title>Just a moment...</title></head><body><script src=\"/cdn-cgi/challenge-platform/h/b\"></script></body></html>"
        val v = BlockDetector.detect(403, mapOf("Server" to "cloudflare", "cf-mitigated" to "challenge"), body)
        assertEquals(BlockKind.CHALLENGE, v.kind)
        assertTrue(v.needsHuman)
    }

    @Test
    fun captchaInterstitial() {
        val body = "<html><head><title>Вы не робот?</title></head><body><div class=\"smart-captcha\"></div><script src=\"https://smartcaptcha.yandexcloud.net/captcha.js\"></script></body></html>"
        assertEquals(BlockKind.CAPTCHA, BlockDetector.detect(200, emptyMap(), body).kind)
    }

    @Test
    fun contentPageWithCaptchaFormIsNotBlocked() {
        val text = "Product description. ".repeat(200)
        val body = "<html><head><title>Shop</title></head><body><p>$text</p><form><div class=\"g-recaptcha\"></div></form></body></html>"
        assertEquals(BlockKind.NONE, BlockDetector.detect(200, emptyMap(), body).kind)
    }

    @Test
    fun rateLimitAndLogin() {
        assertEquals(BlockKind.RATE_LIMITED, BlockDetector.detect(429, emptyMap(), "").kind)
        assertEquals(BlockKind.LOGIN_REQUIRED, BlockDetector.detect(200, emptyMap(), "<html></html>", "https://x.example/login?next=/").kind)
    }

    @Test
    fun robots() {
        val r = Robots.parse("""
            User-agent: *
            Disallow: /search
            Allow: /search/public
            Crawl-delay: 10

            User-agent: BadBot
            Disallow: /
        """.trimIndent())
        assertTrue(r.isAllowed("unipars", "/catalog"))
        assertFalse(r.isAllowed("unipars", "/search?q=1"))
        assertTrue(r.isAllowed("unipars", "/search/public/x"))
        assertFalse(r.isAllowed("BadBot/1.0", "/catalog"))
        assertEquals(10, r.crawlDelay("unipars"))
    }

    @Test
    fun probeFactsAndRecommendation() {
        val body = """<html><head><link rel="alternate" type="application/rss+xml" href="/feed.xml">
            <script type="application/ld+json">{"@type":"Product","name":"x"}</script></head>
            <body><div id="root"></div></body></html>"""
        val facts = SiteProbe.analyze(ProbeInput(
            url = "https://shop.example/catalog", status = 200,
            headers = mapOf("Server" to "cloudflare", "cf-ray" to "1", "ETag" to "\"abc\""),
            body = body, responseTimeMs = 400, robotsTxt = "User-agent: *\nCrawl-delay: 1200",
        ))
        assertEquals(listOf("Cloudflare"), facts.antibot)
        assertTrue(facts.hasJsonLd)
        assertEquals(listOf("Product"), facts.jsonLdTypes)
        assertEquals(listOf("https://shop.example/feed.xml"), facts.feeds)
        assertTrue(facts.supportsEtag)
        assertTrue(facts.jsRequiredLikely)
        val (settings, mode) = SiteProbe.recommend(facts)
        assertEquals(FetchMode.WEBVIEW, mode)
        assertTrue(settings.intervalMinutes >= 35)
    }
}
