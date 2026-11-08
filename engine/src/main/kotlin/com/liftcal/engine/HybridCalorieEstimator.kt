package com.liftcal.engine

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Per-second hybrid estimator:
 *  - inside sets: anaerobic cost from mechanical work + inverse-variance fusion of
 *    (down-weighted) HR-derived aerobic cost with the model's aerobic + EPOC cost;
 *  - between sets: smooth max of HR-measured recovery and predicted EPOC + movement;
 *  - after the session: analytic EPOC tail.
 */
class HybridCalorieEstimator(
    private val config: EngineConfig = EngineConfig(),
) : CalorieEstimator {

    private val hrProcessor = HeartRateProcessor(config)
    private val aerobic = AerobicModel(config)
    private val mechanical = MechanicalWorkModel()
    private val epoc = EpocModel(config)

    override fun estimate(
        session: WorkoutSession,
        hr: List<HrSample>,
        profile: UserProfile,
        calib: Calibration,
    ): CalorieResult {
        val n = max(1, ceil((session.endMs - session.startMs) / 1000.0).toInt())
        val restingRate = RestingEnergy.kcalPerSecond(profile)
        val processed = hrProcessor.process(hr, session.startMs, session.endMs, profile, calib)
        val q = processed.quality

        val sets = session.sortedSets
        val costs = sets.map { mechanical.cost(it, profile, calib) }
        val debts = costs.map { epoc.debtKcal(it.anaerobicKcal, calib) }

        val segment = Array(n) { SegmentType.REST }
        val setAt = IntArray(n) { -1 }
        val startIdx = IntArray(sets.size)
        val endIdx = IntArray(sets.size)
        sets.forEachIndexed { i, s ->
            val a = ((s.startMs - session.startMs) / 1000).toInt().coerceIn(0, n - 1)
            val b = ceil((s.endMs - session.startMs) / 1000.0).toInt().coerceIn(a + 1, n)
            startIdx[i] = a
            endIdx[i] = b
            for (t in a until b) {
                segment[t] = if (s.isWarmup) SegmentType.WARMUP else SegmentType.SET
                setAt[t] = i
            }
        }

        val movementRate = aerobic.vo2ToKcalPerSecond(config.betweenSetMovementVo2, profile)
        val noiseScale = 1.0 / sqrt(1.0 + 0.5 * calib.referenceSessions)
        val timeline = DoubleArray(n)
        val attributed = DoubleArray(sets.size)
        val epocBySet = DoubleArray(sets.size)
        var activeInSession = 0.0
        var epocInSession = 0.0
        var sigmaSum = 0.0
        var sigmaSqSum = 0.0
        var owner = -1

        for (t in 0 until n) {
            while (owner + 1 < sets.size && startIdx[owner + 1] <= t) owner++

            var epocRate = 0.0
            for (i in sets.indices) {
                if (t >= endIdx[i]) {
                    val r = epoc.rate(debts[i], t - endIdx[i] + 0.5)
                    epocRate += r
                    epocBySet[i] += r
                }
            }
            epocInSession += epocRate

            val hrNet = if (processed.isValid(t)) aerobic.netKcalPerSecond(processed.bpm[t], profile, calib) else null
            val active: Double
            val sigma: Double
            val i = setAt[t]
            if (i >= 0) {
                val dur = (endIdx[i] - startIdx[i]).toDouble()
                val anaerobicRate = costs[i].anaerobicKcal / dur
                val model = costs[i].aerobicKcal / dur + epocRate
                val f = Fusion.inverseVariance(
                    hrNet?.times(calib.inSetHrWeight), config.cvHrInSet, q, model, config.cvModel,
                )
                active = anaerobicRate + f.value
                val anaerobicSigma = anaerobicRate * config.cvAnaerobic
                sigma = sqrt(f.sigma * f.sigma + anaerobicSigma * anaerobicSigma)
            } else {
                val f = Fusion.recovery(hrNet, config.cvHrRest, q, movementRate + epocRate, config.cvModel)
                active = f.value
                sigma = f.sigma
            }

            val s = sigma * noiseScale
            sigmaSum += s
            sigmaSqSum += s * s
            activeInSession += active
            timeline[t] = restingRate + active
            if (owner >= 0) attributed[owner] += active
        }

        var tail = 0.0
        for (i in sets.indices) {
            val rem = epoc.remaining(debts[i], (n - endIdx[i]).toDouble())
            tail += rem
            attributed[i] += rem
            epocBySet[i] += rem
        }
        sigmaSum += tail * config.cvModel * noiseScale

        val restingTotal = restingRate * n
        val activeTotal = activeInSession + tail
        val total = restingTotal + activeTotal

        val rho = config.noiseCorrelation
        val noiseSigma = sqrt(rho * sigmaSum * sigmaSum + (1 - rho) * sigmaSqSum)
        // Between-person spread of the HR-VO2 relation during lifting dominates uncalibrated error.
        val paramRel = (0.15 +
            (if (profile.hasMeasuredMaxHr) 0.0 else 0.06) +
            (if (profile.hasMeasuredVo2Max) 0.0 else 0.06)) / sqrt(1.0 + calib.referenceSessions)
        val paramSigma = activeTotal * paramRel
        val restSigma = restingTotal * 0.08
        val sigma = sqrt(noiseSigma * noiseSigma + paramSigma * paramSigma + restSigma * restSigma)

        val perSet = sets.mapIndexed { i, s ->
            SetBreakdown(
                index = i,
                exerciseId = s.exerciseId,
                mechanicalWorkJ = costs[i].mechanicalWorkJ,
                anaerobicKcal = costs[i].anaerobicKcal,
                aerobicKcal = costs[i].aerobicKcal,
                attributedActiveKcal = attributed[i],
                epocKcal = epocBySet[i],
            )
        }

        return CalorieResult(
            totalKcal = total,
            activeKcal = activeTotal,
            restingKcal = restingTotal,
            low90 = max(0.0, total - Z90 * sigma),
            high90 = total + Z90 * sigma,
            perSet = perSet,
            epocKcal = epocInSession + tail,
            postSessionEpocKcal = tail,
            hrQuality = q,
            hrSourceUsed = processed.source,
            timeline = timeline,
        )
    }

    companion object {
        const val Z90 = 1.645
    }
}
