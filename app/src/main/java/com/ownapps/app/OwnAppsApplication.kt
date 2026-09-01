package com.ownapps.app

import android.app.Application
import com.ownapps.app.data.pm.PackageChangeReceiver
import com.ownapps.app.di.AppContainer
import com.ownapps.app.di.DefaultAppContainer

class OwnAppsApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = DefaultAppContainer(this)
        PackageChangeReceiver(container.installedAppsRepository).register(this)
    }
}