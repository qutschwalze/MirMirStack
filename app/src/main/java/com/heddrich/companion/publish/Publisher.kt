package com.heddrich.companion.publish

import android.content.Context
import com.heddrich.companion.bookstack.BookStackClient
import com.heddrich.companion.data.IngestItem
import com.heddrich.companion.data.IngestStatus
import com.heddrich.companion.settings.SettingsStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Ergebnis eines Publish-Laufs (fuer UI/Notification). */
sealed interface PublishResult {
    data class Success(val wikiUrl: String, val created: Boolean) : PublishResult
    data class Failure(val reason: String) : PublishResult
}

/**
 * Publish-Logik (Phase 2, noch ohne LLM):
 * Rohinhalt als HTML-Seite in das Ziel-Buch legen – Monatskapitel sichern,
 * Update statt Duplikat, Original als Attachment.
 *
 * Bewusst deterministisch: Titel = "YYYY-MM-DD <Titel>" im Kapitel "YYYY-MM".
 */
object Publisher {

    const val PAGE_DATE_FORMAT = "yyyy-MM-dd"
    const val CHAPTER_FORMAT = "yyyy-MM"

    suspend fun publish(appContext: Context, item: IngestItem): PublishResult {
        val rawText = item.rawText.orEmpty()
        val html = "<p>" +
                escapeHtml(rawText.take(50_000)).replace("\n", "<br>") +
                "</p>"
        return publishHtml(appContext, item, html)
    }

    /**
     * Publiziert bereits gerendertes HTML (aus dem LLM-Pfad oder Rohtext-Fallback).
     */
    suspend fun publishHtml(appContext: Context, item: IngestItem, html: String): PublishResult {
        val settings = SettingsStore.Holder.get(appContext)
        if (!settings.isConfigured) {
            return PublishResult.Failure(
                "BookStack nicht konfiguriert (URL/Token fehlen) – erst in den Einstellungen setzen."
            )
        }
        if (item.rawText.isNullOrBlank()) {
            return PublishResult.Failure("Kein Inhalt zum Publizieren vorhanden.")
        }

        val client = try {
            BookStackClient.fromSettings(settings)
        } catch (e: IllegalArgumentException) {
            return PublishResult.Failure(e.message ?: "Ungültige Konfiguration")
        }

        return try {
            val now = Date()
            val chapterName = SimpleDateFormat(CHAPTER_FORMAT, Locale.US).format(now)

            // 1) Monatskapitel sichern
            val chapter = client.ensureMonthlyChapter(settings.targetBookId, chapterName)

            // 2) Seite updaten oder anlegen (gleicher Name => Revision)
            val pageTitle = "${SimpleDateFormat(PAGE_DATE_FORMAT, Locale.US).format(now)} ${item.title.orEmpty().trim()}".trim()
            val (page, created) = client.upsertPage(
                chapterId = chapter.id,
                name = pageTitle,
                html = html
            )

            // 3) Original als Attachment – bevorzugt die byte-genue Lossless-Kopie
            //    (PDF), sonst den Text aus der Outbox
            runCatching {
                val localPath = item.rawLocalPath
                val localFile = localPath?.let { java.io.File(it) }
                if (localFile != null && localFile.exists()) {
                    client.uploadAttachmentFile(page.id, attachmentName(item), localFile)
                } else {
                    client.uploadAttachment(page.id, attachmentName(item), item.rawText.orEmpty())
                }
            }

            PublishResult.Success(
                wikiUrl = client.webUrlFor(page),
                created = created
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Abbruch ist kein Publish-Fehler – WorkManager entscheidet ueber Retry.
            throw e
        } catch (e: Exception) {
            PublishResult.Failure(reasonText(e))
        }
    }

    internal fun attachmentName(item: IngestItem): String {
        val mime = item.mime.orEmpty()
        val ext = when {
            mime.contains("json") -> ".json"
            mime.contains("markdown") -> ".md"
            else -> ".txt"
        }
        val base = item.title?.take(40)?.replace(Regex("[^A-Za-z0-9._ -]"), "")?.trim()?.ifBlank { null }
        return ((base ?: "original") + ext).replace(' ', '_')
    }

    internal fun escapeHtml(text: String): String {
        val sb = StringBuilder(text.length + 64)
        for (c in text) when (c) {
            '<' -> sb.append("&lt;")
            '>' -> sb.append("&gt;")
            '&' -> sb.append("&amp;")
            '"' -> sb.append("&quot;")
            '\'' -> sb.append("&#39;")
            '\n' -> sb.append("<br>")
            else -> sb.append(c)
        }
        return "<p>$sb</p>"
    }

    private fun reasonText(e: Exception): String = when {
        e.message?.contains("Unable to resolve host", true) == true ->
            "Server nicht erreichbar (Netzwerk/DNS prüfen)"
        e.message?.contains("401", true) == true || e.message?.contains("Unauthorized", true) == true ->
            "Zugriff abgelehnt (401) – Token-ID/-Secret prüfen"
        e.message?.contains("404", true) == true ->
            "Endpoint nicht gefunden (404) – BookStack-URL prüfen"
        e.message?.contains("422", true) == true ->
            "BookStack lehnte die Anfrage ab (422) – Feldinhalte prüfen"
        else -> "Fehler: ${e.message ?: e.javaClass.simpleName}"
    }
}
