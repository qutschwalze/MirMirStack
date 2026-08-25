package com.heddrich.companion.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [IngestItem::class],
    version = 3,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class CompanionDatabase : RoomDatabase() {

    abstract fun ingestItemDao(): IngestItemDao

    companion object {
        @Volatile
        private var INSTANCE: CompanionDatabase? = null

        fun get(context: Context): CompanionDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    CompanionDatabase::class.java,
                    "companion.db"
                )
                    .addMigrations(*Migrations.ALL)
                    .build().also { INSTANCE = it }
            }

        /**
         * Fuer ViewModel-Kontext ohne direkten Activity-Zeiger (UI-only).
         * appContext wird von App.onCreate gesetzt, bevor irgendein UI
         * auf die Datenbank zugreift.
         */
        @JvmStatic
        fun getForVm(): CompanionDatabase {
            val app = appContext
                ?: error("CompanionDatabase.getForVm() vor App-Start aufgerufen")
            return get(app)
        }

        @Volatile
        var appContext: Context? = null
    }
}
