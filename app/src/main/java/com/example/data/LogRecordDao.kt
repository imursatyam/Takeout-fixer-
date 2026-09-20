package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.model.LogRecord
import kotlinx.coroutines.flow.Flow

@Dao
interface LogRecordDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: LogRecord)

    @Query("SELECT * FROM log_entries ORDER BY id DESC LIMIT :limit")
    fun getRecentLogs(limit: Int = 200): Flow<List<LogRecord>>

    @Query("SELECT * FROM log_entries ORDER BY id ASC")
    suspend fun getAllLogs(): List<LogRecord>

    @Query("DELETE FROM log_entries")
    suspend fun clearAll()
}
