package com.liftcal.engine

import kotlin.math.max

/** Approaches typical of consumer wearables, used as comparison baselines in validation. */
object Baselines {

    /**
     * Keytel et al. (2005) HR-only energy expenditure, the family of equations most wrist
     * devices build on. Returns total kcal over the session.
     */
    fun keytel(session: WorkoutSession, hr: List<HrSample>, profile: UserProfile): Double {
        val proc = HeartRateProcessor().process(hr, session.startMs, session.endMs, profile, Calibration(wristHrReliability = 1.0))
        val rest = RestingEnergy.kcalPerSecond(profile)
        var total = 0.0
        for (i in proc.bpm.indices) {
            val bpm = if (proc.isValid(i)) proc.bpm[i] else profile.effectiveRestingHr
            val kcalPerMin = if (profile.sex == Sex.MALE) {
                (-55.0969 + 0.6309 * bpm + 0.1988 * profile.weightKg + 0.2017 * profile.ageYears) / 4.184
            } else {
                (-20.4022 + 0.4472 * bpm - 0.1263 * profile.weightKg + 0.074 * profile.ageYears) / 4.184
            }
            total += max(rest, kcalPerMin / 60.0)
        }
        return total
    }

    /** Compendium of Physical Activities: resistance training, multiple exercises, 8-15 reps (5.0 METs). */
    fun metTable(session: WorkoutSession, profile: UserProfile, met: Double = 5.0): Double {
        val hours = (session.endMs - session.startMs) / 3_600_000.0
        return met * profile.weightKg * hours
    }
}
