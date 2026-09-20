package com.example.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "processed_entries")
data class ProcessedEntry(
    @PrimaryKey
    @ColumnInfo(name = "entry_path")
    val entryPath: String,

    val status: String, // "COMPLETED", "FAILED", "SKIPPED"

    val details: String? = null,

    @ColumnInfo(name = "processed_at")
    val processedAt: Long = System.currentTimeMillis()
)
