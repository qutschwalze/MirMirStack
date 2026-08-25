package com.heddrich.companion.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.heddrich.companion.share.SourceKind

/**
 * Ein Element in der Outbox: wird sofort beim Teilen persistiert
 * (lossless), bevor irgendetwas anderes passiert.
 */
@Entity(tableName = "ingest_items")
data class IngestItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long,
    /** Referrer-Paket oder Content-URI-Authority der Quell-App (Provenienz). */
    val sourcePkg: String?,
    val sourceKind: SourceKind,
    /** Vorlage beim Absenden gewaehlt (Phase 3/4 verarbeiten sie). */
    val templateId: String?,
    /** Vom User editierbarer bzw. automatisch vorgeschlagener Titel. */
    val title: String?,
    /** Vollstaendiger Inhalt (geteilter Text ODER decodierte Datei, capped 2 MB). */
    val rawText: String?,
    /** Originale Content-URI als Referenz (Inhalt liegt bereits in rawText). */
    val rawUri: String?,
    val mime: String?,
    val status: IngestStatus,
    val error: String?,
    /** Wiki-URL nach erfolgreichem Publish (Phase 2). */
    val resultUrl: String?,
    /** Markdown-Zusammenfassung aus dem LLM-Pfad (Phase 3). */
    val summaryMd: String? = null
)
