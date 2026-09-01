package com.ownapps.app.uihider

/**
 * A single UIHider script, bound to one app package. [source] is the raw script text; it is
 * compiled to an AST at runtime. Preset scripts ship in code and are never persisted — only their
 * ids (in [UiHiderConfig.enabledPresetIds]) are stored, so preset source always comes from the
 * current app version.
 */
data class UiHiderScript(
    val id: String = "",
    val packageName: String = "",
    val label: String = "",
    val source: String = "",
    val isEnabled: Boolean = true
)

/**
 * Top-level configuration for the UIHider feature, stored as a serialized JSON string in DataStore.
 * Scripts only run while [isActive] is true and the script's [UiHiderScript.isEnabled] is set.
 *
 * [scripts] holds only user-created scripts; [enabledPresetIds] lists the enabled preset ids.
 */
data class UiHiderConfig(
    val isActive: Boolean = false,
    val scripts: List<UiHiderScript> = emptyList(),
    val enabledPresetIds: List<String> = emptyList()
)
