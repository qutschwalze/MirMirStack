package com.heddrich.companion.bookstack

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BookStackClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: BookStackClient

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        // normalizeBaseUrl haengt /api/ an – MockWebServer liefert die Pfade
        client = BookStackClient(server.url("/").toString(), "tid", "tsec")
    }

    @After
    fun teardown() {
        server.shutdown()
    }

    private fun json(body: String): MockResponse =
        MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    // ── normalizeBaseUrl ───────────────────────────────────────────────────

    @Test
    fun `base url gets https and api suffix`() {
        assertEquals(
            "https://wiki.example.com/api/",
            BookStackClient.normalizeBaseUrl("wiki.example.com")
        )
    }

    @Test
    fun `existing api path is not doubled`() {
        assertEquals(
            "https://wiki.example.com/api/",
            BookStackClient.normalizeBaseUrl("https://wiki.example.com/api/")
        )
    }

    @Test
    fun `trailing slash is normalized`() {
        assertEquals(
            "https://wiki.example.com/api/",
            BookStackClient.normalizeBaseUrl("https://wiki.example.com/")
        )
    }

    // ── Auth ───────────────────────────────────────────────────────────────

    @Test
    fun `auth header uses token scheme`() = runTest {
        // 2 Antworten: GET chapters (leer) + POST createChapter
        server.enqueue(json("""{"data": []}"""))
        server.enqueue(json("""{"id": 9, "name": "2026-08", "book_id": 3}"""))
        client.ensureMonthlyChapter(3, "2026-08")
        val recorded = server.takeRequest()
        assertEquals("Token tid:tsec", recorded.getHeader("Authorization"))
    }

    // ── ensureMonthlyChapter ───────────────────────────────────────────────

    @Test
    fun `missing chapter is created via POST`() = runTest {
        server.enqueue(json("""{"data": []}""")) // GET chapters -> leer
        server.enqueue(json("""{"id": 9, "name": "2026-08", "book_id": 3}""")) // POST

        val ch = client.ensureMonthlyChapter(3, "2026-08")

        assertEquals(9, ch.id)
        server.takeRequest()                 // 1. Anfrage: GET chapters (leer)
        val post = server.takeRequest()      // 2. Anfrage: POST createChapter
        assertEquals("POST", post.method)
        assertTrue(post.path!!.endsWith("/chapters"))
        val sentBody = post.body.readUtf8()  // nur EINMAL lesen (Buffer wird konsumiert)
        assertTrue(sentBody.contains("\"book_id\":3"))
        assertTrue(sentBody.contains("2026-08"))
    }

    @Test
    fun `existing chapter is returned without creating`() = runTest {
        server.enqueue(
            json("""{"data": [{"id": 5, "name": "2026-08", "book_id": 3}]}""")
        )

        val ch = client.ensureMonthlyChapter(3, "2026-08")

        assertEquals(5, ch.id)
        assertEquals(1, server.requestCount) // nur das GET, kein POST
    }

    // ── upsertPage ─────────────────────────────────────────────────────────

    @Test
    fun `page is created when name not found`() = runTest {
        server.enqueue(json("""{"data": []}"""))
        server.enqueue(
            json("""{"id": 42, "name": "2026-08-25 Test", "slug": "2026-08-25-test", "book_id": 3, "chapter_id": 9}""")
        )

        val (page, created) = client.upsertPage(9, "2026-08-25 Test", "<p>Hi</p>")

        assertTrue(created)
        assertEquals(42, page.id)
        server.takeRequest()             // 1. Anfrage: GET pages (nichts gefunden)
        val post = server.takeRequest()  // 2. Anfrage: POST createPage
        assertEquals("POST", post.method)
        assertTrue(post.path!!.endsWith("/pages"))
    }

    @Test
    fun `page with same name is updated via PUT`() = runTest {
        server.enqueue(
            json("""{"data": [{"id": 42, "name": "2026-08-25 Test", "chapter_id": 9}]}""")
        )
        server.enqueue(
            json("""{"id": 42, "name": "2026-08-25 Test", "slug": "2026-08-25-test"}""")
        )

        val (page, created) = client.upsertPage(9, "2026-08-25 Test", "<p>Neu</p>")

        assertTrue(!created)
        assertEquals(42, page.id)
        server.takeRequest()            // 1. Anfrage: GET pages (Treffer)
        val put = server.takeRequest()  // 2. Anfrage: PUT updatePage
        assertEquals("PUT", put.method)
        assertTrue(put.path!!.endsWith("/pages/42"))
    }

    // ── Attachment-Multipart ───────────────────────────────────────────────

    @Test
    fun `attachment multipart uses file field and uploaded_to`() = runTest {
        server.enqueue(json("""{"id": 7, "name": "original.txt", "uploaded_to": 42}"""))

        client.uploadAttachment(42, "original.txt", "Inhalt")

        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertTrue(req.path!!.endsWith("/attachments"))
        val body = req.body.readUtf8()
        assertTrue(body.contains("name=\"file\""))
        assertTrue(body.contains("filename=\"original.txt\""))
        assertTrue(body.contains("name=\"uploaded_to\""))
        assertTrue(body.contains("42"))
    }

    // ── Wiki-URL ───────────────────────────────────────────────────────────

    @Test
    fun `web url uses book slug and page slug`() = runTest {
        server.enqueue(
            json("""{"data": [{"id": 3, "name": "Meetings und Notizen", "slug": "meetings-and-notizen"}]}""")
        )
        val url = client.webUrlFor(PageDto(id = 42, name = "x", slug = "2026-08-25-test", bookId = 3))
        assertEquals(
            server.url("/").toString().trimEnd('/') + "/books/meetings-and-notizen/page/2026-08-25-test",
            url
        )
    }
}
