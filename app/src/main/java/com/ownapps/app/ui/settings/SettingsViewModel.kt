package com.ownapps.app.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
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
    val uiHiderServiceEnabled: Boolean = false,
    val batteryOptimizationExempt: Boolean = false
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
        refreshBatteryState()
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

    fun refreshBatteryState() {
        val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        _uiState.value = _uiState.value.copy(
            batteryOptimizationExempt =
                powerManager.isIgnoringBatteryOptimizations(appContext.packageName)
        )
    }

    /** Exempts OwnApps from battery optimization via the system dialog; falls back to the battery
     *  settings page if the request intent is ever unroutable. The intents are launched from the
     *  application context, so they must carry NEW_TASK or startActivity throws straight away and
     *  the tap does nothing. */
    fun requestBatteryOptimizationExemption() {
        val request = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${appContext.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            appContext.startActivity(request)
        } catch (_: Exception) {
            runCatching {
                appContext.startActivity(
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
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