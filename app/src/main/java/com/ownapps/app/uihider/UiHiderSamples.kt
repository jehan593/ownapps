package com.ownapps.app.uihider

/**
 * Built-in starter scripts. Rather than being locked presets, each built-in is seeded once into the
 * user's own [UiHiderConfig.scripts] list (via SettingsRepository.seedBuiltinUiHiderScripts), after which
 * it behaves exactly like a user-created script — editable, toggleable and deletable. View-id
 * selectors may need adjusting as target apps update their layouts.
 */
object BuiltinScripts {

    val WhatsApp: UiHiderScript = UiHiderScript(
        id = "whatsapp_hide_distractions",
        packageName = "com.whatsapp",
        label = "WhatsApp: hide Meta AI button and status list",
        isEnabled = true,
        source = """
            if app != "com.whatsapp" {
                return
            }

            fab = find(id="com.whatsapp:id/extended_mini_fab")
            if fab != null {
                hide(fab)
            }

            status_list = find(id="com.whatsapp:id/status_list")
            if status_list != null {
                hide(status_list)
            }
        """.trimIndent()
    )
}

/** Built-in starter scripts, seeded into [UiHiderConfig.scripts] on first run. */
val BUILTIN_UIHIDER_SCRIPTS: List<UiHiderScript> = listOf(BuiltinScripts.WhatsApp)

/** Ids that shipped as non-editable presets in older versions; kept to migrate legacy configs. */
val LEGACY_UIHIDER_PRESET_IDS: Set<String> = BUILTIN_UIHIDER_SCRIPTS.mapTo(HashSet()) { it.id }

/**
 * The scripts available to run. Until the built-ins have been persisted as user scripts, legacy
 * preset state in [enabledPresetIds] (and any preset copies older versions stored in [scripts]) is
 * folded in so the feature keeps working across the migration; afterwards this is just [scripts].
 * The fold never resurrects a script the user has deleted.
 */
fun UiHiderConfig.allScripts(): List<UiHiderScript> {
    val own = scripts.associateBy { it.id }
    if (enabledPresetIds.isEmpty() && own.keys.none { it in LEGACY_UIHIDER_PRESET_IDS }) {
        return scripts
    }
    val folded = BUILTIN_UIHIDER_SCRIPTS.map { builtIn ->
        own[builtIn.id] ?: builtIn.copy(isEnabled = builtIn.id in enabledPresetIds)
    }
    return folded + scripts.filterNot { it.id in LEGACY_UIHIDER_PRESET_IDS }
}