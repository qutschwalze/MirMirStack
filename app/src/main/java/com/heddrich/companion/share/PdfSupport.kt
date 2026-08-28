package com.heddrich.companion.share

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File

/**
 * Datei-Unterstuetzung: PDF-Textextraktion (pdfbox-android, max. 80 Seiten)
 * und verlustfreie Kopie des Originals nach filesDir/originals.
 *
 * Die Kopie ist das Lossless-Rückgrat: Share-URI-Berechtigungen laufen ab,
 * die lokale Kopie bleibt dauerhaft verfügbar (Attachment-Upload, Sammler-Modus).
 */
object PdfSupport {

    private const val MAX_PAGES = 80

    /** Extrahiert Text aus einem PDF-Content-URI (gekappt auf maxChars). */
    fun extractText(context: Context, uri: Uri, maxChars: Int = 2_000_000): String? = try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            PDDocument.load(input).use { doc ->
                val stripper = PDFTextStripper()
                stripper.startPage = 1
                stripper.endPage = minOf(MAX_PAGES, doc.numberOfPages)
                val text = stripper.getText(doc)
                if (text.length > maxChars) text.take(maxChars) else text
            }
        }
    } catch (_: Exception) {
        null
    }

    /** Anzeigename des geteilten Inhalts (aus den Content-Metadaten). */
    fun displayNameOf(context: Context, uri: Uri): String? {
        var name: String? = null
        context.contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { c -> if (c.moveToFirst()) name = c.getString(0) }
        return name ?: uri.lastPathSegment
    }

    /**
     * Kopiert das Original byte-genau nach filesDir/originals/. Liefert den
     * Pfad oder null. Bei Namenskollision wird ein Zeitstempel angehaengt
     * (Sammler-Modus: nichts darf ueberschrieben werden).
     */
    fun copyOriginal(context: Context, uri: Uri): String? {
        return try {
            val dir = File(context.filesDir, "originals").apply { mkdirs() }
            val base = safeName(displayNameOf(context, uri) ?: "original")
            var target = File(dir, base)
            if (target.exists()) {
                val dot = base.lastIndexOf('.')
                val stem = if (dot > 0) base.substring(0, dot) else base
                val ext = if (dot > 0) base.substring(dot) else ""
                target = File(dir, "$stem-${System.currentTimeMillis()}$ext")
            }
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
            } ?: return null
            target.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    /** Dateiname-Kandidaten: Sonderzeichen raus, Leerzeichen zu Unterstrich. */
    fun safeName(raw: String): String =
        raw.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).trim('_')
}