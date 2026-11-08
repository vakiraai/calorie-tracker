package com.liftcal.data.fitbit

import com.liftcal.engine.HrSample
import com.liftcal.engine.HrSource
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

@Serializable
internal data class IntradayPoint(val time: String, val value: Double)

@Serializable
internal data class IntradaySeries(val dataset: List<IntradayPoint> = emptyList())

@Serializable
internal data class HeartIntradayResponse(
    @SerialName("activities-heart-intraday") val intraday: IntradaySeries = IntradaySeries(),
)

@Serializable
internal data class CaloriesIntradayResponse(
    @SerialName("activities-calories-intraday") val intraday: IntradaySeries = IntradaySeries(),
)

/**
 * Fitbit intraday timestamps are local wall-clock times on the requested date in the
 * user's Fitbit profile time zone; they are converted to epoch millis with [zone].
 */
object FitbitIntradayParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parseHeart(body: String, date: LocalDate, zone: ZoneId): List<HrSample> =
        json.decodeFromString<HeartIntradayResponse>(body).intraday.dataset.map {
            HrSample(toEpochMs(date, it.time, zone), it.value, HrSource.WRIST)
        }

    /** Sum of Fitbit's per-minute calorie estimates in the response. */
    fun sumCalories(body: String): Double =
        json.decodeFromString<CaloriesIntradayResponse>(body).intraday.dataset.sumOf { it.value }

    internal fun toEpochMs(date: LocalDate, time: String, zone: ZoneId): Long =
        LocalTime.parse(time).atDate(date).atZone(zone).toInstant().toEpochMilli()
}
