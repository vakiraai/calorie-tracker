package com.liftcal.engine

import kotlin.math.ceil

enum class Sex { MALE, FEMALE }

/**
 * Physiological profile. Optional fields fall back to population estimates; each one
 * supplied (especially measured [maxHr] and [vo2Max]) narrows the uncertainty interval.
 */
data class UserProfile(
    val ageYears: Int,
    val sex: Sex,
    val weightKg: Double,
    val heightCm: Double,
    val bodyFatPct: Double? = null,
    val restingHr: Double? = null,
    val maxHr: Double? = null,
    /** ml/kg/min. */
    val vo2Max: Double? = null,
) {
    init {
        require(ageYears in 10..100) { "ageYears out of range: $ageYears" }
        require(weightKg in 25.0..300.0) { "weightKg out of range: $weightKg" }
        require(heightCm in 100.0..250.0) { "heightCm out of range: $heightCm" }
        bodyFatPct?.let { require(it in 2.0..70.0) { "bodyFatPct out of range: $it" } }
    }

    val effectiveMaxHr: Double get() = maxHr ?: (208.0 - 0.7 * ageYears)
    val effectiveRestingHr: Double get() = restingHr ?: DEFAULT_RESTING_HR

    /** Uth-Sørensen estimate (15.3 * HRmax / HRrest) when no measured VO2max exists. */
    val effectiveVo2Max: Double get() = vo2Max ?: (15.3 * effectiveMaxHr / effectiveRestingHr).coerceIn(20.0, 75.0)

    val hasMeasuredMaxHr: Boolean get() = maxHr != null
    val hasMeasuredVo2Max: Boolean get() = vo2Max != null

    companion object {
        const val DEFAULT_RESTING_HR = 65.0
    }
}

enum class HrSource { WRIST, CHEST_STRAP }

data class HrSample(val epochMs: Long, val bpm: Double, val source: HrSource = HrSource.WRIST)

data class LoggedSet(
    val exerciseId: String,
    val loadKg: Double,
    val reps: Int,
    val startMs: Long,
    val endMs: Long,
    /** Rating of perceived exertion, 1-10. Null means unknown (treated as 8). */
    val rpe: Double? = null,
    val isWarmup: Boolean = false,
) {
    init {
        require(endMs > startMs) { "set must have positive duration" }
        require(reps >= 0) { "reps must be non-negative" }
        require(loadKg >= 0.0) { "loadKg must be non-negative" }
    }

    val durationSec: Double get() = (endMs - startMs) / 1000.0
}

data class WorkoutSession(
    val startMs: Long,
    val endMs: Long,
    val sets: List<LoggedSet>,
) {
    init {
        require(endMs > startMs) { "session must have positive duration" }
    }

    val sortedSets: List<LoggedSet> by lazy { sets.sortedBy { it.startMs } }
}

/**
 * Personal model parameters, fitted against reference data by [Calibrator].
 * Defaults are population priors.
 */
data class Calibration(
    /** Multiplier on net (above-rest) VO2 predicted from heart-rate reserve. */
    val hrVo2Scale: Double = 1.0,
    /** Weight on heart-rate-derived VO2 inside sets, where HR is inflated by the pressor response. */
    val inSetHrWeight: Double = 0.7,
    /** Multiplier on the metabolic cost of mechanical work (inverse of efficiency deviation). */
    val workCostScale: Double = 1.0,
    /** Multiplier on predicted EPOC magnitude. */
    val epocScale: Double = 1.0,
    /** 0..1 confidence in wrist HR during lifting, learned from chest-strap comparison. */
    val wristHrReliability: Double = 0.8,
    /** Number of reference sessions used to fit the parameters above. */
    val referenceSessions: Int = 0,
) {
    companion object {
        val PRIOR = Calibration()
    }
}

enum class SegmentType { SET, WARMUP, REST }

data class SetBreakdown(
    val index: Int,
    val exerciseId: String,
    val mechanicalWorkJ: Double,
    val anaerobicKcal: Double,
    val aerobicKcal: Double,
    /** Active kcal from the set start until the next set starts (or session end), including EPOC. */
    val attributedActiveKcal: Double,
    val epocKcal: Double,
)

data class CalorieResult(
    val totalKcal: Double,
    val activeKcal: Double,
    val restingKcal: Double,
    val low90: Double,
    val high90: Double,
    val perSet: List<SetBreakdown>,
    /** EPOC in kcal, including the post-session tail. */
    val epocKcal: Double,
    /** Portion of [epocKcal] that occurs after the session ends (included in totals). */
    val postSessionEpocKcal: Double,
    /** 0..1 heart-rate signal quality over the session. */
    val hrQuality: Double,
    val hrSourceUsed: HrSource?,
    /** Per-second total (resting + active) kcal over the session, starting at session start. */
    val timeline: DoubleArray,
) {
    fun totalBetween(sessionStartMs: Long, fromMs: Long, toMs: Long): Double {
        val a = ((fromMs - sessionStartMs) / 1000).toInt().coerceIn(0, timeline.size)
        val b = ceil((toMs - sessionStartMs) / 1000.0).toInt().coerceIn(0, timeline.size)
        var s = 0.0
        for (i in a until b) s += timeline[i]
        return s
    }

    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

interface CalorieEstimator {
    fun estimate(
        session: WorkoutSession,
        hr: List<HrSample>,
        profile: UserProfile,
        calib: Calibration = Calibration.PRIOR,
    ): CalorieResult
}
