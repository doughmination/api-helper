package uk.doughmination.devicereporter

import android.content.Context

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
        get() = sp.getString(KEY_DEVICE, "pixel") ?: "pixel"
        set(v) = sp.edit().putString(KEY_DEVICE, v).apply()

    /** Manual toggle: an Apple Watch cannot pair to a Pixel, so the user sets this by hand. */
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

    fun isConfigured(): Boolean = baseUrl.isNotBlank() && deviceName.isNotBlank()

    companion object {
        const val DEFAULT_BASE_URL = "https://doughmination.uk/v2"

        private const val KEY_BASE_URL = "base_url"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_DEVICE = "device"
        private const val KEY_WATCH = "watch"
        private const val KEY_AIRPODS_ENABLED = "airpods_enabled"
        private const val KEY_AIRPODS_ADDR = "airpods_addr"
        private const val KEY_AIRPODS_LABEL = "airpods_label"
        private const val KEY_AIRPODS_CONNECTED = "airpods_connected"
    }
}
