package com.liftcal.validation

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MetricsTest {
    @Test
    fun agreementKnownAnswer() {
        val s = Metrics.agreement(listOf(110.0, 90.0, 105.0), listOf(100.0, 100.0, 100.0))
        assertEquals(3, s.n)
        assertEquals((10.0 + 10.0 + 5.0) / 3, s.mape, 1e-9)
        assertEquals(5.0 / 3, s.biasPct, 1e-9)
        assertEquals(5.0 / 3, s.meanDiff, 1e-9)
        assertTrue(s.loaLow < s.meanDiff && s.meanDiff < s.loaHigh)
    }

    @Test
    fun perfectAgreementHasUnitCcc() {
        val x = listOf(100.0, 200.0, 300.0)
        assertEquals(1.0, Metrics.ccc(x, x), 1e-12)
        assertEquals(0.0, Metrics.agreement(x, x).mape, 1e-12)
    }
}

class HarnessTest {
    @Test
    fun generatorIsDeterministic() {
        val a = SyntheticGenerator(5).generate(2, 2)
        val b = SyntheticGenerator(5).generate(2, 2)
        assertEquals(a.map { it.cartCsv }, b.map { it.cartCsv })
        assertEquals(a.map { it.fixture }, b.map { it.fixture })
    }

    @Test
    fun endToEndOnGeneratedFixtures() {
        val dir = Files.createTempDirectory("liftcal-fixtures").toFile()
        try {
            SyntheticGenerator(11).writeTo(dir, subjects = 2, sessionsPerSubject = 3)
            val loaded = Fixtures.loadDir(dir)
            assertEquals(6, loaded.size)
            val eval = Harness.evaluate(loaded)
            val prior = eval.stats(Harness.LIFTCAL_PRIOR)!!
            val loo = eval.stats(Harness.LIFTCAL_LOO)!!
            assertEquals(6, prior.n)
            assertTrue(loo.mape < prior.mape, "calibration should help: ${loo.mape} vs ${prior.mape}")
            val md = Report.markdown(eval)
            assertTrue("All 6 sessions are synthetic" in md)
            assertTrue(Harness.KEYTEL in md && Harness.MET in md)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun handWrittenFixtureWithTotalReferenceAndDevices() {
        val dir = Files.createTempDirectory("liftcal-fixture").toFile()
        try {
            File(dir, "real.json").writeText(
                """
                {"id":"lab-01","subject":"P1","profile":{"ageYears":31,"sex":"female","weightKg":62,"heightCm":168,"maxHr":186},
                 "session":{"startMs":1780000000000,"durationSec":1800,"sets":[
                   {"exerciseId":"back_squat","loadKg":50,"reps":8,"startSec":120,"endSec":150},
                   {"exerciseId":"back_squat","loadKg":50,"reps":8,"startSec":300,"endSec":330}]},
                 "hr":[{"source":"wrist","samples":[[0,80],[150,140],[300,110],[330,145],[1800,95]]}],
                 "reference":{"totalKcal":150,"kind":"cart"},
                 "devices":{"apple_watch":190,"garmin":120}}
                """.trimIndent(),
            )
            val eval = Harness.evaluate(Fixtures.loadDir(dir))
            val s = eval.sessions.single()
            assertEquals(150.0, s.referenceKcal)
            assertTrue("Device: apple_watch" in s.estimates && "Device: garmin" in s.estimates)
            assertTrue(Harness.LIFTCAL_LOO !in s.estimates)
        } finally {
            dir.deleteRecursively()
        }
    }
}
