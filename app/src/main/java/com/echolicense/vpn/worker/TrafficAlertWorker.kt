package com.echolicense.vpn.worker

import android.content.Context
import androidx.work.*
import com.echolicense.vpn.core.VpnStateManager
import com.echolicense.vpn.data.SubscriptionInfo
import com.echolicense.vpn.data.SubscriptionRepository
import java.util.Locale
import java.util.concurrent.TimeUnit

class TrafficAlertWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        const val PREFS_NAME = "echolicense_prefs"
        const val KEY_SUB_URL = "sub_url"
        private const val KEY_ALERT_SENT = "low_traffic_alert_sent"
        private const val WORK_NAME = "echolicense_traffic_watchdog"
        private const val FIVE_GB_BYTES = 5L * 1024L * 1024L * 1024L

        fun schedule(context: Context, subUrl: String) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_SUB_URL, subUrl)
                .putBoolean(KEY_ALERT_SENT, false)
                .apply()

            val request = PeriodicWorkRequestBuilder<TrafficAlertWorker>(30, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_SUB_URL)
                .putBoolean(KEY_ALERT_SENT, false)
                .apply()
        }

        fun evaluateAndNotify(context: Context, info: SubscriptionInfo) {
            if (info.totalBytes <= 0L) return
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val alreadyAlerted = prefs.getBoolean(KEY_ALERT_SENT, false)
            if (info.remainingBytes < FIVE_GB_BYTES && !alreadyAlerted) {
                val remText = String.format(Locale.US, "%.2f", info.remainingGb)
                NotificationHelper.showLowTrafficNotification(context, remText)
                prefs.edit().putBoolean(KEY_ALERT_SENT, true).apply()
            } else if (info.remainingBytes >= FIVE_GB_BYTES && alreadyAlerted) {
                prefs.edit().putBoolean(KEY_ALERT_SENT, false).apply()
            }
        }
    }

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val subUrl = prefs.getString(KEY_SUB_URL, null) ?: return Result.success()

        return try {
            val (info, _) = SubscriptionRepository().fetchSubscription(subUrl)
            VpnStateManager.update { it.copy(subscriptionInfo = info) }
            evaluateAndNotify(applicationContext, info)
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
