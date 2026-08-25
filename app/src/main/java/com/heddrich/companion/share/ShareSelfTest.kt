package com.heddrich.companion.share

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.heddrich.companion.data.CompanionDatabase

/**
 * Empfangs-Selbsttest (v0.2.2): durchlaeuft denselben Codepfad wie ein echter
 * Share – Intent bauen -> extractFromIntent -> detect -> insert – und loescht
 * das Testelement danach wieder. Liefert eine Ein-Zeilen-Diagnose.
 */
object ShareSelfTest {

    suspend fun run(appContext: Context): String = try {
        // 1) Intent wie beim Teilen einer Datei aus einem FileProvider-Kontext
        val testUri = Uri.parse("content://de.heddrich.selftest/test.txt")
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/markdown"
            putExtra(Intent.EXTRA_TEXT, "Selbsttest-Zeile ${System.currentTimeMillis()}")
            putExtra(Intent.EXTRA_STREAM, testUri)
            // Referrer-Pfad separat geprueft; hier ohne echten Referrer
        }

        val extracted = extractFromIntent(appContext.contentResolver, intent)

        // 2) Detektion (Referrer-frei => Heuristik)
        val kind = SourceDetector.detect(null, extracted.preview?.take(600))

        // 3) Persistenz-Roundtrip
        val dao = CompanionDatabase.get(appContext).ingestItemDao()
        val id = dao.insert(
            com.heddrich.companion.data.IngestItem(
                createdAt = System.currentTimeMillis(),
                sourcePkg = "selftest",
                sourceKind = kind,
                templateId = null,
                title = "Selbsttest ${System.currentTimeMillis()}",
                rawText = extracted.preview,
                rawUri = extracted.rawUri,
                mime = extracted.mime,
                status = com.heddrich.companion.data.IngestStatus.DONE,
                error = null,
                resultUrl = null
            )
        )
        val roundtrip = dao.getById(id)
        dao.delete(id)

        when {
            roundtrip == null -> "FEHLER: Element nach Insert nicht lesbar"
            roundtrip.rawText != extracted.preview -> "FEHLER: Roundtrip-Inhalt weicht ab"
            else -> "OK: Empfangspfad funktioniert (Text=${extracted.preview != null}, " +
                    "Mime=${extracted.mime}, Quelle=$kind, DB-Insert/Read/Delete ok)"
        }
    } catch (t: Throwable) {
        "FEHLER: ${t.javaClass.simpleName}: ${t.message} | " +
                t.stackTraceToString().lineSequence().firstOrNull().orEmpty()
    }
}
