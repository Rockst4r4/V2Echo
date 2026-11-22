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

    val LAN_MIRROR_PACKAGES: Set<String> = setOf(
        "com.google.android.projection.gearhead",
        "com.samsung.android.smartmirroring",
        "com.sec.android.app.smartmirroring",
        "com.sec.android.desktopmode.uiservice",
        "com.samsung.android.dex",
        "com.samsung.android.mdx",
        "com.samsung.android.mdx.quickboard",
        "com.samsung.android.app.sharelive",
        "com.samsung.android.aware.service",
        "com.samsung.android.oneconnect",
        "com.samsung.android.allshare.service.mediashare",
        "com.samsung.android.allshare.service.fileshare",
        "com.google.android.apps.chromecast.app",
        "com.microsoft.appmanager",
        "com.qualcomm.wfd.service",
        "com.milink.service",
        "com.xiaomi.mirror",
        "com.miui.cast",
        "com.huawei.android.airsharing",
        "com.heytap.cast",
        "com.oplus.cast",
        "com.carbit.link",
        "net.easyconn",
        "com.zjinnova.zlink",
        "com.zjinnova.zlink5",
        "gb.xxy.hr"
    )

    fun getInstalledLanMirrorPackages(context: Context): List<String> {
        val pm = context.packageManager
        val installed = mutableListOf<String>()
        for (pkg in LAN_MIRROR_PACKAGES) {
            try {
                pm.getPackageInfo(pkg, PackageManager.GET_META_DATA)
                installed.add(pkg)
            } catch (_: PackageManager.NameNotFoundException) {
            }
        }
        return installed
    }
}
