package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.model.JobState
import kotlinx.coroutines.flow.Flow

@Dao
interface JobStateDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveState(state: JobState)

    @Query("SELECT * FROM job_state WHERE id = 1 LIMIT 1")
    suspend fun getState(): JobState?

    @Query("SELECT * FROM job_state WHERE id = 1 LIMIT 1")
    fun getStateFlow(): Flow<JobState?>

    @Query("DELETE FROM job_state")
    suspend fun clear()
}
