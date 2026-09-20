package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.model.TakeoutMetadata

@Dao
interface TakeoutMetadataDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(metadata: TakeoutMetadata)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBatch(batch: List<TakeoutMetadata>)

    @Query("SELECT * FROM takeout_metadata WHERE filename = :path LIMIT 1")
    suspend fun findByPath(path: String): TakeoutMetadata?

    @Query("SELECT COUNT(*) FROM takeout_metadata")
    suspend fun count(): Int

    @Query("DELETE FROM takeout_metadata")
    suspend fun clearAll()

    @Transaction
    suspend fun replaceBatch(batch: List<TakeoutMetadata>) {
        insertBatch(batch)
    }
}
