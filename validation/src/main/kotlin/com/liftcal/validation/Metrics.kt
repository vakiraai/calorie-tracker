package com.liftcal.validation

import kotlin.math.abs
import kotlin.math.sqrt

data class AgreementStats(
    val n: Int,
    /** Mean absolute percentage error, %. */
    val mape: Double,
    /** Mean signed percentage error, % (positive = overestimates). */
    val biasPct: Double,
    /** Bland-Altman mean difference (estimate - reference), kcal. */
    val meanDiff: Double,
    /** Bland-Altman 95% limits of agreement, kcal. */
    val loaLow: Double,
    val loaHigh: Double,
    /** Lin's concordance correlation coefficient. */
    val ccc: Double,
)

object Metrics {
    fun agreement(estimates: List<Double>, references: List<Double>): AgreementStats {
        require(estimates.size == references.size && estimates.isNotEmpty())
        val n = estimates.size
        val pct = estimates.zip(references) { e, r -> (e - r) / r * 100 }
        val diffs = estimates.zip(references) { e, r -> e - r }
        val md = diffs.average()
        val sd = if (n > 1) sqrt(diffs.sumOf { (it - md) * (it - md) } / (n - 1)) else 0.0
        return AgreementStats(
            n = n,
            mape = pct.map { abs(it) }.average(),
            biasPct = pct.average(),
            meanDiff = md,
            loaLow = md - 1.96 * sd,
            loaHigh = md + 1.96 * sd,
            ccc = ccc(estimates, references),
        )
    }

    fun ccc(x: List<Double>, y: List<Double>): Double {
        val n = x.size
        if (n < 2) return Double.NaN
        val mx = x.average()
        val my = y.average()
        val vx = x.sumOf { (it - mx) * (it - mx) } / n
        val vy = y.sumOf { (it - my) * (it - my) } / n
        val cov = x.indices.sumOf { (x[it] - mx) * (y[it] - my) } / n
        val denom = vx + vy + (mx - my) * (mx - my)
        return if (denom == 0.0) 1.0 else 2 * cov / denom
    }
}
