package com.heddrich.companion.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WikiTemplateLoaderTest {

    private val defaults = Templates.all()

    @Test
    fun `override replaces prompt and name by id`() {
        val html = "<p>[{\"id\":\"meeting\",\"name\":\"Mein Protokoll\",\"prompt\":\"Neuer Prompt\"}]</p>"
        val (merged, warning) = WikiTemplateLoader.merge(defaults, html)

        assertNull(warning)
        assertEquals(4, merged.size)
        val meeting = merged.first { it.id == "meeting" }
        assertEquals("Mein Protokoll", meeting.displayName)
        assertEquals("Neuer Prompt", meeting.systemPrompt)
        // Andere bleiben unveraendert
        assertEquals(defaults.first { it.id == "research" }, merged.first { it.id == "research" })
    }

    @Test
    fun `new templates from wiki are appended`() {
        val html = """[{"id":"standup","name":"Standup","prompt":"Kurzprotokoll","tags":["typ=standup"]}]"""
        val (merged, warning) = WikiTemplateLoader.merge(defaults, html)

        assertNull(warning)
        assertEquals(5, merged.size)
        val standup = merged.firstOrNull { it.id == "standup" }
        assertNotNull(standup)
        assertEquals(listOf("typ=standup"), standup!!.defaultTags)
    }

    @Test
    fun `broken json yields defaults plus warning`() {
        val (merged, warning) = WikiTemplateLoader.merge(defaults, "<p>kein json hier</p>")
        assertEquals(defaults, merged)
        assertTrue(warning != null && warning.contains("kein JSON"))
    }

    @Test
    fun `invalid json syntax yields defaults plus warning`() {
        // Klammer-Mismatch schlaegt auch den toleranten Parser
        val (merged, warning) = WikiTemplateLoader.merge(defaults, "[{\"id\":\"a\"]}")
        assertEquals(defaults, merged)
        assertTrue(warning != null && warning.contains("ungueltig"))
    }

    @Test
    fun `empty prompt keeps default`() {
        val html = """[{"id":"chat","name":"Chat ohne Prompt","prompt":""}]"""
        val (merged, _) = WikiTemplateLoader.merge(defaults, html)
        assertEquals(
            defaults.first { it.id == "chat" }.systemPrompt,
            merged.first { it.id == "chat" }.systemPrompt
        )
    }

    @Test
    fun `extractJson handles codefence in html`() {
        val html = "<p>```json\n[{\"id\":\"x\"}]\n```</p>"
        val json = WikiTemplateLoader.extractJson(html)
        assertEquals("""[{"id":"x"}]""", json)
    }

    @Test
    fun `extractJson falls back to bracket scan`() {
        val json = WikiTemplateLoader.extractJson("<p>Hier: [1,2] fertig</p>")
        assertEquals("[1,2]", json)
    }

    @Test
    fun `blank html means no config`() {
        val (merged, warning) = WikiTemplateLoader.merge(defaults, null)
        assertEquals(defaults, merged)
        assertNull(warning)
    }
}
