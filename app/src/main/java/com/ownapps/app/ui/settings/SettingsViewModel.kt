package com.ownapps.app.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ownapps.app.enforcement.PackageController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SettingsUiState(
    val shizukuServiceReady: Boolean = false,
    val shizukuPermissionGranted: Boolean = false,
    val uiHiderServiceEnabled: Boolean = false
) {
    val shizukuReady: Boolean get() = shizukuServiceReady && shizukuPermissionGranted
}

class SettingsViewModel(
    private val packageController: PackageController,
    private val appContext: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        refreshShizukuState()
        refreshUiHiderServiceState()
    }

    fun refreshShizukuState() {
        _uiState.value = _uiState.value.copy(
            shizukuServiceReady = packageController.isServiceReady(),
            shizukuPermissionGranted = packageController.isPermissionGranted()
        )
    }

    fun refreshUiHiderServiceState() {
        val am = appContext.getSystemService(Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(
            android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_GENERIC
        )
        val serviceEnabled = enabledServices.any {
            it.resolveInfo.serviceInfo?.name == UiHIDER_SERVICE_CLASS
        }
        _uiState.value = _uiState.value.copy(uiHiderServiceEnabled = serviceEnabled)
    }

    companion object {
        private const val UiHIDER_SERVICE_CLASS = "com.ownapps.app.uihider.UiHiderService"
    }

    fun requestShizukuPermission() {
        packageController.requestPermission()
        viewModelScope.launch {
            kotlinx.coroutines.delay(3000)
            refreshShizukuState()
        }
    }
}