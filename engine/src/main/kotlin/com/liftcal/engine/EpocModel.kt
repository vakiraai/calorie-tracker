package com.liftcal.engine

import kotlin.math.exp

/**
 * Bi-exponential oxygen-debt repayment. Each set contributes a debt sized by its anaerobic
 * cost, repaid with a fast (PCr resynthesis) and slow (lactate clearance, thermogenesis) component.
 */
class EpocModel(private val config: EngineConfig = EngineConfig()) {

    fun debtKcal(anaerobicKcal: Double, calib: Calibration): Double =
        anaerobicKcal * config.epocPerAnaerobicKcal * calib.epocScale

    /** Repayment rate (kcal/s) at [tSec] seconds after the set ended. */
    fun rate(debtKcal: Double, tSec: Double): Double {
        if (tSec < 0) return 0.0
        val f = config.epocFastFraction
        return debtKcal * (
            f / config.epocFastTauSec * exp(-tSec / config.epocFastTauSec) +
                (1 - f) / config.epocSlowTauSec * exp(-tSec / config.epocSlowTauSec)
            )
    }

    /** Debt remaining (kcal) after [tSec] seconds. */
    fun remaining(debtKcal: Double, tSec: Double): Double {
        if (tSec <= 0) return debtKcal
        val f = config.epocFastFraction
        return debtKcal * (f * exp(-tSec / config.epocFastTauSec) + (1 - f) * exp(-tSec / config.epocSlowTauSec))
    }
}
