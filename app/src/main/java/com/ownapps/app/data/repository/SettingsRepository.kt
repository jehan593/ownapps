package com.ownapps.app.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import com.ownapps.app.uihider.UiHiderConfig
import com.ownapps.app.uihider.UiHiderScript
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.concurrent.atomic.AtomicBoolean

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
        /** Records that the built-in starter scripts have been seeded into the user's script list. */
        val UI_HIDER_SCRIPTS_SEEDED = booleanPreferencesKey("ui_hider_scripts_seeded")
        /** Last known "is the firewall actually enforced" state — Chain 3 is reset by Android on
         *  reboot, so this is what the boot receiver consults to decide whether to re-enable it. */
        val FIREWALL_ENABLED = booleanPreferencesKey("firewall_enabled")
    }

    private val gson = Gson()

    /** In-memory mirror of [FIREWALL_ENABLED] so the Firewall screen can render the master switch
     *  at its real position from the very first frame instead of waiting on an async DataStore
     *  read. Seeded via [preloadFirewallState] and kept in step by every write. */
    private val cachedFirewallEnabled = AtomicBoolean(false)

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

    /** Seeds [firewallEnabledCached] from disk. Called by the Firewall ViewModel up front so a
     *  fresh screen still renders the correct switch position on its very first frame. */
    suspend fun preloadFirewallState() {
        cachedFirewallEnabled.set(isFirewallEnabled())
    }

    /** Synchronous read of the last known persisted firewall state, for the first rendered frame. */
    fun firewallEnabledCached(): Boolean = cachedFirewallEnabled.get()

    suspend fun setFirewallEnabled(enabled: Boolean) {
        cachedFirewallEnabled.set(enabled)
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

    /**
     * One-time seed: turns the shipped built-in starter scripts into ordinary user scripts in the
     * persisted config. A fresh config starts with the built-ins enabled; an existing install
     * keeps its legacy preset enabled state. Legacy [UiHiderConfig.enabledPresetIds] entries are
     * cleared. The seed marker stops a built-in the user has deleted from ever being re-added.
     */
    suspend fun seedBuiltinUiHiderScripts(builtIns: List<UiHiderScript>) {
        val prefs = dataStore.data.first()
        if (prefs[UI_HIDER_SCRIPTS_SEEDED] == true) return
        val rawConfig = prefs[UI_HIDER_CONFIG]
        val config = parseConfig(rawConfig)
        val own = config.scripts.associateBy { it.id }
        val scriptsToAdd = builtIns.filterNot { it.id in own }.map { script ->
            script.copy(
                isEnabled = if (rawConfig != null) script.id in config.enabledPresetIds else script.isEnabled
            )
        }
        dataStore.edit {
            it[UI_HIDER_CONFIG] = gson.toJson(
                config.copy(
                    scripts = config.scripts + scriptsToAdd,
                    enabledPresetIds = emptyList()
                )
            )
            it[UI_HIDER_SCRIPTS_SEEDED] = true
            it[UI_HIDER_ENABLED] = config.isActive
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