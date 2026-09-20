package com.example.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "takeout_metadata")
data class TakeoutMetadata(
    /**
     * The full ZIP-relative path of the target media file
     * (e.g. "Takeout/Google Photos/Photos from 2026/IMG_1.jpg").
     */
    @PrimaryKey
    val filename: String,

    @ColumnInfo(name = "photo_taken_timestamp")
    val photoTakenTimestamp: Long = 0L,

    val description: String? = null,

    val latitude: Double? = null,

    val longitude: Double? = null,

    val altitude: Double? = null,

    @ColumnInfo(name = "photos_url")
    val photosUrl: String? = null
)
