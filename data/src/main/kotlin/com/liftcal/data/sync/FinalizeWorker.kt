package com.liftcal.data.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.liftcal.data.LiftCalDataProvider
import java.util.concurrent.TimeUnit

/** Re-runs finalisation for sessions waiting on a Fitbit sync. */
class FinalizeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val data = (applicationContext as? LiftCalDataProvider)?.liftCalData ?: return Result.failure()
        return runCatching { data.finalizer.finalizePending() }
            .fold(onSuccess = { Result.success() }, onFailure = { Result.retry() })
    }

    companion object {
        private const val PERIODIC = "liftcal-finalize-periodic"
        private const val ONE_SHOT = "liftcal-finalize-soon"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            val wm = WorkManager.getInstance(context)
            wm.enqueueUniquePeriodicWork(
                PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<FinalizeWorker>(15, TimeUnit.MINUTES).setConstraints(constraints).build(),
            )
            wm.enqueueUniqueWork(
                ONE_SHOT,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<FinalizeWorker>()
                    .setInitialDelay(3, TimeUnit.MINUTES)
                    .setConstraints(constraints)
                    .build(),
            )
        }
    }
}
