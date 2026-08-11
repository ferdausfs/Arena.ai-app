# Arena AI — Android App (Native WebView + Background Service)

An installable Android app that wraps **https://arena.ai** using a **Native Android WebView** coupled with a **Foreground Service** to keep the app alive in the background. 

This approach replaces the previous Trusted Web Activity (TWA) architecture. While TWA was dependent on Chrome and susceptible to being killed when backgrounded, this new native architecture ensures that your Arena AI session stays alive independently, just like a persistent background terminal (e.g., Termux).

---

## Table of contents

1. [What changed & Why](#1-what-changed--why)
2. [Project structure](#2-project-structure)
3. [Key behaviors & Limitations](#3-key-behaviors--limitations)
4. [OAuth / Login Handling — Google Block](#4-oauth--login-handling--google-block)
5. [Building it locally](#5-building-it-locally)
6. [The CI/CD pipeline](#6-the-cicd-pipeline)
7. [Security notes](#7-security-notes)
8. [What still needs manual action from you](#8-what-still-needs-manual-action-from-you)

---

## 1. What changed & Why

The previous version of this app used a Trusted Web Activity (TWA) which tied the app's behavior and lifecycle to Chrome (or the user's default browser). When the app was closed or pushed to the background, Android and Chrome's memory management would often kill the session, causing it to reload upon returning.

**The New Architecture:**
- **Native WebView (`android.webkit.WebView`):** The app runs entirely in its own process without depending on Chrome Custom Tabs (except for OAuth).
- **Foreground Service (`ArenaSessionService`):** A persistent background service with an ongoing notification keeps the app process alive. 
- **Back Button Override:** Pressing the system back button will not destroy the app; instead, it minimizes the activity (`moveTaskToBack`), allowing the background service to keep the session alive.

## 2. Project structure

```
.
├── app/
│   ├── build.gradle                  # The app module (SDK versions, dependencies, signing)
│   └── src/main/
│       ├── AndroidManifest.xml       # App manifest (permissions, service declaration)
│       ├── java/com/federal/arenaai/ # MainActivity, ArenaSessionService, WebViewManager
│       └── res/                      # Icons, styles, colors, strings
├── gradle/wrapper/                   # Gradle wrapper
├── build.gradle                      # Root Gradle build file
├── settings.gradle                   # Declares the :app module
├── .github/workflows/build-apk.yml   # The CI/CD pipeline
└── README.md                         # This file
```

## 3. Key behaviors & Limitations

| Feature | How it's implemented |
|---|---|
| **Independent Process** | Uses `WebView` hosted natively inside `MainActivity`, configured with JS/DOM storage enabled. |
| **Background Persistence** | `ArenaSessionService` starts when the app opens and runs as a Foreground Service. It displays an ongoing "Arena AI is running" notification. |
| **Minimizing** | Pressing the Back button navigates web history or minimizes the app instead of closing it. The session is only destroyed if the user explicitly taps "Close" on the notification. |
| **Battery Exemption** | On first launch, the app prompts the user to disable battery optimization. This is **required** to prevent aggressive Android OEM skins (like Samsung, Xiaomi) from killing the service. |
| **External Links** | Internal `arena.ai` links load in the app. OAuth logins (Google, GitHub) open in Chrome Custom Tabs (see below). Other external links are offloaded to the user's default browser. |

### Limitations (Honest Disclosure)
No Android app can 100% bypass OS-level memory management. If your device comes under extreme memory pressure (e.g., launching very heavy games while Arena AI is backgrounded), Android **will** forcefully kill the background process to free up RAM. In such extreme cases, returning to the app will trigger a reload of the web session. Disabling battery optimizations (which the app prompts for) significantly reduces the chances of this happening, but it cannot override hardware physical limits.

## 4. OAuth / Login Handling — Google Block

> **CRITICAL — DO NOT REGRESS:**
> Google OAuth must never be loaded in the embedded WebView — Google blocks this server-side.
> Use Chrome Custom Tabs for Google (and any provider enforcing the same policy) instead.

Google actively blocks OAuth authorization requests originating from embedded WebViews (`android.webkit.WebView`) at the server level since 2017. This produces a generic "400. That's an error", "disallowed_useragent", or "Access blocked" page. This is **Google policy, not a bug**, and there is no client-side workaround (UA spoofing removed `; wv` does not work).

Policy: https://developers.googleblog.com/2016/08/modernizing-oauth-interactions-in-native-apps.html (enforcement expanded July 2023).

**Correct pattern implemented in this app:**

1. **Keep arena.ai's own pages inside WebView** — `INTERNAL_HOSTS = arena.ai` (with proper subdomain matching, e.g. `host == allowed || host.endsWith("." + allowed)`, NOT substring matching).
2. **Intercept Google OAuth URLs** (`accounts.google.com`, `oauth2.googleapis.com`) and other IdP URLs (`github.com`, `clerk.accounts.dev`, etc.) in `WebViewManager.shouldOverrideUrlLoading` and launch them in `androidx.browser.customtabs.CustomTabsIntent` (Chrome Custom Tab). Custom Tabs use the real Chrome browser process/user-agent, which Google accepts, while still feeling integrated (overlay, styled, no full browser app switch).
3. **Return via App Links:** After authentication, Google redirects to an arena.ai URL (e.g. `https://arena.ai/auth/callback?...`). That redirect is caught by the existing deep link / App Link `intent-filter` (`autoVerify=true`) in `AndroidManifest.xml`, which brings `MainActivity` back to foreground via `onNewIntent()`. The Custom Tab closes (or goes background).
4. **Cookie / session continuity:** Chrome Custom Tabs and WebView do **NOT** share cookie jars. Our fix: `MainActivity.handleIntent()` loads the callback URL **in the WebView itself** (e.g. `https://arena.ai/auth/callback?code=xxx`). The WebView then makes its own request to arena.ai, server exchanges code and sets session cookie via `Set-Cookie` in WebView's `CookieManager` store. `CookieManager.getInstance().flush()` is called after every page load + onPause/onDestroy to persist it. We also added `queries` for Custom Tabs in the manifest for Android 11+ package visibility.
5. **Same treatment for GitHub and other IdPs** for consistency / future-proofing, even though GitHub is currently more permissive of WebViews.

**Dependency:** `androidx.browser:browser:1.8.0` is added to `app/build.gradle` for Custom Tabs.

**What was reverted from PR #4:** PR #4 added all OAuth domains to `ALLOWED_HOSTS` so they stayed inside WebView. That is fundamentally broken for Google. This PR reverts to only `arena.ai` as internal, and moves OAuth hosts to `OAUTH_CUSTOM_TAB_HOSTS` opened in Custom Tabs.

**Manual testing needed:** Test Google login end-to-end on a real Android device (not emulator without Chrome) — verify Custom Tab opens, login succeeds, app returns to MainActivity, WebView shows authenticated session without requiring second login.

## 5. Building it locally

### Prerequisites

* **JDK 17** (e.g. [Eclipse Temurin 17](https://adoptium.net/))
* **Android Studio** (or the Android command-line tools + SDK Platform)

### Steps

```bash
# 1. Clone the repo
git clone https://github.com/ferdausfs/Arena.ai-app.git
cd Arena.ai-app

# 2. Build the release APK
./gradlew assembleRelease
```

The APK will be written to:
`app/build/outputs/apk/release/app-release.apk`

> If you have not configured a signing keystore yet, the release APK is signed with the **debug key** automatically, so you can still install and test it on your phone.

## 6. The CI/CD pipeline

The pipeline lives in [`.github/workflows/build-apk.yml`](.github/workflows/build-apk.yml).

**It runs when:**
* you push to the `main` branch, or
* you click the **"Run workflow"** button, or
* you push a version tag such as `v1.0.0`.

**What it does:**
1. Checks out the code, sets up JDK 17 and Android SDK.
2. Prepares signing — if you have configured the signing GitHub Secrets, the keystore is reconstructed from them; otherwise the debug key is used.
3. Builds the release APK with `./gradlew assembleRelease`.
4. Uploads the APK as a workflow artifact (`arena-ai-apk`).
5. If triggered by a tag (`v*`), it creates a GitHub Release and attaches the APK.

## 7. Security notes

* **No API keys, tokens, or credentials are hardcoded anywhere** in this repository.
* Signing credentials are supplied **only** through GitHub Secrets and a local `keystore.properties` file — both are excluded from git.
* `.gitignore` excludes sensitive files, keystores, and build outputs.

## 8. What still needs manual action from you

1. **Create a real signing keystore** and configure the GitHub Secrets (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`). Until then, CI produces debug-signed APKs (fine for testing, **not** for the Play Store).
2. **Push this branch/commit to `main`** to trigger the first CI build.
3. (Optional) **Publish to the Google Play Console** once signed.
4. **Test Google OAuth login end-to-end on a physical device** with Chrome installed, as described in section 4.
