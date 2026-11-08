package com.liftcal.engine

object RestingEnergy {
    /** kcal per litre O2 at a resting RER of ~0.82. */
    const val KCAL_PER_L_O2_REST = 4.825

    /** Resting metabolic rate in kcal/day. Katch-McArdle when body fat is known, else Mifflin-St Jeor. */
    fun rmrKcalPerDay(p: UserProfile): Double {
        val bf = p.bodyFatPct
        return if (bf != null) {
            val leanMass = p.weightKg * (1.0 - bf / 100.0)
            370.0 + 21.6 * leanMass
        } else {
            val base = 10.0 * p.weightKg + 6.25 * p.heightCm - 5.0 * p.ageYears
            if (p.sex == Sex.MALE) base + 5.0 else base - 161.0
        }
    }

    fun kcalPerSecond(p: UserProfile): Double = rmrKcalPerDay(p) / 86_400.0

    /** Personal resting VO2 in ml/kg/min derived from RMR (more accurate than the 3.5 MET constant). */
    fun vo2RestMlKgMin(p: UserProfile): Double {
        val kcalPerMin = rmrKcalPerDay(p) / 1440.0
        return kcalPerMin / KCAL_PER_L_O2_REST * 1000.0 / p.weightKg
    }
}
