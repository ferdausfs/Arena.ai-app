# Arena AI — Android App (Native WebView + Background Service)

An installable Android app that wraps **https://arena.ai** using a **Native Android WebView** coupled with a **Foreground Service** to keep the app alive in the background. 

This approach replaces the previous Trusted Web Activity (TWA) architecture. While TWA was dependent on Chrome and susceptible to being killed when backgrounded, this new native architecture ensures that your Arena AI session stays alive independently, just like a persistent background terminal (e.g., Termux).

---

## Table of contents

1. [What changed & Why](#1-what-changed--why)
2. [Project structure](#2-project-structure)
3. [Key behaviors & Limitations](#3-key-behaviors--limitations)
4. [Building it locally](#4-building-it-locally)
5. [The CI/CD pipeline](#5-the-cicd-pipeline)
6. [Security notes](#6-security-notes)
7. [What still needs manual action from you](#7-what-still-needs-manual-action-from-you)

---

## 1. What changed & Why

The previous version of this app used a Trusted Web Activity (TWA) which tied the app's behavior and lifecycle to Chrome (or the user's default browser). When the app was closed or pushed to the background, Android and Chrome's memory management would often kill the session, causing it to reload upon returning.

**The New Architecture:**
- **Native WebView (`android.webkit.WebView`):** The app runs entirely in its own process without depending on Chrome Custom Tabs.
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
| **External Links** | Internal `arena.ai` links load in the app. External links are correctly offloaded to the user's default browser. |

### Limitations (Honest Disclosure)
No Android app can 100% bypass OS-level memory management. If your device comes under extreme memory pressure (e.g., launching very heavy games while Arena AI is backgrounded), Android **will** forcefully kill the background process to free up RAM. In such extreme cases, returning to the app will trigger a reload of the web session. Disabling battery optimizations (which the app prompts for) significantly reduces the chances of this happening, but it cannot override hardware physical limits.

## 4. Building it locally

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

## 5. The CI/CD pipeline

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

## 6. Security notes

* **No API keys, tokens, or credentials are hardcoded anywhere** in this repository.
* Signing credentials are supplied **only** through GitHub Secrets and a local `keystore.properties` file — both are excluded from git.
* `.gitignore` excludes sensitive files, keystores, and build outputs.

## 7. What still needs manual action from you

1. **Create a real signing keystore** and configure the GitHub Secrets (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`). Until then, CI produces debug-signed APKs (fine for testing, **not** for the Play Store).
2. **Push this branch/commit to `main`** to trigger the first CI build.
3. (Optional) **Publish to the Google Play Console** once signed.
