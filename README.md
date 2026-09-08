# OwnApps

A fully offline Android app manager. See every app you've installed, pin your favorites to the
top, and disable or enable apps whenever you want. No internet access, no screen-time tracking,
no analytics — just a clean list and a switch.

> **FYI:** This project is fully vibe coded — it was built with heavy help from AI. Read it,
> trust it, but also don't be afraid to change it.

## Features

- **All Apps list** — every installed app in one searchable list.
- **Pin apps** — keep your favorites at the top, and disable or enable them all at once.
- **Disable apps** — flick a switch to hide an app from your launcher until you enable it again
  (done via [Shizuku](https://shizuku.rikka.app), the same as `pm disable-user`).
- **Firewall** — block an app's internet access, one app at a time (needs Android 11+).
- **UI Hider** — an optional tool that hides distracting buttons and pop-ups in your apps
  (e.g. WhatsApp), using a simple on-screen element picker or a tiny script.

## One-time setup

OwnApps can't do this part for you — you do it once on your device:

1. Install and start a Shizuku-family backend ([Shizuku](https://shizuku.rikka.app/) works over
   ADB or root; [Sui](https://github.com/ZQZCC/Sui) works on rooted devices).
2. Open OwnApps → **Settings** and tap **Grant Shizuku permission** (once).
3. Go back to the app list and flick any switch to disable that app.

The app list works without Shizuku — only disabling and the firewall need it.

### Optional: UI Hider

Open **Settings** → **UI Hider** and turn on the accessibility service when Android asks. This
is the standard accessibility toggle; OwnApps can't enable it for you.

## Build

Requires JDK 17 and the Android SDK.

```sh
./gradlew assembleRelease
```

The release APK is in `app/build/outputs/apk/release/app-release.apk`. Install it with
`adb install app/build/outputs/apk/release/app-release.apk`.