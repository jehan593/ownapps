package com.ownapps.app

import android.app.Application
import com.ownapps.app.data.pm.PackageChangeReceiver
import com.ownapps.app.di.AppContainer
import com.ownapps.app.di.DefaultAppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class OwnAppsApplication : Application() {
    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        container = DefaultAppContainer(this)
        PackageChangeReceiver(container.installedAppsRepository).register(this)
        warmUpInstalledApps()
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