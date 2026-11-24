package com.ownapps.app.enforcement

import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess

/** Mirrors ShizuWall's unsupported-platform replies: broad error text would misread harmless ROM
 *  chatter as a missing Chain 3, while these strings are unambiguous. */
private val UNSUPPORTED_FIREWALL_REPLIES = listOf(
    "not available on pre-t devices",
    "unknown command: set-chain3-enabled",
    "unknown command: set-package-networking-enabled"
)

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
 * Chain 3 is available on Android 11+ (API 30) on ROMs that ship the commands; a handful of
 * devices don't, which [isSupported] detects. Rules are cleared by the platform on reboot, so the
 * UI re-applies the locally persisted blocked list whenever the master switch is turned on.
 *
 * Every write is checked for a zero exit code (or the harmless "sUidOwnerMap does not have entry
 * for uid" reply) and for the exact "unsupported platform" replies ShizuWall recognizes. There is
 * deliberately no read-back: `get-chain3-enabled` can be stale right after boot, and a standalone
 * probe could wrongly reject a ROM that has `set-*` but no `get-*`. Support is therefore inferred
 * from real writes only. All commands go through one mutex so concurrent callers (UI, boot restore)
 * cannot race each other.
 */
interface FirewallController {
    suspend fun block(packageName: String): Boolean
    suspend fun unblock(packageName: String): Boolean
    suspend fun enableFirewall(): Boolean
    suspend fun disableFirewall(): Boolean
    /** False once a real firewall write reported that this ROM lacks the Chain 3 commands. */
    suspend fun isSupported(): Boolean
    /** True when a Shizuku-family backend is installed and its binder is alive. */
    fun isServiceReady(): Boolean
    /** True when this app has been granted the Shizuku-family permission. */
    fun isPermissionGranted(): Boolean
    /** True when [isServiceReady] and [isPermissionGranted] are both true. */
    fun isAvailable(): Boolean = isServiceReady() && isPermissionGranted()
    fun requestPermission()
}

class ShizukuFirewallController : FirewallController {

    /** Serializes every command so a toggle can't interleave with another command. */
    private val commandMutex = Mutex()

    @Volatile
    private var unsupportedDetected = false

    override suspend fun block(packageName: String): Boolean = serialized {
        execSucceeded("cmd connectivity set-package-networking-enabled false $packageName")
    }

    override suspend fun unblock(packageName: String): Boolean = serialized {
        execSucceeded("cmd connectivity set-package-networking-enabled true $packageName")
    }

    override suspend fun enableFirewall(): Boolean = serialized {
        execSucceeded("cmd connectivity set-chain3-enabled true")
    }

    override suspend fun disableFirewall(): Boolean = serialized {
        execSucceeded("cmd connectivity set-chain3-enabled false")
    }

    /** No standalone probe: a ROM with `set-*` but without `get-*` would be rejected wrongly, and
     *  a read-back value can be stale after boot. Real writes set [unsupportedDetected]. */
    override suspend fun isSupported(): Boolean = !unsupportedDetected

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

    private suspend fun <T> serialized(block: suspend () -> T): T = commandMutex.withLock { block() }

    private suspend fun execSucceeded(command: String): Boolean = execRaw(command).succeeded

    /** Runs [command] in `/system/bin/sh -c` inside the Shizuku server. Both pipes are drained
     *  concurrently so a verbose reply can't deadlock the process, and the process is always
     *  destroyed. */
    private suspend fun execRaw(command: String): CommandResult = withContext(Dispatchers.IO) {
        try {
            val process = NEW_PROCESS_METHOD.invoke(
                null,
                arrayOf("/system/bin/sh", "-c", command),
                null,
                null
            ) as? ShizukuRemoteProcess
                ?: return@withContext CommandResult(-1, "", "no remote process")

            try {
                val stdout = async { process.inputStream.bufferedReader().use { it.readText() } }
                val stderr = async { process.errorStream.bufferedReader().use { it.readText() } }
                val out = stdout.await()
                val err = stderr.await()
                val exitCode = process.waitFor()
                val result = CommandResult(exitCode, out, err)
                if (result.isUnsupportedPlatform) unsupportedDetected = true
                if (exitCode != 0 && !result.isUidOwnerMapMissing) {
                    Log.e(TAG, "$command failed ($exitCode): ${err.trim().ifEmpty { out.trim() }}")
                }
                result
            } finally {
                process.destroy()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to run $command", e)
            val message = e.message.orEmpty()
            if (UNSUPPORTED_FIREWALL_REPLIES.any { it in message.lowercase() }) unsupportedDetected = true
            CommandResult(-1, "", message)
        }
    }

    private data class CommandResult(val exitCode: Int, val stdout: String, val stderr: String) {
        private val combined: String = "$stdout\n$stderr".lowercase()

        val isUnsupportedPlatform: Boolean get() = UNSUPPORTED_FIREWALL_REPLIES.any { it in combined }

        /** Some ROMs answer with this when a UID has no rule yet; the write itself still landed. */
        val isUidOwnerMapMissing: Boolean get() = combined.contains(UID_OWNER_MAP_MISSING)

        /** Only the exact unsupported replies override a zero exit code — a broad error regex would
         *  misread harmless ROM chatter as failure. */
        val succeeded: Boolean get() = !isUnsupportedPlatform && (exitCode == 0 || isUidOwnerMapMissing)

        companion object {
            private const val UID_OWNER_MAP_MISSING = "suidownermap does not have entry for uid"
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
