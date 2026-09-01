package com.ownapps.app.enforcement

import com.ownapps.app.data.repository.AppSuspendStateRepository

/**
 * Shared disable/enable actions used by any screen that offers an app toggle (the All Apps list).
 * Disable/enable through the privileged backend *and* keep the matching local suspend-state row
 * in step, so the UI reflects the requested state even when the backend is currently down.
 */
class PackageBlocker(
    private val packageController: PackageController,
    private val suspendStateRepository: AppSuspendStateRepository
) {
    suspend fun disable(packageName: String) {
        packageController.disable(packageName)
        suspendStateRepository.markSuspended(packageName)
    }

    suspend fun enable(packageName: String) {
        packageController.enable(packageName)
        suspendStateRepository.markUnsuspended(packageName)
    }

    /** Disables every package in [packageNames]. Idempotent — re-disabling an already-disabled
     *  app just refreshes its local row, never breaks anything. */
    suspend fun disableAll(packageNames: Collection<String>) {
        packageNames.forEach { packageController.disable(it); suspendStateRepository.markSuspended(it) }
    }

    /** Enables (re-enables) every package in [packageNames]. No-ops on already-enabled apps. */
    suspend fun enableAll(packageNames: Collection<String>) {
        packageNames.forEach { packageController.enable(it); suspendStateRepository.markUnsuspended(it) }
    }

    /** Whether disabling is currently possible (backend alive + permission granted). Enabling is
     *  always allowed so a stale local "disabled" flag can always be cleared. */
    fun canDisable(): Boolean = packageController.isAvailable()
}