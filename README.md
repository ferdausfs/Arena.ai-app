# Arena AI — Android App

Native Android wrapper for [https://arena.ai](https://arena.ai) using an in-app `android.webkit.WebView` plus a persistent Foreground Service.

This project intentionally **replaces the previous Trusted Web Activity (TWA) implementation**. It no longer depends on Chrome Custom Tabs, Chrome being installed, Digital Asset Links verification, or Bubblewrap-generated launch code.

## Why this architecture

The rejected TWA approach delegated the runtime to Chrome/Custom Tabs. That meant reliability and page lifetime were tied to the browser version and to Chrome's task/process behavior. A TWA also cannot independently keep the page alive after the Android system reclaims the browser-backed task.

The current implementation is a native Android app:

- `MainActivity` displays `https://arena.ai/` in an Android `WebView`.
- `ArenaSessionService` is a persistent foreground service with an ongoing notification: **“Arena AI is running”**.
- `ArenaWebViewManager` owns a single process-wide WebView instance. The Activity attaches that same WebView when visible and detaches it when the Activity is destroyed, without destroying the web page.
- Pressing Back minimizes the task when there is no WebView history instead of closing the session.
- The session is stopped only by explicit user action: the notification **Stop** action or the app menu **Close session** action.

This keeps the WebView and app process alive much more reliably while the app is backgrounded, similar in intent to apps that keep a long-running session active.

## Honest limitations

Android does not allow any third-party app to guarantee unlimited background survival.

A foreground service with an ongoing notification makes the Arena AI process much less likely to be killed, but the OS can still terminate any app under severe memory pressure, during system maintenance, or because of aggressive OEM battery management. If that happens, the app recreates the WebView on the next launch/service restart and restores the last Arena URL as a best-effort recovery. It cannot perfectly preserve in-memory JavaScript state after process death.

For reliable background persistence, the user should exempt Arena AI from battery optimization:

1. Open Arena AI.
2. Accept the prompt to allow background session/battery optimization exemption.
3. On OEM skins such as Samsung, Xiaomi, Oppo, Vivo, Huawei/Honor, etc., also check the vendor-specific battery/autostart/background activity settings if the app is still being stopped.

The app documents and prompts for this because it is a real Android platform limitation, not something a WebView or foreground service can bypass completely.

## Android SDK choices

- **Compile SDK:** 36
- **Target SDK:** 36
- **Minimum SDK:** 23
- **Java:** source/target compatibility 1.8, built with JDK 17

Targeting SDK 36 follows current Play Store direction for modern Android releases and enables the current foreground-service permission model. The app declares `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, and `POST_NOTIFICATIONS`. The foreground service uses `android:foregroundServiceType="specialUse"` with subtype `persistent_web_session` because its purpose is to keep a user-visible, explicitly stoppable web session alive in the background.

> Play Console note: special-use foreground services require an accurate foreground-service declaration during Play review. The justification should match this README: a user-visible persistent Arena AI web session, controlled by an ongoing notification with a Stop action.

## Project structure

```text
.
├── .github/workflows/build-apk.yml      # CI: JDK 17, Android SDK, assembleRelease, artifacts/releases
├── app/
│   ├── build.gradle                     # Android app module and signing fallback
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml          # Permissions, MainActivity, ArenaSessionService
│       ├── java/com/federal/arenaai/
│       │   ├── ArenaApplication.java
│       │   ├── ArenaSessionService.java
│       │   ├── ArenaWebViewManager.java
│       │   └── MainActivity.java
│       └── res/                         # Reused icons/splash assets and native app resources
├── build.gradle                         # Top-level Gradle config
├── gradle.properties
├── gradlew / gradlew.bat
├── keystore.properties.example          # Local release signing template
├── manifest.json                        # Web manifest retained for the Arena web app/PWA metadata
└── settings.gradle
```

Package/application ID remains:

```text
com.federal.arenaai
```

App name remains:

```text
Arena AI
```

## WebView behavior

`MainActivity` hosts the Android WebView with:

- JavaScript enabled
- DOM storage enabled for `localStorage` / IndexedDB-backed site data
- Cookies enabled, including third-party cookies on supported Android versions
- HTTPS-only Arena loading (`usesCleartextTraffic="false"`)
- `WebViewClient` navigation policy:
  - `arena.ai` and subdomains remain inside the app
  - non-Arena main-frame links open in the default browser
  - SSL errors are cancelled, not bypassed
- `WebChromeClient` progress updates shown as a top loading indicator
- Main-frame error UI with a Reload action
- Best-effort URL restore after process recreation

## Foreground service behavior

`ArenaSessionService`:

- Starts when the app first opens.
- Immediately promotes itself to a foreground service.
- Shows an ongoing, low-priority notification: **Arena AI is running**.
- Provides a notification **Stop** action that explicitly destroys the WebView session and stops the service.
- Does not stop merely because the Activity is backgrounded, swiped from recents, or destroyed for configuration changes.

The Activity uses the same singleton WebView instance held by `ArenaWebViewManager`, so minimizing/switching apps/screen-off does not intentionally reload the page.

## Build locally

Prerequisites:

- JDK 17
- Android SDK with platform 36 and build tools installed

Build a release APK:

```bash
./gradlew assembleRelease
```

Output:

```text
app/build/outputs/apk/release/app-release.apk
```

If `keystore.properties` is absent, the release build uses the Android debug key fallback so the APK remains installable for development/testing. Do **not** publish that debug-signed APK to the Play Store.

## Install locally

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

After first launch:

1. Grant notification permission on Android 13+ so the foreground-service notification is visible.
2. Allow battery optimization exemption when prompted.
3. Leave the notification running while you want Arena AI to remain alive in the background.

## Release signing

Local release signing uses a root-level `keystore.properties` file, which is ignored by Git.

```properties
storeFile=/absolute/path/to/arenaai-release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

A template is provided in `keystore.properties.example`.

Security rules:

- Do not commit keystores.
- Do not commit passwords.
- Do not hardcode credentials.
- Use GitHub Secrets in CI.

## GitHub Actions CI/CD

Workflow: `.github/workflows/build-apk.yml`

The workflow keeps the same CI skeleton:

1. Checkout repository
2. Set up JDK 17
3. Set up Android SDK
4. Install Android SDK platform/build tools
5. Prepare signing config from GitHub Secrets if present
6. Run `./gradlew assembleRelease --no-daemon --stacktrace`
7. Upload the release APK artifact
8. On `v*` tag pushes, create a GitHub Release and attach the APK

Required optional secrets for real release signing:

- `KEYSTORE_BASE64`
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

If these secrets are not configured, CI produces a debug-key-signed release APK for testing only.

## Removed TWA/Bubblewrap pieces

The Android app no longer includes:

- Bubblewrap `twa-manifest.json`
- `LauncherActivity` extending the browser-helper TWA launcher
- `DelegationService`
- Chrome Custom Tabs fallback configuration
- `androidbrowserhelper` dependencies
- `androidx.browser` TWA/Custom Tabs dependencies
- TWA file-provider paths and generated shortcuts XML
- TWA manifest metadata and Digital Asset Links app association metadata

The root `manifest.json` and icon/splash assets are retained because they are still useful app/web metadata and branding assets.
