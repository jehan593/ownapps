package com.ownapps.app.enforcement

import android.util.Log
import com.ownapps.app.data.repository.FirewallRulesRepository
import com.ownapps.app.data.repository.SettingsRepository
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import rikka.shizuku.Shizuku

/**
 * Restores the firewall after a reboot. Android's Chain 3 state (and every per-app rule) is
 * cleared by the platform on reboot, so a blocked list that was set before the reboot would
 * otherwise silently stop being enforced. This is the equivalent of ShizuWall's boot receiver:
 * on `BOOT_COMPLETED` it re-enables the firewall and re-applies the persisted blocked packages.
 *
 * Three guards keep it honest:
 *  - only runs if the firewall was actually left enforced (the persisted "last enforced" flag),
 *    so a deliberately-disabled firewall stays off after a reboot;
 *  - only runs if there is at least one blocked package to restore;
 *  - if the Shizuku binder isn't up yet (common right after boot), it waits for the binder and
 *    retries — this process may not survive long enough, but the master toggle in the UI is the
 *    always-available fallback.
 */
class FirewallBootRestorer(
    private val firewallController: FirewallController,
    private val firewallRulesRepository: FirewallRulesRepository,
    private val settingsRepository: SettingsRepository
) {
    suspend fun restore() {
        if (!settingsRepository.isFirewallEnabled()) return
        val blocked = firewallRulesRepository.getBlockedPackages()
        if (blocked.isEmpty()) return
        if (!firewallController.isAvailable()) {
            awaitBinder()
            restore() // retry — the availability check above now re-decides whether to act
            return
        }
        if (!firewallController.enableFirewall()) {
            Log.w(TAG, "Boot restore: failed to enable Chain 3")
            return
        }
        var allApplied = true
        for (packageName in blocked) {
            if (!firewallController.block(packageName)) allApplied = false
        }
        Log.i(
            TAG,
            if (allApplied) "Boot restore: re-applied ${blocked.size} firewall rule(s)"
            else "Boot restore: applied ${blocked.size} rule(s), some failed"
        )
    }

    /** Suspends until the Shizuku binder is (re)received. Never resumes if Shizuku never comes up
     *  — wait, it *does* resume: the caller re-checks availability after this returns and retries
     *  when the binder has arrived. */
    private suspend fun awaitBinder() = suspendCancellableCoroutine { continuation ->
        val listener = object : Shizuku.OnBinderReceivedListener {
            override fun onBinderReceived() {
                remove(this)
                if (continuation.isActive) continuation.resume(Unit)
            }
        }
        try {
            Shizuku.addBinderReceivedListener(listener)
            continuation.invokeOnCancellation { remove(listener) }
        } catch (e: Throwable) {
            // Shizuku API isn't available at all — nothing to wait for.
            if (continuation.isActive) continuation.resume(Unit)
        }
    }

    private fun remove(listener: Shizuku.OnBinderReceivedListener) {
        try {
            Shizuku.removeBinderReceivedListener(listener)
        } catch (_: Throwable) {
        }
    }

    companion object {
        private const val TAG = "OwnApps/FirewallBoot"
    }
}