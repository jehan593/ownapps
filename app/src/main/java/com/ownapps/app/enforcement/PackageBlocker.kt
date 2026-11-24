package com.ownapps.app.enforcement

import com.ownapps.app.data.repository.AppSuspendStateRepository

/**
 * Shared disable/enable actions used by any screen that offers an app toggle (the All Apps list).
 * Disable/enable through the privileged backend *and* keep the matching local suspend-state row
 * in step, so the UI reflects the requested state even when the backend is currently down.
 *
 * The returned Boolean is the backend's result, not the local write: callers that need to be sure
 * the change really landed (re-disabling an app after an update) use it to decide whether to keep
 * or drop the local row.
 */
class PackageBlocker(
    private val packageController: PackageController,
    private val suspendStateRepository: AppSuspendStateRepository
) {
    suspend fun disable(packageName: String): Boolean {
        val applied = packageController.disable(packageName)
        suspendStateRepository.markSuspended(packageName)
        return applied
    }

    suspend fun enable(packageName: String): Boolean {
        val applied = packageController.enable(packageName)
        suspendStateRepository.markUnsuspended(packageName)
        return applied
    }

    /** Disables every package in [packageNames]. Idempotent — re-disabling an already-disabled
     *  app just refreshes its local row, never breaks anything. */
    suspend fun disableAll(packageNames: Collection<String>): Boolean {
        var ok = true
        for (packageName in packageNames) if (!disable(packageName)) ok = false
        return ok
    }

    /** Re-enables every package in [packageNames]. No-ops on already-enabled apps. */
    suspend fun enableAll(packageNames: Collection<String>): Boolean {
        var ok = true
        for (packageName in packageNames) if (!enable(packageName)) ok = false
        return ok
    }

    /** Whether disabling is currently possible (backend alive + permission granted). Enabling is
     *  always allowed so a stale local "disabled" flag can always be cleared. */
    fun canDisable(): Boolean = packageController.isAvailable()

    /** Whether Shizuku is ready to use. */
    fun isServiceReady(): Boolean = packageController.isServiceReady()

    /** Whether OwnApps can use Shizuku. */
    fun isPermissionGranted(): Boolean = packageController.isPermissionGranted()

    /** Requests Shizuku permission. */
    fun requestPermission() = packageController.requestPermission()
}
