package uk.doughmination.devicereporter

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts the reporting service after a reboot, if the user had configured it. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            if (Prefs(context).isConfigured()) {
                ReporterService.start(context)
            }
        }
    }
}
