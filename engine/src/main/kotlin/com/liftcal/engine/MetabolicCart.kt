package com.liftcal.engine

/** One breath (or averaged epoch) from an indirect calorimetry system. */
data class GasExchangeSample(val epochMs: Long, val vo2MlMin: Double, val vco2MlMin: Double)

object MetabolicCart {

    /** Abbreviated Weir equation, kcal/min from L/min. */
    fun weirKcalPerMin(vo2LMin: Double, vco2LMin: Double): Double = 3.941 * vo2LMin + 1.106 * vco2LMin

    /**
     * Parses CSV with header containing `time_s` (seconds from [t0Ms]) or `epoch_ms`, plus
     * `vo2_ml_min` and `vco2_ml_min`. Column order is free; extra columns are ignored.
     */
    fun parseCsv(text: String, t0Ms: Long = 0L): List<GasExchangeSample> {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
        require(lines.isNotEmpty()) { "empty CSV" }
        val header = lines.first().split(',').map { it.trim().lowercase() }
        val iTime = header.indexOf("time_s")
        val iEpoch = header.indexOf("epoch_ms")
        val iVo2 = header.indexOf("vo2_ml_min")
        val iVco2 = header.indexOf("vco2_ml_min")
        require(iTime >= 0 || iEpoch >= 0) { "CSV needs a time_s or epoch_ms column" }
        require(iVo2 >= 0 && iVco2 >= 0) { "CSV needs vo2_ml_min and vco2_ml_min columns" }
        return lines.drop(1).mapNotNull { line ->
            val c = line.split(',').map { it.trim() }
            val t = if (iEpoch >= 0) c.getOrNull(iEpoch)?.toLongOrNull()
            else c.getOrNull(iTime)?.toDoubleOrNull()?.let { t0Ms + (it * 1000).toLong() }
            val vo2 = c.getOrNull(iVo2)?.toDoubleOrNull()
            val vco2 = c.getOrNull(iVco2)?.toDoubleOrNull()
            if (t == null || vo2 == null || vco2 == null) null else GasExchangeSample(t, vo2, vco2)
        }.sortedBy { it.epochMs }
    }

    /** Integrates energy expenditure over [fromMs, toMs) by holding each sample until the next. */
    fun kcalBetween(samples: List<GasExchangeSample>, fromMs: Long, toMs: Long): Double {
        var kcal = 0.0
        for (i in samples.indices) {
            val s = samples[i]
            val next = if (i + 1 < samples.size) samples[i + 1].epochMs else s.epochMs + 1000
            val a = maxOf(s.epochMs, fromMs)
            val b = minOf(next, toMs)
            if (b > a) kcal += weirKcalPerMin(s.vo2MlMin / 1000, s.vco2MlMin / 1000) * (b - a) / 60_000.0
        }
        return kcal
    }

    fun windows(samples: List<GasExchangeSample>, session: WorkoutSession, windowSec: Int = 300): List<ReferenceWindow> {
        val out = ArrayList<ReferenceWindow>()
        var a = session.startMs
        while (a < session.endMs) {
            val b = minOf(a + windowSec * 1000L, session.endMs)
            val kcal = kcalBetween(samples, a, b)
            if (kcal > 0) out += ReferenceWindow(a, b, kcal)
            a = b
        }
        return out
    }
}
