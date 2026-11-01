package com.unipars.engine

/**
 * Guards for input produced by the model or by web pages: regexes with a time budget,
 * source-domain checks, and personal data masking before page content leaves the device.
 */
object Safety {

    class RegexTimeout : RuntimeException("regex took too long")

    /** CharSequence that aborts a catastrophic-backtracking match once the deadline passes. */
    private class Deadline(private val s: CharSequence, private val deadline: Long) : CharSequence {
        override val length get() = s.length
        override fun get(index: Int): Char {
            if (System.nanoTime() > deadline) throw RegexTimeout()
            return s[index]
        }
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = Deadline(s.subSequence(startIndex, endIndex), deadline)
        override fun toString() = s.toString()
    }

    const val MAX_REGEX_INPUT = 20_000

    /** First match (group 1 if present) or null; null also on timeout or an invalid pattern. */
    fun regexFind(pattern: String, input: String, timeoutMs: Long = 100): String? = try {
        val text = Deadline(input.take(MAX_REGEX_INPUT), System.nanoTime() + timeoutMs * 1_000_000)
        Regex(pattern).find(text)?.let { m -> (m.groups[1]?.value ?: m.value).trim() }
    } catch (_: RegexTimeout) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    fun host(url: String): String? = runCatching { java.net.URI(url.trim()).host?.lowercase()?.removePrefix("www.") }.getOrNull()

    /**
     * Same site = equal hosts or one is a subdomain of the other's registrable part
     * (shop.example.com and api.example.com). Simplified: compares the last two labels,
     * or three for common two-level public suffixes (co.uk, com.ru...).
     */
    fun sameSite(a: String, b: String): Boolean {
        val ha = host(a) ?: return false
        val hb = host(b) ?: return false
        return ha == hb || base(ha) == base(hb)
    }

    private val twoLevelSuffixes = setOf("co.uk", "com.ru", "org.ru", "net.ru", "com.ua", "co.jp", "com.au", "com.br", "co.kr", "com.tr", "msk.ru", "spb.ru", "com.kz")

    private fun base(host: String): String {
        val labels = host.split('.')
        if (labels.size <= 2) return host
        val lastTwo = labels.takeLast(2).joinToString(".")
        return if (lastTwo in twoLevelSuffixes) labels.takeLast(3).joinToString(".") else lastTwo
    }

    private val EMAIL = Regex("[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9.-]{1,253}\\.[A-Za-z]{2,24}")
    private val PHONE = Regex("(?<![\\w.])\\+?\\d[\\d\\s()\\-]{8,}\\d(?![\\w.])")
    private val CARD = Regex("\\b(?:\\d[ -]?){13,19}\\b")

    /** Masks e-mails, phone numbers and card-like numbers in free text. */
    fun maskPersonalData(text: String): String = text
        .replace(EMAIL, "[email]")
        .replace(CARD) { m -> if (m.value.count { it.isDigit() } in 13..19 && luhn(m.value)) "[card]" else m.value }
        .replace(PHONE) { m -> if (m.value.count { it.isDigit() } in 10..15) "[phone]" else m.value }

    private fun luhn(s: String): Boolean {
        val digits = s.filter { it.isDigit() }.map { it - '0' }
        var sum = 0
        digits.reversed().forEachIndexed { i, d -> sum += if (i % 2 == 1) (d * 2).let { if (it > 9) it - 9 else it } else d }
        return sum % 10 == 0
    }

    /** Limits text arriving from untrusted pages (JS bridge, XHR captures). */
    fun clip(text: String, max: Int): String = if (text.length <= max) text else text.take(max)
}
