package com.liftcal.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import com.liftcal.engine.Calibration

enum class WorkoutStatus {
    /** User is still logging sets. */
    ACTIVE,

    /** Workout ended but the band's heart rate for the full session has not synced yet. */
    PENDING_SYNC,

    /** Calories computed from complete data. */
    FINALIZED,
}

@Entity(tableName = "workouts")
data class WorkoutEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startMs: Long,
    val endMs: Long? = null,
    val status: WorkoutStatus = WorkoutStatus.ACTIVE,
    val totalKcal: Double? = null,
    val activeKcal: Double? = null,
    val restingKcal: Double? = null,
    val low90Kcal: Double? = null,
    val high90Kcal: Double? = null,
    val epocKcal: Double? = null,
    val hrQuality: Double? = null,
    val hrSource: String? = null,
    /** Fitbit's own calorie figure for the same window, kept for comparison. */
    val fitbitKcal: Double? = null,
    val computedAtMs: Long? = null,
    val writtenToHealthConnect: Boolean = false,
)

@Entity(
    tableName = "sets",
    foreignKeys = [ForeignKey(WorkoutEntity::class, ["id"], ["workoutId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("workoutId")],
)
data class SetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workoutId: Long,
    val exerciseId: String,
    val loadKg: Double,
    val reps: Int,
    val startMs: Long,
    val endMs: Long,
    val rpe: Double? = null,
    val isWarmup: Boolean = false,
    /** Per-set active kcal from the last computation, for display. */
    val attributedKcal: Double? = null,
)

/** Heart-rate samples captured live (BLE chest strap) or cached from Health Connect / Fitbit. */
@Entity(
    tableName = "hr_samples",
    foreignKeys = [ForeignKey(WorkoutEntity::class, ["id"], ["workoutId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["workoutId", "source", "epochMs"], unique = true)],
)
data class HrSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workoutId: Long,
    val epochMs: Long,
    val bpm: Double,
    /** Matches [com.liftcal.engine.HrSource] names. */
    val source: String,
)

@Entity(
    tableName = "reference_windows",
    foreignKeys = [ForeignKey(WorkoutEntity::class, ["id"], ["workoutId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("workoutId")],
)
data class ReferenceWindowEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workoutId: Long,
    val startMs: Long,
    val endMs: Long,
    val totalKcal: Double,
)

@Entity(tableName = "profile")
data class ProfileEntity(
    @PrimaryKey val id: Int = 1,
    val ageYears: Int,
    val sex: String,
    val weightKg: Double,
    val heightCm: Double,
    val bodyFatPct: Double? = null,
    val restingHr: Double? = null,
    val maxHr: Double? = null,
    val vo2Max: Double? = null,
    val hrVo2Scale: Double = Calibration.PRIOR.hrVo2Scale,
    val inSetHrWeight: Double = Calibration.PRIOR.inSetHrWeight,
    val workCostScale: Double = Calibration.PRIOR.workCostScale,
    val epocScale: Double = Calibration.PRIOR.epocScale,
    val wristHrReliability: Double = Calibration.PRIOR.wristHrReliability,
    val referenceSessions: Int = 0,
)

data class WorkoutWithSets(
    @Embedded val workout: WorkoutEntity,
    @Relation(parentColumn = "id", entityColumn = "workoutId") val sets: List<SetEntity>,
)
