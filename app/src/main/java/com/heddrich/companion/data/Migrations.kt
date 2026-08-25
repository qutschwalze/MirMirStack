package com.heddrich.companion.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migrationen fuer die Companion-Datenbank.
 */
object Migrations {
    /** v1 -> v2: templateId + summaryMd (Phase 3). */
    val V1_TO_V2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE ingest_items ADD COLUMN templateId TEXT")
            db.execSQL("ALTER TABLE ingest_items ADD COLUMN summaryMd TEXT")
        }
    }

    val ALL: Array<Migration> = arrayOf(V1_TO_V2)
}
