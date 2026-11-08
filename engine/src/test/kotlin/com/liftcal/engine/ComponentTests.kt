package com.liftcal.engine

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RestingEnergyTest {
    @Test
    fun mifflinStJeorMale() {
        val p = UserProfile(30, Sex.MALE, 80.0, 180.0)
        assertEquals(1780.0, RestingEnergy.rmrKcalPerDay(p), 1e-9)
    }

    @Test
    fun mifflinStJeorFemale() {
        val p = UserProfile(30, Sex.FEMALE, 60.0, 165.0)
        assertEquals(600.0 + 1031.25 - 150.0 - 161.0, RestingEnergy.rmrKcalPerDay(p), 1e-9)
    }

    @Test
    fun katchMcArdleWhenBodyFatKnown() {
        val p = UserProfile(30, Sex.MALE, 80.0, 180.0, bodyFatPct = 15.0)
        assertEquals(370.0 + 21.6 * 68.0, RestingEnergy.rmrKcalPerDay(p), 1e-9)
    }

    @Test
    fun restingVo2IsPhysiological() {
        val v = RestingEnergy.vo2RestMlKgMin(Synth.profile)
        assertTrue(v in 2.5..4.0, "resting VO2 $v")
    }
}

class AerobicModelTest {
    private val m = AerobicModel()
    private val p = Synth.profile

    @Test
    fun zeroAtOrBelowRestingHr() {
        assertEquals(0.0, m.netVo2(60.0, p, Calibration.PRIOR))
        assertEquals(0.0, m.netVo2(45.0, p, Calibration.PRIOR))
    }

    @Test
    fun fullReserveAtMaxHr() {
        val expected = p.effectiveVo2Max - RestingEnergy.vo2RestMlKgMin(p)
        assertEquals(expected, m.netVo2(190.0, p, Calibration.PRIOR), 1e-9)
    }

    @Test
    fun scalesWithCalibration() {
        val a = m.netVo2(140.0, p, Calibration.PRIOR)
        val b = m.netVo2(140.0, p, Calibration(hrVo2Scale = 1.2))
        assertEquals(a * 1.2, b, 1e-9)
    }

    @Test
    fun uthEstimateUsedWithoutMeasuredVo2Max() {
        assertEquals(15.3 * 190.0 / 60.0, p.effectiveVo2Max, 1e-9)
    }
}

class MechanicalWorkModelTest {
    private val m = MechanicalWorkModel()
    private val p = UserProfile(30, Sex.MALE, 80.0, 175.0)

    @Test
    fun benchPressWorkKnownAnswer() {
        val s = LoggedSet("bench_press", 80.0, 10, 0, 30_000)
        val expected = 80.0 * 9.81 * (0.26 * 1.75) * 10
        assertEquals(expected, m.cost(s, p, Calibration.PRIOR).mechanicalWorkJ, 1e-6)
    }

    @Test
    fun dumbbellLoadCountsBothHands() {
        val one = m.cost(LoggedSet("biceps_curl", 15.0, 10, 0, 30_000), p, Calibration.PRIOR)
        val expected = 30.0 * 9.81 * (0.20 * 1.75) * 10
        assertEquals(expected, one.mechanicalWorkJ, 1e-6)
    }

    @Test
    fun bodyweightExercisesCostEnergyWithoutExternalLoad() {
        val c = m.cost(LoggedSet("pull_up", 0.0, 8, 0, 25_000), p, Calibration.PRIOR)
        assertTrue(c.mechanicalWorkJ > 3000)
        assertTrue(c.metabolicKcal > 0)
    }

    @Test
    fun setCostInPublishedRange() {
        // Scott et al. report roughly 15-40 kJ for a moderate bench set; we expect a few kcal.
        val c = m.cost(LoggedSet("bench_press", 80.0, 10, 0, 30_000), p, Calibration.PRIOR)
        assertTrue(c.metabolicKcal in 3.0..10.0, "bench set ${c.metabolicKcal} kcal")
    }

    @Test
    fun anaerobicFractionBounded() {
        assertEquals(0.85, MechanicalWorkModel.anaerobicFraction(1.0))
        assertEquals(0.4, MechanicalWorkModel.anaerobicFraction(500.0))
        assertTrue(MechanicalWorkModel.anaerobicFraction(30.0) in 0.6..0.8)
    }

    @Test
    fun unknownExerciseFallsBackToGeneric() {
        assertEquals(ExerciseCatalog.GENERIC, ExerciseCatalog["sled_push_custom"])
    }
}

class EpocModelTest {
    private val e = EpocModel()

    @Test
    fun rateIntegratesToDebt() {
        var sum = 0.0
        for (t in 0 until 20_000) sum += e.rate(10.0, t + 0.5)
        assertEquals(10.0, sum, 0.05)
    }

    @Test
    fun remainingDecaysMonotonically() {
        assertEquals(10.0, e.remaining(10.0, 0.0))
        var prev = 10.0
        for (t in listOf(10.0, 60.0, 300.0, 1200.0)) {
            val r = e.remaining(10.0, t)
            assertTrue(r < prev)
            prev = r
        }
    }

    @Test
    fun consumedPlusRemainingEqualsDebt() {
        var consumed = 0.0
        for (t in 0 until 600) consumed += e.rate(8.0, t + 0.5)
        assertEquals(8.0, consumed + e.remaining(8.0, 600.0), 0.01)
    }
}

class FusionTest {
    @Test
    fun zeroQualityReturnsModel() {
        val f = Fusion.inverseVariance(10.0, 0.2, 0.0, 4.0, 0.3)
        assertEquals(4.0, f.value)
    }

    @Test
    fun equalWeightsAverage() {
        val f = Fusion.inverseVariance(10.0, 0.3, 1.0, 4.0, 0.3)
        assertEquals(7.0, f.value, 1e-9)
    }

    @Test
    fun fusedUncertaintyBelowEitherInput() {
        val f = Fusion.inverseVariance(5.0, 0.25, 1.0, 5.0, 0.35)
        assertTrue(f.sigma < 5.0 * 0.25)
    }

    @Test
    fun smoothMaxCloseToMax() {
        for ((a, b) in listOf(1.0 to 1.0, 1.0 to 3.0, 0.2 to 5.0, 0.0 to 0.0)) {
            val s = Fusion.smoothMax(a, b)
            val m = maxOf(a, b)
            assertTrue(s >= m - 1e-12 && s <= m * 1.06 + 1e-12, "smoothMax($a,$b)=$s")
        }
    }
}

class HeartRateProcessorTest {
    private val proc = HeartRateProcessor()
    private val p = Synth.profile

    @Test
    fun linearInterpolationBetweenSamples() {
        val s = listOf(HrSample(0, 100.0), HrSample(10_000, 110.0))
        val g = proc.resample(s, 0, 10)
        assertEquals(100.5, g[0], 1e-9)
        assertEquals(109.5, g[9], 1e-9)
    }

    @Test
    fun largeGapsAreNotInterpolated() {
        val s = listOf(HrSample(0, 100.0), HrSample(120_000, 110.0))
        val g = proc.resample(s, 0, 120)
        assertTrue(g[60].isNaN())
    }

    @Test
    fun spikeIsRemoved() {
        val s = (0 until 20).map { HrSample(it * 1000L, if (it == 10) 200.0 else 100.0) }
        val out = proc.removeArtefacts(s)
        assertEquals(19, out.size)
        assertTrue(out.none { it.bpm == 200.0 })
    }

    @Test
    fun lagCorrectionLeadsAndPreservesMean() {
        val peak = 60.0 * (1 - kotlin.math.exp(-100 / 20.0))
        val x = DoubleArray(600) { i ->
            when {
                i < 100 -> 80.0
                i < 200 -> 80.0 + 60.0 * (1 - kotlin.math.exp(-(i - 100) / 20.0))
                else -> 80.0 + peak * kotlin.math.exp(-(i - 200) / 20.0)
            }
        }
        val y = proc.lagCorrect(x, p)
        assertTrue(y[110] > x[110], "corrected HR should lead raw HR on the rise")
        assertTrue(abs(y.average() - x.average()) < 2.0)
    }

    @Test
    fun prefersChestStrapWhenCovering() {
        val sess = Synth.session()
        val wrist = Synth.hr(sess, periodSec = 5)
        val chest = Synth.hr(sess, periodSec = 1, source = HrSource.CHEST_STRAP)
        val r = proc.process(wrist + chest, sess.startMs, sess.endMs, p, Calibration.PRIOR)
        assertEquals(HrSource.CHEST_STRAP, r.source)
        assertTrue(r.quality > 0.9)
    }

    @Test
    fun wristQualityIncludesReliability() {
        val sess = Synth.session()
        val r = proc.process(Synth.hr(sess), sess.startMs, sess.endMs, p, Calibration(wristHrReliability = 0.5))
        assertEquals(HrSource.WRIST, r.source)
        assertTrue(r.quality in 0.4..0.5)
    }

    @Test
    fun noDataGivesZeroQuality() {
        val r = proc.process(emptyList(), 0, 60_000, p, Calibration.PRIOR)
        assertEquals(0.0, r.quality)
        assertTrue(r.bpm.all { it.isNaN() })
    }
}

class MetabolicCartTest {
    @Test
    fun weirEquation() {
        assertEquals(3.941 + 0.9 * 1.106, MetabolicCart.weirKcalPerMin(1.0, 0.9), 1e-9)
    }

    @Test
    fun parsesAndIntegrates() {
        val csv = """
            # breath-by-breath export
            time_s,vo2_ml_min,vco2_ml_min,hr
            0,1000,900,100
            30,1000,900,101
            60,1000,900,102
        """.trimIndent()
        val s = MetabolicCart.parseCsv(csv, t0Ms = 1_000_000)
        assertEquals(3, s.size)
        val kcal = MetabolicCart.kcalBetween(s, 1_000_000, 1_060_000)
        assertEquals(MetabolicCart.weirKcalPerMin(1.0, 0.9), kcal, 1e-9)
    }
}
