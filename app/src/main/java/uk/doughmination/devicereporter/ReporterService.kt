package uk.doughmination.devicereporter

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Always-on foreground service. Reports device state on:
 *   - wifi connect / disconnect / network change  (ConnectivityManager callback)
 *   - charger connect / disconnect                (ACTION_POWER_CONNECTED/DISCONNECTED)
 *   - power-save (lpm) toggle                     (ACTION_POWER_SAVE_MODE_CHANGED)
 *   - chosen bluetooth device conn/disc           (ACL_CONNECTED/DISCONNECTED -> airpods)
 *   - every 5 minutes                             (AlarmManager heartbeat, with location)
 *
 * The heartbeat is an alarm rather than a coroutine delay: a sleeping CPU doesn't run
 * timers, so a delay loop stalls for as long as the screen is off and the phone dozes.
 */
class ReporterService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val reportMutex = Mutex()
    private lateinit var prefs: Prefs

    private var cm: ConnectivityManager? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    // Wifi networks currently up, with their SSID (null when the OS withholds it).
    private val wifiNetworks = HashMap<Network, String?>()
    @Volatile private var wifiState: WifiState? = null

    private var lastStatus: String = "starting…"

    // Charger + power-save changes.
    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_POWER_CONNECTED -> reportNow("charger connected")
                Intent.ACTION_POWER_DISCONNECTED -> reportNow("charger disconnected")
                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> reportNow("power-save toggled")
            }
        }
    }

    // Bluetooth connect/disconnect for the chosen "airpods" device.
    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!prefs.airpodsEnabled || prefs.airpodsAddress.isBlank()) return
            @Suppress("DEPRECATION")
            val device: BluetoothDevice? =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }
            if (device?.address != prefs.airpodsAddress) return
            when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> setAirpods(true, "airpods connected")
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> setAirpods(false, "airpods disconnected")
            }
        }
    }

    // Our own heartbeat alarm and the notification's Stop action.
    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_HEARTBEAT -> {
                    scheduleHeartbeat()
                    reportNow("periodic 5-min", includeLocation = true)
                }
                ACTION_STOP -> {
                    prefs.reportingEnabled = false
                    stopSelf()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        createChannel()
        ServiceCompat.startForeground(
            this,
            NOTIF_ID,
            buildNotification("Reporting service running"),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
        )
        isRunning = true
        registerReceivers()
        syncAirpodsState()
        scheduleHeartbeat()
        reportNow("service start", includeLocation = true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Restart if killed by the system.
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        unregisterReceivers()
        getSystemService(AlarmManager::class.java).cancel(heartbeatIntent())
        scope.cancel()
        super.onDestroy()
    }

    // --- receivers ---------------------------------------------------------

    private fun registerReceivers() {
        val powerFilter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        // System broadcasts still reach NOT_EXPORTED receivers; other apps can't spoof them.
        ContextCompat.registerReceiver(this, powerReceiver, powerFilter, ContextCompat.RECEIVER_NOT_EXPORTED)

        val btFilter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        ContextCompat.registerReceiver(this, bluetoothReceiver, btFilter, ContextCompat.RECEIVER_NOT_EXPORTED)

        val controlFilter = IntentFilter().apply {
            addAction(ACTION_HEARTBEAT)
            addAction(ACTION_STOP)
        }
        ContextCompat.registerReceiver(this, controlReceiver, controlFilter, ContextCompat.RECEIVER_NOT_EXPORTED)

        cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        // Android 12+ redacts the SSID from callbacks unless we ask for location info.
        val cb = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            WifiCallback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO)
        } else {
            WifiCallback()
        }
        netCallback = cb
        cm?.registerNetworkCallback(request, cb)
    }

    private fun unregisterReceivers() {
        runCatching { unregisterReceiver(powerReceiver) }
        runCatching { unregisterReceiver(bluetoothReceiver) }
        runCatching { unregisterReceiver(controlReceiver) }
        netCallback?.let { cb -> runCatching { cm?.unregisterNetworkCallback(cb) } }
    }

    /**
     * Tracks wifi from the callback itself instead of asking for the active network,
     * which lags behind: right after onAvailable the active network can still be mobile data.
     * Only reports when the connected state or the SSID actually changes, since
     * onCapabilitiesChanged also fires for signal-strength updates.
     */
    private inner class WifiCallback : ConnectivityManager.NetworkCallback {
        constructor() : super()

        @RequiresApi(Build.VERSION_CODES.S)
        constructor(flags: Int) : super(flags)

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val ssid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                DeviceState.cleanSsid((caps.transportInfo as? WifiInfo)?.ssid)
            } else {
                DeviceState.wifiSsid(this@ReporterService)
            }
            updateWifi("wifi connected") { wifiNetworks[network] = ssid }
        }

        override fun onLost(network: Network) {
            updateWifi("wifi disconnected") { wifiNetworks.remove(network) }
        }
    }

    private fun updateWifi(reason: String, change: () -> Unit) {
        val (previous, next) = synchronized(wifiNetworks) {
            change()
            val next = WifiState(wifiNetworks.isNotEmpty(), wifiNetworks.values.firstOrNull { it != null })
            val previous = wifiState
            wifiState = next
            previous to next
        }
        if (previous != next) reportNow(if (next.connected) reason else "wifi disconnected")
    }

    // --- bluetooth ---------------------------------------------------------

    private fun setAirpods(connected: Boolean, reason: String) {
        if (prefs.airpodsConnected == connected) return
        prefs.airpodsConnected = connected
        reportNow(reason)
    }

    /**
     * The ACL broadcasts only tell us about changes, so on start ask the audio
     * profiles whether the chosen device is already connected.
     */
    @SuppressLint("MissingPermission")
    private fun syncAirpodsState() {
        if (!prefs.airpodsEnabled || prefs.airpodsAddress.isBlank()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) return
        val adapter: BluetoothAdapter = getSystemService(BluetoothManager::class.java)?.adapter ?: return
        if (!adapter.isEnabled) {
            setAirpods(false, "bluetooth off")
            return
        }
        // Connected on either profile counts; decide once both have answered (on the main thread).
        val profiles = listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)
        var pending = profiles.size
        var connected = false
        fun answered(isConnected: Boolean) {
            connected = connected || isConnected
            if (--pending == 0) setAirpods(connected, "airpods state on start")
        }
        for (profile in profiles) {
            val asked = adapter.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                    val isConnected = runCatching {
                        proxy.connectedDevices.any { it.address == prefs.airpodsAddress }
                    }.getOrDefault(false)
                    adapter.closeProfileProxy(p, proxy)
                    answered(isConnected)
                }

                override fun onServiceDisconnected(p: Int) {}
            }, profile)
            if (!asked) answered(false)
        }
    }

    // --- heartbeat ---------------------------------------------------------

    private fun heartbeatIntent(): PendingIntent = PendingIntent.getBroadcast(
        this,
        0,
        Intent(ACTION_HEARTBEAT).setPackage(packageName),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /**
     * One alarm at a time, re-armed each time it fires. Exact alarms (if the user has
     * allowed them) fire on time even in Doze; without that permission Android may stretch
     * the gap to roughly 10 minutes while the phone is idle.
     */
    private fun scheduleHeartbeat() {
        val am = getSystemService(AlarmManager::class.java)
        val at = SystemClock.elapsedRealtime() + PERIOD_MS
        val pi = heartbeatIntent()
        try {
            if (canScheduleExact(this)) {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
        }
    }

    // --- reporting ---------------------------------------------------------

    private fun reportNow(reason: String, includeLocation: Boolean = false) {
        // A broadcast only keeps the CPU awake until onReceive returns; hold it until the
        // request finishes. The timeout is a backstop in case something hangs.
        val wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DeviceReporter:report")
            .apply { acquire(WAKELOCK_TIMEOUT_MS) }
        scope.launch {
            try {
                // Serialise reports so overlapping events don't race.
                reportMutex.withLock { report(reason, includeLocation) }
            } finally {
                if (wakeLock.isHeld) wakeLock.release()
            }
        }
    }

    private suspend fun report(reason: String, includeLocation: Boolean) {
        if (!prefs.isConfigured()) {
            updateNotification("Not configured — open the app")
            return
        }
        val result = withContext(Dispatchers.IO) {
            val snap = DeviceState.snapshot(this@ReporterService, prefs, includeLocation, wifiState)
            Reporter.report(prefs, snap)
        }
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        lastStatus = if (result.ok) {
            "$stamp OK ($reason)"
        } else {
            "$stamp FAIL ${result.message} ($reason)"
        }
        Log.i(TAG, lastStatus)
        prefs.lastReport = lastStatus
        updateNotification(lastStatus)
    }

    // --- notification ------------------------------------------------------

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Device Reporter",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Background device-state reporting"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getBroadcast(
            this,
            1,
            Intent(ACTION_STOP).setPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Device Reporter")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_report)
            .setColor(ContextCompat.getColor(this, R.color.accent))
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    companion object {
        private const val TAG = "ReporterService"
        private const val CHANNEL_ID = "device_reporter"
        private const val NOTIF_ID = 1001
        private const val PERIOD_MS = 5 * 60 * 1000L
        private const val WAKELOCK_TIMEOUT_MS = 60 * 1000L
        private const val ACTION_HEARTBEAT = "uk.doughmination.devicereporter.HEARTBEAT"
        private const val ACTION_STOP = "uk.doughmination.devicereporter.STOP"

        /** Whether the service is up in this process; the settings screen shows it. */
        @Volatile
        var isRunning = false
            private set

        fun canScheduleExact(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ReporterService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ReporterService::class.java))
        }
    }
}
