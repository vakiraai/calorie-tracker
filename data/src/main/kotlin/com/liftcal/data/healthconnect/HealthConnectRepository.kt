package com.liftcal.data.healthconnect

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Energy
import com.liftcal.engine.CalorieResult
import com.liftcal.engine.HrSample
import com.liftcal.engine.HrSource
import com.liftcal.engine.WorkoutSession
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.reflect.KClass

/** Values the app can pre-fill into the profile from data the Fitbit app already shares. */
data class ProfileHints(
    val restingHr: Double? = null,
    val vo2Max: Double? = null,
    val weightKg: Double? = null,
    val heightCm: Double? = null,
    val bodyFatPct: Double? = null,
)

class HealthConnectRepository(private val context: Context) {

    val readPermissions: Set<String> = setOf(
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(RestingHeartRateRecord::class),
        HealthPermission.getReadPermission(Vo2MaxRecord::class),
        HealthPermission.getReadPermission(WeightRecord::class),
        HealthPermission.getReadPermission(HeightRecord::class),
        HealthPermission.getReadPermission(BodyFatRecord::class),
    )

    val writePermissions: Set<String> = setOf(
        HealthPermission.getWritePermission(ExerciseSessionRecord::class),
        HealthPermission.getWritePermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getWritePermission(TotalCaloriesBurnedRecord::class),
    )

    val allPermissions: Set<String> get() = readPermissions + writePermissions

    fun sdkStatus(): Int = HealthConnectClient.getSdkStatus(context)

    fun isAvailable(): Boolean = sdkStatus() == HealthConnectClient.SDK_AVAILABLE

    private val client: HealthConnectClient by lazy { HealthConnectClient.getOrCreate(context) }

    suspend fun grantedPermissions(): Set<String> =
        if (isAvailable()) client.permissionController.getGrantedPermissions() else emptySet()

    suspend fun hasReadAccess(): Boolean = grantedPermissions().containsAll(readPermissions)

    /** All heart-rate samples (from any app, including Fitbit) between [start] and [end]. */
    suspend fun readHeartRate(start: Instant, end: Instant): List<HrSample> {
        val out = ArrayList<HrSample>()
        for (r in readAll(HeartRateRecord::class, start, end)) {
            for (s in r.samples) {
                if (!s.time.isBefore(start) && s.time.isBefore(end)) {
                    out += HrSample(s.time.toEpochMilli(), s.beatsPerMinute.toDouble(), HrSource.WRIST)
                }
            }
        }
        return out.sortedBy { it.epochMs }
    }

    suspend fun readProfileHints(now: Instant = Instant.now()): ProfileHints {
        val month = now.minus(Duration.ofDays(30))
        val week = now.minus(Duration.ofDays(7))
        val restingValues = readAll(RestingHeartRateRecord::class, week, now).map { it.beatsPerMinute.toDouble() }.sorted()
        val restingMedian = restingValues.takeIf { it.isNotEmpty() }?.let { it[it.size / 2] }
        return ProfileHints(
            restingHr = restingMedian,
            vo2Max = readAll(Vo2MaxRecord::class, month.minus(Duration.ofDays(60)), now).maxByOrNull { it.time }?.vo2MillilitersPerMinuteKilogram,
            weightKg = readAll(WeightRecord::class, month, now).maxByOrNull { it.time }?.weight?.inKilograms,
            heightCm = readAll(HeightRecord::class, now.minus(Duration.ofDays(3650)), now).maxByOrNull { it.time }?.height?.inMeters?.times(100),
            bodyFatPct = readAll(BodyFatRecord::class, month, now).maxByOrNull { it.time }?.percentage?.value,
        )
    }

    /**
     * Writes the workout and our calorie estimate. Client record ids make the write idempotent,
     * so re-finalising a session after a late sync replaces rather than duplicates it.
     */
    suspend fun writeWorkout(workoutId: Long, session: WorkoutSession, result: CalorieResult, title: String) {
        val start = Instant.ofEpochMilli(session.startMs)
        val end = Instant.ofEpochMilli(session.endMs)
        val zone = ZoneId.systemDefault().rules
        val startOffset = zone.getOffset(start)
        val endOffset = zone.getOffset(end)
        fun meta(kind: String) = Metadata.activelyRecorded(
            device = Device(type = Device.TYPE_PHONE),
            clientRecordId = "liftcal-$kind-$workoutId",
            clientRecordVersion = System.currentTimeMillis(),
        )
        client.insertRecords(
            listOf(
                ExerciseSessionRecord(
                    startTime = start, startZoneOffset = startOffset, endTime = end, endZoneOffset = endOffset,
                    exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING,
                    title = title,
                    notes = "LiftCal estimate %.0f kcal (90%% interval %.0f-%.0f)".format(result.totalKcal, result.low90, result.high90),
                    metadata = meta("session"),
                ),
                ActiveCaloriesBurnedRecord(
                    startTime = start, startZoneOffset = startOffset, endTime = end, endZoneOffset = endOffset,
                    energy = Energy.kilocalories(result.activeKcal),
                    metadata = meta("active"),
                ),
                TotalCaloriesBurnedRecord(
                    startTime = start, startZoneOffset = startOffset, endTime = end, endZoneOffset = endOffset,
                    energy = Energy.kilocalories(result.totalKcal),
                    metadata = meta("total"),
                ),
            ),
        )
    }

    private suspend fun <T : Record> readAll(type: KClass<T>, start: Instant, end: Instant): List<T> {
        if (!isAvailable()) return emptyList()
        val out = ArrayList<T>()
        var token: String? = null
        do {
            val resp = client.readRecords(
                ReadRecordsRequest(type, TimeRangeFilter.between(start, end), pageToken = token),
            )
            out += resp.records
            token = resp.pageToken
        } while (token != null)
        return out
    }
}
