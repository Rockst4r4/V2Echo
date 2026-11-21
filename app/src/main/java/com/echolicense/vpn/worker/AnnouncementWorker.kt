package com.echolicense.vpn.worker

import android.content.Context
import androidx.work.*
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
        const val ANNOUNCEMENT_URL = "https://raw.githubusercontent.com/Rockst4r4/V2Echo/main/announcements.json"
        private const val KEY_LAST_NOTIFIED_ID = "last_notified_announcement_id"
        private const val KEY_DISMISSED_ID = "dismissed_announcement_id"
        private const val WORK_NAME = "echolicense_dynamic_announcements"

        private val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<AnnouncementWorker>(30, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun dismissBanner(context: Context, id: Int) {
            context.getSharedPreferences(TrafficAlertWorker.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_DISMISSED_ID, id)
                .apply()
            VpnStateManager.update { it.copy(announcement = null) }
        }

        suspend fun fetchAndProcess(context: Context): DynamicAnnouncement? = withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("$ANNOUNCEMENT_URL?t=${System.currentTimeMillis() / 60000}")
                    .header("Cache-Control", "no-cache")
                    .build()

                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@runCatching null
                    val body = resp.body?.string().orEmpty()
                    val json = JSONObject(body)
                    val item = DynamicAnnouncement(
                        id = json.optInt("id", 0),
                        active = json.optBoolean("active", false),
                        title = json.optString("title", ""),
                        message = json.optString("message", ""),
                        promoCode = json.optString("promo_code", ""),
                        buttonText = json.optString("button_text", ""),
                        actionUrl = json.optString("action_url", ""),
                        botUsername = json.optString("bot_username", "echopress_bot").ifBlank { "echopress_bot" }
                    )

                    val prefs = context.getSharedPreferences(TrafficAlertWorker.PREFS_NAME, Context.MODE_PRIVATE)
                    val lastNotified = prefs.getInt(KEY_LAST_NOTIFIED_ID, 0)
                    val dismissedId = prefs.getInt(KEY_DISMISSED_ID, 0)

                    if (item.active && item.id > lastNotified) {
                        NotificationHelper.showDynamicAnnouncementNotification(context, item)
                        prefs.edit().putInt(KEY_LAST_NOTIFIED_ID, item.id).apply()
                    }

                    VpnStateManager.update {
                        it.copy(
                            botUsername = item.botUsername,
                            announcement = if (item.active && item.id != dismissedId) item else null
                        )
                    }
                    item
                }
            }.getOrNull()
        }
    }

    override suspend fun doWork(): Result {
        fetchAndProcess(applicationContext)
        return Result.success()
    }
}
