package com.ownapps.app.uihider

/**
 * A single UIHider script, bound to one app package. [source] is the raw script text; it is
 * compiled to an AST at runtime. Built-in starters are seeded into the config as ordinary scripts,
 * so every persisted script is fully owned and editable by the user.
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
 * [scripts] holds the user's own scripts (shipped built-ins are seeded in here and behave like any
 * other script); [enabledPresetIds] only survives from older versions as legacy migration data.
 */
data class UiHiderConfig(
    val isActive: Boolean = false,
    val scripts: List<UiHiderScript> = emptyList(),
    val enabledPresetIds: List<String> = emptyList()
)
