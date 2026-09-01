package com.ownapps.app.uihider

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.ownapps.app.OwnAppsApplication
import com.ownapps.app.data.repository.SettingsRepository

/**
 * Accessibility host for the UIHider feature. Wires the background [UiHider] blocker (driven by
 * the persisted [com.ownapps.app.uihider.UiHiderConfig] flow from [SettingsRepository]) and the
 * interactive [NodePicker]. Both are bound into this single service so they can share
 * `rootInActiveWindow`; the picker suspends UIHider scripts while active so they don't fight each
 * other.
 *
 * The Node Picker's entry point is the separate foreground [NodePickerService]; its notification
 * broadcasts [NodePicker.ACTION_OPEN] here, which shows the picker overlay over the current app.
 *
 * Enable/disable is a pure DataStore write (see SettingsRepository.setUiHiderEnabled / the UI
 * toggle); this service only reacts to config changes and accessibility events.
 */
class UiHiderService : AccessibilityService() {

    private val uiHider = UiHider()
    private val nodePicker = NodePicker(this)

    private val settings: SettingsRepository
        get() = (application as OwnAppsApplication).container.settingsRepository

    override fun onServiceConnected() {
        super.onServiceConnected()
        uiHider.setupBlocker(this, settings.uiHiderConfigFlow)
        uiHider.setupReceivers()
        nodePicker.setupReceivers()
        Log.i("UiHider", "Connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (nodePicker.isActive) return
        uiHider.doUiHiderCheck(event)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        nodePicker.removeReceivers()
        uiHider.removeReceivers()
        super.onDestroy()
    }
}
