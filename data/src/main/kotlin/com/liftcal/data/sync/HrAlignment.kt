package com.liftcal.data.sync

import com.liftcal.engine.HrSample
import com.liftcal.engine.HrSource

enum class SyncState {
    /** Heart rate covers the whole session. */
    COMPLETE,

    /** Band data for the end of the session has not reached the phone yet. */
    WAITING,

    /** Waited long enough; finalise with what exists. */
    GAVE_UP,
}

data class HrCoverage(
    val samples: Int,
    val samplesPerMin: Double,
    val coveredFraction: Double,
    val lastSampleMs: Long?,
)

/**
 * Aligns heart-rate streams from several sources onto the session timeline. Fitbit syncs in
 * batches, so the most recent minutes of a workout often arrive after the user taps "finish".
 */
object HrAlignment {
    const val MAX_GAP_MS = 30_000L
    const val END_TOLERANCE_MS = 120_000L
    const val MAX_WAIT_MS = 48L * 3600 * 1000

    /** Sorted, de-duplicated (one sample per source per second) union of [lists]. */
    fun merge(vararg lists: List<HrSample>): List<HrSample> =
        lists.asSequence().flatten()
            .distinctBy { it.source to it.epochMs / 1000 }
            .sortedBy { it.epochMs }
            .toList()

    fun crop(samples: List<HrSample>, startMs: Long, endMs: Long, marginMs: Long = 60_000): List<HrSample> =
        samples.filter { it.epochMs in (startMs - marginMs)..(endMs + marginMs) }

    fun coverage(samples: List<HrSample>, startMs: Long, endMs: Long): HrCoverage {
        val inWindow = samples.filter { it.epochMs in startMs..endMs }.sortedBy { it.epochMs }
        var covered = 0L
        for (i in 1 until inWindow.size) {
            val dt = inWindow[i].epochMs - inWindow[i - 1].epochMs
            if (dt <= MAX_GAP_MS) covered += dt
        }
        val durationMs = (endMs - startMs).coerceAtLeast(1)
        return HrCoverage(
            samples = inWindow.size,
            samplesPerMin = inWindow.size / (durationMs / 60_000.0),
            coveredFraction = (covered.toDouble() / durationMs).coerceIn(0.0, 1.0),
            lastSampleMs = samples.maxOfOrNull { it.epochMs },
        )
    }

    /** Health Connect data is too sparse for set-level resolution; try Fitbit's 1 s intraday API. */
    fun needsFallback(c: HrCoverage): Boolean = c.samplesPerMin < 4.0 || c.coveredFraction < 0.7

    fun syncState(samples: List<HrSample>, startMs: Long, endMs: Long, nowMs: Long): SyncState {
        val chest = coverage(samples.filter { it.source == HrSource.CHEST_STRAP }, startMs, endMs)
        if (chest.coveredFraction >= 0.9) return SyncState.COMPLETE
        val wrist = coverage(samples.filter { it.source == HrSource.WRIST }, startMs, endMs)
        val last = wrist.lastSampleMs
        if (last != null && last >= endMs - END_TOLERANCE_MS) return SyncState.COMPLETE
        return if (nowMs - endMs > MAX_WAIT_MS) SyncState.GAVE_UP else SyncState.WAITING
    }
}
