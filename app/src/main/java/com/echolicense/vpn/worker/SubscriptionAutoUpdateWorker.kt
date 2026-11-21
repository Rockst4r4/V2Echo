package com.echolicense.vpn.worker

import android.content.Context
import androidx.work.*
import com.echolicense.vpn.core.VpnStateManager
import com.echolicense.vpn.data.ConnectionState
import com.echolicense.vpn.data.SubscriptionRepository
import java.util.concurrent.TimeUnit

class SubscriptionAutoUpdateWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val WORK_NAME = "echolicense_sub_auto_update_12h"
        const val KEY_LAST_SUB_UPDATED_AT = "last_sub_updated_at"

        fun schedule(context: Context, replace: Boolean = false) {
            val request = PeriodicWorkRequestBuilder<SubscriptionAutoUpdateWorker>(12, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                if (replace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences(TrafficAlertWorker.PREFS_NAME, Context.MODE_PRIVATE)
        val subUrl = prefs.getString(TrafficAlertWorker.KEY_SUB_URL, null)
        if (subUrl.isNullOrBlank()) return Result.success()

        return try {
            val repo = SubscriptionRepository()
            val (subInfo, servers) = repo.fetchSubscription(subUrl, applicationContext)
            val sorted = repo.pingAllServers(servers)
            val now = System.currentTimeMillis()
            prefs.edit().putLong(KEY_LAST_SUB_UPDATED_AT, now).apply()

            VpnStateManager.update { state ->
                val currentActive = state.activeServer
                val matchedActive = if (state.connectionState == ConnectionState.CONNECTED && currentActive != null) {
                    sorted.firstOrNull { it.name == currentActive.name }
                        ?: sorted.firstOrNull { it.host == currentActive.host && it.port == currentActive.port }
                        ?: currentActive
                } else if (state.aiProOnly) {
                    sorted.firstOrNull { it.isAiPro() } ?: sorted.firstOrNull()
                } else {
                    sorted.firstOrNull()
                }

                state.copy(
                    subscriptionInfo = subInfo,
                    servers = sorted,
                    activeServer = matchedActive,
                    lastSubUpdatedAt = now
                )
            }

            TrafficAlertWorker.evaluateAndNotify(applicationContext, subInfo)
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
