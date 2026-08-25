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
}
