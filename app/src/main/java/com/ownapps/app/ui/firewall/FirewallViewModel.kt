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

    private val _uiState = MutableStateFlow(FirewallUiState())
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
            // Combine the two Room-backed flows into a single consistent snapshot. Gating the first
            // emission on both the app list and this snapshot being ready (see maybeEmit) keeps the
            // blocked/allowed switches from flashing into the wrong state right after the page
            // opens, the same pattern as the All Apps list.
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

    /** Re-queries the installed app list and refreshes the toggle state. Call whenever the screen
     *  is shown so new installs surface and the backend gets re-probed. The PackageManager list is
     *  served from a short TTL cache dropped on package add/remove/replace broadcasts, keeping
     *  re-opens instant. */
    suspend fun refresh() {
        // Stale-while-revalidate: render the previous snapshot first so a screen re-entry during a
        // session never flashes back to a spinner while the refreshed list is being fetched.
        if (appsCache.isNotEmpty() && statesReady) emitState()
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
     * Resolves the backend state for the master switch. We read the persisted "last enforced"
     * flag instead of probing Chain 3 live — reading it back can fail right after boot, and
     * nothing but the switch ever changes it. If the Shizuku binder isn't up yet, wait briefly
     * so the banner and switch don't flash wrong values on entry.
     */
    private suspend fun refreshFirewallState() {
        val serviceReady = firewallController.isServiceReady()
        val intended = settingsRepository.isFirewallEnabled()
        if (!serviceReady) {
            // Show the persisted "last enforced" state from the very first frame so the toggle
            // never flashes the opposite position while the Shizuku binder is coming up. The
            // switch stays disabled until the backend is resolved ([checkedBackend]).
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

    /**
     * Master firewall switch (original semantics: ON = firewall enforcing, OFF = idle). Turning it
     * on also re-applies every locally-persisted block, which self-heals a set of rules the
     * platform cleared on reboot. The resulting enforced state is recorded in
     * [SettingsRepository] so a later boot can re-apply it automatically.
     */
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