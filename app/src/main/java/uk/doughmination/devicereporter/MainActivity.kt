package uk.doughmination.devicereporter

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.switchmaterial.SwitchMaterial

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs

    private lateinit var inputBaseUrl: EditText
    private lateinit var inputApiKey: EditText
    private lateinit var inputDevice: EditText
    private lateinit var switchWatch: SwitchMaterial
    private lateinit var switchAirpods: SwitchMaterial
    private lateinit var textAirpodsDevice: TextView
    private lateinit var textStatus: TextView

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        inputBaseUrl = findViewById(R.id.inputBaseUrl)
        inputApiKey = findViewById(R.id.inputApiKey)
        inputDevice = findViewById(R.id.inputDevice)
        switchWatch = findViewById(R.id.switchWatch)
        switchAirpods = findViewById(R.id.switchAirpods)
        textAirpodsDevice = findViewById(R.id.textAirpodsDevice)
        textStatus = findViewById(R.id.textStatus)

        loadIntoViews()

        findViewById<Button>(R.id.btnSelectAirpods).setOnClickListener { pickAirpodsDevice() }
        findViewById<Button>(R.id.btnSave).setOnClickListener { saveFromViews(); toast("Saved") }
        findViewById<Button>(R.id.btnStart).setOnClickListener {
            saveFromViews()
            if (!prefs.isConfigured()) {
                toast("Set a base URL and device name first")
            } else {
                requestRuntimePermissions()
                requestBackgroundLocationIfNeeded()
                ReporterService.start(this)
                textStatus.text = "Service started."
            }
        }
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            ReporterService.stop(this)
            textStatus.text = "Service stopped."
        }
        findViewById<Button>(R.id.btnTest).setOnClickListener { sendTestReport() }

        requestRuntimePermissions()
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
        prefs.deviceName = inputDevice.text.toString().trim().ifBlank { "pixel" }
        prefs.watchConnected = switchWatch.isChecked
        prefs.airpodsEnabled = switchAirpods.isChecked
    }

    private fun updateAirpodsLabel() {
        textAirpodsDevice.text = if (prefs.airpodsAddress.isNotBlank()) {
            "AirPods device: ${prefs.airpodsLabel} (${prefs.airpodsAddress})"
        } else {
            "No AirPods device selected"
        }
    }

    private fun pickAirpodsDevice() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
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
        AlertDialog.Builder(this)
            .setTitle("Select AirPods device")
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

    private fun sendTestReport() {
        saveFromViews()
        if (!prefs.isConfigured()) {
            toast("Set a base URL and device name first")
            return
        }
        textStatus.text = "Sending test report…"
        Thread {
            val snap = DeviceState.snapshot(this, prefs, includeLocation = true)
            val url = Reporter.buildUrl(prefs, snap)
            val result = Reporter.report(prefs, snap)
            runOnUiThread {
                textStatus.text = buildString {
                    append(if (result.ok) "Test OK\n" else "Test FAILED: ${result.message}\n")
                    append("\nRequest:\n$url")
                }
            }
        }.start()
    }

    private fun requestRuntimePermissions() {
        val needed = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) needed.add(Manifest.permission.POST_NOTIFICATIONS)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) needed.add(Manifest.permission.ACCESS_FINE_LOCATION)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) needed.add(Manifest.permission.BLUETOOTH_CONNECT)

        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
    }

    /**
     * Background location must be requested on its own, and only after foreground
     * location is already granted (Android opens the "Allow all the time" screen).
     */
    private fun requestBackgroundLocationIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val fineGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val bgGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (fineGranted && !bgGranted) {
            permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
