# CLAUDE.md

Guidance for working on the OwnApps Android app. Read this before editing non-trivial code.

## What this is

A fully **offline** Android **app manager** with **manual** app disabling. Nord color palette,
Martian Mono Nerd Font, Jetpack Compose UI, plus a home-screen launcher shortcut. It requests **no
`INTERNET`** permission and **no `PACKAGE_USAGE_STATS`** — there is no screen-time tracking,
dashboard, history, or widgets; the app is purely a deliberate way to disable/enable apps you
don't want to reach for.

## Key architecture

Disabling/enabling is driven through a **Shizuku-family privileged backend** (`rikka.shizuku` API —
works with official Shizuku, Sui, and compatible forks). No ADB `dpm`/device-owner setup is
required anymore, and there is no API key.

The enforcement layer lives in `app/src/main/java/com/ownapps/app/enforcement/`:

- **`PackageController`** (interface) + **`ShizukuPackageController`** (impl): `disable()` /
  `enable()` a package via reflection on the hidden `IPackageManager.setApplicationEnabledSetting`
  (reached through `ShizukuBinderWrapper` + `SystemServiceHelper`), which is `pm disable-user`
  semantics. Also exposes `isServiceReady()`, `isPermissionGranted()`, `isAvailable()`,
  `requestPermission()`. Only the unified Shizuku layer is used — never fork-specific APIs.
  A `ShizukuProvider` in the manifest auto-initializes Sui from v12.1.0+.
- **`PackageBlocker`**: shared disable/enable actions used by the All Apps list.
  Disables/enables through the controller *and* writes the matching local suspend-state row.
  Also `disableAll`/`enableAll`. `canDisable()` gates whether disabling is possible; enabling is
  always permitted so a stale "disabled" flag can be cleared.
- **`FirewallController`** (interface) + **`ShizukuFirewallController`** (impl): a separate,
  independent per-app **network firewall** using Android's built-in **Chain 3** connectivity
  control (the same mechanism as ShizuWall) instead of a VPN. Runs `cmd connectivity
  set-chain3-enabled true|false` (master) and `cmd connectivity set-package-networking-enabled
  false|true <pkg>` (per-app rules) through `sh -c` in a process spawned *inside* the Shizuku
  server, reached by reflecting on the private `rikka.shizuku.Shizuku.newProcess`. Requires no
  `INTERNET` permission of its own and is Android 11+ only. Also `isServiceReady()`/
  `isPermissionGranted()`/`isAvailable()`/`requestPermission()`. There is deliberately **no live
  `get-chain3-enabled` probe** in the UI: reading Chain 3 back can fail/stale right after boot,
  and nothing but the master switch (or a reboot) ever changes the state, so the switch position
  is derived from the persisted `FIREWALL_ENABLED` flag instead (see below).
- **Boot recovery** (`enforcement/FirewallBootReceiver.kt` + `FirewallBootRestorer.kt`): Android
  clears Chain 3 and all per-app rules on reboot, so a manifest-registered receiver on
  `ACTION_BOOT_COMPLETED` (post-unlock only — the rules DB is credential-protected until then)
  re-enables the firewall and re-applies the persisted blocked list. It no-ops unless a
  "last enforced" flag in `SettingsRepository` (`FIREWALL_ENABLED`, written by the master toggle)
  is set; if the Shizuku binder isn't up yet it waits for it via a binder-received listener and
  retries once. The master toggle in the UI is the always-available fallback.
- **`FirewallBlocker`**: shared block/unblock actions for the Firewall screen. Unlike
  `PackageBlocker`, the local rule row is only mirrored after the backend command succeeds — a
  "blocked" flag that wasn't enforced would be misleading.
- **Firewall screen** (`ui/firewall/FirewallScreen.kt` + `FirewallViewModel.kt`, row
  `ui/components/FirewallRow.kt`): reached from the **shield icon** in the All Apps top bar (next
  to Settings). The master switch keeps original semantics (ON = blocking active, OFF = idle, and
  turning ON reapplies the persisted block list — the platform clears rules on reboot); the
  per-app switches read as the connection state (ON = allowed, OFF = blocked) and are gated on the
  master enforcing. The app list is the same **launcher-only set as the All Apps list** (no
  `QUERY_ALL_PACKAGES`) with search + its own independent pin/reorder set (`firewall_pinned_app`).
  Rule state lives in `firewall_rule`. The master switch shows the persisted last-enforced state
  from the first frame and the ViewModel waits briefly for a cold-starting Shizuku binder before
  showing any "backend unavailable" banner (`checkedBackend`), so the screen never flashes stale
  state; the list also rides `isLoading` (first-load spinner) and scrolls back to the top on
  resume.

AppComponent wiring is in `di/AppContainer.kt` (manual DI, no Hilt).

## Newer features (mind these when touching related code)

- **Pinned apps** (`data/db/entity/PinnedAppEntity.kt`, `data/repository/PinnedAppsRepository.kt`,
  `data/db/dao/PinnedAppDao.kt`): apps the user pins to the top of the All Apps list. The list also
  offers **Disable All Pinned** / **Enable All Pinned** actions.
- **Home-screen launcher shortcut** (`shortcuts/AllAppsShortcutActivity.kt`): a `Theme.NoDisplay`
  activity with an `ACTION_CREATE_SHORTCUT` intent-filter (not an AppWidget). Tapping the pinned
  icon launches OwnApps directly onto the All Apps list via `EXTRA_OPEN_ALL_APPS`.
- **UI Hider** (`uihider/`, opt-in accessibility feature): a scriptable overlay that hides
  distracting UI elements in chosen apps. `UiHiderService` (the accessibility service) reads the
  active window and runs per-package scripts through a tiny interpreted language
  (`uihider/script/` — Lexer/Parser/Interpreter/Builtins) to compute geometry and draw
  overlays/press back/home. Sandboxed with a `Budget` and crash-shielded so a bad script can never
  kill the accessibility service. Config is a serialized `UiHiderConfig`/`UiHiderScript` JSON
  string in DataStore (kept for R8 in `proguard-rules.pro`); preset scripts ship in code
  (`UiHiderSamples.kt`) and only their enabled-ids are persisted. The interactive **Node Picker**
  (`uihider/NodePicker.kt` + `NodePickerService.kt`) lets users build rules by tapping live screen
  nodes. Entry point is Settings → **Manage UI Hider scripts** (`UiHiderScreen`).

## Data / storage

- **Room** `AppDatabase` (`data/db/AppDatabase.kt`, version 3, migrations `MIGRATION_1_2` and
  `MIGRATION_2_3` in `di/AppContainer.kt`): entities are `AppSuspendStateEntity`,
  `PinnedAppEntity`, `FirewallRuleEntity` (per-app block flags) and `FirewallPinnedAppEntity`
  (firewall's own pin set). Add a migration + bump the version when you add/change
  columns/tables. Export schema is on — new schema JSON lands under `app/schemas/` and
  must be committed.
- **DataStore Preferences** `SettingsRepository` (`data/store` backed): UI Hider enabled flag +
  serialized `UiHiderConfig`. No encryption.

## UI / navigation

- Jetpack Compose + Material 3. Navigation via `ui/navigation/OwnAppsNavHost.kt` (Compose
  Navigation). The **All Apps list is the start destination**; Settings is reached from a gear
  icon in its top bar (Firewall sits beside it, a shield icon). `EXTRA_OPEN_ALL_APPS` drives the
  launcher-shortcut deep-link.
- Screens under `ui/` (applist, settings, uihider, firewall). `AppRowWithBlock` and `FirewallRow`
  are the shared app rows.
- Theme in `ui/theme/` (Nord palette). Martian Mono Nerd Font.

## Build / tooling

- Gradle wrapper (`./gradlew …`) is committed and working — no regeneration needed. JDK 17 +
  Android SDK required; on a headless box point `local.properties` `sdk.dir` at the SDK.
- Distributed builds ship **release** (`assembleRelease`), R8-minified, signed with the committed
  `debug.keystore` (see `app/build.gradle.kts`). Use `assembleDebug` for local iteration.
- Dependencies in `gradle/libs.versions.toml`. New: `rikka.shizuku` `api` + `provider`
  (v13.1.5), and `androidx.compose.material:material-icons-extended` (for pin icons).
- Verify with `./gradlew assembleDebug` and (for release-sensitive changes) `assembleRelease`
  before committing.

## Testing on-device

`adb` + the Android SDK are staged under `/tmp/opencode/android-sdk` in a dev box; the Gradle
build and `adb install -r app/build/outputs/apk/debug/app-debug.apk` are the manual loop.

## Conventions

- No code comments unless they explain *why* (class/function docs are welcome; they're consistent
  with the codebase style).
- No `INTERNET` permission. No `QUERY_ALL_PACKAGES` anywhere — scope `<queries>` tightly; the
  Firewall uses the same launcher-only list as the main screen.
- Commit messages mirror the repo style: a short imperative summary line followed by a paragraph
  of context. Often `Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>`.