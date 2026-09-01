package com.ownapps.app.shortcuts

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Icon
import android.os.Bundle
import androidx.core.content.ContextCompat
import com.ownapps.app.MainActivity
import com.ownapps.app.R
import com.ownapps.app.ui.navigation.EXTRA_OPEN_ALL_APPS

/**
 * A launcher **shortcut**, not an AppWidget. Same approach as Tooler's lock-screen shortcut: a
 * `Theme.NoDisplay` activity with an `ACTION_CREATE_SHORTCUT` intent-filter, which launchers
 * surface in the same "add to home screen" picker as widgets — producing a plain icon (no widget
 * host, no RemoteViews). Tapping the icon launches OwnApps straight onto the All Apps list.
 *
 * `onCreate()` runs through two different paths depending on `intent.action`:
 * - `ACTION_CREATE_SHORTCUT` (the launcher's widgets/shortcuts picker): builds and returns a
 *   [ShortcutInfo] result describing the pinned icon.
 * - Anything else (tapping the pinned icon — its stored intent is `ACTION_VIEW` against
 *   [MainActivity] with [EXTRA_OPEN_ALL_APPS] set): launches MainActivity on the All Apps list.
 *
 * Either way this activity never becomes visible: `Theme.NoDisplay` plus `finish()` in both
 * branches.
 */
class AllAppsShortcutActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.action == Intent.ACTION_CREATE_SHORTCUT) {
            setResult(RESULT_OK, buildShortcutResultIntent())
        } else {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    action = Intent.ACTION_VIEW
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra(EXTRA_OPEN_ALL_APPS, true)
                }
            )
        }
        finish()
    }

    private fun buildShortcutResultIntent(): Intent {
        val shortcutManager = ContextCompat.getSystemService(this, ShortcutManager::class.java)
        if (shortcutManager != null) {
            val shortcutInfo = ShortcutInfo.Builder(this, SHORTCUT_ID)
                .setShortLabel(getString(R.string.all_apps_shortcut_label))
                .setIcon(buildAdaptiveIcon())
                .setIntent(
                    Intent(Intent.ACTION_VIEW, null, this, MainActivity::class.java)
                        .putExtra(EXTRA_OPEN_ALL_APPS, true)
                )
                .build()
            return shortcutManager.createShortcutResultIntent(shortcutInfo)
        }
        // ShortcutManager has existed since API 25 — this app's minSdk 26 means it's never
        // actually null. Kept only so a null result can't silently produce a broken shortcut.
        @Suppress("DEPRECATION")
        return Intent()
            .putExtra(Intent.EXTRA_SHORTCUT_INTENT, shortCutIntent())
            .putExtra(Intent.EXTRA_SHORTCUT_NAME, getString(R.string.all_apps_shortcut_label))
            .putExtra(
                Intent.EXTRA_SHORTCUT_ICON_RESOURCE,
                Intent.ShortcutIconResource.fromContext(this, R.mipmap.ic_launcher)
            )
    }

    private fun shortCutIntent(): Intent =
        Intent(Intent.ACTION_VIEW, null, this, MainActivity::class.java)
            .putExtra(EXTRA_OPEN_ALL_APPS, true)

    /**
     * `ShortcutInfo.Builder.setIcon()` does not reliably recognize a `mipmap-anydpi-v26`
     * `<adaptive-icon>` reference (visible doubling/badging on-device), so rasterize the same
     * background+foreground pairing into a plain Bitmap at the 108dp adaptive-icon canvas size and
     * hand it back via `Icon.createWithAdaptiveBitmap()`.
     */
    private fun buildAdaptiveIcon(): Icon {
        val sizePx = (108 * resources.displayMetrics.density).toInt()
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(ContextCompat.getColor(this, R.color.ic_launcher_background))
        ContextCompat.getDrawable(this, R.drawable.ic_launcher_foreground)?.apply {
            setBounds(0, 0, sizePx, sizePx)
            draw(canvas)
        }
        return Icon.createWithAdaptiveBitmap(bitmap)
    }

    private companion object {
        const val SHORTCUT_ID = "all_apps_shortcut"
    }
}
