package com.unipars.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyTest {

    @Test
    fun catastrophicRegexTimesOut() {
        val started = System.currentTimeMillis()
        assertNull(Safety.regexFind("(a+)+$", "a".repeat(5_000) + "!", timeoutMs = 50))
        assertTrue(System.currentTimeMillis() - started < 2_000)
        assertEquals("42", Safety.regexFind("id=(\\d+)", "x id=42 y"))
        assertNull(Safety.regexFind("(unclosed", "x"))
    }

    @Test
    fun sameSite() {
        assertTrue(Safety.sameSite("https://shop.example.com/a", "https://api.example.com/v1"))
        assertTrue(Safety.sameSite("https://www.example.com", "https://example.com/x"))
        assertTrue(Safety.sameSite("https://a.shop.co.uk", "https://b.shop.co.uk"))
        assertFalse(Safety.sameSite("https://a.shop.co.uk", "https://b.other.co.uk"))
        assertFalse(Safety.sameSite("https://example.com", "https://evil.com"))
    }

    @Test
    fun masksPersonalData() {
        val t = Safety.maskPersonalData("Пишите ivan.petrov@mail.ru или звоните +7 (912) 345-67-89, карта 4111 1111 1111 1111, цена 1 299 ₽, артикул 123456")
        assertFalse(t.contains("ivan"))
        assertFalse(t.contains("345-67"))
        assertFalse(t.contains("4111"))
        assertTrue(t.contains("1 299 ₽"))
        assertTrue(t.contains("123456"))
    }

    @Test
    fun cleanerMasksForms() {
        val html = """<html><body><form><input type="hidden" name="csrf" value="SECRET"><input name="q" value="my search">
            <input type="password" value="pw"></form><p>me@example.org</p><span class="price">10</span></body></html>"""
        val c = HtmlCleaner.clean(html, "https://x/").html
        assertFalse(c.contains("SECRET"))
        assertFalse(c.contains("my search"))
        assertFalse(c.contains("me@example.org"))
        assertTrue(c.contains("class=\"price\""))
    }
}
