package uk.doughmination.devicereporter

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import android.Manifest
import java.util.Locale

/**
 * Immutable snapshot of the fields the API accepts.
 * Only [device] is required by the API; the rest are optional.
 */
data class Snapshot(
    val device: String,
    val level: Int?,
    val charging: Boolean?,
    val lpm: Boolean?,
    val wifi: String?,      // null => omit; "" or a disconnected read => reported as "0"
    val wifiConnected: Boolean,
    val watch: Boolean?,
    val airpods: Boolean?,
    val location: String?   // reverse-geocoded "City, Region, Country"; null => omit
)

object DeviceState {

    fun batteryLevel(ctx: Context): Int {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    fun isCharging(ctx: Context): Boolean {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return bm.isCharging
        }
        val intent: Intent? = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    }

    fun isPowerSave(ctx: Context): Boolean {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isPowerSaveMode
    }

    /** True if wifi is the active, validated network. */
    fun isWifiConnected(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /**
     * Current wifi SSID, or null if unavailable/disconnected.
     * On Android 10+ this requires ACCESS_FINE_LOCATION and location services on;
     * the OS returns "<unknown ssid>" when it will not disclose the name.
     */
    @Suppress("DEPRECATION")
    fun wifiSsid(ctx: Context): String? {
        if (!isWifiConnected(ctx)) return null
        return try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val raw = wm.connectionInfo?.ssid ?: return null
            val ssid = raw.trim('"')
            if (ssid.isBlank() || ssid == "<unknown ssid>" || ssid == "0x") null else ssid
        } catch (e: Exception) {
            null
        }
    }

    private fun hasLocationPermission(ctx: Context): Boolean {
        return ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    /** Freshest last-known fix across the available providers, or null. */
    private fun lastKnownLocation(ctx: Context): Location? {
        if (!hasLocationPermission(ctx)) return null
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
        var best: Location? = null
        for (p in providers) {
            val loc = try {
                if (lm.isProviderEnabled(p)) lm.getLastKnownLocation(p) else null
            } catch (e: SecurityException) {
                null
            }
            if (loc != null && (best == null || loc.time > best!!.time)) best = loc
        }
        return best
    }

    /**
     * Reverse-geocodes the last-known location into "City, Region, Country".
     * Blocking (Geocoder) — call from a background thread. Returns null if
     * permission/location/geocoder is unavailable.
     */
    fun locationString(ctx: Context): String? {
        val loc = lastKnownLocation(ctx) ?: return null
        if (!Geocoder.isPresent()) return null
        return try {
            val geocoder = Geocoder(ctx, Locale.getDefault())
            @Suppress("DEPRECATION")
            val results = geocoder.getFromLocation(loc.latitude, loc.longitude, 1)
            val addr = results?.firstOrNull() ?: return null
            // city = locality (fallback sub-admin), region = admin area, then country.
            val city = addr.locality ?: addr.subAdminArea
            val region = addr.adminArea
            val country = addr.countryName
            val parts = listOf(city, region, country).filter { !it.isNullOrBlank() }
            if (parts.isEmpty()) null else parts.joinToString(", ")
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Full current state, honouring user prefs for watch/airpods.
     * [includeLocation] adds the reverse-geocoded location (used on the 5-min timer).
     */
    fun snapshot(ctx: Context, prefs: Prefs, includeLocation: Boolean = false): Snapshot {
        val connected = isWifiConnected(ctx)
        return Snapshot(
            device = prefs.deviceName,
            level = batteryLevel(ctx).takeIf { it in 0..100 },
            charging = isCharging(ctx),
            lpm = isPowerSave(ctx),
            wifi = if (connected) wifiSsid(ctx) else null,
            wifiConnected = connected,
            watch = prefs.watchConnected,
            airpods = if (prefs.airpodsEnabled) prefs.airpodsConnected else null,
            location = if (includeLocation) locationString(ctx) else null
        )
    }
}
