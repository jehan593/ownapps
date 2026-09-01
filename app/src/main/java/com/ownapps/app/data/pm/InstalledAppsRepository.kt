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
 * The installed-app list is expensive to compute (PackageManager query + loadIcon() per app) but
 * rarely changes — most callers just want "what's installed," not a live-updating view of it. In
 * particular the widget re-queries this on every refresh tick (every 15-300s while a widget
 * instance exists), which without caching means a full PackageManager scan + icon load for every
 * installed app that often, just to compute a top-3 list. A short TTL cache cuts that down; a
 * package-add/remove broadcast (see [invalidate]) covers the case where a change happens inside
 * that window instead of shrinking the TTL for everyone.
 *
 * The list is the *launchable* set (apps with a launcher activity). Screens that need a broader
 * set must query PackageManager themselves.
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
        // MATCH_DISABLED_COMPONENTS keeps apps that OwnApps has disabled (pm disable-user) in the
        // list: with flags 0 the query silently drops disabled packages, which made a disabled app
        // vanish from OwnApps's own list — the exact opposite of what a blocker should do. Disabled
        // apps must stay visible so their toggle can still clear them.
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
