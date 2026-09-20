package com.example.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "job_state")
data class JobState(
    @PrimaryKey
    val id: Int = 1,

    val phase: String = "IDLE", // IDLE, PASS_1, PASS_2, COMPLETED, STOPPED, FAILED

    @ColumnInfo(name = "total_bytes")
    val totalBytes: Long = 0L,

    @ColumnInfo(name = "bytes_read")
    val bytesRead: Long = 0L,

    @ColumnInfo(name = "current_file")
    val currentFile: String = "",

    @ColumnInfo(name = "files_processed")
    val filesProcessed: Int = 0,

    @ColumnInfo(name = "dates_fixed")
    val datesFixed: Int = 0,

    @ColumnInfo(name = "gps_added")
    val gpsAdded: Int = 0,

    @ColumnInfo(name = "descriptions_added")
    val descriptionsAdded: Int = 0,

    @ColumnInfo(name = "files_without_json")
    val filesWithoutJson: Int = 0,

    @ColumnInfo(name = "files_skipped_or_failed")
    val filesSkippedOrFailed: Int = 0,

    @ColumnInfo(name = "error_message")
    val errorMessage: String? = null
)
