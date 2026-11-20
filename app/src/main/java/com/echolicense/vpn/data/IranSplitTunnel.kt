package com.echolicense.vpn.data

import android.content.Context
import android.content.pm.PackageManager

object IranSplitTunnel {
    val DEFAULT_IRANIAN_PACKAGES: Set<String> = setOf(
        "cab.snapp.passenger",
        "cab.snapp.driver",
        "com.zoodfood.android",
        "taxi.tap30.passenger",
        "ir.divar",
        "com.digikala",
        "ir.torob.android",
        "ir.balad",
        "org.rajman.neshan.traffic.tehran.navigator",
        "ir.mci.ecareapp",
        "com.mtnirancell.myirancell",
        "ir.rightel.myrightel",
        "app.rbmain.a",
        "ir.nasim",
        "ir.eitaa.messenger",
        "mobi.mmdt.ottplus",
        "com.aparat",
        "com.aparat.filimo",
        "com.namava.app",
        "com.samanpr.blu",
        "com.bmi.bam",
        "com.behpardakht.mellat",
        "ir.tejaratbank.tata.mobile.android.tejarat",
        "com.parsian.bank.mobile",
        "ir.pasargad.mobilebank",
        "com.sepah.mobilebank",
        "com.refahbank.mobilebank",
        "com.fardap.wepod",
        "com.fcp.saderat",
        "ir.shaparak.asanpardakht",
        "com.bpm.up",
        "com.tosan.mobilebanking.saman",
        "com.keshavarzi.mobilebank",
        "ir.bsi.mobilebank",
        "com.ayandehbank.mobilebank",
        "ir.tourismbank.mobile",
        "com.top.app",
        "ir.digipay.app"
    )

    fun getInstalledBypassPackages(context: Context): List<String> {
        val pm = context.packageManager
        val installed = mutableListOf<String>()
        installed.add(context.packageName)

        for (pkg in DEFAULT_IRANIAN_PACKAGES) {
            try {
                pm.getPackageInfo(pkg, PackageManager.GET_META_DATA)
                installed.add(pkg)
            } catch (_: PackageManager.NameNotFoundException) {
            }
        }
        return installed
    }
}
