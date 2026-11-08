package com.liftcal.validation

import com.liftcal.engine.Baselines
import com.liftcal.engine.Calibration
import com.liftcal.engine.CalibrationSample
import com.liftcal.engine.Calibrator
import com.liftcal.engine.CalorieEstimator
import com.liftcal.engine.GasExchangeSample
import com.liftcal.engine.HybridCalorieEstimator
import com.liftcal.engine.MetabolicCart
import java.io.File

data class SessionEval(
    val id: String,
    val subject: String,
    val synthetic: Boolean,
    val referenceKind: String,
    val referenceKcal: Double,
    /** Method name to session kcal over the reference window. */
    val estimates: Map<String, Double>,
    val intervalLow: Double,
    val intervalHigh: Double,
    val hrQuality: Double,
    /** LiftCal (priors) relative errors (%) for each 5-minute reference window. */
    val windowErrorsPct: List<Double>,
)

data class Evaluation(val sessions: List<SessionEval>) {
    val methods: List<String> get() = sessions.flatMap { it.estimates.keys }.distinct()

    fun stats(method: String): AgreementStats? {
        val rows = sessions.filter { method in it.estimates }
        if (rows.isEmpty()) return null
        return Metrics.agreement(rows.map { it.estimates.getValue(method) }, rows.map { it.referenceKcal })
    }

    val intervalCoverage: Double
        get() = sessions.count { it.referenceKcal in it.intervalLow..it.intervalHigh }.toDouble() / sessions.size
}

object Harness {
    const val LIFTCAL_PRIOR = "LiftCal (population priors)"
    const val LIFTCAL_LOO = "LiftCal (personal calibration, leave-one-out)"
    const val KEYTEL = "HR-only (Keytel 2005, typical wearable)"
    const val MET = "MET table (5.0 METs)"

    private class Prepared(val loaded: Fixtures.Loaded, val cart: List<GasExchangeSample>?) {
        val f get() = loaded.fixture
        val session by lazy { f.toSession() }
        val hr by lazy { f.hrSamples() }
        val profile by lazy { f.profile.toUserProfile() }
        val referenceKcal: Double by lazy {
            cart?.let { MetabolicCart.kcalBetween(it, session.startMs, session.endMs) }
                ?: f.reference.totalKcal
                ?: error("fixture ${f.id} has no reference")
        }
    }

    fun evaluate(loaded: List<Fixtures.Loaded>, estimator: CalorieEstimator = HybridCalorieEstimator()): Evaluation {
        val prepared = loaded.map { l ->
            val cart = l.fixture.reference.cartCsv?.let { path ->
                MetabolicCart.parseCsv(File(l.dir, path).readText(), l.fixture.session.startMs)
            }
            Prepared(l, cart)
        }
        val calibrator = Calibrator(estimator)

        val sessions = prepared.map { p ->
            val s = p.session
            val r = estimator.estimate(s, p.hr, p.profile, Calibration.PRIOR)
            val windowKcal = r.totalBetween(s.startMs, s.startMs, s.endMs)
            val tail = r.totalKcal - r.timeline.sum()
            val estimates = linkedMapOf(
                LIFTCAL_PRIOR to windowKcal,
                KEYTEL to Baselines.keytel(s, p.hr, p.profile),
                MET to Baselines.metTable(s, p.profile),
            )

            val others = prepared.filter { it !== p && it.f.subject == p.f.subject && it.cart != null }
            if (others.isNotEmpty()) {
                val samples = others.map { o -> CalibrationSample(o.session, o.hr, MetabolicCart.windows(o.cart!!, o.session)) }
                val fit = calibrator.fit(samples, p.profile)
                val rc = estimator.estimate(s, p.hr, p.profile, fit.calibration)
                estimates[LIFTCAL_LOO] = rc.totalBetween(s.startMs, s.startMs, s.endMs)
            }
            p.f.devices.forEach { (name, kcal) -> estimates["Device: $name"] = kcal }

            val windowErrors = p.cart?.let { cart ->
                MetabolicCart.windows(cart, s).map { w ->
                    (r.totalBetween(s.startMs, w.startMs, w.endMs) - w.totalKcal) / w.totalKcal * 100
                }
            }.orEmpty()

            SessionEval(
                id = p.f.id,
                subject = p.f.subject,
                synthetic = p.f.synthetic,
                referenceKind = p.f.reference.kind,
                referenceKcal = p.referenceKcal,
                estimates = estimates,
                intervalLow = r.low90 - tail,
                intervalHigh = r.high90 - tail,
                hrQuality = r.hrQuality,
                windowErrorsPct = windowErrors,
            )
        }
        return Evaluation(sessions)
    }
}
