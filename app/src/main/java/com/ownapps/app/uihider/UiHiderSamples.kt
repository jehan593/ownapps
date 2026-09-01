package com.ownapps.app.uihider

/**
 * Starter scripts shipped with UIHider. The [WhatsApp] preset is the flagship, enabled by default;
 * the rest are disabled until the user enables them. View-id selectors may need adjusting as target
 * apps update their layouts.
 */
object Presets {

    val WhatsApp: UiHiderScript = UiHiderScript(
        id = "whatsapp_hide_distractions",
        packageName = "com.whatsapp",
        label = "WhatsApp: hide FAB, buttons and second addon button",
        isEnabled = true,
        source = """
            if app != "com.whatsapp" {
                return
            }

            fab = find(id="com.whatsapp:id/fab_second")
            if fab != null {
                hide(fab)
            }

            buttons = find(id="com.whatsapp:id/buttons_layout")
            if buttons != null {
                hide(buttons)
            }

            addons = findAll(id="com.whatsapp:id/addon_button")
            if len(addons) >= 2 {
                hide(addons[1])
            }
        """.trimIndent()
    )
}

val DEFAULT_UIHIDER_SCRIPTS: List<UiHiderScript> = listOf(
    Presets.WhatsApp,
)

val DEFAULT_UIHIDER_SCRIPT_IDS: Set<String> = DEFAULT_UIHIDER_SCRIPTS.mapTo(HashSet()) { it.id }

fun isPresetUiHiderScript(id: String): Boolean = id in DEFAULT_UIHIDER_SCRIPT_IDS

/**
 * Older versions persisted preset scripts inside [UiHiderConfig.scripts]. Strip them out, keeping
 * only their enabled state, so presets always take their source from code.
 */
fun UiHiderConfig.normalized(): UiHiderConfig {
    val legacyPresets = scripts.filter { it.id in DEFAULT_UIHIDER_SCRIPT_IDS }
    if (legacyPresets.isEmpty()) return this
    return copy(
        scripts = scripts.filterNot { it.id in DEFAULT_UIHIDER_SCRIPT_IDS },
        enabledPresetIds = (enabledPresetIds + legacyPresets.filter { it.isEnabled }.map { it.id }).distinct()
    )
}

/** Preset scripts (with the user's enabled state) followed by the user's own scripts. */
fun UiHiderConfig.allScripts(): List<UiHiderScript> {
    val config = normalized()
    return DEFAULT_UIHIDER_SCRIPTS.map { it.copy(isEnabled = it.id in config.enabledPresetIds) } + config.scripts
}
