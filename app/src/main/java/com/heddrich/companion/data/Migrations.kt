package com.heddrich.companion.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migrationen fuer die Companion-Datenbank.
 */
object Migrations {

    /**
     * v1 -> v2: summaryMd neu; templateId war laut Historie bereits im
     * v1-Schema enthalten (seit 0.2.0) – ein blindes ADD COLUMN wuerde mit
     * "duplicate column name" crashen (Feldbefund 0.4.0). Daher guarded Adds.
     */
    val V1_TO_V2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            addColumnIfMissing(db, "ingest_items", "templateId", "TEXT")
            addColumnIfMissing(db, "ingest_items", "summaryMd", "TEXT")
        }
    }

    /** v2 -> v3: lokaler Pfad des Originals (PDF-Lossless-Kopie, Phase 0.4.3). */
    val V2_TO_V3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            addColumnIfMissing(db, "ingest_items", "rawLocalPath", "TEXT")
        }
    }

    fun addColumnIfMissing(db: SupportSQLiteDatabase, table: String, column: String, type: String) {
        if (!columnExists(db, table, column)) {
            db.execSQL("ALTER TABLE $table ADD COLUMN $column $type")
        }
    }

    private fun columnExists(db: SupportSQLiteDatabase, table: String, column: String): Boolean =
        db.query("PRAGMA table_info($table)").use { cursor ->
            val nameIdx = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIdx) == column) return true
            }
            false
        }

    val ALL: Array<Migration> = arrayOf(V1_TO_V2, V2_TO_V3)
}
