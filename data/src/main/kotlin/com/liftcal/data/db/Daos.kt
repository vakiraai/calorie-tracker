package com.liftcal.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkoutDao {
    @Insert
    suspend fun insert(w: WorkoutEntity): Long

    @Update
    suspend fun update(w: WorkoutEntity)

    @Query("DELETE FROM workouts WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM workouts WHERE id = :id")
    suspend fun get(id: Long): WorkoutEntity?

    @Transaction
    @Query("SELECT * FROM workouts WHERE id = :id")
    suspend fun getWithSets(id: Long): WorkoutWithSets?

    @Transaction
    @Query("SELECT * FROM workouts WHERE id = :id")
    fun observeWithSets(id: Long): Flow<WorkoutWithSets?>

    @Transaction
    @Query("SELECT * FROM workouts ORDER BY startMs DESC")
    fun observeAll(): Flow<List<WorkoutWithSets>>

    @Query("SELECT * FROM workouts WHERE status = :status")
    suspend fun byStatus(status: WorkoutStatus): List<WorkoutEntity>

    @Query("SELECT * FROM workouts WHERE status = 'ACTIVE' ORDER BY startMs DESC LIMIT 1")
    fun observeActive(): Flow<WorkoutEntity?>

    @Insert
    suspend fun insertSet(s: SetEntity): Long

    @Update
    suspend fun updateSet(s: SetEntity)

    @Delete
    suspend fun deleteSet(s: SetEntity)
}

@Dao
interface HrSampleDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(samples: List<HrSampleEntity>)

    @Query("SELECT * FROM hr_samples WHERE workoutId = :workoutId ORDER BY epochMs")
    suspend fun forWorkout(workoutId: Long): List<HrSampleEntity>

    @Query("SELECT * FROM hr_samples WHERE workoutId = :workoutId AND source = :source ORDER BY epochMs")
    suspend fun forWorkout(workoutId: Long, source: String): List<HrSampleEntity>
}

@Dao
interface ReferenceWindowDao {
    @Insert
    suspend fun insertAll(w: List<ReferenceWindowEntity>)

    @Query("DELETE FROM reference_windows WHERE workoutId = :workoutId")
    suspend fun deleteForWorkout(workoutId: Long)

    @Query("SELECT * FROM reference_windows ORDER BY workoutId, startMs")
    suspend fun all(): List<ReferenceWindowEntity>

    @Query("SELECT DISTINCT workoutId FROM reference_windows")
    fun observeWorkoutIds(): Flow<List<Long>>
}

@Dao
interface ProfileDao {
    @Upsert
    suspend fun upsert(p: ProfileEntity)

    @Query("SELECT * FROM profile WHERE id = 1")
    suspend fun get(): ProfileEntity?

    @Query("SELECT * FROM profile WHERE id = 1")
    fun observe(): Flow<ProfileEntity?>
}
