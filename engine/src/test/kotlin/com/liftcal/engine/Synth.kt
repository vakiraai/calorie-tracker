package com.liftcal.engine

import kotlin.math.exp
import kotlin.random.Random

/** Deterministic synthetic lifting sessions with plausible HR kinetics, for tests. */
object Synth {
    val T0 = 1_700_000_000_000L

    val profile = UserProfile(ageYears = 30, sex = Sex.MALE, weightKg = 80.0, heightCm = 180.0, restingHr = 60.0, maxHr = 190.0)

    private val plan = listOf(
        "back_squat" to 100.0, "bench_press" to 80.0, "barbell_row" to 70.0,
        "overhead_press" to 45.0, "romanian_deadlift" to 90.0, "biceps_curl" to 14.0,
    )

    fun session(
        seed: Int = 1,
        setsPerExercise: Int = 3,
        restSec: Int = 120,
        loadScale: Double = 1.0,
    ): WorkoutSession {
        val rnd = Random(seed)
        val sets = ArrayList<LoggedSet>()
        var t = T0 + 300_000L
        for ((ex, load) in plan) {
            repeat(setsPerExercise) {
                val reps = 8 + rnd.nextInt(5)
                val dur = (reps * 3.0 * 1000).toLong()
                sets += LoggedSet(ex, load * loadScale, reps, t, t + dur, rpe = 7.0 + rnd.nextDouble() * 2)
                t += dur + restSec * 1000L + rnd.nextInt(30_000)
            }
        }
        return WorkoutSession(T0, t + 120_000L, sets)
    }

    /** First-order HR response: drive rises with relative effort during sets, decays in rest. */
    fun hr(
        session: WorkoutSession,
        periodSec: Int = 1,
        source: HrSource = HrSource.WRIST,
        noiseBpm: Double = 2.0,
        seed: Int = 7,
        restHr: Double = 60.0,
        gymHr: Double = 90.0,
        setHr: Double = 155.0,
    ): List<HrSample> {
        val rnd = Random(seed)
        val out = ArrayList<HrSample>()
        var h = restHr
        var tMs = session.startMs
        val tau = 25.0
        while (tMs <= session.endMs) {
            val inSet = session.sets.any { tMs in it.startMs until it.endMs }
            val target = if (inSet) setHr else gymHr
            h += (target - h) * (1 - exp(-1.0 / tau))
            if ((tMs - session.startMs) / 1000 % periodSec == 0L) {
                out += HrSample(tMs, h + (rnd.nextDouble() * 2 - 1) * noiseBpm, source)
            }
            tMs += 1000
        }
        return out
    }
}
