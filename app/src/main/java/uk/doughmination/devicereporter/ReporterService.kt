package uk.doughmination.devicereporter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Always-on foreground service. Reports device state on:
 *   - wifi connect / disconnect          (ConnectivityManager callback)
 *   - charger connect / disconnect       (ACTION_POWER_CONNECTED/DISCONNECTED)
 *   - power-save (lpm) toggle            (ACTION_POWER_SAVE_MODE_CHANGED)
 *   - chosen bluetooth device conn/disc  (ACL_CONNECTED/DISCONNECTED -> airpods)
 *   - every 5 minutes                    (periodic battery check)
 */
class ReporterService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val reportMutex = Mutex()
    private lateinit var prefs: Prefs
    private var periodicJob: Job? = null

    private var cm: ConnectivityManager? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null

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
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    prefs.airpodsConnected = true
                    reportNow("airpods connected")
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    prefs.airpodsConnected = false
                    reportNow("airpods disconnected")
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        createChannel()
        startForeground(NOTIF_ID, buildNotification("Reporting service running"))
        registerReceivers()
        startPeriodic()
        reportNow("service start")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Restart if killed by the system.
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        unregisterReceivers()
        periodicJob?.cancel()
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
        registerReceiver(powerReceiver, powerFilter)

        val btFilter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        registerReceiver(bluetoothReceiver, btFilter)

        cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = reportNow("wifi connected")
            override fun onLost(network: Network) = reportNow("wifi disconnected")
        }
        netCallback = cb
        cm?.registerNetworkCallback(request, cb)
    }

    private fun unregisterReceivers() {
        runCatching { unregisterReceiver(powerReceiver) }
        runCatching { unregisterReceiver(bluetoothReceiver) }
        netCallback?.let { cb -> runCatching { cm?.unregisterNetworkCallback(cb) } }
    }

    // --- periodic ----------------------------------------------------------

    private fun startPeriodic() {
        periodicJob?.cancel()
        periodicJob = scope.launch {
            while (isActive) {
                delay(PERIOD_MS)
                reportNow("periodic 5-min", includeLocation = true)
            }
        }
    }

    // --- reporting ---------------------------------------------------------

    private fun reportNow(reason: String, includeLocation: Boolean = false) {
        scope.launch {
            // Serialise reports so overlapping events don't race.
            reportMutex.withLock {
                if (!prefs.isConfigured()) {
                    updateNotification("Not configured — open the app")
                    return@withLock
                }
                val snap = DeviceState.snapshot(this@ReporterService, prefs, includeLocation)
                val result = withContext(Dispatchers.IO) { Reporter.report(prefs, snap) }
                val stamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                lastStatus = if (result.ok) {
                    "$stamp OK ($reason)"
                } else {
                    "$stamp FAIL ${result.message} ($reason)"
                }
                Log.i(TAG, lastStatus)
                updateNotification(lastStatus)
            }
        }
    }

    // --- notification ------------------------------------------------------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Device Reporter",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Background device-state reporting" }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Device Reporter")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
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
        const val ACTION_STOP = "uk.doughmination.devicereporter.STOP"

        fun start(context: Context) {
            val intent = Intent(context, ReporterService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, ReporterService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }
    }
}
