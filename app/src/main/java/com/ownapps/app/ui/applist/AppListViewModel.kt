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
import kotlinx.coroutines.flow.collectLatest
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
    @Volatile
    private var suspendedPackages: Set<String> = emptySet()
    @Volatile
    private var pinnedPackages: Set<String> = emptySet()
    @Volatile
    private var pinnedPositions: Map<String, Int> = emptyMap()
    private var pinnedOrder: List<String> = emptyList()
    @Volatile
    private var appsLoaded = false

    init {
        viewModelScope.launch {
            suspendStateRepository.observeAllSuspended().collectLatest { suspended ->
                suspendedPackages = suspended.map { it.packageName }.toSet()
                maybeEmit()
            }
        }
        viewModelScope.launch {
            pinnedAppsRepository.observePinned().collectLatest { pinned ->
                pinnedPackages = pinned.map { it.packageName }.toSet()
                pinnedPositions = pinned.associate { it.packageName to it.position }
                pinnedOrder = pinned.map { it.packageName }
                maybeEmit()
            }
        }
    }

    /** Re-queries the installed app list and pushes a fresh UI state. Call whenever the screen is
     *  shown (All Apps opened) so new installs and state changes surface. */
    suspend fun refresh() {
        reloadApps()
        emitState()
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
        appsCache = installedAppsRepository.getLaunchableApps()
        appsLoaded = true
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