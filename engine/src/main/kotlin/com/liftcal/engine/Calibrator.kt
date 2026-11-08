package com.liftcal.engine

import kotlin.math.abs
import kotlin.math.sqrt

/** Reference total energy expenditure (e.g. from indirect calorimetry) over a time window. */
data class ReferenceWindow(val startMs: Long, val endMs: Long, val totalKcal: Double)

data class CalibrationSample(
    val session: WorkoutSession,
    val hr: List<HrSample>,
    val windows: List<ReferenceWindow>,
)

data class CalibrationFit(
    val calibration: Calibration,
    /** RMS relative error over reference windows before and after fitting. */
    val rmsRelErrorBefore: Double,
    val rmsRelErrorAfter: Double,
    val iterations: Int,
)

/**
 * Fits personal parameters by regularised (MAP) least squares on relative window errors,
 * shrinking toward population priors so a single noisy session cannot overfit.
 * Solved with Levenberg-Marquardt using a numerical Jacobian.
 */
class Calibrator(
    private val estimator: CalorieEstimator = HybridCalorieEstimator(),
) {
    private data class Param(val prior: Double, val sigma: Double, val lo: Double, val hi: Double)

    private val params = listOf(
        Param(Calibration.PRIOR.hrVo2Scale, 0.20, 0.5, 1.8),
        Param(Calibration.PRIOR.inSetHrWeight, 0.15, 0.3, 1.0),
        Param(Calibration.PRIOR.workCostScale, 0.30, 0.4, 2.5),
        Param(Calibration.PRIOR.epocScale, 0.40, 0.2, 3.0),
    )

    fun fit(
        samples: List<CalibrationSample>,
        profile: UserProfile,
        start: Calibration = Calibration.PRIOR,
        maxIterations: Int = 40,
    ): CalibrationFit {
        require(samples.isNotEmpty()) { "need at least one reference session" }
        val windowCount = samples.sumOf { it.windows.size }
        require(windowCount > 0) { "reference sessions contain no windows" }

        var theta = doubleArrayOf(start.hrVo2Scale, start.inSetHrWeight, start.workCostScale, start.epocScale)
        fun calibOf(t: DoubleArray) = start.copy(
            hrVo2Scale = t[0], inSetHrWeight = t[1], workCostScale = t[2], epocScale = t[3],
        )

        fun dataResiduals(t: DoubleArray): DoubleArray {
            val c = calibOf(t)
            val out = ArrayList<Double>(windowCount)
            for (s in samples) {
                val r = estimator.estimate(s.session, s.hr, profile, c)
                for (w in s.windows) {
                    val pred = r.totalBetween(s.session.startMs, w.startMs, w.endMs)
                    out += (pred - w.totalKcal) / w.totalKcal
                }
            }
            return out.toDoubleArray()
        }

        // Priors are scaled so their weight is comparable to one window's relative error budget (~10%).
        fun residuals(t: DoubleArray): DoubleArray {
            val d = dataResiduals(t)
            val p = DoubleArray(params.size) { k -> 0.1 * (t[k] - params[k].prior) / params[k].sigma }
            return d + p
        }

        fun cost(r: DoubleArray) = r.sumOf { it * it }
        fun rms(r: DoubleArray) = sqrt(r.sumOf { it * it } / r.size)

        val before = rms(dataResiduals(theta))
        var r = residuals(theta)
        var c = cost(r)
        var lambda = 1e-2
        var iter = 0
        while (iter < maxIterations) {
            iter++
            val jac = jacobian(theta, r, ::residuals)
            val m = theta.size
            val jtj = Array(m) { a -> DoubleArray(m) { b -> r.indices.sumOf { jac[it][a] * jac[it][b] } } }
            val jtr = DoubleArray(m) { a -> r.indices.sumOf { jac[it][a] * r[it] } }
            var improved = false
            while (lambda < 1e6) {
                val aMat = Array(m) { a -> DoubleArray(m) { b -> jtj[a][b] + if (a == b) lambda * (jtj[a][a] + 1e-9) else 0.0 } }
                val step = solve(aMat, jtr.map { -it }.toDoubleArray()) ?: break
                val cand = DoubleArray(m) { k -> (theta[k] + step[k]).coerceIn(params[k].lo, params[k].hi) }
                val rc = residuals(cand)
                val cc = cost(rc)
                if (cc < c) {
                    val delta = c - cc
                    theta = cand; r = rc; c = cc
                    lambda = maxOf(lambda / 3, 1e-7)
                    improved = true
                    if (delta < 1e-10) iter = maxIterations
                    break
                }
                lambda *= 4
            }
            if (!improved) break
        }

        val after = rms(dataResiduals(theta))
        val fitted = calibOf(theta).copy(referenceSessions = start.referenceSessions + samples.size)
        return CalibrationFit(fitted, before, after, iter)
    }

    private fun jacobian(t: DoubleArray, r0: DoubleArray, f: (DoubleArray) -> DoubleArray): Array<DoubleArray> {
        val jac = Array(r0.size) { DoubleArray(t.size) }
        for (k in t.indices) {
            val h = 1e-4 * maxOf(1.0, abs(t[k]))
            val tp = t.copyOf().also { it[k] += h }
            val rp = f(tp)
            for (i in r0.indices) jac[i][k] = (rp[i] - r0[i]) / h
        }
        return jac
    }

    companion object {
        /**
         * Wrist-HR reliability from a session recorded with both the band and a chest strap.
         * Maps mean absolute error to 0.2..1.0 (0 bpm -> 1.0, >= 16 bpm -> 0.2).
         */
        fun wristReliability(wrist: List<HrSample>, chest: List<HrSample>, startMs: Long, endMs: Long): Double? {
            val profile = UserProfile(30, Sex.MALE, 75.0, 175.0)
            val proc = HeartRateProcessor()
            val calib = Calibration(wristHrReliability = 1.0)
            val w = proc.process(wrist.map { it.copy(source = HrSource.WRIST) }, startMs, endMs, profile, calib)
            val c = proc.process(chest.map { it.copy(source = HrSource.CHEST_STRAP) }, startMs, endMs, profile, calib)
            var sum = 0.0
            var cnt = 0
            for (i in w.bpm.indices) {
                if (w.isValid(i) && c.isValid(i)) {
                    sum += abs(w.bpm[i] - c.bpm[i]); cnt++
                }
            }
            if (cnt < 60) return null
            val mae = sum / cnt
            return (1.0 - mae / 20.0).coerceIn(0.2, 1.0)
        }

        internal fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
            val n = b.size
            val m = Array(n) { i -> a[i].copyOf() + b[i] }
            for (col in 0 until n) {
                val pivot = (col until n).maxByOrNull { abs(m[it][col]) } ?: return null
                if (abs(m[pivot][col]) < 1e-14) return null
                val tmp = m[col]; m[col] = m[pivot]; m[pivot] = tmp
                for (row in 0 until n) {
                    if (row == col) continue
                    val f = m[row][col] / m[col][col]
                    for (k in col..n) m[row][k] -= f * m[col][k]
                }
            }
            return DoubleArray(n) { m[it][n] / m[it][it] }
        }
    }
}
