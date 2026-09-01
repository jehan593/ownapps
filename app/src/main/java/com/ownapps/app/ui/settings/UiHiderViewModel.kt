package com.ownapps.app.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ownapps.app.data.repository.SettingsRepository
import com.ownapps.app.uihider.DEFAULT_UIHIDER_SCRIPT_IDS
import com.ownapps.app.uihider.NodePickerService
import com.ownapps.app.uihider.UiHiderConfig
import com.ownapps.app.uihider.UiHiderScript
import com.ownapps.app.uihider.UiHiderService
import com.ownapps.app.uihider.allScripts
import com.ownapps.app.uihider.script.Parser
import com.ownapps.app.uihider.script.ScriptError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

data class UiHiderScriptItem(
    val id: String,
    val packageName: String,
    val label: String,
    val source: String,
    val isEnabled: Boolean,
    val isPreset: Boolean
)

data class UiHiderListState(
    val isActive: Boolean = false,
    val serviceEnabled: Boolean = false,
    val scripts: List<UiHiderScriptItem> = emptyList()
)

class UiHiderViewModel(
    private val settingsRepository: SettingsRepository,
    private val appContext: android.content.Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(UiHiderListState())
    val uiState: StateFlow<UiHiderListState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.uiHiderConfigFlow.map { config -> toListState(config, _uiState.value.serviceEnabled) }
                .collect { _uiState.value = it }
        }
        refreshServiceState()
    }

    private fun toListState(config: UiHiderConfig, serviceEnabled: Boolean): UiHiderListState {
        val items = config.allScripts().map { script ->
            UiHiderScriptItem(
                id = script.id,
                packageName = script.packageName,
                label = script.label,
                source = script.source,
                isEnabled = script.isEnabled,
                isPreset = script.id in DEFAULT_UIHIDER_SCRIPT_IDS
            )
        }
        return _uiState.value.copy(isActive = config.isActive, scripts = items, serviceEnabled = serviceEnabled)
    }

    fun refreshServiceState() {
        val am = appContext.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE) as
            android.view.accessibility.AccessibilityManager
        val enabled = am.getEnabledAccessibilityServiceList(
            android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_GENERIC
        ).any { it.resolveInfo.serviceInfo?.name == UiHiderService::class.java.name }
        _uiState.value = _uiState.value.copy(serviceEnabled = enabled)
    }

    fun setActive(active: Boolean) {
        viewModelScope.launch { settingsRepository.setUiHiderConfig(currentConfig().copy(isActive = active)) }
    }

    fun togglePreset(id: String, enabled: Boolean) {
        viewModelScope.launch {
            val cfg = currentConfig()
            val ids = cfg.enabledPresetIds.toMutableSet()
            if (enabled) ids.add(id) else ids.remove(id)
            settingsRepository.setUiHiderConfig(cfg.copy(enabledPresetIds = ids.toList()))
        }
    }

    /** Toggle a user-created (custom) script's on/off state. Presets should use [togglePreset]. */
    fun toggleCustomScript(id: String, enabled: Boolean) {
        viewModelScope.launch {
            val cfg = currentConfig()
            val scripts = cfg.scripts.map {
                if (it.id == id) it.copy(isEnabled = enabled) else it
            }
            settingsRepository.setUiHiderConfig(cfg.copy(scripts = scripts))
        }
    }

    /** Persist a custom script, creating it (empty id) or updating an existing [existingId]. */
    fun upsertCustomScript(
        existingId: String?,
        packageName: String,
        label: String,
        source: String
    ) {
        viewModelScope.launch {
            val cfg = currentConfig()
            val scripts = cfg.scripts.toMutableList()
            if (existingId == null) {
                scripts.add(
                    UiHiderScript(
                        id = "custom_${System.currentTimeMillis()}",
                        packageName = packageName.trim(),
                        label = label.trim().ifEmpty { packageName.trim() },
                        source = source.trim(),
                        isEnabled = true
                    )
                )
            } else {
                val idx = scripts.indexOfFirst { it.id == existingId }
                if (idx >= 0) {
                    scripts[idx] = scripts[idx].copy(
                        packageName = packageName.trim(),
                        label = label.trim().ifEmpty { packageName.trim() },
                        source = source.trim()
                    )
                }
            }
            settingsRepository.setUiHiderConfig(cfg.copy(scripts = scripts))
        }
    }

    fun deleteCustomScript(id: String) {
        viewModelScope.launch {
            val cfg = currentConfig()
            settingsRepository.setUiHiderConfig(
                cfg.copy(scripts = cfg.scripts.filterNot { it.id == id })
            )
        }
    }

    fun launchNodePicker() {
        if (!_uiState.value.serviceEnabled) {
            appContext.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            )
            return
        }
        NodePickerService.start(appContext)
    }

    private suspend fun currentConfig(): UiHiderConfig =
        settingsRepository.uiHiderConfigFlow.first()

    /** Test-compile [source]; returns an error message or null when it parses cleanly. */
    fun validateSource(source: String): String? = try {
        Parser.parse(source)
        null
    } catch (e: ScriptError) {
        e.message ?: "Invalid script"
    }
}
