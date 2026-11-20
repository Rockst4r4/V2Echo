package com.echolicense.vpn.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
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
        private const val CHANNEL_ID = "echolicense_traffic_alerts"
        private const val FIVE_GB_BYTES = 5L * 1024L * 1024L * 1024L

        fun schedule(context: Context, subUrl: String) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_SUB_URL, subUrl)
                .apply()

            val request = PeriodicWorkRequestBuilder<TrafficAlertWorker>(30, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "echolicense_traffic_watchdog",
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }
    }

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val subUrl = prefs.getString(KEY_SUB_URL, null) ?: return Result.success()

        return try {
            val (info, _) = SubscriptionRepository().fetchSubscription(subUrl)
            if (info.totalBytes <= 0L) return Result.success()

            val alreadyAlerted = prefs.getBoolean(KEY_ALERT_SENT, false)
            if (info.remainingBytes < FIVE_GB_BYTES && !alreadyAlerted) {
                val remText = String.format(Locale.US, "%.2f", info.remainingGb)
                sendLowTrafficNotification(remText)
                prefs.edit().putBoolean(KEY_ALERT_SENT, true).apply()
            } else if (info.remainingBytes >= FIVE_GB_BYTES && alreadyAlerted) {
                prefs.edit().putBoolean(KEY_ALERT_SENT, false).apply()
            }
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private fun sendLowTrafficNotification(remainingGbText: String) {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "هشدار اتمام حجم اشتراک",
                NotificationManager.IMPORTANCE_HIGH
            )
            nm.createNotificationChannel(channel)
        }

        val notif = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("⚠️ حجم اشتراک اکولایسنس در حال اتمام است")
            .setContentText("تنها $remainingGbText گیگابایت از ترافیک شما باقی مانده است.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "تنها $remainingGbText گیگابایت از حجم اشتراک اکولایسنس شما باقی مانده است. لطفاً پیش از اتمام حجم از طریق ربات تلگرام اقدام به تمدید نمایید."
                )
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        nm.notify(2005, notif)
    }
}
