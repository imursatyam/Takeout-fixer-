package com.example.data

import com.example.model.JobState
import com.example.model.LogRecord
import com.example.model.TakeoutMetadata
import kotlinx.coroutines.flow.Flow

class AppRepository(private val database: AppDatabase) {

    val jobStateFlow: Flow<JobState?> = database.jobStateDao().getStateFlow()

    fun getRecentLogs(limit: Int = 200): Flow<List<LogRecord>> =
        database.logRecordDao().getRecentLogs(limit)

    suspend fun getJobState(): JobState? = database.jobStateDao().getState()

    suspend fun updateJobState(state: JobState) = database.jobStateDao().saveState(state)

    suspend fun insertLog(level: String, message: String) {
        database.logRecordDao().insert(
            LogRecord(
                timestamp = System.currentTimeMillis(),
                level = level,
                message = message
            )
        )
    }

    suspend fun getAllLogs(): List<LogRecord> = database.logRecordDao().getAllLogs()

    suspend fun insertMetadataBatch(batch: List<TakeoutMetadata>) {
        database.takeoutMetadataDao().insertBatch(batch)
    }

    suspend fun findMetadata(path: String): TakeoutMetadata? {
        return database.takeoutMetadataDao().findByPath(path)
    }

    suspend fun isEntryProcessed(path: String): Boolean {
        return database.processedEntryDao().isProcessed(path)
    }

    suspend fun markEntryProcessed(entryPath: String, status: String, details: String? = null) {
        database.processedEntryDao().insert(
            com.example.model.ProcessedEntry(
                entryPath = entryPath,
                status = status,
                details = details
            )
        )
    }

    suspend fun clearJobData() {
        database.takeoutMetadataDao().clearAll()
        database.processedEntryDao().clearAll()
        database.logRecordDao().clearAll()
        database.jobStateDao().clear()
    }
}
