package com.liftcal.engine

enum class MuscleMass { LARGE, MEDIUM, SMALL }

/**
 * @property romHeightFraction concentric range of motion of the load as a fraction of body height.
 * @property bodyMassFraction fraction of body mass that is moved through the ROM each rep.
 * @property grossEfficiency mechanical work / metabolic energy for the concentric phase.
 * @property loadIsPerHand true for dumbbell exercises where the logged load is one dumbbell.
 */
data class Exercise(
    val id: String,
    val name: String,
    val romHeightFraction: Double,
    val bodyMassFraction: Double,
    val grossEfficiency: Double,
    val muscleMass: MuscleMass,
    val loadIsPerHand: Boolean = false,
)

object ExerciseCatalog {
    /** Eccentric phase costs roughly a third of the concentric phase metabolically. */
    const val ECCENTRIC_COST_FRACTION = 0.3

    private val entries = listOf(
        Exercise("back_squat", "Back squat", 0.30, 0.85, 0.18, MuscleMass.LARGE),
        Exercise("front_squat", "Front squat", 0.30, 0.85, 0.18, MuscleMass.LARGE),
        Exercise("deadlift", "Deadlift", 0.30, 0.60, 0.20, MuscleMass.LARGE),
        Exercise("romanian_deadlift", "Romanian deadlift", 0.24, 0.45, 0.20, MuscleMass.LARGE),
        Exercise("leg_press", "Leg press", 0.28, 0.20, 0.18, MuscleMass.LARGE),
        Exercise("lunge", "Lunge", 0.25, 0.85, 0.17, MuscleMass.LARGE, loadIsPerHand = true),
        Exercise("hip_thrust", "Hip thrust", 0.17, 0.45, 0.20, MuscleMass.LARGE),
        Exercise("bench_press", "Bench press", 0.26, 0.0, 0.19, MuscleMass.MEDIUM),
        Exercise("incline_bench_press", "Incline bench press", 0.27, 0.0, 0.19, MuscleMass.MEDIUM),
        Exercise("db_bench_press", "Dumbbell bench press", 0.28, 0.0, 0.18, MuscleMass.MEDIUM, loadIsPerHand = true),
        Exercise("overhead_press", "Overhead press", 0.30, 0.08, 0.18, MuscleMass.MEDIUM),
        Exercise("barbell_row", "Barbell row", 0.22, 0.10, 0.18, MuscleMass.MEDIUM),
        Exercise("pull_up", "Pull-up", 0.33, 0.95, 0.19, MuscleMass.MEDIUM),
        Exercise("dip", "Dip", 0.20, 0.95, 0.19, MuscleMass.MEDIUM),
        Exercise("lat_pulldown", "Lat pulldown", 0.33, 0.0, 0.18, MuscleMass.MEDIUM),
        Exercise("cable_row", "Seated cable row", 0.25, 0.0, 0.18, MuscleMass.MEDIUM),
        Exercise("biceps_curl", "Biceps curl", 0.20, 0.0, 0.15, MuscleMass.SMALL, loadIsPerHand = true),
        Exercise("triceps_extension", "Triceps extension", 0.18, 0.0, 0.15, MuscleMass.SMALL),
        Exercise("lateral_raise", "Lateral raise", 0.25, 0.0, 0.14, MuscleMass.SMALL, loadIsPerHand = true),
        Exercise("leg_extension", "Leg extension", 0.20, 0.03, 0.16, MuscleMass.SMALL),
        Exercise("leg_curl", "Leg curl", 0.20, 0.03, 0.16, MuscleMass.SMALL),
        Exercise("calf_raise", "Calf raise", 0.06, 0.95, 0.16, MuscleMass.SMALL),
    )

    private val byId = entries.associateBy { it.id }

    /** Used for unknown exercise ids so that custom exercises still get a reasonable estimate. */
    val GENERIC = Exercise("generic", "Other exercise", 0.25, 0.10, 0.17, MuscleMass.MEDIUM)

    val all: List<Exercise> get() = entries

    operator fun get(id: String): Exercise = byId[id] ?: GENERIC

    fun contains(id: String): Boolean = id in byId
}
