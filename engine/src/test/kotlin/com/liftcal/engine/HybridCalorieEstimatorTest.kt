package com.liftcal.engine

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HybridCalorieEstimatorTest {
    private val est = HybridCalorieEstimator()
    private val p = Synth.profile

    @Test
    fun typicalSessionIsPlausible() {
        val s = Synth.session()
        val r = est.estimate(s, Synth.hr(s), p)
        val minutes = (s.endMs - s.startMs) / 60_000.0
        val kcalPerMin = r.totalKcal / minutes
        // Published indirect calorimetry for multi-exercise lifting: ~4-9 kcal/min for an 80 kg male.
        assertTrue(kcalPerMin in 3.5..10.0, "kcal/min=$kcalPerMin total=${r.totalKcal}")
        assertTrue(r.low90 < r.totalKcal && r.totalKcal < r.high90)
        assertEquals(r.restingKcal + r.activeKcal, r.totalKcal, 1e-6)
        assertEquals(HrSource.WRIST, r.hrSourceUsed)
    }

    @Test
    fun timelinePlusTailEqualsTotal() {
        val s = Synth.session()
        val r = est.estimate(s, Synth.hr(s), p)
        assertEquals(r.totalKcal, r.timeline.sum() + r.postSessionEpocKcal, 1e-6)
        assertEquals(r.timeline.sum(), r.totalBetween(s.startMs, s.startMs, s.endMs), 1e-6)
    }

    @Test
    fun perSetAttributionCoversActiveAfterFirstSet() {
        val s = Synth.session()
        val r = est.estimate(s, Synth.hr(s), p)
        val firstIdx = ((s.sortedSets.first().startMs - s.startMs) / 1000).toInt()
        val restingRate = RestingEnergy.kcalPerSecond(p)
        val activeBeforeFirst = (0 until firstIdx).sumOf { r.timeline[it] - restingRate }
        assertEquals(r.activeKcal - activeBeforeFirst, r.perSet.sumOf { it.attributedActiveKcal }, 1e-6)
        assertEquals(s.sets.size, r.perSet.size)
    }

    @Test
    fun missingHrStillEstimatesWithWiderInterval() {
        val s = Synth.session()
        val withHr = est.estimate(s, Synth.hr(s), p)
        val noHr = est.estimate(s, emptyList(), p)
        assertEquals(0.0, noHr.hrQuality)
        assertTrue(noHr.totalKcal > noHr.restingKcal)
        val wWith = (withHr.high90 - withHr.low90) / withHr.totalKcal
        val wNo = (noHr.high90 - noHr.low90) / noHr.totalKcal
        assertTrue(wNo > wWith, "no-HR interval $wNo should exceed with-HR $wWith")
    }

    @Test
    fun calibrationAndMeasuredProfileNarrowInterval() {
        val s = Synth.session()
        val hr = Synth.hr(s)
        val base = est.estimate(s, hr, p)
        val calibrated = est.estimate(
            s, hr, p.copy(vo2Max = 48.0), Calibration(referenceSessions = 4, wristHrReliability = 0.95),
        )
        val w0 = (base.high90 - base.low90) / base.totalKcal
        val w1 = (calibrated.high90 - calibrated.low90) / calibrated.totalKcal
        assertTrue(w1 < w0, "calibrated width $w1 vs $w0")
    }

    @Test
    fun countsEnergyHrAloneMisses() {
        // Same HR trace, heavier loads: HR-only methods report identical calories; we should not.
        val light = Synth.session(loadScale = 0.6)
        val heavy = Synth.session(loadScale = 1.3)
        val hr = Synth.hr(light)
        val a = est.estimate(light, hr, p).totalKcal
        val b = est.estimate(heavy, hr, p).totalKcal
        assertTrue(b > a)
        assertEquals(Baselines.keytel(light, hr, p), Baselines.keytel(heavy, hr, p), 1e-9)
    }

    @Test
    fun epocTailIsPositiveAndBounded() {
        val s = Synth.session()
        val r = est.estimate(s, Synth.hr(s), p)
        assertTrue(r.postSessionEpocKcal > 0)
        assertTrue(r.postSessionEpocKcal < r.epocKcal)
        assertTrue(r.epocKcal < r.activeKcal)
    }
}

/** Seeded property tests over randomly generated sessions. */
class EstimatorPropertyTest {
    private val est = HybridCalorieEstimator()

    private fun randomProfile(rnd: Random) = UserProfile(
        ageYears = 18 + rnd.nextInt(50),
        sex = if (rnd.nextBoolean()) Sex.MALE else Sex.FEMALE,
        weightKg = 50.0 + rnd.nextDouble() * 60,
        heightCm = 155.0 + rnd.nextDouble() * 40,
        restingHr = 50.0 + rnd.nextDouble() * 25,
    )

    private fun randomSession(rnd: Random): WorkoutSession {
        val t0 = 1_700_000_000_000L
        var t = t0 + rnd.nextInt(600) * 1000L
        val ids = ExerciseCatalog.all.map { it.id }
        val sets = (0 until 3 + rnd.nextInt(20)).map {
            val dur = (10 + rnd.nextInt(60)) * 1000L
            val s = LoggedSet(
                exerciseId = ids[rnd.nextInt(ids.size)],
                loadKg = rnd.nextDouble() * 150,
                reps = 1 + rnd.nextInt(20),
                startMs = t, endMs = t + dur,
                rpe = if (rnd.nextBoolean()) 5.0 + rnd.nextDouble() * 5 else null,
                isWarmup = rnd.nextInt(5) == 0,
            )
            t += dur + (30 + rnd.nextInt(240)) * 1000L
            s
        }
        return WorkoutSession(t0, t + rnd.nextInt(300) * 1000L, sets)
    }

    private fun randomHr(rnd: Random, s: WorkoutSession): List<HrSample> {
        if (rnd.nextInt(6) == 0) return emptyList()
        val period = listOf(1, 1, 5, 15)[rnd.nextInt(4)]
        return Synth.hr(s, periodSec = period, noiseBpm = rnd.nextDouble() * 8, seed = rnd.nextInt(),
            setHr = 120.0 + rnd.nextDouble() * 50)
            .filter { rnd.nextInt(20) != 0 }
    }

    @Test
    fun outputsAreFiniteAndNonNegative() {
        val rnd = Random(42)
        repeat(60) {
            val p = randomProfile(rnd)
            val s = randomSession(rnd)
            val r = est.estimate(s, randomHr(rnd, s), p)
            assertTrue(r.timeline.all { it.isFinite() && it >= 0 })
            assertTrue(r.totalKcal.isFinite() && r.totalKcal >= r.restingKcal)
            assertTrue(r.low90 <= r.totalKcal && r.totalKcal <= r.high90)
            assertTrue(r.perSet.all { it.attributedActiveKcal >= 0 && it.anaerobicKcal >= 0 })
            assertTrue(r.hrQuality in 0.0..1.0)
        }
    }

    @Test
    fun monotonicInLoadAndReps() {
        val rnd = Random(7)
        repeat(40) {
            val p = randomProfile(rnd)
            val s = randomSession(rnd)
            val hr = randomHr(rnd, s)
            val base = est.estimate(s, hr, p).totalKcal
            val k = rnd.nextInt(s.sets.size)
            val heavier = s.copy(sets = s.sets.mapIndexed { i, x -> if (i == k) x.copy(loadKg = x.loadKg + 20) else x })
            val moreReps = s.copy(sets = s.sets.mapIndexed { i, x -> if (i == k) x.copy(reps = x.reps + 3) else x })
            assertTrue(est.estimate(heavier, hr, p).totalKcal >= base - 1e-9)
            assertTrue(est.estimate(moreReps, hr, p).totalKcal >= base - 1e-9)
        }
    }

    @Test
    fun monotonicInHeartRate() {
        val rnd = Random(11)
        repeat(30) {
            val p = randomProfile(rnd)
            val s = randomSession(rnd)
            val hr = Synth.hr(s, noiseBpm = 0.0)
            val higher = hr.map { it.copy(bpm = it.bpm + 10) }
            assertTrue(est.estimate(s, higher, p).totalKcal >= est.estimate(s, hr, p).totalKcal - 1e-9)
        }
    }

    @Test
    fun deterministic() {
        val s = Synth.session()
        val hr = Synth.hr(s)
        val a = est.estimate(s, hr, Synth.profile).totalKcal
        val b = est.estimate(s, hr, Synth.profile).totalKcal
        assertTrue(abs(a - b) == 0.0)
    }
}
