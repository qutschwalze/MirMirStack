package com.heddrich.companion.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface IngestItemDao {

    @Insert
    suspend fun insert(item: IngestItem): Long

    @Update
    suspend fun update(item: IngestItem)

    @Query("SELECT * FROM ingest_items ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<IngestItem>>

    @Query("SELECT * FROM ingest_items WHERE id = :id")
    suspend fun getById(id: Long): IngestItem?

    @Query("DELETE FROM ingest_items WHERE id = :id")
    suspend fun delete(id: Long): Int

    @Query("DELETE FROM ingest_items WHERE id IN (:ids)")
    suspend fun deleteAll(ids: List<Long>): Int

    /**
     * Hängengebliebene Aufträge: eingereicht (templateId gesetzt), aber nie
     * fertig geworden – inklusive RUNNING-Limbo nach Prozess-Abbruch.
     */
    @Query(
        "SELECT * FROM ingest_items " +
                "WHERE templateId IS NOT NULL AND status IN ('QUEUED', 'RUNNING')"
    )
    suspend fun submittedNotDone(): List<IngestItem>
}
