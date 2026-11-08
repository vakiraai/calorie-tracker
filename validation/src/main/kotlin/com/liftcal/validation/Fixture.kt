package com.liftcal.validation

import com.liftcal.engine.HrSample
import com.liftcal.engine.HrSource
import com.liftcal.engine.LoggedSet
import com.liftcal.engine.Sex
import com.liftcal.engine.UserProfile
import com.liftcal.engine.WorkoutSession
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class FixtureProfile(
    val ageYears: Int,
    val sex: String,
    val weightKg: Double,
    val heightCm: Double,
    val bodyFatPct: Double? = null,
    val restingHr: Double? = null,
    val maxHr: Double? = null,
    val vo2Max: Double? = null,
) {
    fun toUserProfile() = UserProfile(ageYears, Sex.valueOf(sex.uppercase()), weightKg, heightCm, bodyFatPct, restingHr, maxHr, vo2Max)
}

/** Set times are seconds from session start so fixtures are easy to write by hand. */
@Serializable
data class FixtureSet(
    val exerciseId: String,
    val loadKg: Double,
    val reps: Int,
    val startSec: Double,
    val endSec: Double,
    val rpe: Double? = null,
    val isWarmup: Boolean = false,
)

@Serializable
data class FixtureSession(val startMs: Long, val durationSec: Double, val sets: List<FixtureSet>)

/** [samples] are `[secondsFromStart, bpm]` pairs. */
@Serializable
data class FixtureHr(val source: String = "WRIST", val samples: List<List<Double>>)

/**
 * Either [cartCsv] (path relative to the fixture file; time_s from session start) or a
 * single [totalKcal]. [kind] documents what the reference measures, e.g. "cart" for
 * indirect calorimetry or "cart+lactate" for Scott-style aerobic + anaerobic estimates.
 */
@Serializable
data class FixtureReference(
    val cartCsv: String? = null,
    val totalKcal: Double? = null,
    val kind: String = "cart",
)

@Serializable
data class Fixture(
    val id: String,
    val subject: String,
    val synthetic: Boolean = false,
    val notes: String? = null,
    val profile: FixtureProfile,
    val session: FixtureSession,
    val hr: List<FixtureHr> = emptyList(),
    val reference: FixtureReference,
    /** Other devices' calorie totals for the same session, e.g. "fitbit", "apple_watch", "garmin". */
    val devices: Map<String, Double> = emptyMap(),
) {
    fun toSession(): WorkoutSession = WorkoutSession(
        session.startMs,
        session.startMs + (session.durationSec * 1000).toLong(),
        session.sets.map {
            LoggedSet(
                it.exerciseId, it.loadKg, it.reps,
                session.startMs + (it.startSec * 1000).toLong(),
                session.startMs + (it.endSec * 1000).toLong(),
                it.rpe, it.isWarmup,
            )
        },
    )

    fun hrSamples(): List<HrSample> = hr.flatMap { h ->
        val src = HrSource.valueOf(h.source.uppercase())
        h.samples.map { (t, bpm) -> HrSample(session.startMs + (t * 1000).toLong(), bpm, src) }
    }.sortedBy { it.epochMs }
}

object Fixtures {
    val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    data class Loaded(val fixture: Fixture, val dir: File)

    fun loadDir(dir: File): List<Loaded> =
        (dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: emptyArray())
            .sortedBy { it.name }
            .map { Loaded(json.decodeFromString(Fixture.serializer(), it.readText()), dir) }

    fun write(f: Fixture, dir: File) {
        dir.mkdirs()
        File(dir, "${f.id}.json").writeText(json.encodeToString(Fixture.serializer(), f) + "\n")
    }
}
