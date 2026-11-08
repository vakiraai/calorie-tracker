package com.liftcal.data.fitbit

import com.liftcal.engine.HrSample
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class FitbitApiException(val code: Int, message: String) : IOException(message)

/**
 * Fitbit Web API client for second-level intraday heart rate. Intraday access requires a
 * "Personal" app type (or approved intraday access) registered at dev.fitbit.com.
 */
class FitbitApiClient(
    private val http: OkHttpClient,
    private val accessToken: suspend () -> String,
    private val baseUrl: String = "https://api.fitbit.com",
) {
    suspend fun intradayHeartRate(start: Instant, end: Instant, zone: ZoneId): List<HrSample> =
        splitByDay(start, end, zone).flatMap { (date, from, to) ->
            val path = "/1/user/-/activities/heart/date/${date}/1d/1sec/time/${HHMM.format(from)}/${HHMM.format(to)}.json"
            FitbitIntradayParser.parseHeart(get(path), date, zone)
        }.filter { it.epochMs >= start.toEpochMilli() && it.epochMs < end.toEpochMilli() }
            .sortedBy { it.epochMs }

    /** Fitbit's own calorie estimate over the window, used as a comparison baseline. */
    suspend fun intradayCalories(start: Instant, end: Instant, zone: ZoneId): Double =
        splitByDay(start, end, zone).sumOf { (date, from, to) ->
            val path = "/1/user/-/activities/calories/date/${date}/1d/1min/time/${HHMM.format(from)}/${HHMM.format(to)}.json"
            FitbitIntradayParser.sumCalories(get(path))
        }

    private suspend fun get(path: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(baseUrl.trimEnd('/') + path)
            .header("Authorization", "Bearer ${accessToken()}")
            .header("Accept-Language", "en_US")
            .build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw FitbitApiException(resp.code, "Fitbit API ${resp.code}: ${body.take(200)}")
            body
        }
    }

    internal data class DayRange(val date: LocalDate, val from: LocalDateTime, val to: LocalDateTime)

    companion object {
        private val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        /** Splits [start, end) into per-local-day minute ranges (the API is per date). */
        internal fun splitByDay(start: Instant, end: Instant, zone: ZoneId): List<DayRange> {
            val s = LocalDateTime.ofInstant(start, zone).withSecond(0).withNano(0)
            val e = LocalDateTime.ofInstant(end, zone)
            val out = ArrayList<DayRange>()
            var cur = s
            while (cur < e) {
                val date = cur.toLocalDate()
                val lastMinute = date.atTime(23, 59)
                val nextDay = date.plusDays(1).atStartOfDay()
                val to = if (e < nextDay) minOf(ceilToMinute(e), lastMinute) else lastMinute
                out += DayRange(date, cur, to)
                cur = nextDay
            }
            return out
        }

        private fun ceilToMinute(t: LocalDateTime): LocalDateTime {
            val floored = t.withSecond(0).withNano(0)
            return if (floored == t) t else floored.plusMinutes(1)
        }
    }
}
