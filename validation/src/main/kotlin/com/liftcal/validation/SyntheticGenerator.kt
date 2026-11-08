package com.liftcal.validation

import com.liftcal.engine.ExerciseCatalog
import com.liftcal.engine.MuscleMass
import java.io.File
import java.util.Locale
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Generates synthetic sessions from a "ground truth" physiology that deliberately differs from
 * the engine's internal model (different kinetics, hidden personal traits, wrist HR errors),
 * so the harness can be exercised end to end before real calorimetry data exists.
 */
class SyntheticGenerator(private val seed: Int = 2026) {

    private class Subject(
        val id: String,
        val profile: FixtureProfile,
        val hrRest: Double,
        val hrMax: Double,
        val vo2Max: Double,
        val rmrKcalDay: Double,
        val efficiency: Double,
        val romScale: Double,
        val anaerobicBias: Double,
        val epocFactor: Double,
        val epocFastTau: Double,
        val epocSlowTau: Double,
        val pressorBpm: Double,
        val movementVo2: Double,
        val wristErrorProb: Double,
        val hrSlope: Double,
    )

    private fun gaussian(rnd: Random): Double {
        var s = 0.0
        repeat(12) { s += rnd.nextDouble() }
        return s - 6.0
    }

    private fun subject(rnd: Random, idx: Int): Subject {
        val male = rnd.nextBoolean()
        val age = 20 + rnd.nextInt(35)
        val weight = if (male) 70.0 + rnd.nextDouble() * 30 else 55.0 + rnd.nextDouble() * 25
        val height = if (male) 170.0 + rnd.nextDouble() * 20 else 158.0 + rnd.nextDouble() * 16
        val hrRest = 52.0 + rnd.nextDouble() * 22
        val hrMax = 208 - 0.7 * age + gaussian(rnd) * 7
        val vo2Max = (15.3 * hrMax / hrRest * (0.85 + rnd.nextDouble() * 0.3)).coerceIn(28.0, 62.0)
        val mifflin = 10 * weight + 6.25 * height - 5 * age + if (male) 5 else -161
        val measured = idx % 2 == 0
        return Subject(
            id = "S%02d".format(idx + 1),
            profile = FixtureProfile(
                ageYears = age,
                sex = if (male) "MALE" else "FEMALE",
                weightKg = round1(weight),
                heightCm = round1(height),
                restingHr = (hrRest + gaussian(rnd) * 2).roundToInt().toDouble(),
                maxHr = if (measured) (hrMax + gaussian(rnd) * 2).roundToInt().toDouble() else null,
                vo2Max = if (measured) round1(vo2Max + gaussian(rnd) * 2) else null,
            ),
            hrRest = hrRest,
            hrMax = hrMax,
            vo2Max = vo2Max,
            rmrKcalDay = mifflin * (0.92 + rnd.nextDouble() * 0.16),
            efficiency = 0.13 + rnd.nextDouble() * 0.09,
            romScale = 0.9 + rnd.nextDouble() * 0.2,
            anaerobicBias = rnd.nextDouble() * 0.1 - 0.05,
            epocFactor = 0.4 + rnd.nextDouble() * 0.6,
            epocFastTau = 30.0 + rnd.nextDouble() * 30,
            epocSlowTau = 200.0 + rnd.nextDouble() * 300,
            pressorBpm = 5.0 + rnd.nextDouble() * 20,
            movementVo2 = 0.5 + rnd.nextDouble() * 1.5,
            wristErrorProb = 0.1 + rnd.nextDouble() * 0.3,
            hrSlope = 1.0 + rnd.nextDouble() * 0.5,
        )
    }

    private data class SetPlan(val exerciseId: String, val load: Double, val reps: Int, val start: Int, val end: Int, val rpe: Double, val warmup: Boolean)

    private fun plan(rnd: Random, s: Subject): Pair<List<SetPlan>, Int> {
        val pool = ExerciseCatalog.all.shuffled(rnd).take(5 + rnd.nextInt(3))
        val sets = ArrayList<SetPlan>()
        var t = 180 + rnd.nextInt(180)
        pool.forEachIndexed { exIdx, ex ->
            val ratio = when (ex.muscleMass) {
                MuscleMass.LARGE -> 0.9 + rnd.nextDouble() * 0.5
                MuscleMass.MEDIUM -> 0.45 + rnd.nextDouble() * 0.4
                MuscleMass.SMALL -> 0.1 + rnd.nextDouble() * 0.12
            } * if (ex.loadIsPerHand) 0.5 else 1.0
            val load = if (ex.bodyMassFraction > 0.9 && rnd.nextBoolean()) 0.0 else (s.profile.weightKg * ratio / 2.5).roundToInt() * 2.5
            val working = 3 + rnd.nextInt(2)
            val total = if (exIdx == 0) working + 1 else working
            repeat(total) { i ->
                val warm = exIdx == 0 && i == 0
                val reps = if (warm) 10 else 5 + rnd.nextInt(8)
                val dur = (reps * (2.5 + rnd.nextDouble() * 1.5)).roundToInt() + 2
                val rpe = if (warm) 5.0 else (7.0 + rnd.nextInt(6) * 0.5)
                sets += SetPlan(ex.id, if (warm) load * 0.5 else load, reps, t, t + dur, rpe, warm)
                t += dur + 60 + rnd.nextInt(120)
            }
            t += 30 + rnd.nextInt(90)
        }
        return sets to t + 120
    }

    data class Generated(val fixture: Fixture, val cartCsv: String)

    fun generate(subjects: Int = 4, sessionsPerSubject: Int = 3): List<Generated> {
        val rnd = Random(seed)
        val out = ArrayList<Generated>()
        repeat(subjects) { si ->
            val subj = subject(rnd, si)
            repeat(sessionsPerSubject) { k ->
                out += session(rnd, subj, k)
            }
        }
        return out
    }

    private fun session(rnd: Random, s: Subject, k: Int): Generated {
        val (sets, n) = plan(rnd, s)
        val w = s.profile.weightKg
        val heightM = s.profile.heightCm / 100
        val kcalPerMlKgMin = w / 1000.0 / 60.0 * 5.0
        val vo2Rest = s.rmrKcalDay / 1440 / 4.825 * 1000 / w

        val demandAer = DoubleArray(n)
        val anaerobic = DoubleArray(n)
        val inSet = BooleanArray(n)
        val debts = ArrayList<Pair<Int, Double>>()
        for (p in sets) {
            val ex = ExerciseCatalog[p.exerciseId]
            val moved = (if (ex.loadIsPerHand) p.load * 2 else p.load) + ex.bodyMassFraction * w
            val work = moved * 9.81 * ex.romHeightFraction * heightM * s.romScale * p.reps
            val dur = p.end - p.start
            val iso = when (ex.muscleMass) { MuscleMass.LARGE -> 0.035; MuscleMass.MEDIUM -> 0.022; MuscleMass.SMALL -> 0.012 } * dur
            val effort = 1.0 + 0.05 * (p.rpe - 8)
            val kcal = (work / s.efficiency * 1.3 / 4184.0 + iso) * effort
            val fAn = (0.95 - 0.007 * dur + s.anaerobicBias).coerceIn(0.35, 0.9)
            for (t in p.start until p.end) {
                demandAer[t] += kcal * (1 - fAn) / dur
                anaerobic[t] += kcal * fAn / dur
                inSet[t] = true
            }
            debts += p.end to kcal * fAn * s.epocFactor
        }

        val energy = DoubleArray(n)
        val hrTrue = DoubleArray(n)
        val rmr = s.rmrKcalDay / 86400
        val movement = s.movementVo2 * kcalPerMlKgMin
        var uptake = 0.0
        var hr = s.hrRest + 15
        for (t in 0 until n) {
            uptake += (demandAer[t] - uptake) * (1 - exp(-1.0 / 30.0))
            var epoc = 0.0
            for ((end, d) in debts) if (t >= end) {
                val dt = t - end + 0.5
                epoc += d * (0.65 / s.epocFastTau * exp(-dt / s.epocFastTau) + 0.35 / s.epocSlowTau * exp(-dt / s.epocSlowTau))
            }
            val mov = if (inSet[t]) 0.0 else movement
            energy[t] = rmr + uptake + anaerobic[t] + epoc + mov
            val netVo2 = (uptake + epoc + mov) / kcalPerMlKgMin
            val frac = (netVo2 / (s.vo2Max - vo2Rest)).coerceIn(0.0, 1.0)
            // Assumed HR-VO2 dissociation during lifting: steeper slope, sympathetic offset,
            // pressor response under load, and cardiovascular drift.
            val target = s.hrRest + (s.hrMax - s.hrRest) * frac * s.hrSlope + (if (inSet[t]) s.pressorBpm else 0.0) + 0.1 * t / 60.0 + 6
            val tau = if (target > hr) 18.0 else 35.0
            hr += (target.coerceAtMost(s.hrMax) - hr) * (1 - exp(-1.0 / tau))
            hrTrue[t] = hr
        }

        val period = if (rnd.nextBoolean()) 1 else 5
        val noise = 1.5 + rnd.nextDouble() * 2.5
        val offset = DoubleArray(n)
        for (p in sets) if (rnd.nextDouble() < s.wristErrorProb) {
            val o = -15.0 + rnd.nextDouble() * 35
            for (t in p.start until minOf(n, p.end + 10)) offset[t] = o
        }
        val dropped = BooleanArray(n)
        repeat(rnd.nextInt(3)) {
            val a = rnd.nextInt(n)
            for (t in a until minOf(n, a + 30 + rnd.nextInt(60))) dropped[t] = true
        }
        val wrist = (0 until n step period).filterNot { dropped[it] }.map { t ->
            listOf(t.toDouble(), (hrTrue[t] + offset[t] + gaussian(rnd) * noise).roundToInt().toDouble())
        }

        val csv = buildString {
            appendLine("time_s,vo2_ml_min,vco2_ml_min")
            for (t in 0 until n step 3) {
                val kcalPerMin = (t until minOf(n, t + 3)).map { energy[it] }.average() * 60
                val vo2 = kcalPerMin / (3.941 + 1.106 * 0.95) * 1000 * (1 + gaussian(rnd) * 0.04)
                appendLine(String.format(Locale.US, "%d,%.1f,%.1f", t, vo2, vo2 * 0.95))
            }
        }

        val id = "synthetic-${s.id.lowercase()}-${k + 1}"
        val startMs = 1_780_000_000_000L + (s.id.drop(1).toInt() * 10 + k) * 86_400_000L
        val fixture = Fixture(
            id = id,
            subject = s.id,
            synthetic = true,
            notes = "Generated by SyntheticGenerator(seed=$seed). Reference is energy-equivalent VO2 including anaerobic work.",
            profile = s.profile,
            session = FixtureSession(startMs, n.toDouble(), sets.map {
                FixtureSet(it.exerciseId, it.load, it.reps, it.start.toDouble(), it.end.toDouble(), it.rpe, it.warmup)
            }),
            hr = listOf(FixtureHr("WRIST", wrist)),
            reference = FixtureReference(cartCsv = "$id.csv", kind = "synthetic-truth"),
        )
        return Generated(fixture, csv)
    }

    fun writeTo(dir: File, subjects: Int = 4, sessionsPerSubject: Int = 3): List<Generated> {
        val g = generate(subjects, sessionsPerSubject)
        for (x in g) {
            Fixtures.write(x.fixture, dir)
            File(dir, x.fixture.reference.cartCsv!!).writeText(x.cartCsv)
        }
        return g
    }

    private fun round1(v: Double) = (v * 10).roundToInt() / 10.0
}
