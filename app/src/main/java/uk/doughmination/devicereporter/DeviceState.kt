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
import android.os.CancellationSignal
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import android.Manifest
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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

/** Wi-Fi state as tracked by the service's network callback. */
data class WifiState(val connected: Boolean, val ssid: String?)

object DeviceState {

    /** A last-known fix older than this gets refreshed before geocoding. */
    private const val STALE_FIX_MS = 10 * 60 * 1000L
    private const val FRESH_FIX_TIMEOUT_S = 20L

    fun batteryLevel(ctx: Context): Int {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    /**
     * True when a charger is attached. Deliberately "plugged in" rather than
     * [BatteryManager.isCharging]: with Samsung's battery protection (stop at 80/85%)
     * the phone sits plugged in but not charging, and the API's `charging` means plugged in.
     */
    fun isPluggedIn(ctx: Context): Boolean {
        val intent: Intent? = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        return plugged != 0
    }

    fun isPowerSave(ctx: Context): Boolean {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isPowerSaveMode
    }

    /** True if wifi is the active network. */
    fun isWifiConnected(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /** Strips the quotes Android wraps SSIDs in; null for the placeholders it uses when withholding them. */
    fun cleanSsid(raw: String?): String? {
        val ssid = raw?.trim('"') ?: return null
        return if (ssid.isBlank() || ssid == "<unknown ssid>" || ssid == "0x") null else ssid
    }

    /**
     * Current wifi SSID, or null if unavailable/disconnected. Fallback for when the
     * service's network callback isn't running (e.g. a test report from the app).
     * Requires ACCESS_FINE_LOCATION and location services on.
     */
    @Suppress("DEPRECATION")
    fun wifiSsid(ctx: Context): String? {
        if (!isWifiConnected(ctx)) return null
        return try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            cleanSsid(wm.connectionInfo?.ssid)
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

    private fun providers(lm: LocationManager): List<String> {
        val all = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) all.add(LocationManager.FUSED_PROVIDER)
        all.add(LocationManager.NETWORK_PROVIDER)
        all.add(LocationManager.GPS_PROVIDER)
        all.add(LocationManager.PASSIVE_PROVIDER)
        return all.filter { p -> runCatching { lm.isProviderEnabled(p) }.getOrDefault(false) }
    }

    /** Freshest last-known fix across the available providers, or null. */
    private fun lastKnownLocation(lm: LocationManager): Location? {
        var best: Location? = null
        for (p in providers(lm)) {
            val loc = try {
                lm.getLastKnownLocation(p)
            } catch (e: SecurityException) {
                null
            }
            if (loc != null && (best == null || loc.elapsedRealtimeNanos > best.elapsedRealtimeNanos)) best = loc
        }
        return best
    }

    /**
     * Asks for one fresh fix (Android 11+), waiting up to [FRESH_FIX_TIMEOUT_S].
     * City-level text is all we send, so any non-GPS provider's accuracy is plenty.
     */
    private fun freshLocation(ctx: Context, lm: LocationManager): Location? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val provider = providers(lm).firstOrNull { it != LocationManager.PASSIVE_PROVIDER } ?: return null
        val latch = CountDownLatch(1)
        var result: Location? = null
        val signal = CancellationSignal()
        return try {
            lm.getCurrentLocation(provider, signal, ContextCompat.getMainExecutor(ctx)) { loc ->
                result = loc
                latch.countDown()
            }
            if (!latch.await(FRESH_FIX_TIMEOUT_S, TimeUnit.SECONDS)) signal.cancel()
            result
        } catch (e: SecurityException) {
            null
        }
    }

    private fun bestLocation(ctx: Context): Location? {
        if (!hasLocationPermission(ctx)) return null
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val last = lastKnownLocation(lm)
        val ageMs = last?.let { (SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) / 1_000_000 }
        if (last != null && ageMs != null && ageMs < STALE_FIX_MS) return last
        return freshLocation(ctx, lm) ?: last
    }

    /**
     * Reverse-geocodes the device's location into "City, Region, Country".
     * Blocking (fresh fix + Geocoder) — call from a background thread. Returns null if
     * permission/location/geocoder is unavailable.
     */
    fun locationString(ctx: Context): String? {
        val loc = bestLocation(ctx) ?: return null
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
     * [includeLocation] adds the reverse-geocoded location (used on the 5-min heartbeat).
     * [wifi] is the service's tracked state; without it wifi is read directly.
     */
    fun snapshot(
        ctx: Context,
        prefs: Prefs,
        includeLocation: Boolean = false,
        wifi: WifiState? = null
    ): Snapshot {
        val connected = wifi?.connected ?: isWifiConnected(ctx)
        val ssid = if (wifi != null) wifi.ssid else if (connected) wifiSsid(ctx) else null
        return Snapshot(
            device = prefs.deviceName,
            level = batteryLevel(ctx).takeIf { it in 0..100 },
            charging = isPluggedIn(ctx),
            lpm = isPowerSave(ctx),
            wifi = if (connected) ssid else null,
            wifiConnected = connected,
            watch = prefs.watchConnected,
            airpods = if (prefs.airpodsEnabled) prefs.airpodsConnected else null,
            location = if (includeLocation) locationString(ctx) else null
        )
    }
}
