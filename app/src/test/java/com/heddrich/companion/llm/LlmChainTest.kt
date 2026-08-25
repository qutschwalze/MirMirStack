package com.heddrich.companion.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmChainTest {

    // ── SummaryParser ──────────────────────────────────────────────────────

    @Test
    fun `parses clean json answer`() {
        val result = SummaryParser.parse(
            """{"title":"Wochenmeeting","summary_md":"## Kern\n- Punkt A",
               "decisions":["A freigegeben"],"todos":["X bis Freitag"],
               "participants":["Jens","Anna"],"tags":["Meeting"," Team A"]}"""
        )
        assertEquals("Wochenmeeting", result.title)
        assertTrue(result.summaryMd.startsWith("## Kern"))
        assertEquals(listOf("A freigegeben"), result.decisions)
        assertEquals(listOf("Jens", "Anna"), result.participants)
        assertEquals(listOf("meeting", "team-a"), result.tags)
    }

    @Test
    fun `parses answer wrapped in code fence`() {
        val raw = "```json\n{\"title\":\"T\",\"summary_md\":\"S\"}\n```"
        val result = SummaryParser.parse(raw)
        assertEquals("T", result.title)
        assertEquals("S", result.summaryMd)
    }

    @Test
    fun `parses answer with prose around json`() {
        val raw = "Hier ist das Ergebnis:\n{\"title\":\"T\",\"summary_md\":\"S\"}\nViel Erfolg!"
        val result = SummaryParser.parse(raw)
        assertEquals("T", result.title)
    }

    @Test(expected = IllegalStateException::class)
    fun `missing title fails with clear error`() {
        SummaryParser.parse("""{"summary_md":"nur Text"}""")
    }

    @Test(expected = IllegalStateException::class)
    fun `non-json answer fails`() {
        SummaryParser.parse("Das war leider kein JSON.")
    }

    // ── MdRenderer (Golden-Tests) ──────────────────────────────────────────

    @Test
    fun `renders headings lists and bold deterministically`() {
        val html = MdRenderer.render(
            "## Kernpunkte\n- **wichtig** zuerst\n1. Erster\n2. Zweiter\n- [x] erledigt\n- [ ] offen"
        )
        assertTrue(html.contains("<h2>Kernpunkte</h2>"))
        assertTrue(html.contains("<li><strong>wichtig</strong> zuerst</li>"))
        assertTrue(html.contains("<ol><li>Erster</li><li>Zweiter</li></ol>"))
        assertTrue(html.contains("\u2611 erledigt"))
        assertTrue(html.contains("\u2610 offen"))
        assertTrue(!html.contains("**"))
    }

    @Test
    fun `escapes html in markdown input`() {
        val html = MdRenderer.render("Text mit <script>alert(1)</script>")
        assertTrue(html.contains("&lt;script&gt;"))
        assertTrue(!html.contains("<script>"))
    }

    @Test
    fun `plain paragraph becomes p`() {
        assertEquals("<div><p>Hallo Welt</p></div>", MdRenderer.render("Hallo Welt"))
    }

    // ── Template-Routing ───────────────────────────────────────────────────

    @Test
    fun `routing picks meeting for sherpa`() {
        assertEquals("meeting", Templates.defaultFor("SHERPA"))
    }

    @Test
    fun `routing picks research for browser`() {
        assertEquals("research", Templates.defaultFor("BROWSER"))
    }

    @Test
    fun `routing picks chat for whatsapp and email`() {
        assertEquals("chat", Templates.defaultFor("WHATSAPP"))
        assertEquals("chat", Templates.defaultFor("EMAIL"))
    }

    @Test
    fun `unknown source falls back to universal`() {
        assertEquals("universal", Templates.defaultFor("UNKNOWN"))
        assertEquals("universal", Templates.byId(null).id)
    }

    @Test
    fun `llm client normalizes base url with trailing slash`() {
        assertEquals("https://x/v1/", LlmClient.withTrailingSlash("https://x/v1"))
        assertEquals("https://x/v1/", LlmClient.withTrailingSlash("https://x/v1/"))
    }
}
