package com.heddrich.companion.bookstack

import com.heddrich.companion.settings.SettingsStore
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/**
 * High-Level-Client fuer den Publish-Pfad. Alle Aufrufe sind suspend;
 * HTTP-/Serialisierungsfehler laufen als Retrofit/Serialization-Exceptions auf
 * und werden im Worker zu verstaendlichen FAILED-Meldungen verarbeitet.
 */
class BookStackClient internal constructor(
    baseUrl: String,
    tokenId: String,
    tokenSecret: String,
    timeoutSeconds: Long = 30
) {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private val api: BookStackApi
    private val rootUrl: String

    init {
        require(baseUrl.isNotBlank()) { "BookStack-URL fehlt" }
        require(tokenId.isNotBlank() && tokenSecret.isNotBlank()) { "Token unvollstaendig" }

        rootUrl = normalizeBaseUrl(baseUrl).removeSuffix("api/").trimEnd('/')

        val authInterceptor = Interceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("Authorization", "Token $tokenId:$tokenSecret")
                    .header("Accept", "application/json")
                    .build()
            )
        }
        val http = OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .build()

        api = Retrofit.Builder()
            .baseUrl(normalizeBaseUrl(baseUrl))
            .client(http)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(BookStackApi::class.java)
    }

    companion object {
        fun fromSettings(s: SettingsStore): BookStackClient =
            BookStackClient(s.bookstackUrl, s.bookstackTokenId, s.bookstackTokenSecret)

        /** "/api" erzwingen + trailing slash (Retrofit braucht abschliessenden Slash). */
        fun normalizeBaseUrl(url: String): String {
            var u = url.trim()
            if (!u.startsWith("http")) u = "https://$u"
            u = u.trimEnd('/')
            return if (u.endsWith("/api")) "$u/" else "$u/api/"
        }

        /** Verbindungs-Test (GET /api/books) – wirft bei Auth-/Netzfehlern. */
        suspend fun testConnection(s: SettingsStore): String {
            val books = fromSettings(s).api.books(50)
            return "OK – ${books.data.size} Buecher erreichbar"
        }
    }

    /**
     * Echte Wiki-URL einer Seite: {base}/books/{book-slug}/page/{page-slug}.
     * Beide Slugs kommen aus der API – nichts wird erraten.
     */
    suspend fun webUrlFor(page: PageDto): String {
        val bookSlug = api.books(50).data.firstOrNull { it.id == page.bookId }?.slug
            ?: return "$rootUrl/page/${page.id}" // Notfall-Link ohne Buchkontext
        return "$rootUrl/books/$bookSlug/page/${page.slug ?: page.id}"
    }

    /**
     * Stellt sicher, dass das Monatskapitel existiert (Idempotenz).
     * Kapitelnamen wie "2026-08" sind global nicht eindeutig – deshalb
     * wird per book_id gefiltert und im Filter-Ergebnis verglichen.
     */
    suspend fun ensureMonthlyChapter(bookId: Int, chapterName: String): ChapterDto {
        val existing = api.chapters(count = 100, bookId = bookId)
            .data.firstOrNull { it.name == chapterName }
        if (existing != null) return existing
        return api.createChapter(ChapterCreateRequest(bookId, chapterName))
    }

    /**
     * Seite anlegen oder updaten (Idempotenz ueber Seitennamen im Kapitel):
     * gleicher Name => PUT (neue Revision), sonst POST. Liefert (Seite, created?).
     */
    suspend fun upsertPage(
        chapterId: Int,
        name: String,
        html: String
    ): Pair<PageDto, Boolean> {
        val existing = api.pages(count = 10, nameEquals = name, chapterId = chapterId)
            .data.firstOrNull()
        return if (existing != null) {
            api.updatePage(existing.id, PageWriteRequest(chapterId, null, name, html)) to false
        } else {
            api.createPage(PageWriteRequest(chapterId, null, name, html)) to true
        }
    }

    /**
     * Original als Attachment an die Seite haengen.
     * Pitfall aus dem Bestandsskill: Multipart-Feld heisst zwingend "file",
     * Zielseite via Formfeld "uploaded_to".
     */
    suspend fun uploadAttachment(pageId: Int, fileName: String, content: String): AttachmentDto {
        val body = content.toByteArray(Charsets.UTF_8)
            .toRequestBody("text/markdown".toMediaType())
        val part = MultipartBody.Part.createFormData("file", fileName, body)
        val uploadedTo = pageId.toString().toPlainPart()
        val name = fileName.toPlainPart()
        return api.createAttachment(part, uploadedTo, name)
    }

    /** Binärsichere Variante fuer lokale Dateien (z. B. PDF-Lossless-Kopie). */
    suspend fun uploadAttachmentFile(pageId: Int, fileName: String, file: java.io.File): AttachmentDto {
        val mime = if (fileName.endsWith(".pdf", true)) "application/pdf" else "application/octet-stream"
        val body = file.asRequestBody(mime.toMediaType())
        val part = MultipartBody.Part.createFormData("file", fileName, body)
        val uploadedTo = pageId.toString().toPlainPart()
        val name = fileName.toPlainPart()
        return api.createAttachment(part, uploadedTo, name)
    }

    private fun String.toPlainPart() =
        toRequestBody("text/plain".toMediaType())
}
