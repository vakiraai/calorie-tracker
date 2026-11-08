package com.liftcal.engine

/** Population-level constants. Personal deviations are captured in [Calibration], not here. */
data class EngineConfig(
    /** Samples further than this apart are not interpolated across. */
    val maxHrGapSec: Int = 30,
    /** First-order heart-rate on/off kinetics time constant used for lag correction. */
    val hrLagTauSec: Double = 20.0,
    val maxLagCorrectionBpm: Double = 25.0,
    /** Samples deviating from their local median by more than this are treated as motion artefacts. */
    val artefactThresholdBpm: Double = 30.0,
    /** Chest-strap data must cover this fraction of the session to be preferred over wrist data. */
    val chestStrapMinCoverage: Double = 0.6,
    /** kcal per litre O2 at an exercise RER of ~0.96. */
    val kcalPerLiterO2Exercise: Double = 5.0,
    /** Net VO2 for moving around the gym between sets (re-racking, walking), ml/kg/min. */
    val betweenSetMovementVo2: Double = 1.0,
    /** EPOC oxygen debt (as kcal) per kcal of anaerobic work in a set. */
    val epocPerAnaerobicKcal: Double = 0.6,
    val epocFastFraction: Double = 0.7,
    val epocFastTauSec: Double = 45.0,
    val epocSlowTauSec: Double = 360.0,
    /** Coefficient of variation of HR-derived net VO2 during lifting at perfect signal quality. */
    val cvHrInSet: Double = 0.25,
    val cvHrRest: Double = 0.18,
    /** Coefficient of variation of the mechanical-work and EPOC models. */
    val cvModel: Double = 0.35,
    val cvAnaerobic: Double = 0.30,
    /** Fraction of per-second noise assumed correlated across a session (systematic). */
    val noiseCorrelation: Double = 0.5,
)
