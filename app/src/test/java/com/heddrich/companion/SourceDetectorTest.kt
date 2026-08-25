package com.heddrich.companion

import com.heddrich.companion.share.SourceDetector
import com.heddrich.companion.share.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Test

class SourceDetectorTest {

    // ── Referrer-Pfad (primäres Signal) ────────────────────────────────────

    @Test
    fun `referrer sherpa package maps to SHERPA`() {
        assertEquals(SourceKind.SHERPA, SourceDetector.fromPackage("com.sherpa.transcript"))
    }

    @Test
    fun `referrer whatsapp prefix maps to WHATSAPP`() {
        assertEquals(SourceKind.WHATSAPP, SourceDetector.detect("com.whatsapp.w4b", null))
    }

    @Test
    fun `referrer chrome maps to BROWSER`() {
        assertEquals(SourceKind.BROWSER, SourceDetector.detect("com.android.chrome", null))
    }

    @Test
    fun `referrer firefox nightly maps to BROWSER`() {
        assertEquals(SourceKind.BROWSER, SourceDetector.detect("org.mozilla.firefox", null))
    }

    @Test
    fun `referrer gmail maps to EMAIL`() {
        assertEquals(SourceKind.EMAIL, SourceDetector.detect("com.google.android.gm", null))
    }

    @Test
    fun `unknown referrer with mail substring maps to EMAIL`() {
        assertEquals(SourceKind.EMAIL, SourceDetector.detect("com.example.mailapp", null))
    }

    @Test
    fun `unknown referrer without mapping maps to OTHER_APP`() {
        assertEquals(SourceKind.OTHER_APP, SourceDetector.detect("com.some.otherapp", null))
    }

    // ── Heuristik-Pfad (Fallback ohne Referrer) ────────────────────────────

    @Test
    fun `transcript with speaker markers detected as SHERPA`() {
        val text = """
            Sprecher A: Guten Tag zusammen.
            Sprecher B: Hallo, ich habe den Bericht vorbereitet.
            Sprecher A: Sehr gut, legen wir los.
        """.trimIndent()
        assertEquals(SourceKind.SHERPA, SourceDetector.heuristic(text))
    }

    @Test
    fun `english speaker markers detected as transcript too`() {
        val text = "Speaker 1: Welcome everyone.\nSpeaker 2: Thanks for having me."
        assertEquals(SourceKind.SHERPA, SourceDetector.heuristic(text))
    }

    @Test
    fun `plain urls detected as BROWSER`() {
        val text = "https://example.com/article\nhttps://example.org/page2"
        assertEquals(SourceKind.BROWSER, SourceDetector.heuristic(text))
    }

    @Test
    fun `url plus one title line still BROWSER`() {
        val text = "Interessanter Artikel\nhttps://example.com/article"
        assertEquals(SourceKind.BROWSER, SourceDetector.heuristic(text))
    }

    @Test
    fun `json object is UNKNOWN not browser`() {
        val text = """{"segments": [{"speaker": "A"}]}"""
        assertEquals(SourceKind.UNKNOWN, SourceDetector.heuristic(text))
    }

    @Test
    fun `plain prose without markers is UNKNOWN`() {
        val text = "Das ist ein normaler Text aus einer Notiz-App ohne besondere Struktur."
        assertEquals(SourceKind.UNKNOWN, SourceDetector.heuristic(text))
    }

    @Test
    fun `empty text is UNKNOWN`() {
        assertEquals(SourceKind.UNKNOWN, SourceDetector.heuristic("   \n  "))
    }

    // ── Prioritaet: Referrer schlaegt Heuristik ────────────────────────────

    @Test
    fun `referrer wins over heuristic even with speaker markers in text`() {
        // z.B. WhatsApp-Nachricht mit zitiertem Transkript-Ausschnitt
        val text = "Sprecher A: Hallo\nSprecher B: Hi"
        assertEquals(SourceKind.WHATSAPP, SourceDetector.detect("com.whatsapp", text))
    }
}
