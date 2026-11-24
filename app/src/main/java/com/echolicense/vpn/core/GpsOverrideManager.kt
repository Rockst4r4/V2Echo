package com.echolicense.vpn.core

import android.content.Context
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.SystemClock
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.random.Random

data class VpnGeoLocation(
    val latitude: Double,
    val longitude: Double,
    val city: String,
    val country: String,
    val ip: String
) {
    fun displayLabel(): String {
        return listOf(city, country).filter { it.isNotBlank() }.joinToString(", ").ifBlank { ip }
    }

    fun coordsLabel(): String {
        return String.format(Locale.US, "%.3f, %.3f", latitude, longitude)
    }
}

enum class GpsPushResult {
    OK,
    NEEDS_MOCK_APP_SETUP,
    ERROR
}

object GpsOverrideManager {

    private const val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private val activeProviders = mutableSetOf<String>()

    private fun proxyClient(proxyPort: Int): OkHttpClient {
        return OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxyPort)))
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    private fun readDouble(obj: JSONObject, key: String): Double? {
        if (!obj.has(key) || obj.isNull(key)) return null
        val raw = obj.opt(key)
        return when (raw) {
            is Number -> raw.toDouble()
            is String -> raw.trim().toDoubleOrNull()
            else -> null
        }
    }

    /**
     * دریافت لوکیشن واقعی IP خروجی VPN از داخل تونل (از طریق پروکسی محلی probe-http)
     */
    fun resolveVpnGeoLocation(proxyPort: Int): VpnGeoLocation? {
        if (proxyPort <= 0) return null
        val client = proxyClient(proxyPort)

        // 1. ipwho.is
        runCatching {
            val req = Request.Builder()
                .url("https://ipwho.is/")
                .header("User-Agent", BROWSER_UA)
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val j = JSONObject(resp.body?.string().orEmpty())
                    if (j.optBoolean("success", true)) {
                        val lat = readDouble(j, "latitude")
                        val lon = readDouble(j, "longitude")
                        if (lat != null && lon != null && (lat != 0.0 || lon != 0.0)) {
                            return VpnGeoLocation(
                                latitude = lat,
                                longitude = lon,
                                city = j.optString("city", ""),
                                country = j.optString("country", ""),
                                ip = j.optString("ip", "")
                            )
                        }
                    }
                }
            }
        }

        // 2. get.geojs.io
        runCatching {
            val req = Request.Builder()
                .url("https://get.geojs.io/v1/ip/geo.json")
                .header("User-Agent", BROWSER_UA)
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val j = JSONObject(resp.body?.string().orEmpty())
                    val lat = readDouble(j, "latitude")
                    val lon = readDouble(j, "longitude")
                    if (lat != null && lon != null && (lat != 0.0 || lon != 0.0)) {
                        return VpnGeoLocation(
                            latitude = lat,
                            longitude = lon,
                            city = j.optString("city", ""),
                            country = j.optString("country", ""),
                            ip = j.optString("ip", "")
                        )
                    }
                }
            }
        }

        // 3. ip-api.com
        runCatching {
            val req = Request.Builder()
                .url("http://ip-api.com/json/?fields=status,lat,lon,city,country,query")
                .header("User-Agent", BROWSER_UA)
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val j = JSONObject(resp.body?.string().orEmpty())
                    if (j.optString("status") == "success") {
                        val lat = readDouble(j, "lat")
                        val lon = readDouble(j, "lon")
                        if (lat != null && lon != null && (lat != 0.0 || lon != 0.0)) {
                            return VpnGeoLocation(
                                latitude = lat,
                                longitude = lon,
                                city = j.optString("city", ""),
                                country = j.optString("country", ""),
                                ip = j.optString("query", "")
                            )
                        }
                    }
                }
            }
        }

        return null
    }

    private fun targetProviders(): List<String> {
        val list = mutableListOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list.add(LocationManager.FUSED_PROVIDER)
        }
        return list
    }

    @Synchronized
    private fun ensureTestProvider(lm: LocationManager, provider: String) {
        if (activeProviders.contains(provider)) return
        runCatching { lm.removeTestProvider(provider) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val props = ProviderProperties.Builder()
                .setHasAltitudeSupport(true)
                .setHasSpeedSupport(true)
                .setHasBearingSupport(true)
                .setPowerUsage(ProviderProperties.POWER_USAGE_LOW)
                .setAccuracy(ProviderProperties.ACCURACY_FINE)
                .build()
            lm.addTestProvider(provider, props)
        } else {
            @Suppress("DEPRECATION")
            lm.addTestProvider(
                provider,
                false, false, false, false,
                true, true, true,
                Criteria.POWER_LOW,
                Criteria.ACCURACY_FINE
            )
        }
        lm.setTestProviderEnabled(provider, true)
        activeProviders.add(provider)
    }

    @Synchronized
    fun pushLocation(context: Context, geo: VpnGeoLocation): GpsPushResult {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return GpsPushResult.ERROR

        var anySuccess = false
        var sawSecurityError = false

        val jitterLat = (Random.nextDouble() - 0.5) * 0.00005
        val jitterLon = (Random.nextDouble() - 0.5) * 0.00005

        for (provider in targetProviders()) {
            try {
                ensureTestProvider(lm, provider)
                val loc = Location(provider).apply {
                    latitude = geo.latitude + jitterLat
                    longitude = geo.longitude + jitterLon
                    altitude = 34.0
                    accuracy = 4.5f
                    bearing = 0f
                    speed = 0f
                    time = System.currentTimeMillis()
                    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        bearingAccuracyDegrees = 1.0f
                        speedAccuracyMetersPerSecond = 0.1f
                        verticalAccuracyMeters = 2.0f
                    }
                }
                lm.setTestProviderLocation(provider, loc)
                anySuccess = true
            } catch (_: SecurityException) {
                sawSecurityError = true
                activeProviders.remove(provider)
            } catch (_: Throwable) {
                activeProviders.remove(provider)
            }
        }

        return when {
            anySuccess -> GpsPushResult.OK
            sawSecurityError -> GpsPushResult.NEEDS_MOCK_APP_SETUP
            else -> GpsPushResult.ERROR
        }
    }

    @Synchronized
    fun stop(context: Context) {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        val toRemove = (activeProviders + targetProviders()).toSet()
        for (provider in toRemove) {
            runCatching { lm.setTestProviderEnabled(provider, false) }
            runCatching { lm.removeTestProvider(provider) }
        }
        activeProviders.clear()
    }
}
