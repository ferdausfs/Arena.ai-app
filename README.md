# Arena AI — Android App (Native WebView + Background Service)

An installable Android app that wraps **https://arena.ai** using a **Native Android WebView** coupled with a **Foreground Service** to keep the app alive in the background. 

---

## Table of contents

1. [What changed & Why](#1-what-changed--why)
2. [Project structure](#2-project-structure)
3. [Key behaviors & Limitations](#3-key-behaviors--limitations)
4. [In-App Authentication & Login Handling](#4-in-app-authentication--login-handling)
5. [Building it locally](#5-building-it-locally)
6. [The CI/CD pipeline](#6-the-cicd-pipeline)
7. [Security notes](#7-security-notes)
8. [What still needs manual action from you](#8-what-still-needs-manual-action-from-you)

---

## 1. What changed & Why

The previous versions had issues where logging in redirected users to a separate Chrome Custom Tab that remained open and never returned to the native app, or left the app logged out due to separated cookie jars.

**The Architecture:**
- **Native WebView (`android.webkit.WebView`):** The app runs entirely in its own native process.
- **In-App Authentication Flow:** Google, GitHub, Apple, Microsoft, Clerk, and Email login flows happen directly inside the native app with standard Chrome Mobile user-agent formatting (removing `; wv` and `Version/X.X`), avoiding OAuth `403 disallowed_useragent` blocks while keeping authentication sessions and cookies in the app's persistent `CookieManager`.
- **Multiple Windows & OAuth Popups:** Full support for `window.open` OAuth dialogs with automatic cleanup on `window.close()`.
- **Foreground Service (`ArenaSessionService`):** A persistent background service with an ongoing notification keeps the app process alive. 
- **Back Button Override:** Pressing the system back button minimizes the activity (`moveTaskToBack`), allowing the background service to keep the session alive.

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
| **Seamless Login** | Full login flows (Google OAuth, GitHub, Email, etc.) occur directly within the app. Cookies persist directly to `CookieManager`. |
| **Background Persistence** | `ArenaSessionService` starts when the app opens and runs as a Foreground Service. It displays an ongoing "Arena AI is running" notification. |
| **Minimizing** | Pressing the Back button navigates web history or minimizes the app instead of closing it. The session is only destroyed if the user explicitly taps "Close" on the notification. |
| **Battery Exemption** | On first launch, the app prompts the user to disable battery optimization to prevent OEM battery savers from killing the background service. |
| **External Links** | Internal `arena.ai` links and auth providers load in the app. Other non-auth external links (social media, docs) are offloaded to the user's default browser. |
| **File upload** | `WebChromeClient.onShowFileChooser` on the main *and* popup WebViews opens the system picker (plus camera for image accepts). Selected files are copied into app cache and handed back as `FileProvider` `content://` Uris. |
| **File download** | Real `http(s)` attachments go through `DownloadManager` (cookies + UA forwarded). SPA `blob:` / `data:` downloads (workspace zip, generated images) are intercepted in-page and saved via MediaStore (API 29+) to public Downloads. |

## 4. In-App Authentication & Login Handling

**How In-App Login Works:**

1. **Clean User-Agent:** The WebView's User-Agent is formatted to remove `; wv` and `Version/X.X`, transforming it into an authentic Chrome Mobile User-Agent string for the device. This prevents Google OAuth from blocking the view with `403 disallowed_useragent`.
2. **Direct In-App Navigation:** All auth providers (`accounts.google.com`, `github.com`, `appleid.apple.com`, `clerk.*`, `supabase.co`, etc.) and `arena.ai` domains load inside the native WebView and in-app popup dialogs.
3. **Popup Support:** OAuth popups (`window.open`) are rendered in an in-app fullscreen dialog. Upon login completion, `window.close()` automatically dismisses the dialog and updates the main WebView.
4. **Cookie & Session Continuity:** Cookies are stored directly in the app's `CookieManager` with third-party cookie support enabled. `CookieManager.getInstance().flush()` is called on page loads, app backgrounding, and dialog close, ensuring the user stays logged in across app restarts.
5. **Deep Linking:** Email verification and magic links are caught by `AndroidManifest.xml` and loaded directly in `MainActivity.handleIntent()`.

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

## 6. The CI/CD pipeline

The pipeline lives in [`.github/workflows/build-apk.yml`](.github/workflows/build-apk.yml).

**It runs when:**
* you push to the `main` branch, or
* you click the **"Run workflow"** button, or
* you push a version tag such as `v1.0.0`.

## 7. Security notes

* No API keys, tokens, or credentials are hardcoded anywhere in this repository.
* Signing credentials are supplied only through GitHub Secrets and local `keystore.properties`.

## 8. What still needs manual action from you

1. **Create a real signing keystore** and configure the GitHub Secrets (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`).
2. **Push to GitHub** to trigger the CI build and download the generated release APK.
