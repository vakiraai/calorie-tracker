package com.liftcal.data.repo

import com.liftcal.data.db.HrSampleEntity
import com.liftcal.data.db.LiftCalDatabase
import com.liftcal.data.db.ProfileEntity
import com.liftcal.data.db.ReferenceWindowEntity
import com.liftcal.data.db.SetEntity
import com.liftcal.data.db.WorkoutEntity
import com.liftcal.data.db.WorkoutStatus
import com.liftcal.data.db.WorkoutWithSets
import com.liftcal.data.healthconnect.ProfileHints
import com.liftcal.data.toHrSample
import com.liftcal.data.toSession
import com.liftcal.data.toUserProfile
import com.liftcal.data.withCalibration
import com.liftcal.engine.Calibration
import com.liftcal.engine.CalibrationFit
import com.liftcal.engine.CalibrationSample
import com.liftcal.engine.Calibrator
import com.liftcal.engine.HrSource
import com.liftcal.engine.MetabolicCart
import com.liftcal.engine.ReferenceWindow
import kotlinx.coroutines.flow.Flow

class WorkoutRepository(private val db: LiftCalDatabase, private val clock: () -> Long = System::currentTimeMillis) {
    private val dao = db.workouts()

    fun observeAll(): Flow<List<WorkoutWithSets>> = dao.observeAll()
    fun observe(id: Long): Flow<WorkoutWithSets?> = dao.observeWithSets(id)
    fun observeActive(): Flow<WorkoutEntity?> = dao.observeActive()

    suspend fun start(): Long = dao.insert(WorkoutEntity(startMs = clock()))

    suspend fun addSet(set: SetEntity): Long = dao.insertSet(set)
    suspend fun updateSet(set: SetEntity) = dao.updateSet(set)
    suspend fun deleteSet(set: SetEntity) = dao.deleteSet(set)

    /** Ends the workout; calories are computed by the finaliser. */
    suspend fun end(id: Long) {
        val w = dao.get(id) ?: return
        val last = dao.getWithSets(id)?.sets?.maxOfOrNull { it.endMs } ?: w.startMs
        dao.update(w.copy(endMs = maxOf(clock(), last + 1000), status = WorkoutStatus.PENDING_SYNC))
    }

    suspend fun delete(id: Long) = dao.delete(id)

    suspend fun recordLiveHr(samples: List<HrSampleEntity>) = db.hrSamples().insertAll(samples)
}

class ProfileRepository(private val db: LiftCalDatabase) {
    fun observe(): Flow<ProfileEntity?> = db.profile().observe()
    suspend fun get(): ProfileEntity? = db.profile().get()
    suspend fun save(p: ProfileEntity) = db.profile().upsert(p)

    /** Fills only fields the user has not set, from Health Connect data shared by the Fitbit app. */
    fun applyHints(p: ProfileEntity, h: ProfileHints): ProfileEntity = p.copy(
        restingHr = p.restingHr ?: h.restingHr,
        vo2Max = p.vo2Max ?: h.vo2Max,
        bodyFatPct = p.bodyFatPct ?: h.bodyFatPct,
        weightKg = h.weightKg ?: p.weightKg,
        heightCm = if (p.heightCm > 0) p.heightCm else h.heightCm ?: p.heightCm,
    )
}

class CalibrationRepository(private val db: LiftCalDatabase) {

    fun observeReferenceWorkoutIds(): Flow<List<Long>> = db.referenceWindows().observeWorkoutIds()

    /** Stores 5-minute reference windows from a metabolic cart export aligned to the workout. */
    suspend fun importMetabolicCart(workoutId: Long, csv: String, cartStartMs: Long? = null): Int {
        val w = db.workouts().getWithSets(workoutId) ?: error("workout $workoutId not found")
        val session = w.toSession() ?: error("workout $workoutId is still active")
        val samples = MetabolicCart.parseCsv(csv, cartStartMs ?: session.startMs)
        val windows = MetabolicCart.windows(samples, session)
        require(windows.isNotEmpty()) { "metabolic cart data does not overlap the workout" }
        db.referenceWindows().deleteForWorkout(workoutId)
        db.referenceWindows().insertAll(windows.map { ReferenceWindowEntity(workoutId = workoutId, startMs = it.startMs, endMs = it.endMs, totalKcal = it.totalKcal) })
        return windows.size
    }

    /** Refits personal parameters on all reference sessions, starting from the prior. */
    suspend fun refit(calibrator: Calibrator = Calibrator()): CalibrationFit? {
        val profile = db.profile().get() ?: return null
        val byWorkout = db.referenceWindows().all().groupBy { it.workoutId }
        val samples = byWorkout.mapNotNull { (id, ws) ->
            val session = db.workouts().getWithSets(id)?.toSession() ?: return@mapNotNull null
            val hr = db.hrSamples().forWorkout(id).map { it.toHrSample() }
            CalibrationSample(session, hr, ws.map { ReferenceWindow(it.startMs, it.endMs, it.totalKcal) })
        }
        if (samples.isEmpty()) return null
        val start = Calibration.PRIOR.copy(wristHrReliability = profile.wristHrReliability)
        val fit = calibrator.fit(samples, profile.toUserProfile(), start)
        db.profile().upsert(profile.withCalibration(fit.calibration))
        return fit
    }

    /** Learns how far wrist HR can be trusted during lifting from a session with a chest strap. */
    suspend fun updateWristReliability(workoutId: Long): Double? {
        val w = db.workouts().getWithSets(workoutId)?.toSession() ?: return null
        val all = db.hrSamples().forWorkout(workoutId).map { it.toHrSample() }
        val r = Calibrator.wristReliability(
            all.filter { it.source == HrSource.WRIST }, all.filter { it.source == HrSource.CHEST_STRAP }, w.startMs, w.endMs,
        ) ?: return null
        val p = db.profile().get() ?: return null
        db.profile().upsert(p.copy(wristHrReliability = r))
        return r
    }
}
