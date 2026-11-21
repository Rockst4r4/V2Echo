package com.echolicense.vpn.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.echolicense.vpn.MainActivity
import com.echolicense.vpn.data.DynamicAnnouncement

object NotificationHelper {
    private const val TRAFFIC_CHANNEL_ID = "echolicense_traffic_alerts"
    private const val PROMO_CHANNEL_ID = "echolicense_dynamic_promos"

    fun showLowTrafficNotification(context: Context, remainingGbText: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    TRAFFIC_CHANNEL_ID,
                    "هشدار اتمام حجم اشتراک",
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
        }

        val openIntent = PendingIntent.getActivity(
            context,
            2005,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(context, TRAFFIC_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("⚠️ حجم اشتراک اکولایسنس در حال اتمام است")
            .setContentText("تنها $remainingGbText گیگابایت از ترافیک شما باقی مانده است.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "تنها $remainingGbText گیگابایت از حجم اشتراک اکولایسنس شما باقی مانده است. برای تمدید سریع وارد ربات @echopress_bot شوید."
                )
            )
            .setContentIntent(openIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        nm.notify(2005, notif)
    }

    fun showDynamicAnnouncementNotification(context: Context, item: DynamicAnnouncement) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    PROMO_CHANNEL_ID,
                    "اطلاعیه‌ها و کدهای تخفیف اکولایسنس",
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
        }

        val openIntent = PendingIntent.getActivity(
            context,
            3000 + item.id,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val bodyText = buildString {
            append(item.message)
            if (item.promoCode.isNotBlank()) {
                append("\n🎁 کد تخفیف: ${item.promoCode}")
            }
        }

        val notif = NotificationCompat.Builder(context, PROMO_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(item.title.ifBlank { "اطلاعیه جدید اکولایسنس" })
            .setContentText(bodyText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bodyText))
            .setContentIntent(openIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        nm.notify(3000 + item.id, notif)
    }
}
