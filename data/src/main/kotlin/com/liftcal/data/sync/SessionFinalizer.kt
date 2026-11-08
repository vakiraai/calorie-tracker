package com.liftcal.data.sync

import com.liftcal.data.db.LiftCalDatabase
import com.liftcal.data.db.WorkoutStatus
import com.liftcal.data.fitbit.FitbitApiClient
import com.liftcal.data.healthconnect.HealthConnectRepository
import com.liftcal.data.toCalibration
import com.liftcal.data.toEntity
import com.liftcal.data.toHrSample
import com.liftcal.data.toSession
import com.liftcal.data.toUserProfile
import com.liftcal.engine.CalorieEstimator
import com.liftcal.engine.CalorieResult
import com.liftcal.engine.HrSample
import com.liftcal.engine.HrSource
import com.liftcal.engine.WorkoutSession
import java.time.Instant
import java.time.ZoneId

sealed interface FinalizeOutcome {
    data object NotFound : FinalizeOutcome
    data object StillActive : FinalizeOutcome
    data object MissingProfile : FinalizeOutcome
    data class Computed(val status: WorkoutStatus, val result: CalorieResult) : FinalizeOutcome
}

/** Abstraction over HR sources so the finaliser is testable without Android services. */
interface HeartRateSources {
    suspend fun healthConnect(start: Instant, end: Instant): List<HrSample>?
    suspend fun fitbitIntraday(start: Instant, end: Instant): List<HrSample>?
    suspend fun fitbitCalories(start: Instant, end: Instant): Double?
    suspend fun writeToHealthConnect(workoutId: Long, session: WorkoutSession, result: CalorieResult): Boolean
}

class DefaultHeartRateSources(
    private val hc: HealthConnectRepository,
    private val fitbit: FitbitApiClient?,
    private val fitbitAuthorized: () -> Boolean,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) : HeartRateSources {
    override suspend fun healthConnect(start: Instant, end: Instant): List<HrSample>? =
        if (hc.isAvailable() && hc.hasReadAccess()) runCatching { hc.readHeartRate(start, end) }.getOrNull() else null

    override suspend fun fitbitIntraday(start: Instant, end: Instant): List<HrSample>? {
        val api = fitbit?.takeIf { fitbitAuthorized() } ?: return null
        return runCatching { api.intradayHeartRate(start, end, zone()) }.getOrNull()
    }

    override suspend fun fitbitCalories(start: Instant, end: Instant): Double? {
        val api = fitbit?.takeIf { fitbitAuthorized() } ?: return null
        return runCatching { api.intradayCalories(start, end, zone()) }.getOrNull()
    }

    override suspend fun writeToHealthConnect(workoutId: Long, session: WorkoutSession, result: CalorieResult): Boolean {
        if (!hc.isAvailable() || !hc.grantedPermissions().containsAll(hc.writePermissions)) return false
        return runCatching { hc.writeWorkout(workoutId, session, result, "Weight training") }.isSuccess
    }
}

/**
 * Gathers all heart-rate data for a workout, runs the engine and persists the result.
 * Safe to call repeatedly: a session stays PENDING_SYNC (with a provisional estimate)
 * until band data covers its end, then becomes FINALIZED and is written to Health Connect.
 */
class SessionFinalizer(
    private val db: LiftCalDatabase,
    private val sources: HeartRateSources,
    private val estimator: CalorieEstimator,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun finalize(workoutId: Long): FinalizeOutcome {
        val w = db.workouts().getWithSets(workoutId) ?: return FinalizeOutcome.NotFound
        val session = w.toSession() ?: return FinalizeOutcome.StillActive
        val profileEntity = db.profile().get() ?: return FinalizeOutcome.MissingProfile

        val start = Instant.ofEpochMilli(session.startMs - 60_000)
        val end = Instant.ofEpochMilli(session.endMs + 60_000)
        val stored = db.hrSamples().forWorkout(workoutId).map { it.toHrSample() }
        val storedWrist = stored.filter { it.source == HrSource.WRIST }

        var wrist = HrAlignment.merge(storedWrist, sources.healthConnect(start, end).orEmpty())
        if (HrAlignment.needsFallback(HrAlignment.coverage(wrist, session.startMs, session.endMs))) {
            sources.fitbitIntraday(start, end)?.let { fb -> wrist = HrAlignment.merge(wrist, fb) }
        }
        val newWrist = wrist.filter { s -> storedWrist.none { it.epochMs / 1000 == s.epochMs / 1000 } }
        if (newWrist.isNotEmpty()) db.hrSamples().insertAll(newWrist.map { it.toEntity(workoutId) })

        val merged = HrAlignment.merge(stored, wrist)
        val sync = HrAlignment.syncState(merged, session.startMs, session.endMs, clock())
        val status = if (sync == SyncState.WAITING) WorkoutStatus.PENDING_SYNC else WorkoutStatus.FINALIZED

        val result = estimator.estimate(
            session, HrAlignment.crop(merged, session.startMs, session.endMs),
            profileEntity.toUserProfile(), profileEntity.toCalibration(),
        )

        val fitbitKcal = if (status == WorkoutStatus.FINALIZED) {
            sources.fitbitCalories(Instant.ofEpochMilli(session.startMs), Instant.ofEpochMilli(session.endMs))
        } else null

        val written = if (status == WorkoutStatus.FINALIZED) {
            sources.writeToHealthConnect(workoutId, session, result)
        } else false

        db.workouts().update(
            w.workout.copy(
                status = status,
                totalKcal = result.totalKcal,
                activeKcal = result.activeKcal,
                restingKcal = result.restingKcal,
                low90Kcal = result.low90,
                high90Kcal = result.high90,
                epocKcal = result.epocKcal,
                hrQuality = result.hrQuality,
                hrSource = result.hrSourceUsed?.name,
                fitbitKcal = fitbitKcal ?: w.workout.fitbitKcal,
                computedAtMs = clock(),
                writtenToHealthConnect = w.workout.writtenToHealthConnect || written,
            ),
        )
        val ordered = w.sets.sortedBy { it.startMs }
        ordered.forEachIndexed { i, s ->
            db.workouts().updateSet(s.copy(attributedKcal = result.perSet.getOrNull(i)?.attributedActiveKcal))
        }
        return FinalizeOutcome.Computed(status, result)
    }

    suspend fun finalizePending(): Int {
        var n = 0
        for (w in db.workouts().byStatus(WorkoutStatus.PENDING_SYNC)) {
            if (finalize(w.id) is FinalizeOutcome.Computed) n++
        }
        return n
    }
}
