package uk.doughmination.devicereporter

import android.content.Context
import android.content.SharedPreferences

/**
 * Thin wrapper around SharedPreferences holding all user configuration.
 */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("device_reporter", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = sp.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        set(v) = sp.edit().putString(KEY_BASE_URL, v).apply()

    var apiKey: String
        get() = sp.getString(KEY_API_KEY, "") ?: ""
        set(v) = sp.edit().putString(KEY_API_KEY, v).apply()

    var deviceName: String
        get() = sp.getString(KEY_DEVICE, DEFAULT_DEVICE) ?: DEFAULT_DEVICE
        set(v) = sp.edit().putString(KEY_DEVICE, v).apply()

    /** Manual toggle: an Apple Watch cannot pair with Android, so the user sets this by hand. */
    var watchConnected: Boolean
        get() = sp.getBoolean(KEY_WATCH, false)
        set(v) = sp.edit().putBoolean(KEY_WATCH, v).apply()

    var airpodsEnabled: Boolean
        get() = sp.getBoolean(KEY_AIRPODS_ENABLED, false)
        set(v) = sp.edit().putBoolean(KEY_AIRPODS_ENABLED, v).apply()

    /** Bluetooth MAC address of the device we treat as "airpods". Empty = none chosen. */
    var airpodsAddress: String
        get() = sp.getString(KEY_AIRPODS_ADDR, "") ?: ""
        set(v) = sp.edit().putString(KEY_AIRPODS_ADDR, v).apply()

    var airpodsLabel: String
        get() = sp.getString(KEY_AIRPODS_LABEL, "") ?: ""
        set(v) = sp.edit().putString(KEY_AIRPODS_LABEL, v).apply()

    /** Last known connection state of the chosen airpods device, updated by the BT receiver. */
    var airpodsConnected: Boolean
        get() = sp.getBoolean(KEY_AIRPODS_CONNECTED, false)
        set(v) = sp.edit().putBoolean(KEY_AIRPODS_CONNECTED, v).apply()

    /** True while the user wants reporting on; boot/update only restart the service if so. */
    var reportingEnabled: Boolean
        get() = sp.getBoolean(KEY_REPORTING, false)
        set(v) = sp.edit().putBoolean(KEY_REPORTING, v).apply()

    /** Outcome of the most recent report, shown on the settings screen. */
    var lastReport: String
        get() = sp.getString(KEY_LAST_REPORT, "") ?: ""
        set(v) = sp.edit().putString(KEY_LAST_REPORT, v).apply()

    fun isConfigured(): Boolean = baseUrl.isNotBlank() && deviceName.isNotBlank()

    fun registerListener(l: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.registerOnSharedPreferenceChangeListener(l)

    fun unregisterListener(l: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.unregisterOnSharedPreferenceChangeListener(l)

    companion object {
        const val DEFAULT_BASE_URL = "https://doughmination.uk/v2"
        const val DEFAULT_DEVICE = "phone"

        const val KEY_LAST_REPORT = "last_report"

        private const val KEY_BASE_URL = "base_url"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_DEVICE = "device"
        private const val KEY_WATCH = "watch"
        private const val KEY_AIRPODS_ENABLED = "airpods_enabled"
        private const val KEY_AIRPODS_ADDR = "airpods_addr"
        private const val KEY_AIRPODS_LABEL = "airpods_label"
        private const val KEY_AIRPODS_CONNECTED = "airpods_connected"
        private const val KEY_REPORTING = "reporting_enabled"
    }
}
