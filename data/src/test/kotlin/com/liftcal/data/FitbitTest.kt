package com.liftcal.data

import com.liftcal.data.fitbit.FitbitApiClient
import com.liftcal.data.fitbit.FitbitApiException
import com.liftcal.data.fitbit.FitbitIntradayParser
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FitbitIntradayParserTest {
    private val zone = ZoneId.of("Europe/London")

    @Test
    fun parsesHeartIntraday() {
        val body = """
            {"activities-heart":[{"dateTime":"2026-11-08","value":{"restingHeartRate":58}}],
             "activities-heart-intraday":{"dataset":[{"time":"18:00:00","value":82},{"time":"18:00:01","value":84}],
             "datasetInterval":1,"datasetType":"second"}}
        """.trimIndent()
        val s = FitbitIntradayParser.parseHeart(body, LocalDate.of(2026, 11, 8), zone)
        assertEquals(2, s.size)
        assertEquals(LocalDateTime.of(2026, 11, 8, 18, 0).atZone(zone).toInstant().toEpochMilli(), s[0].epochMs)
        assertEquals(84.0, s[1].bpm)
    }

    @Test
    fun missingIntradayIsEmpty() {
        assertEquals(0, FitbitIntradayParser.parseHeart("{}", LocalDate.of(2026, 1, 1), zone).size)
    }

    @Test
    fun sumsCalories() {
        val body = """{"activities-calories-intraday":{"dataset":[{"level":1,"mets":30,"time":"18:00:00","value":5.5},{"level":2,"mets":40,"time":"18:01:00","value":6.5}]}}"""
        assertEquals(12.0, FitbitIntradayParser.sumCalories(body), 1e-9)
    }
}

class FitbitApiClientTest {
    private val zone = ZoneId.of("UTC")

    @Test
    fun splitsSessionsAcrossMidnight() {
        val start = Instant.parse("2026-11-07T23:30:20Z")
        val end = Instant.parse("2026-11-08T00:40:10Z")
        val parts = FitbitApiClient.splitByDay(start, end, zone)
        assertEquals(2, parts.size)
        assertEquals(LocalDate.of(2026, 11, 7), parts[0].date)
        assertEquals("23:30", parts[0].from.toLocalTime().toString())
        assertEquals("23:59", parts[0].to.toLocalTime().toString())
        assertEquals("00:00", parts[1].from.toLocalTime().toString())
        assertEquals("00:41", parts[1].to.toLocalTime().toString())
    }

    @Test
    fun fetchesIntradayWithBearerToken() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                """{"activities-heart-intraday":{"dataset":[{"time":"10:00:05","value":100},{"time":"10:59:00","value":120},{"time":"11:30:00","value":90}]}}""",
            ),
        )
        server.start()
        val client = FitbitApiClient(OkHttpClient(), { "tok" }, server.url("/").toString())
        val s = client.intradayHeartRate(Instant.parse("2026-11-08T10:00:00Z"), Instant.parse("2026-11-08T11:00:00Z"), zone)
        val req = server.takeRequest()
        assertEquals("Bearer tok", req.getHeader("Authorization"))
        assertEquals("/1/user/-/activities/heart/date/2026-11-08/1d/1sec/time/10:00/11:00.json", req.path)
        assertEquals(listOf(100.0, 120.0), s.map { it.bpm })
        server.shutdown()
    }

    @Test
    fun surfacesHttpErrors() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"errors":[{"errorType":"insufficient_permissions"}]}"""))
        server.start()
        val client = FitbitApiClient(OkHttpClient(), { "tok" }, server.url("/").toString())
        val ex = assertFailsWith<FitbitApiException> {
            client.intradayHeartRate(Instant.parse("2026-11-08T10:00:00Z"), Instant.parse("2026-11-08T10:10:00Z"), zone)
        }
        assertEquals(403, ex.code)
        server.shutdown()
    }
}
