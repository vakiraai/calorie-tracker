package com.liftcal.data

import com.liftcal.data.ble.HeartRateMeasurementParser
import com.liftcal.data.sync.HrAlignment
import com.liftcal.data.sync.SyncState
import com.liftcal.engine.HrSample
import com.liftcal.engine.HrSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HrAlignmentTest {
    private val start = 1_000_000L
    private val end = start + 600_000L

    private fun series(step: Long, until: Long = end, source: HrSource = HrSource.WRIST) =
        (start..until step step).map { HrSample(it, 100.0, source) }

    @Test
    fun mergeDedupesPerSourcePerSecond() {
        val a = listOf(HrSample(1000, 80.0), HrSample(2000, 81.0))
        val b = listOf(HrSample(1500, 99.0), HrSample(2000, 82.0, HrSource.CHEST_STRAP))
        val m = HrAlignment.merge(a, b)
        assertEquals(3, m.size)
        assertEquals(listOf(1000L, 2000L, 2000L), m.map { it.epochMs })
    }

    @Test
    fun coverageAndFallbackDecision() {
        val dense = HrAlignment.coverage(series(1000), start, end)
        assertTrue(dense.coveredFraction > 0.99)
        assertFalse(HrAlignment.needsFallback(dense))
        val sparse = HrAlignment.coverage(series(60_000), start, end)
        assertTrue(HrAlignment.needsFallback(sparse))
    }

    @Test
    fun waitsForLateSync() {
        val partial = series(1000, until = start + 300_000)
        assertEquals(SyncState.WAITING, HrAlignment.syncState(partial, start, end, end + 60_000))
        assertEquals(SyncState.GAVE_UP, HrAlignment.syncState(partial, start, end, end + HrAlignment.MAX_WAIT_MS + 1))
        assertEquals(SyncState.COMPLETE, HrAlignment.syncState(series(1000), start, end, end + 60_000))
    }

    @Test
    fun chestStrapCompletesWithoutWristSync() {
        val chest = series(1000, source = HrSource.CHEST_STRAP)
        assertEquals(SyncState.COMPLETE, HrAlignment.syncState(chest, start, end, end + 1000))
    }
}

class HeartRateMeasurementParserTest {
    @Test
    fun uint8WithoutExtras() {
        val m = HeartRateMeasurementParser.parse(byteArrayOf(0x00, 72))!!
        assertEquals(72, m.bpm)
        assertNull(m.sensorContact)
        assertTrue(m.rrIntervalsMs.isEmpty())
    }

    @Test
    fun uint16WithContactEnergyAndRr() {
        // flags: uint16 | contact supported+detected | energy | rr
        val flags = 0x01 or 0x02 or 0x04 or 0x08 or 0x10
        val bytes = byteArrayOf(
            flags.toByte(), 0x2C, 0x01, // 300 bpm (little endian)
            0x10, 0x00, // 16 kJ
            0x00, 0x04, // 1024/1024 s = 1000 ms
            0x00, 0x02, // 512/1024 s = 500 ms
        )
        val m = HeartRateMeasurementParser.parse(bytes)!!
        assertEquals(300, m.bpm)
        assertEquals(true, m.sensorContact)
        assertEquals(16, m.energyExpendedKj)
        assertEquals(listOf(1000.0, 500.0), m.rrIntervalsMs)
    }

    @Test
    fun rejectsTruncated() {
        assertNull(HeartRateMeasurementParser.parse(byteArrayOf(0x01, 0x10)))
        assertNull(HeartRateMeasurementParser.parse(byteArrayOf()))
    }
}
