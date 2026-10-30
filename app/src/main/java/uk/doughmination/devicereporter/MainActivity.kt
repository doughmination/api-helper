package uk.doughmination.devicereporter

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.ColorRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs

    private lateinit var inputBaseUrl: EditText
    private lateinit var inputApiKey: EditText
    private lateinit var inputDevice: EditText
    private lateinit var switchWatch: MaterialSwitch
    private lateinit var switchAirpods: MaterialSwitch
    private lateinit var textAirpodsDevice: TextView
    private lateinit var textStatus: TextView
    private lateinit var textServiceState: TextView
    private lateinit var textBattery: TextView
    private lateinit var textLocation: TextView
    private lateinit var textAlarms: TextView
    private lateinit var btnBattery: Button
    private lateinit var btnLocation: Button
    private lateinit var btnAlarms: Button
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button

    // Background location has to be asked for on its own, after foreground location is
    // granted, so the foreground request chains into it.
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true) requestBackgroundLocation()
            refreshBackgroundRows()
        }

    private val backgroundLocationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshBackgroundRows() }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Prefs.KEY_LAST_REPORT) runOnUiThread { showLastReport() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        // Edge-to-edge: keep content clear of the status bar, camera cutout, nav bar and keyboard.
        val content = findViewById<View>(R.id.content)
        val basePadTop = content.paddingTop
        val basePadBottom = content.paddingBottom
        val basePadStart = content.paddingStart
        val basePadEnd = content.paddingEnd
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.scroll)) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime()
            )
            content.updatePadding(
                left = basePadStart + bars.left,
                top = basePadTop + bars.top,
                right = basePadEnd + bars.right,
                bottom = basePadBottom + bars.bottom
            )
            insets
        }

        inputBaseUrl = findViewById(R.id.inputBaseUrl)
        inputApiKey = findViewById(R.id.inputApiKey)
        inputDevice = findViewById(R.id.inputDevice)
        switchWatch = findViewById(R.id.switchWatch)
        switchAirpods = findViewById(R.id.switchAirpods)
        textAirpodsDevice = findViewById(R.id.textAirpodsDevice)
        textStatus = findViewById(R.id.textStatus)
        textServiceState = findViewById(R.id.textServiceState)
        textBattery = findViewById(R.id.textBattery)
        textLocation = findViewById(R.id.textLocation)
        textAlarms = findViewById(R.id.textAlarms)
        btnBattery = findViewById(R.id.btnBattery)
        btnLocation = findViewById(R.id.btnLocation)
        btnAlarms = findViewById(R.id.btnAlarms)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)

        loadIntoViews()

        findViewById<Button>(R.id.btnSelectAirpods).setOnClickListener { pickAirpodsDevice() }
        findViewById<Button>(R.id.btnSave).setOnClickListener { saveFromViews(); toast("Saved") }
        btnStart.setOnClickListener {
            saveFromViews()
            if (!prefs.isConfigured()) {
                toast("Set a base URL and device name first")
            } else {
                requestRuntimePermissions()
                prefs.reportingEnabled = true
                ReporterService.start(this)
                // The service flips isRunning in onCreate, a moment after this returns.
                btnStart.post { refreshServiceState() }
            }
        }
        btnStop.setOnClickListener {
            prefs.reportingEnabled = false
            ReporterService.stop(this)
            refreshServiceState()
        }
        findViewById<Button>(R.id.btnTest).setOnClickListener { sendTestReport() }
        btnBattery.setOnClickListener { requestBatteryExemption() }
        btnLocation.setOnClickListener { requestLocation() }
        btnAlarms.setOnClickListener { requestExactAlarms() }

        requestRuntimePermissions()
    }

    override fun onResume() {
        super.onResume()
        prefs.registerListener(prefsListener)
        refreshServiceState()
        refreshBackgroundRows()
        showLastReport()
    }

    override fun onPause() {
        prefs.unregisterListener(prefsListener)
        super.onPause()
    }

    private fun loadIntoViews() {
        inputBaseUrl.setText(prefs.baseUrl)
        inputApiKey.setText(prefs.apiKey)
        inputDevice.setText(prefs.deviceName)
        switchWatch.isChecked = prefs.watchConnected
        switchAirpods.isChecked = prefs.airpodsEnabled
        updateAirpodsLabel()
    }

    private fun saveFromViews() {
        prefs.baseUrl = inputBaseUrl.text.toString().trim().ifBlank { Prefs.DEFAULT_BASE_URL }
        prefs.apiKey = inputApiKey.text.toString().trim()
        prefs.deviceName = inputDevice.text.toString().trim().ifBlank { Prefs.DEFAULT_DEVICE }
        prefs.watchConnected = switchWatch.isChecked
        prefs.airpodsEnabled = switchAirpods.isChecked
    }

    // --- status ------------------------------------------------------------

    private fun refreshServiceState() {
        val running = ReporterService.isRunning
        btnStart.visibility = if (running) View.GONE else View.VISIBLE
        btnStop.visibility = if (running) View.VISIBLE else View.GONE
        setPill(textServiceState, if (running) "Reporting" else "Stopped", if (running) R.color.ok else R.color.muted)
    }

    private fun showLastReport() {
        val last = prefs.lastReport
        if (last.isNotBlank()) textStatus.text = last
    }

    private fun setPill(view: TextView, text: String, @ColorRes color: Int) {
        val c = ContextCompat.getColor(this, color)
        view.text = text
        view.setTextColor(c)
        view.compoundDrawablesRelative[0]?.setTint(c)
    }

    /** One UI needs all three of these for the service to keep running and report on time. */
    private fun refreshBackgroundRows() {
        val pm = getSystemService(PowerManager::class.java)
        val unrestricted = pm.isIgnoringBatteryOptimizations(packageName)
        setRow(
            textBattery, btnBattery, unrestricted,
            ok = "Allowed",
            missing = "One UI may stop the service while the screen's off"
        )

        val fine = has(Manifest.permission.ACCESS_FINE_LOCATION)
        val background = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            has(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        setRow(
            textLocation, btnLocation, fine && background,
            ok = "Allowed",
            missing = if (!fine) "Needed for the Wi-Fi name and location" else "Only while open: background reports won't have Wi-Fi name or location"
        )

        setRow(
            textAlarms, btnAlarms, ReporterService.canScheduleExact(this),
            ok = "Allowed: every 5 minutes, even asleep",
            missing = "Without it, idle gaps can stretch to ~10 minutes"
        )
    }

    private fun setRow(hint: TextView, button: Button, granted: Boolean, ok: String, missing: String) {
        hint.text = if (granted) ok else missing
        hint.setTextColor(ContextCompat.getColor(this, if (granted) R.color.ok else R.color.warn))
        button.visibility = if (granted) View.GONE else View.VISIBLE
    }

    // --- permissions -------------------------------------------------------

    private fun has(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun requestRuntimePermissions() {
        val needed = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !has(Manifest.permission.POST_NOTIFICATIONS)
        ) needed.add(Manifest.permission.POST_NOTIFICATIONS)

        if (!has(Manifest.permission.ACCESS_FINE_LOCATION)) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
            needed.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !has(Manifest.permission.BLUETOOTH_CONNECT)
        ) needed.add(Manifest.permission.BLUETOOTH_CONNECT)

        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
    }

    private fun requestLocation() {
        when {
            !has(Manifest.permission.ACCESS_FINE_LOCATION) -> permissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
            else -> requestBackgroundLocation(explain = true)
        }
    }

    /**
     * Android sends background location to its own settings page ("Allow all the time").
     * Once the user has declined it there, the request does nothing, so go to app settings.
     */
    private fun requestBackgroundLocation(explain: Boolean = false) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (has(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) return
        val ask = {
            if (shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_BACKGROUND_LOCATION) || !explain) {
                backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            } else {
                openAppSettings()
            }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Location all the time")
            .setMessage(
                "Reports sent while the app is closed need location to include your Wi-Fi name " +
                    "and city. On the next screen, choose \"Allow all the time\"."
            )
            .setPositiveButton("Continue") { _, _ -> ask() }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun requestBatteryExemption() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:$packageName"))
        runCatching { startActivity(intent) }.onFailure { openAppSettings() }
    }

    private fun requestExactAlarms() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
            .setData(Uri.parse("package:$packageName"))
        runCatching { startActivity(intent) }.onFailure { openAppSettings() }
    }

    private fun openAppSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:$packageName"))
        )
    }

    // --- airpods -----------------------------------------------------------

    private fun updateAirpodsLabel() {
        textAirpodsDevice.text = if (prefs.airpodsAddress.isNotBlank()) {
            "${prefs.airpodsLabel} · ${prefs.airpodsAddress}"
        } else {
            "No device selected"
        }
    }

    @SuppressLint("MissingPermission") // BLUETOOTH_CONNECT is checked first; SecurityException is caught
    private fun pickAirpodsDevice() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !has(Manifest.permission.BLUETOOTH_CONNECT)
        ) {
            permissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT))
            toast("Grant Bluetooth permission, then tap again")
            return
        }

        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            toast("Turn Bluetooth on first")
            return
        }

        val bonded = try {
            adapter.bondedDevices?.toList().orEmpty()
        } catch (e: SecurityException) {
            toast("Bluetooth permission missing")
            return
        }
        if (bonded.isEmpty()) {
            toast("No paired Bluetooth devices found")
            return
        }

        val names = bonded.map { runCatching { it.name }.getOrNull() ?: it.address }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Choose your AirPods")
            .setItems(names) { _, which ->
                val chosen = bonded[which]
                prefs.airpodsAddress = chosen.address
                prefs.airpodsLabel = names[which]
                prefs.airpodsEnabled = true
                switchAirpods.isChecked = true
                updateAirpodsLabel()
                toast("Saved: ${names[which]}")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // --- test --------------------------------------------------------------

    private fun sendTestReport() {
        saveFromViews()
        if (!prefs.isConfigured()) {
            toast("Set a base URL and device name first")
            return
        }
        textStatus.text = "Sending test report…"
        lifecycleScope.launch {
            val (url, result) = withContext(Dispatchers.IO) {
                val snap = DeviceState.snapshot(this@MainActivity, prefs, includeLocation = true)
                Reporter.buildUrl(prefs, snap) to Reporter.report(prefs, snap)
            }
            textStatus.text = buildString {
                append(if (result.ok) "Test OK\n" else "Test FAILED: ${result.message}\n")
                append("\nRequest:\n$url")
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
