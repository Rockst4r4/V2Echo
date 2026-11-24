package com.echolicense.vpn.core

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.echolicense.vpn.MainActivity
import com.echolicense.vpn.R
import com.echolicense.vpn.data.AppLanguage

object AppUpdateNotifier {
    private const val CHANNEL_ID = "echolicense_app_updates"
    private const val NOTIF_ID = 7749
    private const val PREFS_NAME = "echolicense_updater_prefs"
    private const val KEY_LAST_NOTIFIED = "last_notified_update_key"
    const val EXTRA_OPEN_UPDATE = "open_update_dialog"

    fun notifyIfNew(context: Context, info: AppUpdateInfo) {
        if (!info.hasUpdate || info.latestTag.isBlank()) return

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = "${info.latestTag}|${info.updatedAt}"
        if (prefs.getString(KEY_LAST_NOTIFIED, "") == key) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "App Updates",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifies when a new version of Echolicense VPN is released"
            }
            nm.createNotificationChannel(ch)
        }

        val isFa = VpnStateManager.state.value.language == AppLanguage.FA
        val title = if (isFa) "نسخه جدید اکولایسنس منتشر شد 🚀" else "New Echolicense Update Available 🚀"
        val body = if (isFa) {
            "نسخه ${info.latestTag} آماده دانلود است. برای به‌روزرسانی ضربه بزنید."
        } else {
            "Version ${info.latestTag} is now available. Tap to update."
        }

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(EXTRA_OPEN_UPDATE, true)
        }
        val pi = PendingIntent.getActivity(
            context,
            NOTIF_ID,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_qs_tile)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()

        runCatching {
            nm.notify(NOTIF_ID, notification)
            prefs.edit().putString(KEY_LAST_NOTIFIED, key).apply()
        }
    }
}
