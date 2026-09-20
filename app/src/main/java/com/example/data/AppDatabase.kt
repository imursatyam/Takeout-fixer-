package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.model.JobState
import com.example.model.LogRecord
import com.example.model.ProcessedEntry
import com.example.model.TakeoutMetadata

@Database(
    entities = [
        TakeoutMetadata::class,
        ProcessedEntry::class,
        LogRecord::class,
        JobState::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun takeoutMetadataDao(): TakeoutMetadataDao
    abstract fun processedEntryDao(): ProcessedEntryDao
    abstract fun logRecordDao(): LogRecordDao
    abstract fun jobStateDao(): JobStateDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "takeout_restorer.db"
                ).fallbackToDestructiveMigration().build().also {
                    INSTANCE = it
                }
            }
        }
    }
}
