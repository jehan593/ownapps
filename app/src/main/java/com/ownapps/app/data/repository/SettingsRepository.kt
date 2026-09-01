package com.ownapps.app.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import com.ownapps.app.uihider.UiHiderConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Plain DataStore Preferences — no encryption.
 *
 * There's deliberately no "enforcement enabled" flag: disabling is a direct manual action per app
 * (see the All Apps list switch), not a background policy, so there's nothing to toggle.
 */
class SettingsRepository(private val dataStore: DataStore<Preferences>) {

    companion object {
        val UI_HIDER_ENABLED = booleanPreferencesKey("ui_hider_enabled")
        val UI_HIDER_CONFIG = stringPreferencesKey("ui_hider_config")
        /** Last known "is the firewall actually enforced" state — Chain 3 is reset by Android on
         *  reboot, so this is what the boot receiver consults to decide whether to re-enable it. */
        val FIREWALL_ENABLED = booleanPreferencesKey("firewall_enabled")
    }

    private val gson = Gson()

    val uiHiderEnabledFlow: Flow<Boolean> =
        dataStore.data.map { it[UI_HIDER_ENABLED] ?: false }

    /** Full [UiHiderConfig] combining the master toggle ([UI_HIDER_ENABLED]) with the persisted
     *  scripts. Mirrors the boolean so either write path keeps the other in step. */
    val uiHiderConfigFlow: Flow<UiHiderConfig> =
        dataStore.data.map { prefs ->
            UiHiderConfig(
                isActive = prefs[UI_HIDER_ENABLED] ?: false,
                scripts = parseConfig(prefs[UI_HIDER_CONFIG]).scripts,
                enabledPresetIds = parseConfig(prefs[UI_HIDER_CONFIG]).enabledPresetIds
            )
        }

    suspend fun setUiHiderEnabled(enabled: Boolean) {
        dataStore.edit { it[UI_HIDER_ENABLED] = enabled }
    }

    /** Whether the firewall was left enforced (Chain 3 on) the last time it was toggled. Overrides
     *  the live platform state after a reboot, where Android has silently reset Chain 3 to off. */
    suspend fun isFirewallEnabled(): Boolean = dataStore.data.first()[FIREWALL_ENABLED] ?: false

    suspend fun setFirewallEnabled(enabled: Boolean) {
        dataStore.edit { it[FIREWALL_ENABLED] = enabled }
    }

    /** Persist the full UIHider config, keeping [UI_HIDER_ENABLED] in step with [UiHiderConfig.isActive]. */
    suspend fun setUiHiderConfig(config: UiHiderConfig) {
        val json = gson.toJson(config)
        dataStore.edit { prefs ->
            prefs[UI_HIDER_CONFIG] = json
            prefs[UI_HIDER_ENABLED] = config.isActive
        }
    }

    private fun parseConfig(json: String?): UiHiderConfig =
        try {
            json?.takeIf { it.isNotEmpty() }?.let { gson.fromJson(it, UiHiderConfig::class.java) }
                ?: UiHiderConfig()
        } catch (_: Exception) {
            UiHiderConfig()
        }
}