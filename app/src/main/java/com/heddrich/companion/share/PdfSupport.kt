package com.heddrich.companion.share

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File

/**
 * PDF-Unterstuetzung: Textextraktion (pdfbox-android, max. 80 Seiten) und
 * verlustfreie Kopie des Originals in filesDir/originals (Share-URIs laufen
 * ab – die Outbox-Kopie ist die dauerhafte Quelle fuer den Attachment-Upload).
 */
object PdfSupport {

    private const val MAX_PAGES = 80

    /** Extrahiert Text aus einem PDF-Content-URI (gekapped auf maxChars). */
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

    /** Kopiert das Original byte-genau nach filesDir/originals/. Liefert Pfad oder null. */
    fun copyOriginal(context: Context, uri: Uri): String? {
        return try {
            val dir = File(context.filesDir, "originals").apply { mkdirs() }
            val target = File(dir, safeName(context, uri))
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
            } ?: return null
            target.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    private fun safeName(context: Context, uri: Uri): String {
        var name: String? = null
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) name = c.getString(0) }
        val base = name ?: uri.lastPathSegment ?: "original"
        val cleaned = base.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(60).trim()
        return if (cleaned.endsWith(".pdf", true)) cleaned.replace(' ', '_')
        else "${cleaned.replace(' ', '_')}.pdf"
    }
}
