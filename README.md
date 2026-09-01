# OwnApps

A fully offline Android app manager: a searchable, pinnable **All Apps** list where you can
manually **disable and enable** any installed app through a
[Shizuku](https://shizuku.rikka.app/)-family privileged backend (works with official Shizuku, Sui,
and compatible forks). Nord color palette, Martian Mono Nerd Font, Jetpack Compose UI.

OwnApps itself requests **no `INTERNET` permission** — all enforcement and storage happen entirely
on-device. There is **no screen-time tracking** (no usage monitoring, no dashboard, no history,
no widgets); the app is purely a deliberate, manual way to hide apps you don't want to reach for.

## Features

- **All Apps list** (the home screen): every installed launchable app in one searchable list.
- **Pin apps** to the top of the list for quick access, plus **Disable all** / **Enable all**
  buttons that act on everything pinned at once.
- **Manual disabling is deliberate friction-free**: flick the switch off to immediately disable
  an app via Shizuku (the same as `pm disable-user`) — it's removed from the launcher and can't be
  launched until re-enabled. A disable persists until you explicitly enable it again.
- **UI Hider** (optional, opt-in): a scriptable overlay that draws rectangles over distracting UI
  elements inside chosen apps (e.g. WhatsApp's FAB and addon buttons) so they stay out of the way.
  It runs through an accessibility service, comes with a starter WhatsApp preset, and lets you
  build your own per-app hiding rules with an interactive **Node Picker** — or write them directly
  in its tiny built-in scripting language (find nodes by view-id or text, compute geometry, hide /
  press back / press home). Every script is sandboxed with an execution budget and run in a
  try/catch so a faulty one can never crash the accessibility service.

## One-time setup (required before disabling works)

OwnApps cannot automate any of this itself — it's a device-level configuration step you do once,
outside the app:

1. **Install and start a Shizuku-family backend.** The most common is
   [Shizuku](https://shizuku.rikka.app/) (from F-Droid, a GitHub release, or the Play version),
   which can run over **ADB** (no root needed) or **root**. Alternatives like
   [Sui](https://github.com/ZQZCC/Sui) (a Magisk/KernelSU module for rooted devices) work too —
   OwnApps uses only the unified Shizuku API, so any backend that speaks that protocol works
   without app changes.
2. **Start Shizuku** (e.g. via wireless debugging from the Shizuku app). No ADB `dpm` command and
   **no device-owner setup** is required — Shizuku needs neither a factory reset nor removing
   your Google account.
3. **Open OwnApps** → **Settings** (gear icon in the All Apps top bar). Under the Shizuku banner,
   tap **Grant Shizuku permission** (this is needed once).
4. Back on the All Apps list, flick any app's switch off to disable it.

If Shizuku isn't running or permission isn't granted, disabling simply won't take effect — a
banner in Settings flags this.

### Optional: UI Hider accessibility service

The UI Hider is entirely separate from Shizuku and needs no root. To use it, open **Settings** →
**UI Hider** and enable the **OwnApps UI Hider** accessibility service when prompted by Android
(this is the standard accessibility toggle — OwnApps can't enable it itself). Disabling/enabling
apps works fine without it.

## Building

This has actually been built and verified: both `./gradlew assembleDebug` and
`./gradlew assembleRelease` succeed. The Gradle wrapper (`gradlew`, `gradlew.bat`,
`gradle/wrapper/gradle-wrapper.jar`) is included and working, so no regeneration step is needed.

Any distributed build ships **release** (`app/build/outputs/apk/release/app-release.apk`) —
R8-minified and resource-shrunk, signed with the same committed `debug.keystore` as the debug
build (see `app/build.gradle.kts`) so in-place updates via Obtainium keep working. Use
`assembleDebug` (`app/build/outputs/apk/debug/app-debug.apk`) for local iteration only.

1. Open this `ownapps/` folder in Android Studio (it will pick up the existing wrapper and
   sync automatically), **or** from a terminal with JDK 17 and the Android SDK installed:
   ```sh
   # local.properties needs sdk.dir pointed at your Android SDK if Android Studio hasn't
   # already created one for you, e.g.:
   echo "sdk.dir=/path/to/Android/sdk" > local.properties
   ./gradlew assembleRelease
   ```
2. Install/run on a device or emulator running Android 8.0 (API 26) or newer:
   `adb install app/build/outputs/apk/release/app-release.apk`. A Shizuku-family backend is only
   needed to actually disable apps; the list itself works on its own.

### Fonts

Martian Mono Nerd Font (`.ttf`, Regular + Medium — Bold isn't used anywhere and was dropped) is
bundled under `app/src/main/res/font/`, Latin-subsetted from the full
[Nerd Fonts](https://github.com/ryanoasis/nerd-fonts) release down to just the glyphs the app
actually renders (license in `app/licenses/MARTIAN_MONO_LICENSE.txt`, covering only the
font — it is not the license for OwnApps's own source code).

## Permissions

| Permission | Why |
|---|---|
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` | The Node Picker's "press to open" notification service. |
| `POST_NOTIFICATIONS` | For that Node Picker notification (Android 13+). |
| Shizuku-related (via `rikka.shizuku.ShizukuProvider`) | Supplies the privileged binder used to disable/enable apps, at runtime through the Shizuku-family permission dialog — no install-time permission is requested for it. |
| Accessibility service (`BIND_ACCESSIBILITY_SERVICE`) | Backs the optional **UI Hider** overlays only — granted opt-in by the user through Android's accessibility settings, never requested at install time. |

Deliberately not requested: `INTERNET`, `PACKAGE_USAGE_STATS`, `QUERY_ALL_PACKAGES`, any
exact-alarm permission, `WAKE_LOCK`.