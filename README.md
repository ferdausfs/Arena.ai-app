# Arena AI — Android App (native WebView + foreground session)

A native Android wrapper for **[arena.ai](https://arena.ai)** whose defining feature is that
**the page stays loaded**. Open the app a day later and you are on the same screen, with the same
scroll position and the same half-typed message — not on a freshly reloaded home page.

- **Package:** `com.federal.arenaai`
- **App name:** Arena AI
- **Site:** `https://arena.ai`
- **Language:** Java 17
- **Min / target / compile SDK:** 24 / 36 / 36

---

## Table of contents

1. [What this project is](#1-what-this-project-is)
2. [Why the Trusted Web Activity was replaced](#2-why-the-trusted-web-activity-was-replaced)
3. [Architecture](#3-architecture)
4. [Honest limitations](#4-honest-limitations--read-this-one)
5. [Project structure](#5-project-structure)
6. [Behaviour reference](#6-behaviour-reference)
7. [SDK levels and why](#7-sdk-levels-and-why)
8. [Building locally](#8-building-locally)
9. [CI/CD](#9-cicd)
10. [Installing on a phone](#10-installing-on-a-phone)
11. [Signing and security](#11-signing-and-security)
12. [Deep links / Digital Asset Links](#12-deep-links--digital-asset-links)
13. [Publishing to Google Play](#13-publishing-to-google-play)
14. [What needs manual action from you](#14-what-needs-manual-action-from-you)

---

## 1. What this project is

A single-module Gradle project producing an Android APK. The app renders arena.ai in an
`android.webkit.WebView` that is owned by the *process*, not by the Activity, and is kept resident
by a user-controlled foreground service.

Concretely, the app gives you:

- The full site, with JavaScript, `localStorage`, `sessionStorage`, IndexedDB and cookies.
- A session that survives leaving the app, rotating the device, pressing Back, and swiping the app
  off the recents screen.
- An ongoing, silent notification ("Arena AI is running") that is both the indicator that the
  session is live and the way to stop it.
- In-app navigation confined to `arena.ai`; every other link opens in your default browser.
- File uploads, downloads, and fullscreen video.

---

## 2. Why the Trusted Web Activity was replaced

The previous version of this repo was a Bubblewrap-generated **Trusted Web Activity** (TWA): a
thin `LauncherActivity` from `androidbrowserhelper` that asked Chrome to display arena.ai in a
Custom Tab without browser chrome.

A TWA is a good way to ship a PWA, and it is the wrong tool here, for one structural reason:

> **In a TWA, your app does not own the browser tab. Chrome does.**

Everything else follows from that:

| TWA constraint | Consequence |
| --- | --- |
| The page runs inside Chrome's process, not yours. | You cannot keep it alive. When Chrome discards the tab, the page reloads, and your app has no say in it. |
| An app cannot raise the priority of another app's process. | A foreground service in your app does nothing for a tab hosted by Chrome. The central requirement of this rebuild is unimplementable in a TWA. |
| No `WebViewClient` / `WebChromeClient`. | No load progress, no error screen, no retry, no navigation policy, no file-chooser control. You get whatever Chrome does. |
| Requires Digital Asset Links verification against `arena.ai`. | Without a correctly deployed `assetlinks.json` the app degrades to a Custom Tab *with* a URL bar — visibly not an app. |
| Behaviour depends on the user's browser. | On a device with an old WebView/browser, or a non-Chromium default, the experience varies or falls back entirely. |

The rebuild moves the page into a WebView the app owns, which makes process priority, lifecycle and
navigation policy things this codebase can actually control.

---

## 3. Architecture

Four classes carry the design.

### `ArenaWebSession` — the page

A process-scoped singleton holding **one** `WebView`, created against the *application* context.

- The Activity does not create it and does not own it. It borrows it: `attachTo()` adds the view
  to the Activity's layout, `detach()` removes it again. Neither call touches the page.
- `WebView.onPause()` and `pauseTimers()` are **never** called. JavaScript timers, WebSockets and
  in-flight fetches keep running while the app is in the background.
- The WebView is constructed against a `MutableContextWrapper` whose base is swapped to the current
  Activity while attached, and back to the application context when not. This is what lets the same
  WebView show `<select>` dropdowns and JS dialogs (which need a window) without permanently
  leaking an Activity.
- It owns the navigation policy, the error reporting, downloads, popups and fullscreen plumbing.
- If the **renderer** process is killed (`onRenderProcessGone`), the dead WebView is discarded, a
  new one is built and the last URL is reloaded — instead of the app crashing, which is what
  returning `false` there would do.

### `ArenaSessionService` — the priority

A foreground service of type `specialUse`.

Android decides what to kill by *process importance*. A process whose only component is a stopped
Activity is "cached" and is first in line to be reclaimed — that is why ordinary WebView apps reload
after a while in the background. A process running a foreground service sits near the top of the
importance list. That is the entire mechanism: the service holds a reference to the session and
keeps the process important.

- Started by `MainActivity` on first open.
- Shows a silent, `IMPORTANCE_MIN`, non-dismissable notification with a **Stop** action.
- `android:stopWithTask="false"` — swiping the app out of recents does not end the session.
- Stops **only** on an explicit user action: the notification's Stop button, or the in-app
  overflow → *Stop session* (which confirms first).
- `START_STICKY`: if the OS kills the process anyway, the service is recreated. If the user had
  previously stopped the session, the restarted service checks `SessionStore` and exits instead of
  resurrecting itself.

### `MainActivity` — the window

Owns only what genuinely needs a window: the splash screen, the load progress bar, the error/retry
screen, the file chooser, the fullscreen video container, the runtime permission flow and the
dialogs. Attaches the session in `onStart()`, detaches in `onStop()`. `onDestroy()` deliberately does
**not** destroy the session.

Back behaves like a browser: page history first, and when history is exhausted the app goes to the
background (`moveTaskToBack`) rather than finishing — so returning is instant.

### `SessionStore` + `UrlPolicy` — the small pieces

`SessionStore` is a `SharedPreferences` wrapper holding the last URL, whether a session is wanted,
and which prompts have been shown. `UrlPolicy` is deliberately framework-free — plain Java, unit
tested in `app/src/test/` — and answers one question: does this URL stay in the app, or go to the
browser? `arena.ai` and its sub-domains stay; a short allow-list of identity providers
(Google, Apple, GitHub, Microsoft, Auth0, Okta, Clerk, …) also stays so sign-in redirects can
complete; everything else leaves. Host matching is anchored on a dot, so `notarena.ai` and
`arena.ai.evil.com` are correctly treated as third-party.

### State restore

Two layers, honestly described:

1. **Process alive (the normal case).** Nothing is "restored" because nothing was lost. The same
   WebView, the same DOM, the same JS heap.
2. **Process was killed.** The JavaScript heap is gone and cannot be recovered by any app. What
   survives is the last URL (persisted on every navigation, including SPA History API navigations)
   and the WebView's on-disk storage — cookies, `localStorage`, IndexedDB. The next launch reloads
   that URL, still signed in. Unsubmitted form input and in-flight streaming responses are lost.

---

## 4. Honest limitations — read this one

This app makes an OS kill **much less likely**. It cannot make it impossible. Anyone claiming
otherwise on Android is selling something.

- **Android can kill any app process at any time.** A foreground service moves you up the
  low-memory-killer's list; it does not remove you from it. On a device under real memory pressure
  — a big game, a camera app, a 3 GB phone with 40 apps installed — the session will eventually be
  reclaimed. When that happens the app comes back at the last URL rather than pretending nothing
  happened.
- **OEM battery management is more aggressive than stock Android and does not follow its rules.**
  Samsung ("Put unused apps to sleep"), Xiaomi/MIUI, Huawei ("protected apps"), OPPO/realme/OnePlus
  (ColorOS deep optimization), vivo and others freeze or kill backgrounded apps *regardless* of
  foreground services. See [dontkillmyapp.com](https://dontkillmyapp.com) for the per-vendor detail.
  On those devices the battery-optimization exemption below is not a nicety; it is the difference
  between the session surviving and not.
- **That is why the app asks about battery optimization.** It shows an explanation and opens the
  system screen where you can exclude it. It cannot grant the exemption for you: the one-tap system
  dialog needs `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, which Google Play restricts to app categories
  a web wrapper does not belong to. Declaring it would risk rejection, so the app takes the route
  that is allowed for every app — explain, then hand you the settings screen. Some OEM skins hide
  additional per-app switches ("Allow background activity", "Unrestricted") that the app cannot
  reach at all; those must be set by hand.
- **A resident WebView costs memory and some battery.** That is the trade the user is making, which
  is why the session is always visible in the notification shade and always one tap from being
  stopped.
- **Killing the app from Settings → Force stop ends everything**, including the service. This is
  correct and intended: force-stop means force-stop.
- **The renderer can be killed independently of the app.** Handled by rebuilding and reloading —
  a visible reload, not a crash.
- **No offline mode.** With no network, the app shows its error screen with a retry button.
- **Camera and microphone requests from the page are denied.** The app declares no such permissions;
  geolocation is disabled too. If arena.ai ever needs them, the permissions must be declared in the
  manifest and `onPermissionRequest` in `ArenaWebSession` updated to prompt.

---

## 5. Project structure

```
.
├── app/
│   ├── build.gradle                  App module: SDK levels, signing, BuildConfig, deps
│   ├── proguard-rules.pro            R8 keeps for WebView clients / JS interfaces
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml   Permissions, Activity, foreground service
│       │   ├── java/com/federal/arenaai/
│       │   │   ├── ArenaApplication.java           WebView data dir, memory logging
│       │   │   ├── ArenaSessionService.java        Foreground service + notification
│       │   │   ├── ArenaWebSession.java            The process-wide WebView (core)
│       │   │   ├── BatteryOptimizationHelper.java  Exemption explanation + settings
│       │   │   ├── MainActivity.java               The window onto the session
│       │   │   ├── SessionStore.java               SharedPreferences state
│       │   │   └── UrlPolicy.java                  In-app vs. browser decision
│       │   └── res/
│       │       ├── drawable-*/       Splash + notification icons (per density)
│       │       ├── layout/           activity_main.xml (no <WebView> — see its comment)
│       │       ├── menu/             Overflow: reload / battery / stop session
│       │       ├── mipmap-*/         Launcher icons incl. adaptive (API 26+)
│       │       ├── values/           strings, colors, themes
│       │       ├── values-night/     Night theme
│       │       └── xml/              Backup / data-extraction rules
│       └── test/java/com/federal/arenaai/
│           └── UrlPolicyTest.java    JVM unit tests for the navigation policy
├── .github/workflows/build-apk.yml   CI: test → assembleRelease → artifact → release
├── .well-known/assetlinks.json.example
├── icons/, store_icon.png, manifest.json   Web/PWA assets for arena.ai itself
├── keystore.properties.example
└── build.gradle, settings.gradle, gradle.properties, gradlew, gradle/
```

`manifest.json`, `icons/` and `store_icon.png` are **web** assets belonging to the arena.ai site and
the Play listing. They are not used by the APK build, and are kept because deleting them would
break whatever deploys them.

---

## 6. Behaviour reference

| Situation | What happens |
| --- | --- |
| First launch | Splash → service starts → notification permission asked → page loads → battery dialog |
| Later launches | The already-loaded page appears immediately; no splash wait, no reload |
| Press Back with history | Page goes back |
| Press Back with no history | App goes to the background; session stays live |
| Rotate the device | No Activity recreation (`configChanges`); the page does not blink |
| Swipe out of recents | Window closes, **session stays live**, notification remains |
| Tap the notification | Returns to the existing task and the same page |
| Notification → Stop | Session destroyed, notification removed, app closed |
| Overflow → Stop session | Confirmation dialog, then the same as above |
| Tap an `arena.ai` link | Loads in the app |
| Tap any other link | Opens in the default browser |
| Tap `mailto:` / `tel:` | Opens the mail app / dialer |
| `target="_blank"` popup | Routed through the same policy — in-app for arena.ai, browser otherwise |
| Network failure | Error screen with a retry button |
| Certificate error | Load cancelled, error screen. Never proceeds — fails closed |
| HTTP 5xx on the main frame | Error screen (4xx pages are rendered; they usually say something useful) |
| Renderer killed | WebView rebuilt, last URL reloaded |
| Download tapped | Handed to Android's DownloadManager, with the session cookie attached |
| `<input type="file">` | System file chooser |
| Fullscreen video | Fullscreen container, screen kept awake |

---

## 7. SDK levels and why

| Setting | Value | Reason |
| --- | --- | --- |
| `compileSdk` | 36 | Compile against the newest APIs so new platform behaviour can be handled explicitly. |
| `targetSdk` | 36 | Google Play requires new apps **and updates** to target Android 16 (API 36) from **31 August 2026**. Targeting lower also opts the app into legacy compatibility behaviour that is unhelpful for a foreground-service app. |
| `minSdk` | 24 | Android 7.0. Below this, WebView, foreground-service and runtime-permission behaviour diverges enough that it would need a second code path for a rounding error's worth of devices. API 24+ covers the overwhelming majority of active devices. |

Platform behaviours the code handles explicitly, all of which are why the target level matters:

- **API 26** — notification channels; adaptive icons; `setRendererPriorityPolicy`.
- **API 28** — one WebView data directory per process (`ArenaApplication`).
- **API 31** — `PendingIntent` mutability must be explicit; splash-screen API.
- **API 33** — `POST_NOTIFICATIONS` runtime permission; `RECEIVER_NOT_EXPORTED` on registration.
- **API 34** — every foreground service must declare a type; `specialUse` + justification here.

---

## 8. Building locally

### Prerequisites

- **JDK 17** (Temurin recommended). AGP 8.x requires it.
- **Android SDK** with platform `android-36` and build-tools `36.0.0` — via Android Studio, or
  `cmdline-tools` + `sdkmanager`.
- No global Gradle install needed; the wrapper (Gradle 8.11.1) is committed.

### Steps

```bash
git clone https://github.com/ferdausfs/Arena.ai-app.git
cd Arena.ai-app

# Point Gradle at your SDK (Android Studio does this for you).
# local.properties is git-ignored.
echo "sdk.dir=$HOME/Android/Sdk" > local.properties

# Debug build — signed with the debug key, installable immediately
./gradlew assembleDebug
# -> app/build/outputs/apk/debug/app-debug.apk

# Release build — minified and shrunk with R8
./gradlew assembleRelease
# -> app/build/outputs/apk/release/app-release.apk

# Unit tests (fast, no device or emulator needed)
./gradlew testDebugUnitTest
# report -> app/build/reports/tests/testDebugUnitTest/index.html
```

Without a `keystore.properties`, `assembleRelease` signs with the **debug** key. The APK installs
and runs, but cannot be published. See [§11](#11-signing-and-security).

### Changing the site

`arenaHost` and `arenaStartUrl` at the top of `app/build.gradle` are the single source of truth.
They flow into `BuildConfig` (used by `UrlPolicy`) and into the manifest's intent filter through a
manifest placeholder. Change them in one place and rebuild.

---

## 9. CI/CD

`.github/workflows/build-apk.yml`, on push to `main`, on `v*` tags, on pull requests, and manually
via *Run workflow*:

1. Checkout.
2. JDK 17 (Temurin).
3. Android SDK + `platforms;android-36`, `build-tools;36.0.0`, licences accepted.
4. Gradle cache.
5. Write `keystore.properties` from GitHub Secrets **if they exist**; otherwise log that the debug
   key will be used.
6. `./gradlew testDebugUnitTest` — unit tests, with the HTML report uploaded even on failure.
7. `./gradlew assembleRelease`.
8. Delete the decoded keystore and `keystore.properties` from the workspace (runs even if the build
   failed, so signing material can never reach an artifact).
9. Upload the APK as the `arena-ai-apk` artifact.
10. On a `v*` tag only: download the artifact and publish a GitHub Release with generated notes.

To cut a release:

```bash
git tag v2.0.0
git push origin v2.0.0
```

---

## 10. Installing on a phone

1. Download `app-release.apk` (from the CI artifact, the GitHub Release, or your local build).
2. Copy it to the device and open it.
3. Allow "Install unknown apps" for whichever app you opened it from.
4. Install, open, and — on first run — allow notifications and follow the battery prompt.

Or over ADB:

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

> A debug-signed and a release-signed build cannot be installed over each other. Uninstall first if
> you see `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.

---

## 11. Signing and security

**There are no credentials in this repository, and there must never be any.** `.gitignore` excludes
`local.properties`, `keystore.properties`, `*.jks`, `*.keystore`, `*.p12`, `*.pem`,
`service-account.json` and `google-services.json`.

### Create a keystore

```bash
keytool -genkeypair -v \
  -keystore arenaai-release.jks \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -alias arenaai
```

Keep it safe and backed up. Losing it means you can never update the app on Play under the same
package name.

### Local signing

```bash
cp keystore.properties.example keystore.properties
# then fill in storeFile / storePassword / keyAlias / keyPassword
```

### CI signing

Repository → Settings → Secrets and variables → Actions:

| Secret | Value |
| --- | --- |
| `KEYSTORE_BASE64` | `base64 -w 0 arenaai-release.jks` (macOS: `base64 -i arenaai-release.jks`) |
| `KEYSTORE_PASSWORD` | The keystore password |
| `KEY_ALIAS` | The key alias (e.g. `arenaai`) |
| `KEY_PASSWORD` | The key password |

If they are absent the workflow still succeeds and produces a debug-signed APK.

### Other security properties of the build

- `usesCleartextTraffic="false"` — no plaintext HTTP, at all.
- `setMixedContentMode(MIXED_CONTENT_NEVER_ALLOW)` — no downgraded sub-resources.
- SSL errors are **never** bypassed; the load is cancelled and the error screen is shown.
- `setAllowFileAccess(false)` and `setAllowContentAccess(false)` — the page cannot reach `file://`
  or `content://` URIs.
- No `addJavascriptInterface` anywhere: the page has no bridge into app code.
- Geolocation, camera and microphone requests from the page are denied.
- The session-stopped broadcast is registered `RECEIVER_NOT_EXPORTED` and sent package-scoped.
- Backup rules include only the small preferences file — cookies and web storage are excluded from
  cloud backup and device transfer, so an authenticated session is never copied off the device.

---

## 12. Deep links / Digital Asset Links

The manifest handles `https://arena.ai/…` and `https://*.arena.ai/…`, deliberately **without**
`android:autoVerify="true"`. The app therefore appears in the normal "Open with" chooser.

To make arena.ai links open in the app automatically, host
`https://arena.ai/.well-known/assetlinks.json` (see `.well-known/assetlinks.json.example`) with the
release certificate's SHA-256 fingerprint:

```bash
keytool -list -v -keystore arenaai-release.jks -alias arenaai | grep SHA256
```

Then add `android:autoVerify="true"` to the VIEW intent filter and rebuild. Verify with:

```bash
adb shell pm get-app-links com.federal.arenaai
```

If you publish through Play App Signing, use the fingerprint Play shows you — not your upload key's.

---

## 13. Publishing to Google Play

1. Build an App Bundle: `./gradlew bundleRelease`
   (`app/build/outputs/bundle/release/app-release.aab`).
2. Create the app in the Play Console under `com.federal.arenaai`.
3. **Declare the foreground service.** Policy → App content → *Foreground service permissions*.
   Select **Special use** and paste the justification, which is kept verbatim in
   `res/values/strings.xml` as `fgs_special_use_justification` and is also in the manifest as
   `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`. Google reviews this text; a mismatch is a rejection.
4. Complete the data safety form. The app itself collects nothing; the website may.
5. Upload the AAB, fill in the listing, and roll out.

Note that Play also reviews whether a wrapper app adds enough value over the website. The persistent
session, native error handling and link routing here are the argument.

---

## 14. What needs manual action from you

Nothing in the repository is a stub, but four things live outside it:

1. **Create and store a release keystore**, then add the four GitHub Secrets in
   [§11](#11-signing-and-security). Until then CI produces a debug-signed APK.
2. **Deploy `assetlinks.json`** to arena.ai and add `autoVerify="true"` if you want automatic deep
   linking ([§12](#12-deep-links--digital-asset-links)).
3. **Fill in the Play Console foreground-service declaration** with the justification string
   ([§13](#13-publishing-to-google-play)).
4. **Test on real OEM hardware** — ideally a Samsung and a Xiaomi — with and without the battery
   exemption. That is where background survival is actually decided, and no amount of correct code
   substitutes for measuring it.
