package com.ownapps.app.ui.applist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ownapps.app.data.pm.InstalledAppsRepository
import com.ownapps.app.data.pm.LaunchableApp
import com.ownapps.app.data.repository.AppSuspendStateRepository
import com.ownapps.app.data.repository.PinnedAppsRepository
import com.ownapps.app.enforcement.PackageBlocker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class AppListRow(
    val packageName: String,
    val label: String,
    val icon: android.graphics.drawable.Drawable,
    val isSuspended: Boolean,
    val isPinned: Boolean,
    val pinPosition: Int = Int.MAX_VALUE
)

data class AppListUiState(
    val apps: List<AppListRow> = emptyList(),
    val pinnedOrder: List<String> = emptyList(),
    val canDisable: Boolean = false,
    val permissionNeeded: Boolean = false,
    val isLoading: Boolean = true
)

class AppListViewModel(
    private val installedAppsRepository: InstalledAppsRepository,
    private val suspendStateRepository: AppSuspendStateRepository,
    private val pinnedAppsRepository: PinnedAppsRepository,
    private val packageBlocker: PackageBlocker
) : ViewModel() {

    private val _uiState = MutableStateFlow(AppListUiState())
    val uiState: StateFlow<AppListUiState> = _uiState.asStateFlow()

    private var appsCache: List<LaunchableApp> = emptyList()
    private var suspendedPackages: Set<String> = emptySet()
    private var pinnedPackages: Set<String> = emptySet()
    private var pinnedPositions: Map<String, Int> = emptyMap()
    private var pinnedOrder: List<String> = emptyList()
    private var appsLoaded = false
    private var statesReady = false

    init {
        viewModelScope.launch {
            // Combine the two flows into one snapshot so a switch never shows stale toggle state.
            combine(
                suspendStateRepository.observeAllSuspended(),
                pinnedAppsRepository.observePinned()
            ) { suspended, pinned ->
                suspendedPackages = suspended.map { it.packageName }.toSet()
                pinnedPackages = pinned.map { it.packageName }.toSet()
                pinnedPositions = pinned.associate { it.packageName to it.position }
                pinnedOrder = pinned.map { it.packageName }
                statesReady = true
            }.collect { maybeEmit() }
        }
    }

    /** Reloads the app list, served from a short-lived cache so re-opening stays instant.
     *  The local mirror is only reconciled on a deliberate [refreshAll], never here: doing it
     *  automatically races any toggle whose local DB write is still being emitted. */
    suspend fun refresh() {
        if (appsCache.isNotEmpty() && statesReady) emitState()
        appsCache = installedAppsRepository.getLaunchableApps()
        appsLoaded = true
        maybeEmit()
        reapplyDisablesAfterUpdates()
        emitState()
    }

    /** Android re-enables a disabled app when it receives an update. Only packages we actually saw
     *  replaced (via the package-change receiver) are considered, so this can never re-disable an
     *  app just because of a flaky timestamp or a stale cached list. */
    private suspend fun reapplyDisablesAfterUpdates() {
        if (!packageBlocker.canDisable()) return
        val replaced = installedAppsRepository.takeReplacedPackages()
        if (replaced.isEmpty()) return
        val suspended = suspendStateRepository.getSuspendedPackages()
        for (packageName in replaced) {
            if (packageName !in suspended) continue
            val app = appsCache.firstOrNull { it.packageName == packageName } ?: continue
            if (!app.isEnabled) continue
            val applied = packageBlocker.disable(packageName)
            if (applied) {
                // Keep the current snapshot in step so emitState() shows the re-disabled app.
                appsCache = appsCache.map {
                    if (it.packageName == packageName) it.copy(isEnabled = false) else it
                }
            } else {
                // Never claim a failed re-disable worked.
                suspendStateRepository.markUnsuspended(packageName)
            }
        }
    }

    /** Manual-pull variant: bypasses the cache so new installs surface and the local mirror is
     *  reconciled against the real on-device state. Never runs automatically, so it can't override
     *  a toggle being applied right now. */
    suspend fun refreshAll() {
        installedAppsRepository.invalidate()
        appsCache = installedAppsRepository.getLaunchableApps()
        // Re-apply disables before reconciling, so an app that was re-enabled by an update isn't
        // recorded as deliberately enabled and left that way.
        reapplyDisablesAfterUpdates()
        suspendedPackages = suspendStateRepository.reconcile(
            appsCache.associate { it.packageName to !it.isEnabled }
        )
        appsLoaded = true
        maybeEmit()
    }

    private fun maybeEmit() {
        // Hold the first frame until rows and toggle state are ready, so switches don't pop in.
        if (appsLoaded && statesReady) {
            emitState()
        }
    }

    private fun emitState() {
        _uiState.value = AppListUiState(
            apps = appsCache.map { app ->
                AppListRow(
                    packageName = app.packageName,
                    label = app.label,
                    icon = app.icon,
                    isSuspended = app.packageName in suspendedPackages,
                    isPinned = app.packageName in pinnedPackages,
                    pinPosition = pinnedPositions[app.packageName] ?: Int.MAX_VALUE
                )
            },
            pinnedOrder = pinnedOrder,
            canDisable = packageBlocker.canDisable(),
            permissionNeeded = packageBlocker.isServiceReady() && !packageBlocker.isPermissionGranted(),
            isLoading = false
        )
    }

    fun toggleEnabled(packageName: String, disable: Boolean) {
        viewModelScope.launch {
            if (disable) packageBlocker.disable(packageName) else packageBlocker.enable(packageName)
        }
    }

    fun enable(packageName: String) {
        viewModelScope.launch {
            packageBlocker.enable(packageName)
        }
    }

    fun requestPermission() {
        packageBlocker.requestPermission()
    }

    fun togglePin(packageName: String) {
        viewModelScope.launch {
            pinnedAppsRepository.togglePin(packageName)
        }
    }

    /** Persist a new manual ordering of pinned apps (full list, top to bottom). */
    fun reorderPinned(orderedPackageNames: List<String>) {
        viewModelScope.launch {
            pinnedAppsRepository.reorder(orderedPackageNames)
        }
    }

    /** Disables every currently-pinned app. */
    fun disableAllPinned() {
        viewModelScope.launch {
            packageBlocker.disableAll(pinnedPackages)
        }
    }

    /** Re-enables every currently-pinned app. */
    fun enableAllPinned() {
        viewModelScope.launch {
            packageBlocker.enableAll(pinnedPackages)
        }
    }
}
