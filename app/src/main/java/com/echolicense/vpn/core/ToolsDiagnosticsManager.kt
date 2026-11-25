package com.echolicense.vpn.core

import android.content.Context
import android.content.Intent

object ToolsDiagnosticsManager {

    fun loadInstalledUserApps(context: Context): List<Pair<String, String>> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val myPkg = context.packageName
        return runCatching {
            pm.queryIntentActivities(intent, 0)
                .mapNotNull { ri ->
                    val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
                    if (pkg == myPkg) return@mapNotNull null
                    val label = ri.loadLabel(pm)?.toString()?.trim().orEmpty().ifBlank { pkg }
                    pkg to label
                }
                .distinctBy { it.first }
                .sortedBy { it.second.lowercase() }
        }.getOrDefault(emptyList())
    }
}
