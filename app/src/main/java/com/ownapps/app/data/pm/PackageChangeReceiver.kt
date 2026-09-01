package com.ownapps.app.data.pm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat

/**
 * Invalidates [InstalledAppsRepository]'s cache whenever a package is installed, removed, or
 * replaced, so the All Apps screen doesn't keep showing an uninstalled app
 * for up to the cache's TTL. Must be registered dynamically (context.registerReceiver) — these
 * are implicit broadcasts and can no longer be declared in the manifest.
 */
class PackageChangeReceiver(private val installedAppsRepository: InstalledAppsRepository) : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        installedAppsRepository.invalidate()
    }

    fun register(context: Context) {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(context, this, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }
}
