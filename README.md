# Arena AI — Android App (Trusted Web Activity)

An installable Android app that wraps **https://arena.ai** using a **Trusted Web
Activity (TWA)**. A TWA runs the site in a **full-screen Chrome Custom Tab** —
Chrome's engine, not a plain WebView — so the website behaves exactly as it
does in the browser, including logins, cookies, and Chrome features.

This project was generated with [Bubblewrap](https://github.com/GoogleChromeLabs/bubblewrap)
and is built automatically by **GitHub Actions** into an APK that you can
install on any Android phone.

---

## Table of contents

1. [What this project is](#1-what-this-project-is)
2. [Project structure](#2-project-structure)
3. [Key behaviors (how the requirements are met)](#3-key-behaviors)
4. [Building it locally](#4-building-it-locally)
5. [The CI/CD pipeline](#5-the-cicd-pipeline)
6. [Installing the APK on your phone](#6-installing-the-apk-on-your-phone)
7. [Publishing to the Play Store](#7-publishing-to-the-play-store)
8. [Digital Asset Links (domain verification)](#8-digital-asset-links-domain-verification)
9. [Security notes](#9-security-notes)
10. [What still needs manual action from you](#10-what-still-needs-manual-action-from-you)

---

## 1. What this project is

This repository contains a complete Android Gradle project. When you build it,
it produces an APK called **Arena AI**. Tapping the app icon on your phone opens
`https://arena.ai` in a Trusted Web Activity.

Because the site itself does **not** currently serve a web manifest
(`https://arena.ai/manifest.json` returns `404`), this project ships its own
complete web manifest in [`manifest.json`](./manifest.json) and bundles it into
the app at build time (`app/src/main/res/raw/web_app_manifest.json`).

The core Android classes come from Google's
[`androidx.browser:browser` / `com.google.androidbrowserhelper:androidbrowserhelper`]
library — this is the official, standard way to build a TWA.

## 2. Project structure

```
.
├── app/
│   ├── build.gradle                  # The app module (SDK versions, signing, resources)
│   └── src/main/
│       ├── AndroidManifest.xml       # App manifest (activities, TWA intent-filter, ...)
│       ├── java/com/federal/arenaai/ # LauncherActivity, Application, DelegationService
│       └── res/                      # Icons, splash screen, colors, strings, web manifest
├── gradle/wrapper/                   # Gradle wrapper (pins the Gradle version)
├── build.gradle                      # Root Gradle build file
├── settings.gradle                   # Declares the :app module
├── gradle.properties                 # Gradle/JVM/Android settings
├── gradlew / gradlew.bat             # Gradle wrapper scripts (build with ./gradlew)
├── twa-manifest.json                 # Bubblewrap's config for this TWA
├── manifest.json                     # The web manifest for arena.ai (host this on the site)
├── icons/                            # App icons (192, 512, maskable) — also for hosting
├── .well-known/assetlinks.json.example  # Template for the domain-verification file
├── keystore.properties.example       # Template for local release signing
├── .gitignore
└── .github/workflows/build-apk.yml   # The CI/CD pipeline
```

## 3. Key behaviors

| Requirement | How it's implemented |
|---|---|
| **Real TWA (Chrome engine, not WebView)** | `LauncherActivity` extends `com.google.androidbrowserhelper.trusted.LauncherActivity`. The fallback strategy is set to `customtabs`, not `webview`. |
| **Does not reload on resume** | A TWA launches a **Chrome Custom Tab**. When you leave the app and come back, Chrome keeps the existing page in memory — it is **not** recreated. The `LauncherActivity` is declared with `android:alwaysRetainTaskState="true"` so the task (and the open page) is preserved on resume. |
| **Portrait orientation** | `orientation` is set to `portrait`; this is applied both via the manifest metadata (`SCREEN_ORIENTATION`) and in `LauncherActivity.onCreate()`. |
| **No push notifications** | `enableNotifications` is `false`. The `DelegationService` (which forwards notification permissions) is disabled (`android:enabled="false"`). |
| **Splash screen with the app name** | A native splash screen is configured: a full-bleed background color with the app icon and the "Arena AI" name while the site loads, then a smooth fade-out. |
| **Opens arena.ai links** | The `LauncherActivity` has a `VIEW` intent-filter with `autoVerify=true` for the `arena.ai` host, so links to arena.ai open in the app. |

## 4. Building it locally

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

## 5. The CI/CD pipeline

The pipeline lives in [`.github/workflows/build-apk.yml`](.github/workflows/build-apk.yml).

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

## 6. Installing the APK on your phone

1. Download the APK to your phone (or transfer it via USB/cable).
2. On the phone, open **Settings → Security** and enable **"Install unknown
   apps"** (or "Allow from this source") for the app you'll use to open the APK
   (e.g. your file manager or browser).
3. Tap the APK file and confirm the install.
4. You'll see the **Arena AI** icon in your app drawer. Open it.

> For the TWA to run (rather than fall back to a Custom Tab), Chrome must be
> installed on the phone, and the Digital Asset Links file must be deployed on
> `arena.ai` (see section 8). Without the asset-links file the app still opens
> the site — it just can't "own" the domain, so links may open in the browser.

## 7. Publishing to the Play Store

1. Create a permanent **app signing keystore** (this key must be kept safe —
   it is what identifies your app forever).
2. Add the keystore and passwords to **GitHub Secrets** (see section 5 and
   `.github/workflows/build-apk.yml`):
   - `KEYSTORE_BASE64` — base64-encoded contents of your `.jks` file
   - `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`
3. Push a tag (e.g. `git tag v1.0.0 && git push origin v1.0.0`) → CI builds a
   properly signed APK and attaches it to a GitHub Release.
4. Upload that APK to the Google Play Console.

## 8. Digital Asset Links (domain verification)

A TWA needs to prove that the app belongs to `arena.ai`. This is done with a
file called `assetlinks.json` hosted on the site, at:

```
https://arena.ai/.well-known/assetlinks.json
```

A template is committed here: [`.well-known/assetlinks.json.example`](.well-known/assetlinks.json.example).

```json
[
  {
    "relation": ["delegate_permission/common.handle_all_urls"],
    "target": {
      "namespace": "android_app",
      "package_name": "com.federal.arenaai",
      "sha256_cert_fingerprints": [
        "REPLACE_WITH_THE_SHA256_FINGERPRINT_OF_YOUR_SIGNING_CERTIFICATE"
      ]
    }
  }
]
```

**To fill in the fingerprint** (after you have a signing keystore), run:

```bash
keytool -list -v -keystore your-release.jks -alias your-alias
```

Copy the 32-byte **SHA256 certificate fingerprint** (colons are fine) into the
file, then deploy it at `https://arena.ai/.well-known/assetlinks.json`. Verify
with Google's [Digital Asset Links API](https://developers.google.com/digital-asset-links/tools/generator).

> Because there is no signing key yet, this file cannot be finalized in this
> repository — this is expected and is a normal manual step before release.

The app also declares the same relationship in `AndroidManifest.xml` via the
`asset_statements` metadata (see `app/src/main/res/values/strings.xml`).

## 9. Security notes

* **No API keys, tokens, or credentials are hardcoded anywhere** in this
  repository.
* Signing credentials are supplied **only** through GitHub Secrets and a local
  `keystore.properties` file — both are excluded from git.
* `.gitignore` excludes `local.properties`, `keystore.properties`, `*.keystore`,
  `*.jks`, generated secret files, and build outputs.

## 10. What still needs manual action from you

1. **Deploy the web manifest + icons on arena.ai** so the PWA metadata is served
   at `https://arena.ai/manifest.json` (and icons under `/icons/`). This is
   optional for the APK to work, but recommended for a complete PWA/TWA setup.
2. **Create a real signing keystore** and configure the GitHub Secrets (see
   sections 5 and 7). Until then, CI produces debug-signed APKs (fine for
   testing, **not** for the Play Store).
3. **Host `assetlinks.json`** at `https://arena.ai/.well-known/assetlinks.json`
   with the real certificate fingerprint (see section 8). Without it, the app
   can't take over arena.ai links on devices.
4. **Push this branch/commit to `main`** to trigger the first CI build.
5. (Optional) **Publish to the Google Play Console** once signed.
