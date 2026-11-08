package com.liftcal.engine

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CalibratorTest {
    private val est = HybridCalorieEstimator()
    private val p = Synth.profile

    private fun windowsFrom(truth: Calibration, s: WorkoutSession, hr: List<HrSample>): List<ReferenceWindow> {
        val r = est.estimate(s, hr, p, truth)
        val out = ArrayList<ReferenceWindow>()
        var a = s.startMs
        while (a < s.endMs) {
            val b = minOf(a + 300_000, s.endMs)
            out += ReferenceWindow(a, b, r.totalBetween(s.startMs, a, b))
            a = b
        }
        return out
    }

    @Test
    fun recoversPersonalParametersAndReducesError() {
        val truth = Calibration(hrVo2Scale = 1.25, inSetHrWeight = 0.6, workCostScale = 1.4, epocScale = 1.3)
        val samples = (1..3).map { seed ->
            val s = Synth.session(seed = seed, restSec = 60 + seed * 40)
            val hr = Synth.hr(s, seed = seed, setHr = 140.0 + seed * 8)
            CalibrationSample(s, hr, windowsFrom(truth, s, hr))
        }
        val fit = Calibrator(est).fit(samples, p)
        assertTrue(fit.rmsRelErrorBefore > 0.05, "prior should be off: ${fit.rmsRelErrorBefore}")
        assertTrue(fit.rmsRelErrorAfter < fit.rmsRelErrorBefore / 3, "after=${fit.rmsRelErrorAfter}")
        assertTrue(abs(fit.calibration.hrVo2Scale - truth.hrVo2Scale) < 0.2)
        assertEquals(3, fit.calibration.referenceSessions)
    }

    @Test
    fun staysAtPriorWhenPriorIsCorrect() {
        val s = Synth.session()
        val hr = Synth.hr(s)
        val fit = Calibrator(est).fit(listOf(CalibrationSample(s, hr, windowsFrom(Calibration.PRIOR, s, hr))), p)
        assertTrue(fit.rmsRelErrorAfter < 1e-3)
        assertEquals(1.0, fit.calibration.hrVo2Scale, 0.02)
    }

    @Test
    fun wristReliabilityFromChestStrapComparison() {
        val s = Synth.session()
        val chest = Synth.hr(s, source = HrSource.CHEST_STRAP, noiseBpm = 0.0)
        val goodWrist = Synth.hr(s, noiseBpm = 1.0)
        val badWrist = goodWrist.map { it.copy(bpm = it.bpm + 14.0) }
        val good = assertNotNull(Calibrator.wristReliability(goodWrist, chest, s.startMs, s.endMs))
        val bad = assertNotNull(Calibrator.wristReliability(badWrist, chest, s.startMs, s.endMs))
        assertTrue(good > 0.9)
        assertTrue(bad < good)
    }

    @Test
    fun linearSolver() {
        val x = Calibrator.solve(arrayOf(doubleArrayOf(2.0, 1.0), doubleArrayOf(1.0, 3.0)), doubleArrayOf(3.0, 5.0))!!
        assertEquals(0.8, x[0], 1e-9)
        assertEquals(1.4, x[1], 1e-9)
    }
}
