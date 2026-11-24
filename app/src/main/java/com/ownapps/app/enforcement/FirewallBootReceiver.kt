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
 * Makes one immediate restore attempt after boot. Android clears Chain 3 and every per-app rule on
 * reboot, so a saved ON state would otherwise silently stop being enforced. Deliberately only
 * listens to `BOOT_COMPLETED` (post-unlock): the Room database holding the blocked list lives in
 * credential-protected storage and isn't readable until the user unlocks the device.
 *
 * Shizuku often starts later than this receiver. A failed attempt is therefore not final: the
 * coordinator keeps the saved intent and marks the restore pending, and [OwnAppsApplication] retries
 * when Shizuku's binder arrives (as does the Firewall screen when opened).
 */
class FirewallBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as? OwnAppsApplication ?: return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                when (app.container.firewallRestoreCoordinator.restore()) {
                    FirewallRestoreResult.PENDING -> FirewallRestoreNotifier.showWaiting(app)
                    FirewallRestoreResult.RESTORED,
                    FirewallRestoreResult.SKIPPED,
                    FirewallRestoreResult.UNSUPPORTED -> FirewallRestoreNotifier.cancel(app)
                }
            } finally {
                // Post/cancel before finish(): the process can be killed right after finish().
                pendingResult.finish()
            }
        }
    }
}
