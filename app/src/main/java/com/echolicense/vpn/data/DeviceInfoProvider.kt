package com.echolicense.vpn.data

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import java.security.MessageDigest

object DeviceInfoProvider {

    @SuppressLint("HardwareIds")
    fun getHwid(context: Context): String {
        val rawId = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull().orEmpty()

        val source = if (rawId.isNotBlank()) "echo_$rawId" else "echo_${Build.BRAND}_${Build.MODEL}_${Build.DEVICE}"
        val digest = MessageDigest.getInstance("SHA-256").digest(source.toByteArray())
        return digest.take(16).joinToString("") { "%02x".format(it) }
    }

    fun getDeviceHeaders(context: Context): Map<String, String> {
        val model = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        return mapOf(
            "x-hwid" to getHwid(context),
            "x-device-os" to "Android",
            "x-ver-os" to (Build.VERSION.RELEASE ?: "14"),
            "x-device-model" to model
        )
    }
}
