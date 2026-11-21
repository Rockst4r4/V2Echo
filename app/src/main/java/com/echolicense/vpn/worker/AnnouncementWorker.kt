package com.echolicense.vpn.worker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.echolicense.vpn.MainActivity
import com.echolicense.vpn.R
import com.echolicense.vpn.core.VpnStateManager
import com.echolicense.vpn.data.DynamicAnnouncement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class AnnouncementWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val WORK_NAME = "echolicense_remote_notif_15m"
        private const val CHANNEL_ID = "echolicense_push_announcements"
        private const val KEY_DISMISSED_ID = "dismissed_announcement_id"
        private const val KEY_LAST_NOTIFIED_ID = "last_notified_announcement_id"

        private val ENDPOINTS = listOf(
            "https://raw.githubusercontent.com/Rockst4r4/V2Echo/main/announcements.json",
            "https://cdn.jsdelivr.net/gh/Rockst4r4/V2Echo@main/announcements.json"
        )

        private val httpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<AnnouncementWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        fun dismissBanner(context: Context, id: Int) {
            val prefs = context.getSharedPreferences(TrafficAlertWorker.PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putInt(KEY_DISMISSED_ID, id).apply()
            VpnStateManager.update { it.copy(announcement = null) }
        }

        suspend fun fetchAndProcess(context: Context) = withContext(Dispatchers.IO) {
            val body = fetchRawJson() ?: return@withContext
            val json = runCatching { JSONObject(body) }.getOrNull() ?: return@withContext

            val id = json.optInt("id", 0)
            val active = json.optBoolean("active", false)
            val target = json.optString("target", "all").lowercase()
            val pushEnabled = json.optBoolean("push_notification", true)
            val showBanner = json.optBoolean("show_banner", true)
            val title = json.optString("title", "").trim()
            val message = json.optString("message", "").trim()
            val promoCode = json.optString("promo_code", "").trim()
            val buttonText = json.optString("button_text", "").trim()
            val actionUrl = json.optString("action_url", "").trim()
            val botUsername = json.optString("bot_username", "echopress_bot").ifBlank { "echopress_bot" }

            if (target != "all" && target != "android") {
                VpnStateManager.update { it.copy(announcement = null, botUsername = botUsername) }
                return@withContext
            }

            val prefs = context.getSharedPreferences(TrafficAlertWorker.PREFS_NAME, Context.MODE_PRIVATE)
            val dismissedId = prefs.getInt(KEY_DISMISSED_ID, 0)
            val lastNotifiedId = prefs.getInt(KEY_LAST_NOTIFIED_ID, 0)

            val item = DynamicAnnouncement(
                id = id,
                active = active,
                title = title,
                message = message,
                promoCode = promoCode,
                buttonText = buttonText,
                actionUrl = actionUrl,
                botUsername = botUsername
            )

            VpnStateManager.update { state ->
                state.copy(
                    botUsername = botUsername,
                    announcement = if (active && showBanner && id > 0 && id != dismissedId) item else null
                )
            }

            if (active && pushEnabled && id > lastNotifiedId && title.isNotBlank()) {
                val shown = showPushNotification(context, item)
                if (shown) {
                    prefs.edit().putInt(KEY_LAST_NOTIFIED_ID, id).apply()
                }
            }
        }

        private fun fetchRawJson(): String? {
            val cacheBuster = System.currentTimeMillis() / 30000L
            for (baseUrl in ENDPOINTS) {
                val res = runCatching {
                    val req = Request.Builder()
                        .url("$baseUrl?t=$cacheBuster")
                        .header("Cache-Control", "no-cache")
                        .build()
                    httpClient.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) resp.body?.string()?.trim() else null
                    }
                }.getOrNull()
                if (!res.isNullOrBlank() && res.startsWith("{")) return res
            }
            return null
        }

        private fun showPushNotification(context: Context, item: DynamicAnnouncement): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val granted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
                if (!granted) return false
            }

            val nm = context.getSystemService(NotificationManager::class.java) ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Echolicense Notifications",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Announcements, offers & alerts from Echolicense"
                    enableVibration(true)
                }
                nm.createNotificationChannel(channel)
            }

            val clickIntent = if (item.actionUrl.startsWith("http://") || item.actionUrl.startsWith("https://")) {
                Intent(Intent.ACTION_VIEW, Uri.parse(item.actionUrl)).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            } else {
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
            }

            val pendingIntent = PendingIntent.getActivity(
                context,
                5000 + item.id,
                clickIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val fullMessage = buildString {
                if (item.message.isNotBlank()) append(item.message)
                if (item.promoCode.isNotBlank()) {
                    if (isNotEmpty()) append("\n")
                    append("Promo Code: ${item.promoCode}")
                }
            }.ifBlank { "Tap to open Echolicense VPN" }

            val notif = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_qs_tile)
                .setContentTitle(item.title)
                .setContentText(fullMessage)
                .setStyle(NotificationCompat.BigTextStyle().bigText(fullMessage))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

            return runCatching {
                nm.notify(5000 + item.id, notif)
                true
            }.getOrDefault(false)
        }
    }

    override suspend fun doWork(): Result {
        return try {
            fetchAndProcess(applicationContext)
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
