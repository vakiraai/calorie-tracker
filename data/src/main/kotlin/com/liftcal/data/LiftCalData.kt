package com.liftcal.data

import android.content.Context
import android.net.Uri
import com.liftcal.data.ble.BleHeartRateClient
import com.liftcal.data.db.LiftCalDatabase
import com.liftcal.data.fitbit.FitbitApiClient
import com.liftcal.data.fitbit.FitbitAuthManager
import com.liftcal.data.healthconnect.HealthConnectRepository
import com.liftcal.data.repo.CalibrationRepository
import com.liftcal.data.repo.ProfileRepository
import com.liftcal.data.repo.WorkoutRepository
import com.liftcal.data.sync.DefaultHeartRateSources
import com.liftcal.data.sync.SessionFinalizer
import com.liftcal.engine.HybridCalorieEstimator
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Implemented by the Application so background workers can reach the object graph. */
interface LiftCalDataProvider {
    val liftCalData: LiftCalData
}

/** Manual dependency graph for the data layer. */
class LiftCalData(context: Context, fitbitClientId: String, fitbitRedirectUri: Uri) {
    private val appContext = context.applicationContext

    val db: LiftCalDatabase by lazy { LiftCalDatabase.create(appContext) }
    val estimator = HybridCalorieEstimator()
    val healthConnect = HealthConnectRepository(appContext)
    val fitbitAuth = FitbitAuthManager(appContext, fitbitClientId, fitbitRedirectUri)

    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    }

    val fitbitApi: FitbitApiClient? by lazy {
        if (fitbitAuth.isConfigured) FitbitApiClient(http, { fitbitAuth.freshAccessToken() }) else null
    }

    val workouts by lazy { WorkoutRepository(db) }
    val profile by lazy { ProfileRepository(db) }
    val calibration by lazy { CalibrationRepository(db) }
    val ble by lazy { BleHeartRateClient(appContext) }

    val finalizer by lazy {
        SessionFinalizer(
            db,
            DefaultHeartRateSources(healthConnect, fitbitApi, { fitbitAuth.isAuthorized }),
            estimator,
        )
    }
}
