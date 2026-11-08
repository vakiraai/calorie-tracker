package com.liftcal.engine

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min

/**
 * Heart rate on a 1 Hz grid aligned to the session start. [bpm] entries are NaN where no
 * trustworthy data exists.
 */
class ProcessedHr(
    val bpm: DoubleArray,
    /** 0..1 overall signal quality (coverage x density x artefact rate x source reliability). */
    val quality: Double,
    val source: HrSource?,
    val coverage: Double,
    val artefactFraction: Double,
) {
    fun isValid(i: Int): Boolean = i in bpm.indices && !bpm[i].isNaN()
}

class HeartRateProcessor(private val config: EngineConfig = EngineConfig()) {

    fun process(
        samples: List<HrSample>,
        startMs: Long,
        endMs: Long,
        profile: UserProfile,
        calib: Calibration,
    ): ProcessedHr {
        val n = ceil((endMs - startMs) / 1000.0).toInt()
        val (source, chosen) = selectSource(samples, startMs, endMs)
        if (chosen.size < 2) {
            return ProcessedHr(DoubleArray(n) { Double.NaN }, 0.0, source, 0.0, 0.0)
        }

        val inRange = chosen.filter { it.bpm in 30.0..230.0 }
        val cleaned = removeArtefacts(inRange)
        val artefactFraction = 1.0 - cleaned.size.toDouble() / chosen.size

        val grid = resample(cleaned, startMs, n)
        val smoothed = movingAverage(grid, 5)
        val corrected = lagCorrect(smoothed, profile)

        val validCount = corrected.count { !it.isNaN() }
        val coverage = if (n == 0) 0.0 else validCount.toDouble() / n
        val windowed = cleaned.count { it.epochMs in startMs..endMs }
        val samplesPerMin = windowed / ((endMs - startMs) / 60_000.0)
        val density = min(1.0, samplesPerMin / 12.0)
        val reliability = if (source == HrSource.CHEST_STRAP) 1.0 else calib.wristHrReliability.coerceIn(0.0, 1.0)
        val quality = (coverage * density * (1.0 - artefactFraction) * reliability).coerceIn(0.0, 1.0)

        return ProcessedHr(corrected, quality, source, coverage, artefactFraction)
    }

    internal fun selectSource(samples: List<HrSample>, startMs: Long, endMs: Long): Pair<HrSource?, List<HrSample>> {
        val window = samples.filter { it.epochMs in (startMs - 60_000)..(endMs + 60_000) }.sortedBy { it.epochMs }
        if (window.isEmpty()) return null to emptyList()
        val chest = window.filter { it.source == HrSource.CHEST_STRAP }
        val wrist = window.filter { it.source == HrSource.WRIST }
        val chestCoverage = coverageOf(chest, startMs, endMs)
        return if (chest.size >= 2 && chestCoverage >= config.chestStrapMinCoverage) {
            HrSource.CHEST_STRAP to chest
        } else if (wrist.size >= 2) {
            HrSource.WRIST to wrist
        } else {
            HrSource.CHEST_STRAP to chest
        }
    }

    private fun coverageOf(s: List<HrSample>, startMs: Long, endMs: Long): Double {
        if (s.size < 2) return 0.0
        var covered = 0L
        for (i in 1 until s.size) {
            val dt = s[i].epochMs - s[i - 1].epochMs
            if (dt <= config.maxHrGapSec * 1000L) {
                val a = maxOf(s[i - 1].epochMs, startMs)
                val b = minOf(s[i].epochMs, endMs)
                if (b > a) covered += b - a
            }
        }
        return covered.toDouble() / (endMs - startMs)
    }

    /** Drops samples that deviate from the median of their +/-10 s neighbourhood. */
    internal fun removeArtefacts(s: List<HrSample>): List<HrSample> {
        if (s.size < 5) return s
        val out = ArrayList<HrSample>(s.size)
        var lo = 0
        var hi = 0
        for (i in s.indices) {
            val t = s[i].epochMs
            while (s[lo].epochMs < t - 10_000) lo++
            while (hi + 1 < s.size && s[hi + 1].epochMs <= t + 10_000) hi++
            val neighbours = (lo..hi).filter { it != i }.map { s[it].bpm }
            if (neighbours.size < 2) {
                out += s[i]
                continue
            }
            val med = neighbours.sorted().let { if (it.size % 2 == 1) it[it.size / 2] else (it[it.size / 2 - 1] + it[it.size / 2]) / 2 }
            if (abs(s[i].bpm - med) <= config.artefactThresholdBpm) out += s[i]
        }
        return out
    }

    internal fun resample(s: List<HrSample>, startMs: Long, n: Int): DoubleArray {
        val out = DoubleArray(n) { Double.NaN }
        if (s.isEmpty()) return out
        val maxGapMs = config.maxHrGapSec * 1000L
        var j = 0
        for (i in 0 until n) {
            val t = startMs + i * 1000L + 500L
            while (j + 1 < s.size && s[j + 1].epochMs <= t) j++
            val a = s[j]
            if (a.epochMs > t) {
                if (a.epochMs - t <= 5_000) out[i] = a.bpm
                continue
            }
            if (j + 1 < s.size) {
                val b = s[j + 1]
                if (b.epochMs - a.epochMs <= maxGapMs) {
                    val f = (t - a.epochMs).toDouble() / (b.epochMs - a.epochMs)
                    out[i] = a.bpm + f * (b.bpm - a.bpm)
                }
            } else if (t - a.epochMs <= 5_000) {
                out[i] = a.bpm
            }
        }
        return out
    }

    internal fun movingAverage(x: DoubleArray, window: Int): DoubleArray {
        val half = window / 2
        return DoubleArray(x.size) { i ->
            if (x[i].isNaN()) return@DoubleArray Double.NaN
            var sum = 0.0
            var c = 0
            for (k in (i - half)..(i + half)) {
                if (k in x.indices && !x[k].isNaN()) {
                    sum += x[k]; c++
                }
            }
            sum / c
        }
    }

    /**
     * Inverts first-order HR kinetics (hr' = (drive - hr) / tau) to estimate the underlying
     * metabolic drive: drive = hr + tau * dhr/dt. Over a closed interval the derivative term
     * integrates to roughly zero, so energy is shifted earlier in time rather than created.
     */
    internal fun lagCorrect(x: DoubleArray, profile: UserProfile): DoubleArray {
        val lower = profile.effectiveRestingHr * 0.8
        val upper = profile.effectiveMaxHr
        return DoubleArray(x.size) { i ->
            if (x[i].isNaN()) return@DoubleArray Double.NaN
            val a = (i - 3).coerceAtLeast(0)
            val b = (i + 3).coerceAtMost(x.size - 1)
            val slope = if (b > a && !x[a].isNaN() && !x[b].isNaN()) (x[b] - x[a]) / (b - a) else 0.0
            val corr = (config.hrLagTauSec * slope).coerceIn(-config.maxLagCorrectionBpm, config.maxLagCorrectionBpm)
            (x[i] + corr).coerceIn(lower, upper)
        }
    }
}
