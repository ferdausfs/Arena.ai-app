# Arena AI — Android App (Native WebView + Foreground Service)

An installable Android app that wraps **https://arena.ai** using a **native
`android.webkit.WebView`** hosted by the app's own Activity, kept alive in the
background by a dedicated **Foreground Service**. This is a from-scratch
rebuild that replaces an earlier Trusted Web Activity (TWA) implementation —
see [§1](#1-why-twa-was-replaced) for why.

---

## Table of contents

1. [Why TWA was replaced](#1-why-twa-was-replaced)
2. [New architecture](#2-new-architecture)
3. [Honest limitations (please read)](#3-honest-limitations-please-read)
4. [Target / compile SDK](#4-target--compile-sdk)
5. [Permissions](#5-permissions)
6. [Project structure](#6-project-structure)
7. [Building it locally](#7-building-it-locally)
8. [The CI/CD pipeline](#8-the-cicd-pipeline)
9. [Installing the APK on your phone](#9-installing-the-apk-on-your-phone)
10. [Publishing to the Play Store](#10-publishing-to-the-play-store)
11. [Security notes](#11-security-notes)
12. [What still needs manual action from you](#12-what-still-needs-manual-action-from-you)

---

## 1. Why TWA was replaced

The previous version of this app was a **Trusted Web Activity (TWA)**, generated with
[Bubblewrap](https://github.com/GoogleChromeLabs/bubblewrap). A TWA opens the site in a
full-screen **Chrome Custom Tab** — i.e. it hands the page off to whatever browser is installed
on the device (normally Chrome) and lets that browser's process render and manage it. That has
two problems for this app's goals:

* **Engine dependency.** The app's reliability and behavior are tied to whichever
  browser/Chrome version happens to be installed and up to date on the user's device — not
  something this app controls.
* **No independent background survival.** A TWA's page lives inside the browser's own process,
  not this app's process. When the app is closed or backgrounded long enough for Android to
  reclaim memory, the browser can throw away that tab and the page **reloads** the next time the
  user returns — chat state, scroll position, and in some cases login state can be lost.

This rebuild removes the TWA entirely and instead uses a **plain `android.webkit.WebView`**
running inside **this app's own process**, kept alive by a **Foreground Service** — the same
general pattern used by "keep a session running in the background" apps like Termux.

## 2. New architecture

```
MainActivity (UI, visible only while the app is in the foreground)
        │  binds to / unbinds from, but never destroys
        ▼
ArenaSessionService (Foreground Service, owns the single WebView instance)
        │  shows an ongoing, low-priority notification: "Arena AI is running"
        ▼
android.webkit.WebView  →  https://arena.ai
```

* **`MainActivity`** hosts the visible UI: it starts `ArenaSessionService`, binds to it, and
  attaches the service's `WebView` into its own layout while it is on screen. When the Activity
  stops (minimized, screen off, task-switched away), it **detaches** the WebView from its view
  hierarchy and unbinds — but it does **not** destroy the WebView and does **not** stop the
  service. The WebView is constructed with an **application `Context`**, not an Activity
  `Context`, specifically so its lifetime is tied to the process, not to any one Activity
  instance.
* **`ArenaSessionService`** is a `Service` that calls `startForeground()`/`ServiceCompat.
  startForeground()` with an ongoing, low-priority notification ("Arena AI is running"). This
  tells Android "this process is doing something the user actively cares about right now", which
  makes the whole process a much lower-priority target for the low-memory killer than a plain
  backgrounded Activity would be. The service is only ever stopped by an **explicit user action**:
  the notification's "Stop" action, or "Close session" in the app's overflow menu — never merely
  because `MainActivity` was backgrounded. `onTaskRemoved()` is deliberately a no-op: swiping the
  app away from Recents does not stop the session.
* **WebView configuration**: JavaScript enabled, DOM storage enabled (`localStorage`/IndexedDB
  work), mixed content blocked, file/content URL access disabled, third-party cookies allowed
  (arena.ai's own login flow needs this). `ArenaWebViewClient` keeps navigation confined to the
  `arena.ai` domain (and subdomains); any link to a different domain, or a `mailto:`/`tel:`/etc.
  URI, is handed off to the user's default browser/app via an explicit `Intent`, instead of being
  loaded inside this app's WebView.
* **Loading / error UI**: `WebChromeClient.onProgressChanged()` drives a determinate progress
  bar; `WebViewClient.onReceivedError()`/`onReceivedSslError()` show a full-screen error state
  with a manual "Retry" button.
* **Battery optimization prompt**: on first launch (and on demand from the overflow menu), the
  app shows a dialog explaining why disabling battery optimization matters, then — only if the
  user agrees — opens the system's `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` screen. See
  `BatteryOptimizationHelper`.
* **Best-effort state recovery**: `SessionStateStore` (a small `SharedPreferences` wrapper)
  remembers the last URL the WebView was showing, so if the process is ever killed and later
  restarted, the WebView reloads at the same page instead of always going back to the arena.ai
  homepage. Cookies, `localStorage`, and IndexedDB survive process death on their own, because
  the WebView engine persists them to disk continuously — that part does **not** depend on this
  app doing anything special.

## 3. Honest limitations (please read)

**No app — native, WebView-based, TWA, or otherwise — can fully prevent Android from killing a
background process.** This is an OS-level guarantee, not a bug: Android's low-memory killer can,
and will, terminate any process (including ones running a foreground service) if the whole system
is under severe enough memory pressure. What this rebuild does is make that **much less likely in
normal use**, by:

1. Running a real foreground service with an ongoing notification, which puts the process in a
   much higher priority bucket than a cached/backgrounded app.
2. Requesting an exemption from battery optimization, since stock Android's "App Standby"/Doze
   and OEM-specific battery managers (Samsung's "Sleeping apps", Xiaomi's MIUI battery saver,
   Huawei, OnePlus, etc.) are usually the actual cause of a background app being killed on real
   devices — often well before plain system memory pressure would ever kill it.
3. Persisting the last-known URL so that if the process *is* killed, the next launch resumes at
   the same page instead of the homepage, and logging low-memory signals for diagnostics
   (`ArenaApplication.onTrimMemory()` / `onLowMemory()`).

None of this is a 100% guarantee, and this README will not claim otherwise. If a user wants the
most reliable background persistence:

* Disable battery optimization for Arena AI when prompted (or later, via the app's overflow
  menu → "Disable battery optimization…").
* On some OEM skins (Samsung, Xiaomi/MIUI, Huawei, Oppo/ColorOS, Vivo, OnePlus), there are
  **additional**, non-standard "auto-start" / "protected apps" / "battery manager" settings
  outside of Android's standard battery optimization API that can still kill background apps.
  These are OEM-specific and cannot be controlled programmatically by any third-party app; users
  on those devices may need to allow "auto-start" or add the app to a battery manager's allowlist
  manually.

## 4. Target / compile SDK

`compileSdk` / `targetSdk` are set to **36 (Android 16)**. Google Play requires new apps and app
updates to target API 36 starting **August 31, 2026** (already-published apps must target at
least API 35 to remain visible to new users on newer devices); targeting 36 now avoids being
blocked from future Play Store submissions. `minSdk` is **24 (Android 7.0)**, a broad floor that
comfortably covers the modern `WebView`/foreground-service APIs this app relies on
(`WebViewClient#onReceivedError(WebResourceRequest, WebResourceError)`, notification channels,
etc.) without needlessly excluding older devices.

## 5. Permissions

| Permission | Why |
|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE` | Load `https://arena.ai` and detect connectivity for the error/retry UI. |
| `FOREGROUND_SERVICE` | Required to run any foreground service. |
| `FOREGROUND_SERVICE_SPECIAL_USE` | `ArenaSessionService` doesn't fit any of Android 14's predefined foreground service categories (media, location, camera, …) — "keep this WebView session alive in the background until the user closes it" is exactly the documented `specialUse` case. The manifest also declares a `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` describing the use case, as Google Play requires for review. |
| `POST_NOTIFICATIONS` | Required on Android 13+ to show the ongoing "Arena AI is running" notification. Requested at runtime via the Activity Result API (`MainActivity.notificationPermissionLauncher`); if denied, the service still runs, it just won't have a visible notification. |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Lets the app launch the system's "ignore battery optimizations" screen from an explicit, user-initiated dialog. It does not grant the exemption by itself — the user must approve it in Settings. |

## 6. Project structure

```
.
├── app/
│   ├── build.gradle                        # SDK versions, signing, dependencies
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml             # MainActivity, ArenaSessionService, permissions
│       ├── java/com/federal/arenaai/
│       │   ├── ArenaApplication.java       # Process-level memory-pressure logging
│       │   ├── MainActivity.java           # Hosts/attaches the WebView, menu, permissions UI
│       │   ├── ArenaSessionService.java    # Foreground Service; owns the WebView instance
│       │   ├── ArenaWebViewClient.java     # Domain allowlist + loading/error callbacks
│       │   ├── BatteryOptimizationHelper.java
│       │   └── SessionStateStore.java      # SharedPreferences: last URL, dialog-shown flag
│       └── res/                            # Icons, splash art (reused), layout, menu, strings
├── gradle/wrapper/                         # Gradle wrapper (pins the Gradle version)
├── build.gradle                            # Root Gradle build file
├── settings.gradle                         # Declares the :app module
├── gradle.properties
├── gradlew / gradlew.bat
├── manifest.json                           # Web manifest for arena.ai (PWA metadata; unrelated to TWA)
├── icons/                                  # App icons (192, 512, maskable) — also for hosting
├── .well-known/assetlinks.json.example     # Optional: Android App Links verification template
├── keystore.properties.example             # Template for local release signing
├── .gitignore
└── .github/workflows/build-apk.yml         # CI/CD pipeline
```

## 7. Building it locally

### Prerequisites

* **JDK 17** (e.g. [Eclipse Temurin 17](https://adoptium.net/))
* **Android Studio** (or the Android command-line tools + SDK Platform **Android 16 / API 36**)
* A machine with internet access (Gradle downloads dependencies on first run)

### Steps

```bash
# 1. Clone the repo (or use your existing clone)
git clone https://github.com/ferdausfs/Arena.ai-app.git
cd Arena.ai-app

# 2. (Recommended) point Gradle at your Android SDK.
#    Android Studio does this automatically. From the command line, create a
#    local.properties file (this file is git-ignored):
#       echo "sdk.dir=$HOME/Android/Sdk" > local.properties

# 3. Build the release APK
./gradlew assembleRelease
```

The APK will be written to:

```
app/build/outputs/apk/release/app-release.apk
```

> If you have not configured a signing keystore yet (see below), the release
> APK is signed with the **debug key** automatically, so you can still install
> and test it on your phone.

> **Windows users:** use `gradlew.bat assembleRelease` instead of `./gradlew`.

### Building a debug APK

```bash
./gradlew assembleDebug   # -> app/build/outputs/apk/debug/app-debug.apk
```

### Testing background persistence

1. Install and open the app; grant the notification permission when prompted and accept the
   battery-optimization dialog.
2. Confirm the "Arena AI is running" notification appears.
3. Send the app to the background (Home button, switch apps, or turn the screen off) — **do not**
   swipe it away from Recents or tap "Stop" on the notification.
4. Wait, then return to the app (or tap the notification). The page should still be exactly where
   you left it, with no reload. `adb logcat -s ArenaSessionService ArenaApplication` shows the
   service and process lifecycle events described in [§3](#3-honest-limitations-please-read).

## 8. The CI/CD pipeline

The pipeline lives in [`.github/workflows/build-apk.yml`](.github/workflows/build-apk.yml). It
needs no Bubblewrap-specific setup — this is a standard Android Gradle project.

**It runs when:**
* you push to the `main` branch, or
* you click the **"Run workflow"** button (Actions tab → "Build Android APK" → Run workflow), or
* you push a version tag such as `v1.0.0`.

**What it does:**
1. **Checkout** the code.
2. **Set up JDK 17** (Temurin).
3. **Set up the Android SDK** and install platform 36 / build tools.
4. **Cache Gradle** for faster runs.
5. **Prepare signing** — if you have configured the signing GitHub Secrets, the
   keystore is reconstructed from them; otherwise the debug key is used.
6. **Build** the release APK with `./gradlew assembleRelease`.
7. **Upload the APK as a workflow artifact** (`arena-ai-apk`) on **every** run.

**When a tag (`v*`) is pushed**, an extra job runs that **creates a GitHub
Release** and attaches the APK to it.

**How to download the APK from CI:**
1. Open the repo on GitHub → **Actions** tab.
2. Click the latest run → **Artifacts** → download `arena-ai-apk`.
3. Unzip it — inside is `app-release.apk`.

## 9. Installing the APK on your phone

1. Download the APK to your phone (or transfer it via USB/cable).
2. On the phone, open **Settings → Security** and enable **"Install unknown
   apps"** (or "Allow from this source") for the app you'll use to open the APK
   (e.g. your file manager or browser).
3. Tap the APK file and confirm the install.
4. You'll see the **Arena AI** icon in your app drawer. Open it, grant the notification
   permission, and accept the battery-optimization prompt for the best background experience.

## 10. Publishing to the Play Store

1. Create a permanent **app signing keystore** (this key must be kept safe —
   it is what identifies your app forever).
2. Add the keystore and passwords to **GitHub Secrets** (see section 8 and
   `.github/workflows/build-apk.yml`):
   - `KEYSTORE_BASE64` — base64-encoded contents of your `.jks` file
   - `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`
3. Push a tag (e.g. `git tag v1.0.0 && git push origin v1.0.0`) → CI builds a
   properly signed APK and attaches it to a GitHub Release.
4. Upload that APK to the Google Play Console.
5. In **Play Console → Policy → App content**, declare the `specialUse` foreground service type
   and its justification (Google reviews this manually for apps targeting API 34+). The
   explanation already embedded in the manifest (`PROPERTY_SPECIAL_USE_FGS_SUBTYPE`) is a good
   starting point.

## 11. Security notes

* **No API keys, tokens, or credentials are hardcoded anywhere** in this
  repository.
* Signing credentials are supplied **only** through GitHub Secrets and a local
  `keystore.properties` file — both are excluded from git.
* `.gitignore` excludes `local.properties`, `keystore.properties`, `*.keystore`,
  `*.jks`, generated secret files, and build outputs.

## 12. What still needs manual action from you

1. **Create a real signing keystore** and configure the GitHub Secrets (see
   sections 8 and 10). Until then, CI produces debug-signed APKs (fine for
   testing, **not** for the Play Store).
2. **Test on a real device across an extended background period** (hours, not just
   minutes) and on at least one aggressive OEM skin (Samsung/Xiaomi) to confirm the
   background-persistence behavior meets your expectations before shipping.
3. **(Optional) Host `assetlinks.json`** at `https://arena.ai/.well-known/assetlinks.json` if you
   want `https://arena.ai` links tapped elsewhere on the device to open directly in this app
   (Android App Links) instead of prompting the user to choose an app. A template is at
   [`.well-known/assetlinks.json.example`](.well-known/assetlinks.json.example).
4. **Push this branch and open a pull request** — this work was intentionally committed to a
   branch, not `main`.
5. **If publishing to Play**, declare the `specialUse` foreground service justification in Play
   Console as noted in section 10.
6. (Optional) **Publish to the Google Play Console** once signed.
