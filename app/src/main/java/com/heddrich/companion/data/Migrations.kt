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
     * "duplicate column name" crashen (Feldbefund 0.4.0: App starb nach
     * 2 s beim ersten DB-Zugriff). Daher guarded Adds fuer beide Spalten.
     */
    val V1_TO_V2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            if (!columnExists(db, "ingest_items", "templateId")) {
                db.execSQL("ALTER TABLE ingest_items ADD COLUMN templateId TEXT")
            }
            if (!columnExists(db, "ingest_items", "summaryMd")) {
                db.execSQL("ALTER TABLE ingest_items ADD COLUMN summaryMd TEXT")
            }
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

    val ALL: Array<Migration> = arrayOf(V1_TO_V2)
}
