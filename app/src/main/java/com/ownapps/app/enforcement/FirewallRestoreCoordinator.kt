package com.ownapps.app.enforcement

import android.util.Log
import com.ownapps.app.data.repository.FirewallRulesRepository
import com.ownapps.app.data.repository.SettingsRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Outcome of one restore attempt. [PENDING] means the saved state was kept and will be retried;
 *  [UNSUPPORTED] means the ROM can't run the commands, so retrying would be pointless. */
enum class FirewallRestoreResult { RESTORED, PENDING, SKIPPED, UNSUPPORTED }

/**
 * Re-applies the user's saved firewall state whenever the privileged backend is ready: at boot,
 * when Shizuku's binder arrives later, and when the Firewall screen is opened. The user's intent
 * ([SettingsRepository.FIREWALL_ENABLED]) is never cleared by a failed attempt — instead a separate
 * pending flag drives the honest "not enforced yet" banner and the automatic retries.
 *
 * Every attempt is serialized so the boot receiver, binder callback, and UI resume can't race.
 */
class FirewallRestoreCoordinator(
    private val firewallController: FirewallController,
    private val firewallRulesRepository: FirewallRulesRepository,
    private val settingsRepository: SettingsRepository
) {
    private val mutex = Mutex()

    suspend fun restore(): FirewallRestoreResult = mutex.withLock {
        try {
            restoreLocked()
        } catch (e: Throwable) {
            Log.e(TAG, "Restore crashed", e)
            settingsRepository.setFirewallRestorePending(true)
            FirewallRestoreResult.PENDING
        }
    }

    /** Used by the long-lived binder listener and the UI: only attempts a real restore when a
     *  previous attempt left work pending. */
    suspend fun restoreIfPending(): FirewallRestoreResult {
        if (!settingsRepository.isFirewallRestorePending()) return FirewallRestoreResult.SKIPPED
        return restore()
    }

    private suspend fun restoreLocked(): FirewallRestoreResult {
        if (!settingsRepository.isFirewallEnabled()) {
            settingsRepository.setFirewallRestorePending(false)
            return FirewallRestoreResult.SKIPPED
        }
        val blocked = firewallRulesRepository.getBlockedPackages()
        if (blocked.isEmpty()) {
            // Nothing to enforce, so the saved ON state needs no boot-time work.
            settingsRepository.setFirewallRestorePending(false)
            return FirewallRestoreResult.SKIPPED
        }

        if (!firewallController.isAvailable()) {
            settingsRepository.setFirewallRestorePending(true)
            return FirewallRestoreResult.PENDING
        }
        if (!firewallController.enableFirewall()) return failed("failed to enable Chain 3")

        var allApplied = true
        for (packageName in blocked) {
            if (!firewallController.block(packageName)) allApplied = false
        }
        if (!allApplied) {
            // A half-applied rule set is not the persisted state either — roll the master back.
            if (!firewallController.disableFirewall()) {
                Log.w(TAG, "Rollback after rule failure also failed")
            }
            return failed("some rules failed")
        }

        settingsRepository.setFirewallRestorePending(false)
        Log.i(TAG, "Re-applied ${blocked.size} firewall rule(s)")
        return FirewallRestoreResult.RESTORED
    }

    /** Keeps the saved intent and marks it pending unless the ROM itself is unsupported, in which
     *  case the UI shows that reason instead of an endless "waiting for Shizuku" state. */
    private suspend fun failed(reason: String): FirewallRestoreResult {
        Log.w(TAG, "Restore: $reason")
        val unsupported = firewallController.isAvailable() && !firewallController.isSupported()
        settingsRepository.setFirewallRestorePending(!unsupported)
        return if (unsupported) FirewallRestoreResult.UNSUPPORTED else FirewallRestoreResult.PENDING
    }

    private companion object {
        private const val TAG = "OwnApps/FirewallRestore"
    }
}
