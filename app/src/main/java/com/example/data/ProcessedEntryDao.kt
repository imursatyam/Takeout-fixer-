package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.model.ProcessedEntry

@Dao
interface ProcessedEntryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: ProcessedEntry)

    @Query("SELECT COUNT(*) > 0 FROM processed_entries WHERE entry_path = :path AND status = 'COMPLETED'")
    suspend fun isProcessed(path: String): Boolean

    @Query("SELECT * FROM processed_entries WHERE entry_path = :path LIMIT 1")
    suspend fun findByPath(path: String): ProcessedEntry?

    @Query("SELECT COUNT(*) FROM processed_entries WHERE status = 'COMPLETED'")
    suspend fun countCompleted(): Int

    @Query("DELETE FROM processed_entries")
    suspend fun clearAll()
}
