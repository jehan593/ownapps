package com.ownapps.app.enforcement

import android.os.IBinder
import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/**
 * Drives app disabling/enabling through a Shizuku-family privileged backend.
 *
 * Only the *unified* `rikka.shizuku.Shizuku` compatibility layer is used here, never any
 * fork-specific API — so this works identically against official Shizuku, Sui, and the various
 * Shizuku forks that keep the upstream binder protocol. `ShizukuProvider` (see the manifest)
 * already auto-initializes Sui from v12.1.0 on, so no fork-conditional code path is maintained.
 *
 * "Disable" is `pm disable-user` semantics: `PackageManager` is told to set the app to the
 * user-disabled state ([COMPONENT_ENABLED_STATE_DISABLED_USER]) via the privileged
 * `IPackageManager` service, which removes it from the launcher and prevents it from launching
 * until re-enabled.
 */
interface PackageController {
    suspend fun disable(packageName: String)
    suspend fun enable(packageName: String)
    /** True when a Shizuku-family backend is installed and its binder is alive. */
    fun isServiceReady(): Boolean
    /** True when this app has been granted the Shizuku-family permission. */
    fun isPermissionGranted(): Boolean
    /** True when [isServiceReady] and [isPermissionGranted] are both true. */
    fun isAvailable(): Boolean = isServiceReady() && isPermissionGranted()
    fun requestPermission()
}

class ShizukuPackageController : PackageController {

    override suspend fun disable(packageName: String) {
        setEnabled(packageName, COMPONENT_ENABLED_STATE_DISABLED_USER)
    }

    override suspend fun enable(packageName: String) {
        setEnabled(packageName, COMPONENT_ENABLED_STATE_ENABLED)
    }

    override fun isServiceReady(): Boolean = try {
        Shizuku.pingBinder()
    } catch (e: Exception) {
        false
    }

    override fun isPermissionGranted(): Boolean = try {
        Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Exception) {
        false
    }

    /** Opens the Shizuku-family permission grant dialog (no-op if already granted / binder dead). */
    override fun requestPermission() {
        try {
            if (isPermissionGranted().not()) {
                Shizuku.requestPermission(REQUEST_CODE)
            }
        } catch (e: Exception) {
            // Ignore — the settings screen will show the button again if unavailable.
        }
    }

    /**
     * Calls `IPackageManager.setApplicationEnabledSetting` through the privileged shell/root
     * identity. The AIDL interface is hidden from the public SDK, so it's reached via the
     * standard reflection pattern used by Shizuku-family tools ([ShizukuBinderWrapper] +
     * [SystemServiceHelper]). The method gained a trailing `callingPackage` argument in newer
     * API levels, so we resolve it by name and pass the extra argument only when the found
     * overload expects it. Failures are logged and swallowed rather than crashing the app — the
     * caller's "looks like it didn't take effect" heuristic covers a silent no-op.
     */
    private fun setEnabled(packageName: String, newState: Int) {
        try {
            val intType = Int::class.javaPrimitiveType
            val binder: IBinder =
                ShizukuBinderWrapper(SystemServiceHelper.getSystemService("package"))
            val stub = Class.forName("android.content.pm.IPackageManager\$Stub")
            val asInterface = stub.getMethod("asInterface", IBinder::class.java)
            val pm = asInterface.invoke(null, binder)

            val api = Class.forName("android.content.pm.IPackageManager")
            // The owner user (0) is where OwnApps and the apps it disables live; UserHandle.myUserId()
            // and friends are hidden from the public SDK, so this is spelled out directly — the same
            // user `pm disable-user` targets on a typical single-owner device.
            val userId = 0
            val method = api.methods.firstOrNull {
                it.name == "setApplicationEnabledSetting" && it.parameterCount in 4..5
            } ?: throw NoSuchMethodException("setApplicationEnabledSetting")

            if (method.parameterCount == 5) {
                method.invoke(pm, packageName, newState, 0, userId, CALLING_PACKAGE)
            } else {
                method.invoke(pm, packageName, newState, 0, userId)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to ${if (newState == COMPONENT_ENABLED_STATE_DISABLED_USER) "disable" else "enable"} $packageName", e)
        }
    }

    companion object {
        const val REQUEST_CODE = 1001
        private const val TAG = "OwnApps/Shizuku"
        /** The identity the OS's own `pm` uses when it drives IPackageManager as shell; matches
         *  the privilege Shizuku gives us (ADB/root), so attribution is consistent. */
        private const val CALLING_PACKAGE = "com.android.shell"
        /** `COMPONENT_ENABLED_STATE_DISABLED_USER` — what `pm disable-user` sets. */
        private const val COMPONENT_ENABLED_STATE_DISABLED_USER = 3
        /** `COMPONENT_ENABLED_STATE_ENABLED` — what `pm enable` sets. */
        private const val COMPONENT_ENABLED_STATE_ENABLED = 1
    }
}
