package uk.doughmination.devicereporter

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Sends a device-state snapshot to the API as an HTTP GET:
 *   GET {baseUrl}/devices?device=...&level=...  with header  X-Battery-Key: {key}
 *
 * The API updates only the fields supplied and leaves the rest untouched,
 * so sending a full snapshot on every event is safe.
 */
object Reporter {

    private const val TAG = "Reporter"

    data class Result(val ok: Boolean, val code: Int, val message: String)

    fun buildUrl(prefs: Prefs, snap: Snapshot): String = buildUrl(prefs.baseUrl, snap)

    /** Testable core: builds the request URL from a base URL and a snapshot. */
    fun buildUrl(baseUrl: String, snap: Snapshot): String {
        val base = baseUrl.trim().trimEnd('/')
        val params = ArrayList<String>()

        fun add(key: String, value: String) {
            params.add("$key=${URLEncoder.encode(value, "UTF-8")}")
        }

        add("device", snap.device)
        snap.level?.let { add("level", it.toString()) }
        snap.charging?.let { add("charging", if (it) "1" else "0") }
        snap.lpm?.let { add("lpm", if (it) "1" else "0") }

        // wifi: disconnected => "0"; connected with a known name => the name;
        // connected but name withheld by the OS => omit (don't overwrite with a guess).
        if (!snap.wifiConnected) {
            add("wifi", "0")
        } else if (!snap.wifi.isNullOrBlank()) {
            add("wifi", snap.wifi)
        }

        snap.watch?.let { add("watch", if (it) "1" else "0") }
        snap.airpods?.let { add("airpods", if (it) "1" else "0") }
        snap.location?.let { if (it.isNotBlank()) add("location", it) }

        return "$base/devices?${params.joinToString("&")}"
    }

    /** Blocking network call — invoke from a background thread / IO dispatcher. */
    fun report(prefs: Prefs, snap: Snapshot): Result {
        val urlStr = buildUrl(prefs, snap)
        return try {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 15_000
                setRequestProperty("X-Battery-Key", prefs.apiKey)
                setRequestProperty("Accept", "application/json")
                instanceFollowRedirects = true
            }
            val code = conn.responseCode
            val ok = code in 200..299
            val body = try {
                (if (ok) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() }?.take(300) ?: ""
            } catch (e: Exception) {
                ""
            }
            conn.disconnect()
            Log.i(TAG, "GET $urlStr -> $code")
            Result(ok, code, if (ok) "OK" else "HTTP $code $body")
        } catch (e: Exception) {
            Log.w(TAG, "report failed: ${e.message}")
            Result(false, -1, e.message ?: "network error")
        }
    }
}
