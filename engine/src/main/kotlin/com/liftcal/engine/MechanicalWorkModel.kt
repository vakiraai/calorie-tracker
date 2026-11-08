package com.liftcal.engine

data class SetCost(
    val mechanicalWorkJ: Double,
    /** Total metabolic cost of the set itself (excluding EPOC), kcal. */
    val metabolicKcal: Double,
    val anaerobicFraction: Double,
) {
    val anaerobicKcal: Double get() = metabolicKcal * anaerobicFraction
    val aerobicKcal: Double get() = metabolicKcal * (1.0 - anaerobicFraction)
}

/**
 * Converts logged load x reps x range of motion into metabolic cost. This is the component
 * heart rate cannot see: short sets are fuelled mostly by phosphocreatine and glycolysis.
 */
class MechanicalWorkModel {

    fun cost(set: LoggedSet, profile: UserProfile, calib: Calibration): SetCost {
        val ex = ExerciseCatalog[set.exerciseId]
        val externalLoad = if (ex.loadIsPerHand) set.loadKg * 2 else set.loadKg
        val movedMass = externalLoad + ex.bodyMassFraction * profile.weightKg
        val romM = ex.romHeightFraction * profile.heightCm / 100.0
        val workJ = movedMass * G * romM * set.reps

        val concentricJ = workJ / ex.grossEfficiency
        val dynamicJ = concentricJ * (1.0 + ExerciseCatalog.ECCENTRIC_COST_FRACTION)
        val isometricJ = isometricKcalPerSec(ex.muscleMass) * set.durationSec * KJ_PER_KCAL * 1000.0
        val rpe = set.rpe ?: if (set.isWarmup) 5.0 else 8.0
        val effortFactor = (1.0 + 0.04 * (rpe - 8.0)).coerceIn(0.85, 1.10)
        val kcal = (dynamicJ + isometricJ) / (KJ_PER_KCAL * 1000.0) * effortFactor * calib.workCostScale

        return SetCost(workJ, kcal, anaerobicFraction(set.durationSec))
    }

    companion object {
        const val G = 9.81
        const val KJ_PER_KCAL = 4.184

        /** Short sets are predominantly anaerobic; longer sets shift toward oxidative supply. */
        fun anaerobicFraction(durationSec: Double): Double = (0.9 - 0.006 * durationSec).coerceIn(0.4, 0.85)

        /** Bracing and stabilisation cost while under load, independent of reps. */
        fun isometricKcalPerSec(m: MuscleMass): Double = when (m) {
            MuscleMass.LARGE -> 0.03
            MuscleMass.MEDIUM -> 0.02
            MuscleMass.SMALL -> 0.01
        }
    }
}
