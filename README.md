# OwnApps

A fully offline Android app manager. Browse your installed apps, pin favorites to the top, and
disable or enable any app with a switch. No internet, no screen-time tracking, no analytics.

> **FYI:** This project is fully vibe coded

## Features

- **All Apps list** — every installed app in one searchable list.
- **Pin apps** — keep favorites at the top; disable or enable them all at once.
- **Disable apps** — flick a switch to hide an app from your launcher until you enable it again.
- **Firewall** — block an app's internet access, one app at a time (needs Android 11+).
- **UI Hider** — an optional tool that hides distracting buttons and pop-ups in your apps
  (e.g. WhatsApp), using a simple on-screen element picker or a tiny script.

## One-time setup

OwnApps can't do this for you — you do it once on your device:

1. Install and start a Shizuku-family backend ([Shizuku](https://shizuku.rikka.app/) works over
   ADB or root; [Sui](https://github.com/ZQZCC/Sui) works on rooted devices).
2. Open OwnApps → **Settings** → **Grant Shizuku permission** (once).
3. Back in the app list, flick any switch to disable that app.

Browsing the list works without Shizuku; disabling and the firewall need it.

### Optional: UI Hider

Tap the **UI Hider** icon (a crossed-out eye) in the top bar of the app list, then turn on the
accessibility service when Android asks. OwnApps can't enable it for you.

## Build

Requires JDK 17 and the Android SDK.

```sh
./gradlew assembleRelease
```

The release APK is in `app/build/outputs/apk/release/app-release.apk`. Install it with
`adb install app/build/outputs/apk/release/app-release.apk`.