package com.heddrich.companion.data

import androidx.room.TypeConverter

/** Lebenszyklus eines Ingest-Elements (Outbox-Muster). */
enum class IngestStatus { QUEUED, RUNNING, DONE, FAILED }

/** Room-Konverter fuer Enums (als Name-Strings in der DB). */
class Converters {
    @TypeConverter
    fun statusToString(value: IngestStatus): String = value.name

    @TypeConverter
    fun stringToStatus(value: String): IngestStatus = IngestStatus.valueOf(value)

    @TypeConverter
    fun sourceKindToString(value: com.heddrich.companion.share.SourceKind): String = value.name

    @TypeConverter
    fun stringToSourceKind(value: String): com.heddrich.companion.share.SourceKind =
        com.heddrich.companion.share.SourceKind.valueOf(value)
}
