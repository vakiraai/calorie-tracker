package com.liftcal.app

import android.app.Application
import android.net.Uri
import com.liftcal.data.LiftCalData
import com.liftcal.data.LiftCalDataProvider
import com.liftcal.data.sync.FinalizeWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers

class LiftCalApp : Application(), LiftCalDataProvider {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val liftCalData: LiftCalData by lazy {
        LiftCalData(this, BuildConfig.FITBIT_CLIENT_ID, Uri.parse("com.liftcal.app:/oauth2redirect"))
    }

    val liveHr: LiveHeartRate by lazy { LiveHeartRate(liftCalData, appScope) }

    override fun onCreate() {
        super.onCreate()
        FinalizeWorker.schedule(this)
    }
}
