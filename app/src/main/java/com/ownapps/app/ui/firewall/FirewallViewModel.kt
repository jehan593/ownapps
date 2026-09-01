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
import kotlinx.coroutines.flow.collectLatest
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
    @Volatile
    private var blockedPackages: Set<String> = emptySet()
    @Volatile
    private var pinnedPackages: Set<String> = emptySet()
    @Volatile
    private var pinnedPositions: Map<String, Int> = emptyMap()
    private var pinnedOrder: List<String> = emptyList()
    @Volatile
    private var appsLoaded = false

    init {
        viewModelScope.launch {
            firewallRulesRepository.observeAllBlocked().collectLatest { blocked ->
                blockedPackages = blocked.map { it.packageName }.toSet()
                maybeEmit()
            }
        }
        viewModelScope.launch {
            firewallPinnedAppsRepository.observePinned().collectLatest { pinned ->
                pinnedPackages = pinned.map { it.packageName }.toSet()
                pinnedPositions = pinned.associate { it.packageName to it.position }
                pinnedOrder = pinned.map { it.packageName }
                maybeEmit()
            }
        }
    }

    /** Re-queries the installed app list and refreshes the toggle state. Call whenever the screen
     *  is shown so new installs surface and the backend gets re-probed. */
    suspend fun refresh() {
        reloadApps()
        emitState()
        refreshFirewallState()
    }

    private fun maybeEmit() {
        if (appsLoaded) {
            emitState()
        } else {
            viewModelScope.launch {
                reloadApps()
                emitState()
            }
        }
    }

    private suspend fun reloadApps() {
        installedAppsRepository.invalidate()
        // The same launcher-only list as the All Apps screen (no QUERY_ALL_PACKAGES), minus the
        // Shizuku/Sui backends ownapps's firewall runs through — blocking their network would kill
        // the very privilege channel the firewall uses.
        appsCache = installedAppsRepository.getLaunchableApps()
            .filter { it.packageName !in BACKEND_GUARD }
        appsLoaded = true
    }

    /**
     * Resolves the backend state the master switch depends on. The switch position is driven by
     * the persisted "last enforced" flag (see [SettingsRepository.FIREWALL_ENABLED]) rather than a
     * live probe of Chain 3 — reading that back from the platform is unreliable (it can fail right
     * after boot or during a cold start) and nothing but this switch ever changes it. If the
     * Shizuku binder isn't up yet, wait briefly for it so the "needs Shizuku" banner and the
     * switch don't flash wrong values on every screen entry; once [checkedBackend] is set the
     * result is final for this screen visit.
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