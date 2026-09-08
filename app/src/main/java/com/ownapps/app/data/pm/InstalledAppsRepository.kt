package com.ownapps.app.data.pm

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class LaunchableApp(
    val packageName: String,
    val label: String,
    val icon: Drawable
)

/**
 * Caches the installed-app list, which is expensive to build (PackageManager query + icon per
 * app) and rarely changes. A short TTL plus invalidation on package add/remove keeps callers fast.
 *
 * The list is the *launchable* set (apps with a launcher activity).
 */
class InstalledAppsRepository(private val packageManager: PackageManager, private val selfPackage: String) {

    private val mutex = Mutex()
    @Volatile
    private var cache: List<LaunchableApp>? = null
    @Volatile
    private var cachedAtMillis: Long = 0L

    suspend fun getLaunchableApps(): List<LaunchableApp> = mutex.withLock {
        val cached = cache
        val now = System.currentTimeMillis()
        if (cached != null && now - cachedAtMillis < CACHE_TTL_MILLIS) {
            return@withLock cached
        }
        val fresh = queryLaunchableApps()
        cache = fresh
        cachedAtMillis = now
        fresh
    }

    /** Drops the cached list so the next [getLaunchableApps] call re-queries PackageManager. */
    fun invalidate() {
        cache = null
        cachedAtMillis = 0L
    }

    /** The launcher intent for opening [packageName], or null if it has none / isn't visible. */
    fun getLaunchIntent(packageName: String): Intent? =
        packageManager.getLaunchIntentForPackage(packageName)

    private suspend fun queryLaunchableApps(): List<LaunchableApp> = withContext(Dispatchers.Default) {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        // MATCH_DISABLED_COMPONENTS keeps apps OwnApps has disabled in the list — without it,
        // the query silently drops them, hiding exactly the apps the blocker is meant to manage.
        packageManager.queryIntentActivities(launcherIntent, PackageManager.MATCH_DISABLED_COMPONENTS)
            .asSequence()
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName != selfPackage }
            .map { appInfo: ApplicationInfo ->
                LaunchableApp(
                    packageName = appInfo.packageName,
                    label = appInfo.loadLabel(packageManager).toString(),
                    icon = appInfo.loadIcon(packageManager)
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    companion object {
        private const val CACHE_TTL_MILLIS = 5 * 60 * 1000L
    }
}
