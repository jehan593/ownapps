package com.ownapps.app.ui.firewall

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
    val isLoading: Boolean = true
)

class FirewallViewModel(
    private val installedAppsRepository: InstalledAppsRepository,
    private val firewallRulesRepository: FirewallRulesRepository,
    private val firewallPinnedAppsRepository: FirewallPinnedAppsRepository,
    private val firewallController: FirewallController,
    private val firewallBlocker: FirewallBlocker,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        FirewallUiState(firewallEnabled = settingsRepository.firewallEnabledCached())
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
            // Seed the master switch from disk in case the binder's own probe lands later.
            settingsRepository.preloadFirewallState()
            _uiState.update { it.copy(firewallEnabled = settingsRepository.firewallEnabledCached()) }
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
        // Render the previous snapshot first so re-entry never flashes back to a spinner.
        if (appsCache.isNotEmpty() && statesReady) emitState()
        appsCache = installedAppsRepository.getLaunchableApps()
            .filter { it.packageName !in BACKEND_GUARD }
        appsLoaded = true
        maybeEmit()
        refreshFirewallState()
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
    }

    private fun maybeEmit() {
        if (appsLoaded && statesReady) {
            emitState()
        }
    }

    /**
     * Resolves backend state for the master switch. Reads the persisted "last enforced" flag
     * instead of probing Chain 3 live: the probe can fail right after boot, and nothing but the
     * switch ever changes it. Waits briefly for the binder so nothing flashes a wrong value.
     */
    private suspend fun refreshFirewallState() {
        val serviceReady = firewallController.isServiceReady()
        val intended = settingsRepository.isFirewallEnabled()
        if (!serviceReady) {
            // Keep the persisted state until the binder resolves so the switch never flips wrong.
            _uiState.update {
                it.copy(
                    firewallEnabled = intended,
                    canControl = false,
                    isServiceReady = false,
                    isPermissionGranted = false
                )
            }
            val binderArrived = awaitBinder(BINDER_WAIT_MILLIS)
            if (binderArrived) {
                viewModelScope.launch { refreshFirewallState() }
                return
            }
            _uiState.update {
                it.copy(
                    firewallEnabled = false,
                    canControl = false,
                    isServiceReady = false,
                    isPermissionGranted = false,
                    checkedBackend = true
                )
            }
            return
        }
        val permissionGranted = firewallController.isPermissionGranted()
        val available = permissionGranted
        _uiState.update {
            it.copy(
                firewallEnabled = available && intended,
                canControl = available,
                isServiceReady = true,
                isPermissionGranted = permissionGranted,
                checkedBackend = true
            )
        }
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

    /** Master firewall switch. Turning it on re-applies every persisted block (the platform clears
     *  them on reboot); the resulting state is recorded so a later boot can restore it. */
    fun setFirewallEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val chainTurnedOn = if (enabled) {
                firewallController.enableFirewall()
            } else {
                firewallController.disableFirewall()
            }
            if (enabled && chainTurnedOn) reapplyBlocked()
            val stillAvailable = firewallController.isAvailable()
            val effectiveEnabled = stillAvailable && if (enabled) chainTurnedOn else !chainTurnedOn
            _uiState.update {
                it.copy(
                    firewallEnabled = effectiveEnabled,
                    canControl = stillAvailable
                )
            }
            settingsRepository.setFirewallEnabled(effectiveEnabled)
        }
    }

    fun toggleBlocked(packageName: String, block: Boolean) {
        viewModelScope.launch {
            if (block) firewallBlocker.block(packageName) else firewallBlocker.unblock(packageName)
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
            firewallBlocker.blockAll(pinnedPackages)
            emitState()
        }
    }

    /** Re-allows networking for every currently-pinned app. */
    fun unblockAllPinned() {
        viewModelScope.launch {
            firewallBlocker.unblockAll(pinnedPackages)
            emitState()
        }
    }

    fun requestPermission() = firewallController.requestPermission()

    private suspend fun reapplyBlocked(): Boolean {
        var ok = true
        for (packageName in blockedPackages) {
            if (!firewallController.block(packageName)) ok = false
        }
        return ok
    }

    companion object {
        /** Shizuku-family backends the firewall runs through; never offered as block targets. */
        private val BACKEND_GUARD = setOf("moe.shizuku.privileged.api", "rikka.sui")

        /** How long to wait for the Shizuku binder before concluding the backend is unavailable. */
        private const val BINDER_WAIT_MILLIS = 2_000L
    }
}