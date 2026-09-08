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
            // The first emission waits for both plus the app list (see maybeEmit), so switches
            // don't flash "enabled" and snap into place once Room's first reading arrives.
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

    /**
     * Reloads the app list. Called when the screen opens; the list is served from a short-lived
     * cache so re-opening stays instant, and drop the cache only on package add/remove.
     */
    suspend fun refresh() {
        // Stale-while-revalidate: show the last snapshot immediately if we have one, so a refresh
        // never flashes back to a spinner.
        if (appsCache.isNotEmpty() && statesReady) emitState()
        appsCache = installedAppsRepository.getLaunchableApps()
        appsLoaded = true
        maybeEmit()
    }

    private fun maybeEmit() {
        // Hold the first frame back until both inputs are available: app rows alone (or toggle
        // state alone) would render a list whose switches jump to their real states a moment after
        // the page opens.
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

    /** Enables (re-enables) every currently-pinned app. */
    fun enableAllPinned() {
        viewModelScope.launch {
            packageBlocker.enableAll(pinnedPackages)
        }
    }
}