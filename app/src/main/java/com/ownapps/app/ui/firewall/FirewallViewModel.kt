package com.ownapps.app.ui.firewall

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ownapps.app.data.pm.InstalledAppsRepository
import com.ownapps.app.data.pm.LaunchableApp
import com.ownapps.app.data.repository.FirewallPinnedAppsRepository
import com.ownapps.app.data.repository.FirewallRulesRepository
import com.ownapps.app.data.repository.SettingsRepository
import com.ownapps.app.enforcement.FirewallBlocker
import com.ownapps.app.enforcement.FirewallController
import com.ownapps.app.enforcement.FirewallRestoreCoordinator
import com.ownapps.app.enforcement.FirewallRestoreNotifier
import com.ownapps.app.enforcement.FirewallRestoreResult
import com.ownapps.app.ui.components.StateFilter
import kotlin.coroutines.resume
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku

data class FirewallRow(
    val packageName: String,
    val label: String,
    val icon: Drawable,
    val isBlocked: Boolean,
    val isPinned: Boolean,
    val pinPosition: Int = Int.MAX_VALUE
)

data class FirewallUiState(
    val rows: List<FirewallRow> = emptyList(),
    val pinnedOrder: List<String> = emptyList(),
    val firewallEnabled: Boolean = false,
    val canControl: Boolean = false,
    val isServiceReady: Boolean = false,
    val isPermissionGranted: Boolean = false,
    /** True once the backend state has been resolved — gates the "needs Shizuku" banner so it
     *  never flashes while the binder connection is still being established. */
    val checkedBackend: Boolean = false,
    val isLoading: Boolean = true,
    /** The ROM doesn't ship the Chain 3 shell commands. */
    val unsupported: Boolean = false,
    /** Saved ON, but Chain 3 wasn't restored yet (Shizuku not ready). Not enforced yet. */
    val restorePending: Boolean = false,
    /** Visible warning when a rule or the master switch could not be applied. */
    val statusMessage: String? = null
)

class FirewallViewModel(
    private val installedAppsRepository: InstalledAppsRepository,
    private val firewallRulesRepository: FirewallRulesRepository,
    private val firewallPinnedAppsRepository: FirewallPinnedAppsRepository,
    private val firewallController: FirewallController,
    private val firewallBlocker: FirewallBlocker,
    private val firewallRestoreCoordinator: FirewallRestoreCoordinator,
    private val settingsRepository: SettingsRepository,
    private val appContext: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        FirewallUiState(
            firewallEnabled = settingsRepository.firewallEnabledCached(),
            restorePending = settingsRepository.firewallRestorePendingCached()
        )
    )
    val uiState: StateFlow<FirewallUiState> = _uiState.asStateFlow()

    private var appsCache: List<LaunchableApp> = emptyList()
    private var blockedPackages: Set<String> = emptySet()
    private var pinnedPackages: Set<String> = emptySet()
    private var pinnedPositions: Map<String, Int> = emptyMap()
    private var pinnedOrder: List<String> = emptyList()
    private var appsLoaded = false
    private var statesReady = false

    init {
        viewModelScope.launch {
            // Seed the switch and the pending banner from disk before the binder resolves.
            settingsRepository.preloadFirewallState()
            _uiState.update {
                it.copy(
                    firewallEnabled = settingsRepository.firewallEnabledCached(),
                    restorePending = settingsRepository.firewallRestorePendingCached()
                )
            }
        }
        viewModelScope.launch {
            // The app-level Shizuku listener can finish a pending restore while this screen is open;
            // observe the flag so the banner disappears without waiting for a resume.
            settingsRepository.firewallRestorePendingFlow.collect { pending ->
                _uiState.update {
                    it.copy(
                        restorePending = pending,
                        statusMessage = when {
                            pending -> PENDING_MESSAGE
                            it.statusMessage == PENDING_MESSAGE -> null
                            else -> it.statusMessage
                        }
                    )
                }
            }
        }
        viewModelScope.launch {
            // Combine the two Room flows into one snapshot; the first emission then waits for the
            // app list too (see maybeEmit) so blocked/allowed switches never flash wrong values.
            combine(
                firewallRulesRepository.observeAllBlocked(),
                firewallPinnedAppsRepository.observePinned()
            ) { blockedRows, pinnedRows ->
                blockedPackages = blockedRows.map { it.packageName }.toSet()
                pinnedPackages = pinnedRows.map { it.packageName }.toSet()
                pinnedPositions = pinnedRows.associate { it.packageName to it.position }
                pinnedOrder = pinnedRows.map { it.packageName }
                statesReady = true
            }.collect { maybeEmit() }
        }
    }

    /** Re-queries the installed app list and re-probes the backend. Call whenever the screen is shown.
     *  The list is served from a short TTL cache dropped on package add/remove/replace. */
    suspend fun refresh() {
        if (appsCache.isNotEmpty() && statesReady) emitState()
        appsCache = installedAppsRepository.getLaunchableApps()
            .filter { it.packageName !in BACKEND_GUARD }
        appsLoaded = true
        maybeEmit()
        refreshFirewallState()
        pruneUninstalledRules()
        catchUpPendingRestore()
    }

    /** Manual-pull variant: bypasses the cache and re-probes the backend. Only ever triggered by a
     *  manual pull gesture. */
    suspend fun refreshAll() {
        installedAppsRepository.invalidate()
        appsCache = installedAppsRepository.getLaunchableApps()
            .filter { it.packageName !in BACKEND_GUARD }
        appsLoaded = true
        maybeEmit()
        refreshFirewallState()
        pruneUninstalledRules()
        catchUpPendingRestore()
    }

    private fun maybeEmit() {
        if (appsLoaded && statesReady) emitState()
    }

    /**
     * Resolves backend state. The switch shows the saved user intent, never a live Chain 3 probe:
     * the probe can be stale after boot and shouldn't override what the user chose. A separate
     * pending flag says whether that intent is actually enforced yet. Waits briefly for the binder
     * so the "needs Shizuku" banner doesn't flash during cold start.
     */
    private suspend fun refreshFirewallState() {
        val serviceReady = firewallController.isServiceReady()
        val intended = settingsRepository.isFirewallEnabled()
        val pending = settingsRepository.isFirewallRestorePending()
        if (!serviceReady) {
            _uiState.update {
                it.copy(
                    firewallEnabled = intended,
                    restorePending = pending,
                    canControl = false,
                    isServiceReady = false,
                    isPermissionGranted = false,
                    statusMessage = if (pending) PENDING_MESSAGE else null
                )
            }
            if (awaitBinder(BINDER_WAIT_MILLIS)) {
                // Binder arrived — re-resolve with the now-live connection instead of recursing.
                refreshFirewallState()
            } else {
                _uiState.update {
                    it.copy(
                        firewallEnabled = intended,
                        restorePending = pending,
                        canControl = false,
                        isServiceReady = false,
                        isPermissionGranted = false,
                        checkedBackend = true,
                        statusMessage = if (pending) PENDING_MESSAGE else null
                    )
                }
            }
            return
        }
        val permissionGranted = firewallController.isPermissionGranted()
        val available = permissionGranted
        val unsupported = available && !firewallController.isSupported()
        _uiState.update {
            it.copy(
                firewallEnabled = intended,
                restorePending = pending,
                canControl = available && !unsupported,
                isServiceReady = true,
                isPermissionGranted = permissionGranted,
                checkedBackend = true,
                unsupported = unsupported,
                statusMessage = when {
                    unsupported -> UNSUPPORTED_MESSAGE
                    pending -> PENDING_MESSAGE
                    else -> null
                }
            )
        }
    }

    /** If boot left a saved restore unfinished, take another shot now that the screen is open and
     *  the backend may be up. Only runs on open/pull, never after a toggle, so it cannot fight a
     *  user action. */
    private suspend fun catchUpPendingRestore() {
        if (!settingsRepository.isFirewallRestorePending()) return
        if (!firewallController.isAvailable()) return
        when (firewallRestoreCoordinator.restoreIfPending()) {
            FirewallRestoreResult.PENDING -> FirewallRestoreNotifier.showWaiting(appContext)
            FirewallRestoreResult.RESTORED,
            FirewallRestoreResult.SKIPPED,
            FirewallRestoreResult.UNSUPPORTED -> FirewallRestoreNotifier.cancel(appContext)
        }
        refreshFirewallState()
    }

    /**
     * A saved rule for an app that no longer exists can never be shown or switched off in the UI,
     * and would be silently re-applied on a later toggle or reboot. Only runs on an explicit
     * screen open / pull, never after a toggle, so it can't fight a user action.
     */
    private suspend fun pruneUninstalledRules() {
        val blocked = firewallRulesRepository.getBlockedPackages()
        if (blocked.isEmpty()) return
        val stale = blocked - installedAppsRepository.filterInstalled(blocked)
        if (stale.isNotEmpty()) firewallRulesRepository.deleteRules(stale)
    }

    /** Suspends until the Shizuku binder arrives, giving up after [timeoutMillis]. The listener is
     *  removed on success, timeout, or ViewModel teardown. */
    private suspend fun awaitBinder(timeoutMillis: Long): Boolean {
        return try {
            withTimeout(timeoutMillis) {
                suspendCancellableCoroutine<Unit> { continuation ->
                    val listener = object : Shizuku.OnBinderReceivedListener {
                        override fun onBinderReceived() {
                            removeBinderListener(this)
                            if (continuation.isActive) continuation.resume(Unit)
                        }
                    }
                    try {
                        Shizuku.addBinderReceivedListener(listener)
                        continuation.invokeOnCancellation { removeBinderListener(listener) }
                    } catch (e: Throwable) {
                        // Shizuku API isn't available at all — nothing to wait for.
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                }
                true
            }
        } catch (e: TimeoutCancellationException) {
            false
        }
    }

    private fun removeBinderListener(listener: Shizuku.OnBinderReceivedListener) {
        try {
            Shizuku.removeBinderReceivedListener(listener)
        } catch (_: Throwable) {
        }
    }

    private fun emitState() {
        _uiState.update {
            it.copy(
                rows = appsCache.map { app ->
                    FirewallRow(
                        packageName = app.packageName,
                        label = app.label,
                        icon = app.icon,
                        isBlocked = app.packageName in blockedPackages,
                        isPinned = app.packageName in pinnedPackages,
                        pinPosition = pinnedPositions[app.packageName] ?: Int.MAX_VALUE
                    )
                },
                pinnedOrder = pinnedOrder,
                isLoading = false
            )
        }
    }

    /** UI-only filter; always starts at All on a fresh launch and is never persisted. */
    fun setFilter(filter: StateFilter) {
        _uiState.update { it.copy(filter = filter) }
    }

    /** Master firewall switch. Turning it on re-applies every persisted block (the platform clears
     *  them on reboot); the resulting state is recorded so a later boot can restore it. */
    fun setFirewallEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val commandSucceeded = if (enabled) {
                firewallController.enableFirewall()
            } else {
                firewallController.disableFirewall()
            }
            var failedRules = 0
            if (enabled && commandSucceeded) {
                failedRules = firewallRulesRepository.getBlockedPackages().count { packageName ->
                    !firewallController.block(packageName)
                }
            }
            val stillAvailable = firewallController.isAvailable()
            // Support is inferred from the write we just ran, not from a flaky get-* probe.
            val unsupported = stillAvailable && !firewallController.isSupported()
            // The switch reflects the user's intent only when the command actually landed. A
            // failed "off" must NOT be recorded as "on" (that was the inversion bug).
            val effectiveEnabled = stillAvailable && commandSucceeded && enabled
            _uiState.update {
                it.copy(
                    firewallEnabled = effectiveEnabled,
                    restorePending = false,
                    canControl = stillAvailable && !unsupported,
                    unsupported = unsupported,
                    statusMessage = when {
                        unsupported -> UNSUPPORTED_MESSAGE
                        !commandSucceeded -> "Couldn't change the firewall state. Check Shizuku."
                        failedRules > 0 -> "Firewall is on, but $failedRules block rule(s) failed."
                        else -> null
                    }
                )
            }
            settingsRepository.setFirewallEnabled(effectiveEnabled)
            settingsRepository.setFirewallRestorePending(false)
            FirewallRestoreNotifier.cancel(appContext)
        }
    }

    fun toggleBlocked(packageName: String, block: Boolean) {
        viewModelScope.launch {
            val applied = if (block) {
                firewallBlocker.block(packageName)
            } else {
                firewallBlocker.unblock(packageName)
            }
            val unsupported = firewallController.isAvailable() && !firewallController.isSupported()
            _uiState.update {
                it.copy(
                    canControl = !unsupported && it.canControl,
                    unsupported = unsupported,
                    statusMessage = when {
                        unsupported -> UNSUPPORTED_MESSAGE
                        applied -> null
                        else -> "Couldn't update networking for that app. Check Shizuku."
                    }
                )
            }
            emitState()
        }
    }

    fun togglePin(packageName: String) {
        viewModelScope.launch {
            firewallPinnedAppsRepository.togglePin(packageName)
        }
    }

    /** Persist a new manual ordering of pinned apps (full list, top to bottom). */
    fun reorderPinned(orderedPackageNames: List<String>) {
        viewModelScope.launch {
            firewallPinnedAppsRepository.reorder(orderedPackageNames)
        }
    }

    /** Blocks networking for every currently-pinned app. */
    fun blockAllPinned() {
        viewModelScope.launch {
            val applied = firewallBlocker.blockAll(pinnedPackages)
            val unsupported = firewallController.isAvailable() && !firewallController.isSupported()
            _uiState.update {
                it.copy(
                    canControl = !unsupported && it.canControl,
                    unsupported = unsupported,
                    statusMessage = when {
                        unsupported -> UNSUPPORTED_MESSAGE
                        applied -> null
                        else -> "Some pinned apps couldn't be blocked."
                    }
                )
            }
            emitState()
        }
    }

    /** Re-allows networking for every currently-pinned app. */
    fun unblockAllPinned() {
        viewModelScope.launch {
            val applied = firewallBlocker.unblockAll(pinnedPackages)
            val unsupported = firewallController.isAvailable() && !firewallController.isSupported()
            _uiState.update {
                it.copy(
                    canControl = !unsupported && it.canControl,
                    unsupported = unsupported,
                    statusMessage = when {
                        unsupported -> UNSUPPORTED_MESSAGE
                        applied -> null
                        else -> "Some pinned apps couldn't be unblocked."
                    }
                )
            }
            emitState()
        }
    }

    fun requestPermission() = firewallController.requestPermission()

    companion object {
        /** Shizuku-family backends the firewall runs through; never offered as block targets. */
        private val BACKEND_GUARD = setOf("moe.shizuku.privileged.api", "rikka.sui")

        /** How long to wait for the Shizuku binder before concluding the backend is unavailable. */
        private const val BINDER_WAIT_MILLIS = 2_000L

        private const val UNSUPPORTED_MESSAGE = "This phone doesn't support the firewall commands."
        private const val PENDING_MESSAGE =
            "Firewall is saved as ON but not restored yet. Start Shizuku to enforce it; per-app rules stay paused until then."
    }
}
