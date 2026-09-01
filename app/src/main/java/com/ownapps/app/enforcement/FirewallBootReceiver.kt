package com.ownapps.app.enforcement

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ownapps.app.OwnAppsApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Re-applies the persisted firewall rules after a device reboot, because Android clears Chain 3
 * (and every per-app rule) on reboot. Deliberately only listens to `BOOT_COMPLETED` (post-unlock)
 * rather than `LOCKED_BOOT_COMPLETED`: the Room database holding the blocked list lives in
 * credential-protected storage and isn't readable until the user unlocks the device. Uses
 * [FirewallBootRestorer], which no-ops when the firewall was deliberately left off.
 */
class FirewallBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as? OwnAppsApplication ?: return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                app.container.firewallBootRestorer.restore()
            } finally {
                pendingResult.finish()
            }
        }
    }
}