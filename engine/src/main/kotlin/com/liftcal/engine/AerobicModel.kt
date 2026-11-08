package com.liftcal.engine

/**
 * VO2-reserve method: %HRR ~= %VO2R (Swain & Leutholtz), with a personal resting VO2 derived
 * from RMR and VO2max from measurement or the Uth-Sorensen ratio.
 */
class AerobicModel(private val config: EngineConfig = EngineConfig()) {

    /** Net (above resting) VO2 in ml/kg/min for a given heart rate. */
    fun netVo2(bpm: Double, profile: UserProfile, calib: Calibration): Double {
        val rest = profile.effectiveRestingHr
        val max = profile.effectiveMaxHr
        if (max <= rest) return 0.0
        val hrr = ((bpm - rest) / (max - rest)).coerceIn(0.0, 1.1)
        val vo2Reserve = (profile.effectiveVo2Max - RestingEnergy.vo2RestMlKgMin(profile)).coerceAtLeast(5.0)
        return hrr * vo2Reserve * calib.hrVo2Scale
    }

    fun netKcalPerSecond(bpm: Double, profile: UserProfile, calib: Calibration): Double =
        vo2ToKcalPerSecond(netVo2(bpm, profile, calib), profile)

    fun vo2ToKcalPerSecond(vo2MlKgMin: Double, profile: UserProfile): Double =
        vo2MlKgMin * profile.weightKg / 1000.0 / 60.0 * config.kcalPerLiterO2Exercise
}
