package com.echolicense.vpn.core

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.echolicense.vpn.BuildConfig
import java.util.concurrent.TimeUnit

class UpdateCheckWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val info = runCatching {
            AppUpdater.checkForUpdate(BuildConfig.VERSION_NAME, BuildConfig.BUILD_EPOCH)
        }.getOrNull()

        if (info != null && info.hasUpdate) {
            VpnStateManager.update { st ->
                st.copy(
                    updateAvailable = true,
                    updateTag = info.latestTag,
                    updateUrl = info.downloadUrl,
                    updateFileName = info.fileName,
                    updateSizeMb = info.sizeMb
                )
            }
            AppUpdateNotifier.notifyIfNew(applicationContext, info)
        }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "echolicense_periodic_update_check"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val req = PeriodicWorkRequestBuilder<UpdateCheckWorker>(2, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                req
            )
        }
    }
}
