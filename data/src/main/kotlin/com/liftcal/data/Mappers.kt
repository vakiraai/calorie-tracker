package com.liftcal.data

import com.liftcal.data.db.HrSampleEntity
import com.liftcal.data.db.ProfileEntity
import com.liftcal.data.db.SetEntity
import com.liftcal.data.db.WorkoutWithSets
import com.liftcal.engine.Calibration
import com.liftcal.engine.HrSample
import com.liftcal.engine.HrSource
import com.liftcal.engine.LoggedSet
import com.liftcal.engine.Sex
import com.liftcal.engine.UserProfile
import com.liftcal.engine.WorkoutSession

fun ProfileEntity.toUserProfile() = UserProfile(
    ageYears = ageYears,
    sex = Sex.valueOf(sex),
    weightKg = weightKg,
    heightCm = heightCm,
    bodyFatPct = bodyFatPct,
    restingHr = restingHr,
    maxHr = maxHr,
    vo2Max = vo2Max,
)

fun ProfileEntity.toCalibration() = Calibration(
    hrVo2Scale = hrVo2Scale,
    inSetHrWeight = inSetHrWeight,
    workCostScale = workCostScale,
    epocScale = epocScale,
    wristHrReliability = wristHrReliability,
    referenceSessions = referenceSessions,
)

fun ProfileEntity.withCalibration(c: Calibration) = copy(
    hrVo2Scale = c.hrVo2Scale,
    inSetHrWeight = c.inSetHrWeight,
    workCostScale = c.workCostScale,
    epocScale = c.epocScale,
    wristHrReliability = c.wristHrReliability,
    referenceSessions = c.referenceSessions,
)

fun SetEntity.toLoggedSet() = LoggedSet(exerciseId, loadKg, reps, startMs, endMs, rpe, isWarmup)

/** Returns null while the workout is still active (no end time). */
fun WorkoutWithSets.toSession(): WorkoutSession? {
    val end = workout.endMs ?: return null
    return WorkoutSession(workout.startMs, end, sets.map { it.toLoggedSet() })
}

fun HrSampleEntity.toHrSample() = HrSample(epochMs, bpm, HrSource.valueOf(source))

fun HrSample.toEntity(workoutId: Long) = HrSampleEntity(workoutId = workoutId, epochMs = epochMs, bpm = bpm, source = source.name)
