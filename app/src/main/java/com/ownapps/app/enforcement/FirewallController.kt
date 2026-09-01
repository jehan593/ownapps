package com.ownapps.app.enforcement

import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess

/**
 * Per-app network firewall driven through the same Shizuku-family privileged backend as app
 * disabling, but as a separate, independent feature. It uses Android's built-in **Chain 3**
 * connectivity control (the mechanism ShizuWall is built on) rather than a VPN: the platform
 * intercepts per-package traffic at the kernel/netd level, so there's no VPN prompt, no
 * persistent tunnel, and the app still needs no `INTERNET` permission of its own.
 *
 * The commands run through `sh -c` in a process spawned inside the Shizuku server (`shell` or
 * `root` identity), which is what makes `cmd connectivity` privileged:
 *  - `cmd connectivity set-chain3-enabled true|false` — master firewall switch
 *  - `cmd connectivity set-package-networking-enabled false|true <pkg>` — per-app rule
 *
 * Chain 3 is available on Android 11+ (API 30); on older devices every command just fails. The
 * rules are cleared by the platform on reboot, so the UI reapplies the locally persisted blocked
 * list whenever the master switch is turned back on. Failures are logged and swallowed rather
 * than crashing the app.
 *
 * Note: there is deliberately no live "is the firewall on" probe here. Reading Chain 3 state back
 * from the platform (`cmd connectivity get-chain3-enabled`) is unreliable — it can fail or read
 * stale at boot/cold-start — and nothing except this master switch (or a reboot) ever changes the
 * state, so the UI derives the switch position from the persisted "last enforced" flag in
 * SettingsRepository instead. On a reboot the boot receiver re-applies the flag's rules.
 */
interface FirewallController {
    suspend fun block(packageName: String): Boolean
    suspend fun unblock(packageName: String): Boolean
    suspend fun enableFirewall(): Boolean
    suspend fun disableFirewall(): Boolean
    /** True when a Shizuku-family backend is installed and its binder is alive. */
    fun isServiceReady(): Boolean
    /** True when this app has been granted the Shizuku-family permission. */
    fun isPermissionGranted(): Boolean
    /** True when [isServiceReady] and [isPermissionGranted] are both true. */
    fun isAvailable(): Boolean = isServiceReady() && isPermissionGranted()
    fun requestPermission()
}

class ShizukuFirewallController : FirewallController {

    override suspend fun block(packageName: String): Boolean =
        exec("cmd connectivity set-package-networking-enabled false $packageName")

    override suspend fun unblock(packageName: String): Boolean =
        exec("cmd connectivity set-package-networking-enabled true $packageName")

    override suspend fun enableFirewall(): Boolean =
        exec("cmd connectivity set-chain3-enabled true")

    override suspend fun disableFirewall(): Boolean =
        exec("cmd connectivity set-chain3-enabled false")

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
            // Ignore — the firewall screen will show the button again if unavailable.
        }
    }

    /** Runs [command] in a `/system/bin/sh -c` process in the Shizuku server and reports whether
     *  it exited 0. stdout/stderr are drained first so a large reply can never deadlock the pipe. */
    private suspend fun exec(command: String): Boolean = withContext(Dispatchers.IO) {
        execWithOutputOnIo(command).first == 0
    }

    private fun execWithOutputOnIo(command: String): Pair<Int, String> {
        return try {
            val process = NEW_PROCESS_METHOD.invoke(
                null,
                arrayOf("/system/bin/sh", "-c", command),
                null,
                null
            ) as? ShizukuRemoteProcess ?: return Pair(-1, "")

            val stdout = process.inputStream.bufferedReader().use { it.readText() }
            val stderr = process.errorStream.bufferedReader().use { it.readText() }
            process.outputStream.close()
            val exitCode = process.waitFor()
            process.destroy()
            if (exitCode != 0 && stderr.isNotBlank()) {
                Log.e(TAG, "$command failed ($exitCode): ${stderr.trim()}")
            }
            Pair(exitCode, stdout)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to run $command", e)
            Pair(-1, "")
        }
    }

    companion object {
        private const val TAG = "OwnApps/Firewall"
        const val REQUEST_CODE = 1002

        /** `Shizuku.newProcess` spawns the command *inside* the Shizuku server, so it runs with
         *  the server's identity (adb `shell` or `root`) — exactly the privilege the `cmd
         *  connectivity` firewall commands need. The method is not part of the public API
         *  surface in 13.1.x, so it's reached by name; R8 keeps it via proguard-rules.pro. */
        private val NEW_PROCESS_METHOD by lazy {
            Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            ).apply { isAccessible = true }
        }
    }
}