package com.liftcal.engine

import kotlin.math.max
import kotlin.math.sqrt

data class Fused(val value: Double, val sigma: Double)

/**
 * Inverse-variance (static Kalman) combination of an HR-derived estimate and a model-derived
 * estimate, using relative coefficients of variation so weights are scale free.
 */
object Fusion {

    fun inverseVariance(hr: Double?, cvHr: Double, quality: Double, model: Double, cvModel: Double): Fused {
        if (hr == null || quality <= 0.0) return Fused(model, model * cvModel)
        val wHr = quality / (cvHr * cvHr)
        val wModel = 1.0 / (cvModel * cvModel)
        val value = (wHr * hr + wModel * model) / (wHr + wModel)
        val relSigma = sqrt(1.0 / (wHr + wModel))
        return Fused(value, value * relSigma)
    }

    /**
     * Smooth approximation of max(a, b): within ~5% of max, differentiable everywhere.
     * Used during rest where both measured recovery and predicted EPOC are lower bounds.
     */
    fun smoothMax(a: Double, b: Double): Double {
        val eps = 0.1 * (a + b) / 2.0
        return (a + b + sqrt((a - b) * (a - b) + eps * eps)) / 2.0
    }

    fun recovery(hr: Double?, cvHr: Double, quality: Double, model: Double, cvModel: Double): Fused {
        if (hr == null || quality <= 0.0) return Fused(model, model * cvModel)
        val combined = smoothMax(hr, model)
        val value = quality * combined + (1.0 - quality) * model
        val cv = quality * cvHr + (1.0 - quality) * cvModel
        return Fused(value, max(value, 0.0) * cv)
    }
}
