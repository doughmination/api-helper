package uk.doughmination.devicereporter

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restarts the reporting service after a reboot or an app update (installing a new APK
 * kills the running service), if the user had it switched on.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val prefs = Prefs(context)
                if (prefs.reportingEnabled && prefs.isConfigured()) {
                    ReporterService.start(context)
                }
            }
        }
    }
}
