package com.ownapps.app

import android.app.Application
import com.ownapps.app.data.pm.PackageChangeReceiver
import com.ownapps.app.di.AppContainer
import com.ownapps.app.di.DefaultAppContainer
import com.ownapps.app.enforcement.FirewallRestoreNotifier
import com.ownapps.app.enforcement.FirewallRestoreResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

class OwnAppsApplication : Application() {
    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Shizuku frequently starts after our boot receiver has already run. Keeping this listener for
     *  the life of the app process lets a saved firewall restore itself the moment the binder
     *  arrives, without polling or a persistent foreground service. */
    private val binderListener = object : Shizuku.OnBinderReceivedListener {
        override fun onBinderReceived() = retryPendingFirewallRestore()
    }

    override fun onCreate() {
        super.onCreate()
        container = DefaultAppContainer(this)
        PackageChangeReceiver(container.installedAppsRepository).register(this)
        try {
            Shizuku.addBinderReceivedListener(binderListener)
        } catch (_: Throwable) {
            // Shizuku API unavailable — the UI still retries on open.
        }
        warmUpInstalledApps()
        // Covers the case where Shizuku was already up before this process started.
        retryPendingFirewallRestore()
    }

    private fun retryPendingFirewallRestore() {
        appScope.launch(Dispatchers.IO) {
            when (container.firewallRestoreCoordinator.restoreIfPending()) {
                FirewallRestoreResult.PENDING -> FirewallRestoreNotifier.showWaiting(this@OwnAppsApplication)
                FirewallRestoreResult.RESTORED,
                FirewallRestoreResult.SKIPPED,
                FirewallRestoreResult.UNSUPPORTED -> FirewallRestoreNotifier.cancel(this@OwnAppsApplication)
            }
        }
    }

    // Fresh launches land on the All Apps list, whose first render waits on a PackageManager
    // query + icon load. Kick that off here so the screen finds the cache warm instead of showing
    // a spinner for the whole scan.
    private fun warmUpInstalledApps() {
        appScope.launch {
            container.installedAppsRepository.getLaunchableApps()
        }
    }
}
