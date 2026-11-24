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
        /** The user's firewall intent. Chain 3 is reset by Android on reboot, so this is what the
         *  restore coordinator consults to decide whether to re-enable it. Never cleared by a
         *  failed restore — the user's choice must survive until Shizuku is available. */
        val FIREWALL_ENABLED = booleanPreferencesKey("firewall_enabled")
        /** Saved ON, but not currently enforced because Shizuku wasn't ready (or a rule failed).
         *  Drives the visible "waiting for Shizuku" state and retries. */
        val FIREWALL_RESTORE_PENDING = booleanPreferencesKey("firewall_restore_pending")
    }

    private val gson = Gson()

    /** In-memory mirror of [FIREWALL_ENABLED] so the Firewall screen can render the master switch
     *  at its real position from the very first frame instead of waiting on an async DataStore
     *  read. Seeded via [preloadFirewallState] and kept in step by every write. */
    private val cachedFirewallEnabled = AtomicBoolean(false)
    private val cachedFirewallRestorePending = AtomicBoolean(false)

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

    /** The user's firewall intent. Android silently resets Chain 3 on reboot, so this is the saved
     *  state the restore coordinator tries to re-enforce; a failed restore does not erase it. */
    suspend fun isFirewallEnabled(): Boolean = dataStore.data.first()[FIREWALL_ENABLED] ?: false

    val firewallRestorePendingFlow: Flow<Boolean> =
        dataStore.data.map { it[FIREWALL_RESTORE_PENDING] ?: false }

    /** True while the saved firewall state hasn't been re-applied after boot / a backend failure. */
    suspend fun isFirewallRestorePending(): Boolean =
        dataStore.data.first()[FIREWALL_RESTORE_PENDING] ?: false

    /** Seeds both cached firewall flags from disk. Called by the Firewall ViewModel up front so a
     *  fresh screen renders the correct switch and pending state on its very first frame. */
    suspend fun preloadFirewallState() {
        val prefs = dataStore.data.first()
        cachedFirewallEnabled.set(prefs[FIREWALL_ENABLED] ?: false)
        cachedFirewallRestorePending.set(prefs[FIREWALL_RESTORE_PENDING] ?: false)
    }

    /** Synchronous reads of the last known persisted state, for the first rendered frame. */
    fun firewallEnabledCached(): Boolean = cachedFirewallEnabled.get()
    fun firewallRestorePendingCached(): Boolean = cachedFirewallRestorePending.get()

    suspend fun setFirewallEnabled(enabled: Boolean) {
        cachedFirewallEnabled.set(enabled)
        dataStore.edit { it[FIREWALL_ENABLED] = enabled }
    }

    suspend fun setFirewallRestorePending(pending: Boolean) {
        cachedFirewallRestorePending.set(pending)
        dataStore.edit { it[FIREWALL_RESTORE_PENDING] = pending }
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