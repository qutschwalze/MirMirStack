package com.heddrich.companion.publish

import com.heddrich.companion.data.IngestItem
import com.heddrich.companion.data.IngestStatus
import com.heddrich.companion.share.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Test

class PublisherTest {

    private fun item(mime: String?, title: String?) = IngestItem(
        id = 1,
        createdAt = 0L,
        sourcePkg = null,
        sourceKind = SourceKind.UNKNOWN,
        templateId = null,
        title = title,
        rawText = "x",
        rawUri = null,
        mime = mime,
        status = IngestStatus.QUEUED,
        error = null,
        resultUrl = null
    )

    @Test
    fun `escapeHtml escapes markup and keeps newlines as br`() {
        val out = Publisher.escapeHtml("<b>&\"'</b>\nZwei")
        assertEquals(
            "<p>&lt;b&gt;&amp;&quot;&#39;&lt;/b&gt;<br>Zwei</p>",
            out
        )
    }

    @Test
    fun `attachmentName maps mime to extension`() {
        assertEquals("Titel.json", Publisher.attachmentName(item("application/json", "Titel")))
        assertEquals("Bericht.md", Publisher.attachmentName(item("text/markdown", "Bericht")))
        assertEquals("Notiz.txt", Publisher.attachmentName(item("text/plain", "Notiz")))
    }

    @Test
    fun `attachmentName sanitizes special characters`() {
        // ":/\\?" entfernt, Leerzeichen -> "_"
        val name = Publisher.attachmentName(item(null, "Meeting: A/B \\C?"))
        assertEquals("Meeting_AB_C.txt", name)
    }

    @Test
    fun `attachmentName falls back to original when title blank`() {
        assertEquals("original.md", Publisher.attachmentName(item("text/markdown", "")))
    }

    @Test
    fun `date formats match convention`() {
        assertEquals("yyyy-MM-dd", Publisher.PAGE_DATE_FORMAT)
        assertEquals("yyyy-MM", Publisher.CHAPTER_FORMAT)
    }
}
