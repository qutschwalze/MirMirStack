package com.heddrich.companion.publish

import android.content.Context
import com.heddrich.companion.data.CompanionDatabase
import com.heddrich.companion.data.IngestStatus
import com.heddrich.companion.settings.SettingsStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Server-Verarbeitungsmodus: POST an das BookStack-Theme-Plugin
 * (/mirmirstack/ingest), das LLM + Seitenanlage asynchron uebernimmt.
 *
 * Die App bleibt dumm und gluecklich: Outbox -> ein HTTP-POST -> fertig.
 * Alle Robustheitsprobleme von Android (Doze, Prozess-Stop, Cancellation)
 * betreffen uns nicht mehr – der Server retryed bei Bedarf selbst.
 */
object ServerPublisher {

    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun endpoint(baseUrl: String): String {
        var u = baseUrl.trim().trimEnd('/')
        if (!u.startsWith("http")) u = "https://$u"
        return "$u/mirmirstack/ingest"
    }

    /**
     * Sendet den Inhalt an den Server. Wirft bei Fehlern eine Exception
     * mit verstaendlicher Meldung; Erfolg = HTTP 202.
     *
     * @return Wiki-URL oder null (der Server liefert sie erst nach dem
     *         asynchronen Durchlauf; der Nutzer sieht sie via Notification/
     *         Inbox-Refresh bzw. im Wiki).
     */
    suspend fun send(appContext: Context, itemId: Long): String? {
        val settings = SettingsStore.Holder.get(appContext)
        if (!settings.isIngestConfigured) {
            throw IllegalStateException(
                "Server-Verarbeitung nicht konfiguriert (URL/Token fehlen)"
            )
        }
        val dao = CompanionDatabase.get(appContext).ingestItemDao()
        val item = dao.getById(itemId)
            ?: throw IllegalStateException("Eintrag nicht gefunden")
        val text = item.rawText.orEmpty()
        if (text.isBlank()) throw IllegalStateException("Kein Inhalt zum Senden")

        val payload = buildJsonObject {
            put("text", text.take(300_000))
            put("template", item.templateId ?: "universal")
            put("title", item.title.orEmpty())
        }.toString()

        val request = Request.Builder()
            .url(endpoint(settings.ingestBaseUrl))
            .header("X-MirMir-Token", settings.ingestToken)
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            when {
                response.code == 202 -> return null
                response.code == 401 ->
                    throw IllegalStateException("Server lehnt Token ab (401) – Ingest-Token prüfen")
                response.code == 422 ->
                    throw IllegalStateException("Server lehnte Inhalt ab (422): ${body.take(120)}")
                else ->
                    throw IllegalStateException("Server-Fehler ${response.code}: ${body.take(150)}")
            }
        }
    }

    /** Optionaler Healthcheck fuer den Einstellungen-Testbutton. */
    fun testConnection(baseUrl: String, token: String): String {
        val request = Request.Builder()
            .url(endpoint(baseUrl))
            .header("X-MirMir-Token", token)
            .post("{\"text\":\"ping\",\"template\":\"universal\"}"
                .toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            return when (response.code) {
                202 -> "OK – Server nimmt Anfragen an (202)"
                401 -> "FEHLER 401 – Token wird abgelehnt"
                else -> "HTTP ${response.code} – Endpoint antwortet unerwartet"
            }
        }
    }
}
