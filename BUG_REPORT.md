# Arena.ai-app — Security & Reliability Bug Report

**Repository:** `ferdausfs/Arena.ai-app` (branch `arena/019ff132-arena-ai-app`)
**Analyzed:** 2026-08-11
**Scope:** All Kotlin sources, `AndroidManifest.xml`, `build.gradle`, CI workflow, ProGuard/NSC config.

> Methodology: every `.kt`, `.xml`, `.gradle`, `.pro`, and workflow file was read in full before any finding was filed. Each bug below cites the exact file + line and is backed by the current code. Fixes were applied to this branch; see `git diff`.

---

## Summary

| Severity | Count | Highlights |
|----------|-------|-----------|
| 🔴 Critical | 3 | `allowFileAccess=true`, `intent://` scheme injection, abrupt `exitProcess` |
| 🟠 High | 6 | notification icon broken, error page loses URL, `onDestroy` re-creates WebView, popup double-nav, geolocation dead-code, popup XSS-surface via mixed content |
| 🟡 Medium | 6 | `MIXED_CONTENT_COMPATIBILITY_MODE`, autoplay, `allowBackup`, deprecated `databaseEnabled`, light-only popup theme, deprecated `onBackPressed` |
| 🟢 Low | 4 | UA regex redundancy, unreachable code, dangling `proguard-rules.pro`, CI SDK mismatch |

**Verdict:** No hardcoded secrets, no API keys, no credential leakage in source. The architecture (singleton + `MutableContextWrapper` + foreground service) is sound. The issues below are hardening + correctness fixes, the most serious being two security settings (`allowFileAccess`, `intent://` parsing) and several UX/crash edge cases.

---

# 🔴 CRITICAL — Security & Crashes

### Bug #1: `allowFileAccess = true` exposes the local file system

**Severity:** 🔴 Critical
**Category:** Security
**File:** `app/src/main/java/com/federal/arenaai/WebViewManager.kt`
**Line:** ~210 (main WebView) and ~373 (popup WebView)

**Current Code:**
```kotlin
settings.apply {
    javaScriptEnabled = true
    ...
    allowFileAccess = true          // <-- both the main and popup WebView
    ...
}
```

**Problem:**
`allowFileAccess` lets the WebView read `file://` and (combined with `allowFileAccessFromFileURLs`-style flows on some OEM WebViews) access the app's private files. This app only ever loads remote `https://arena.ai` content, so there is no legitimate reason to permit `file://`. The default flips to `false` on API 30+, but explicitly setting `true` re-opens the hole on every supported API (minSdk 24). If any loaded page (or a compromised multi-tenant auth subdomain like `*.supabase.co` / `*.hf.space` / `*.auth0.com` that the allowlist trusts) can script-navigate to `file://`, it can exfiltrate local files.

**Impact:**
Local file disclosure (app private storage, other files the app can read) reachable from a malicious or compromised in-WebView page.

**Fix:**
```kotlin
allowFileAccess = false
allowFileAccessFromFileURLs = false
allowUniversalAccessFromFileURLs = false
```
*(Applied to both the main and popup WebView.)*

**Verification:**
Build, load `https://arena.ai`, then try navigating to `file:///data/data/com.federal.arenaai/...` from any in-page script — must be blocked.

---

### Bug #2: `intent://` scheme is launchable with an explicit component (intent injection)

**Severity:** 🔴 Critical
**Category:** Security
**File:** `app/src/main/java/com/federal/arenaai/WebViewManager.kt`
**Line:** ~241–254

**Current Code:**
```kotlin
if (scheme == "intent") {
    try {
        val intent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
        if (intent != null && ctx != null) {
            val pm = ctx.packageManager
            val info = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            if (info != null) {
                ctx.startActivity(intent)        // <-- launched as-is
            } else {
                ...fallback...
            }
            return true
        }
    } catch (_: Exception) {}
    return true
}
```

**Problem:**
The `MATCH_DEFAULT_ONLY` check only filters activities that match via an `<intent-filter>` with `DEFAULT` category. It does **not** stop an intent that carries an explicit `component=...`. A page that is allowed in-WebView can craft:

```
intent:#Intent;component=com.victim.app/.ExportedSensitiveActivity;S.token=...;end
```

`resolveActivity()` returns the explicit component regardless of `MATCH_DEFAULT_ONLY`, so `startActivity(intent)` launches it with attacker-controlled extras. The selector extra is also unstripped, opening a second launch path. This is the classic WebView intent-scheme vulnerability (it has historically enabled the launch of arbitrary exported activities). The trusted multi-tenant domains in `isAllowedInWebView` (`*.supabase.co`, `*.auth0.com`, `*.hf.space`, `*.gradio.app`) expand the attack surface.

**Impact:**
Arbitrary exported-activity launch with attacker-controlled Intent extras/flags — privilege escalation / sensitive-activity invocation from an in-app page.

**Fix** (Google-documented secure pattern):
```kotlin
val parsed = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
    .addCategory(Intent.CATEGORY_BROWSABLE)   // only activities that declare browsable
    .setComponent(null)                        // strip explicit component attacks
    .setSelector(null)                         // strip selector attacks
    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

val pm = ctx.packageManager
if (parsed.resolveActivity(pm) != null) {
    ctx.startActivity(parsed)
} else {
    val fallbackUrl = parsed.getStringExtra("browser_fallback_url")
    // ... existing fallback handling ...
}
```

**Verification:**
From a test page in-WebView, navigate to an `intent://` URI with a `component=` set to another app's activity — it must NOT launch. A benign `intent://` (e.g. a share intent) with a proper `<intent-filter>` still launches.

---

### Bug #3: `exitProcess(0)` from the service, with unreachable code after it

**Severity:** 🔴 Critical (behavior) / 🟢 Low (dead code)
**Category:** Crash / Best Practice
**File:** `app/src/main/java/com/federal/arenaai/ArenaSessionService.kt`
**Line:** 31–41

**Current Code:**
```kotlin
if (intent?.action == ACTION_STOP) {
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf()

    WebViewManager.flushCookies()

    kotlin.system.exitProcess(0)
    return START_NOT_STICKY          // <-- unreachable; compiler warns
}
```

**Problem:**
`exitProcess(0)` tears down the whole process (Activity included) without running onDestroy/finish callbacks. On several OEM ROMs (MIUI, EMUI, ColorOS) calling this synchronously from `onStartCommand` while the Activity is mid-transition can manifest as an ANR or a black screen flash; the user also loses any unsent in-page input because the WebView is killed before its `onPause`/`flush` path can run from the Activity side. The comment says it's intentional, but the **`return START_NOT_STICKY` after it is dead code** — confusing and produces a compiler warning. More importantly, `START_STICKY` (returned at the bottom) means that if the *system* (not the user) kills the service, it restarts as an empty foreground service with **no WebView** (process memory is gone), so the "keeps the session alive" claim in the README is only true while the process is left running.

**Impact:**
Abrupt termination bypasses Activity teardown; ANR/black-screen risk on some OEMs; misleading dead code; service-restart does not actually preserve the WebView.

**Fix:**
Keep a clean process exit for the explicit "Close" action, but remove the unreachable line and flush defensively first; document the START_STICKY limitation:
```kotlin
if (intent?.action == ACTION_STOP) {
    WebViewManager.flushCookies()
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf()
    // User explicitly closed → fully tear down the process so the
    // singleton WebView and all session state are released.
    kotlin.system.exitProcess(0)
}
```

**Verification:**
Tap "Close" in the notification; the app process ends (`adb shell ps | grep arenaai` shows nothing) with no ANR dialog. Build emits no "unreachable code" warning.

---

# 🟠 HIGH — Functionality & UX

### Bug #4: Notification small icon uses the full-color launcher (`mipmap`) → renders as a white blob

**Severity:** 🟠 High
**Category:** UX
**File:** `app/src/main/java/com/federal/arenaai/ArenaSessionService.kt`
**Line:** 81

**Current Code:**
```kotlin
.setSmallIcon(R.mipmap.ic_launcher)
```

**Problem:**
Android forces notification small icons into the system-tray color mask (white silhouette + alpha). `R.mipmap.ic_launcher` is a full-color adaptive icon; when drawn as a small icon it becomes an unrecognizable white square/blob (and on Android 12+ can be rejected/distorted by the themed-icon renderer). The correct resource is a single-color `drawable` (vector), not a `mipmap`.

**Impact:**
Broken/ugly notification icon on all Android versions; possible rendering failure on Android 12+.

**Fix:**
Add a dedicated monochrome vector drawable `ic_notification.xml` and reference it:
```kotlin
.setSmallIcon(R.drawable.ic_notification)
```
*(New resource `res/drawable/ic_notification.xml` added — a single-color "A" glyph.)*

**Verification:**
Trigger the foreground service; the status-bar icon is a crisp white glyph, not a white square.

---

### Bug #5: Error page "Retry" always returns to the home page, and `loadData` clobbers history

**Severity:** 🟠 High
**Category:** UX / Crash
**File:** `app/src/main/java/com/federal/arenaai/WebViewManager.kt`
**Line:** ~296–310 (`onReceivedError`)

**Current Code:**
```kotlin
override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
    super.onReceivedError(view, request, error)
    if (request?.isForMainFrame == true) {
        view?.loadData(
            """<html>... <a href="https://arena.ai" ...>Retry</a> ...</html>""".trimIndent(),
            "text/html", "UTF-8"
        )
    }
}
```

**Problem:**
1. The Retry link is hard-coded to `https://arena.ai`, so if a deep link (e.g. a conversation URL) fails, "Retry" throws the user back to the homepage instead of the page that failed.
2. `loadData(...)` with no history URL places an `about:blank`-style entry in history, so the Back button after an error behaves oddly (the failed page is gone from the stack).
3. Re-entrant errors: if the network is still down, `onReceivedError` fires again for the loaded error page and you can recurse.

**Impact:**
Broken retry (loses context), confused back-stack, potential error-page recursion.

**Fix:** capture the failed URL, render it into the retry link, and use `loadDataWithBaseURL` with a sentinel history:
```kotlin
override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
    super.onReceivedError(view, request, error)
    if (request?.isForMainFrame != true) return
    val failedUrl = request.url?.toString() ?: currentUrl
    if (failedUrl.startsWith("about:")) return          // already on error page
    val html = """
        <html><body style="...">
        <h2>Connection Error</h2>
        <p>Unable to load the page. Check your internet connection.</p>
        <p><a href="$failedUrl" style="color:#4fc3f7;">Retry</a></p>
        </body></html>
    """.trimIndent()
    view?.loadDataWithBaseURL("about:blank", html, "text/html", "UTF-8", failedUrl)
}
```

**Verification:**
Kill network, open a deep-linked conversation URL → error page; tap Retry (with network back) → returns to the conversation, not the homepage. Back button works.

---

### Bug #6: `onDestroy()` re-creates the singleton WebView as a side effect

**Severity:** 🟠 High
**Category:** Memory / Crash
**File:** `app/src/main/java/com/federal/arenaai/MainActivity.kt`
**Line:** 195–200

**Current Code:**
```kotlin
override fun onDestroy() {
    super.onDestroy()
    try {
        val webView = WebViewManager.getWebView(this)   // <-- CREATES if null!
        if (webView.parent === container) {
            container.removeView(webView)
        }
    } catch (_: Exception) {}
    ...
}
```

**Problem:**
`WebViewManager.getWebView(context)` has the side effect of **creating and loading a brand-new WebView** when the singleton is `null` (e.g. right after `onRenderProcessGone` nulled it, or during a finishing Activity). Calling it in `onDestroy` purely to detach therefore spins up a new WebView — which immediately starts loading `https://arena.ai` — only to be abandoned, leaking memory, CPU, and a dangling `MutableContextWrapper` tied to a dead Activity.

**Impact:**
WebView singleton recreated during teardown → memory leak, wasted network load, potential crash if the Activity is already finishing.

**Fix:** add a non-creating detach method to `WebViewManager` and use it:
```kotlin
// WebViewManager.kt
fun detachFrom(container: ViewGroup) {
    webView?.let { w ->
        if (w.parent === container) container.removeView(w)
    }
}

// MainActivity.onDestroy()
override fun onDestroy() {
    super.onDestroy()
    WebViewManager.detachFrom(container)
    WebViewManager.flushCookies()
    if (WebViewManager.listener === this) WebViewManager.listener = null
}
```

**Verification:**
After `onRenderProcessGone` (which nulls the singleton), rotate/back the Activity — confirm via profiler that no new WebView is created during onDestroy.

---

### Bug #7: OAuth popup navigates twice (dialog dismiss `reload()` races `loadUrl`)

**Severity:** 🟠 High
**Category:** UX / Crash
**File:** `app/src/main/java/com/federal/arenaai/WebViewManager.kt`
**Line:** ~429–439 (popup `onPageFinished`) and the dismiss listener

**Current Code:**
```kotlin
// inside popup onPageFinished, when redirected back to arena.ai:
if (path == "/" || path.startsWith("/c/") || path.startsWith("/leaderboard")) {
    try { dialog.dismiss() } catch (_: Exception) {}
    loadUrl(it)            // (1) loads 'it' into the MAIN singleton WebView
}
...
// inside dialog.setOnDismissListener:
webView?.let { mainView ->
    val mainUrl = mainView.url ?: currentUrl
    if (mainUrl.contains("arena.ai") || mainUrl.contains("lmarena.ai")) {
        mainView.reload()  // (2) reloads the main WebView AGAIN
    }
}
```

**Problem:**
`dialog.dismiss()` fires the dismiss listener synchronously which calls `mainView.reload()`, **and** the next line calls `loadUrl(it)` which navigates the main WebView to `it`. The result is two concurrent navigations on the main WebView (a reload and a fresh load), which can cause a flash, a cancelled navigation, or — because `loadUrl` mutates `currentUrl` mid-flight — landing on the wrong page. This is a real race.

**Impact:**
Flicker / wrong page / cancelled navigation after OAuth completes; flaky "am I logged in?" experience.

**Fix:** do the navigation in exactly one place. Let the dismiss listener own the reload, and have the popup only dismiss (it already shares the cookie jar):
```kotlin
// popup onPageFinished — just dismiss; the dismiss listener will reload the main view
if (path == "/" || path.startsWith("/c/") || path.startsWith("/leaderboard")) {
    // Store where to land, then dismiss. Dismiss listener reloads main WebView.
    targetUrl = it
    try { dialog.dismiss() } catch (_: Exception) {}
    return
}
```
and in the dismiss listener, load the captured target (or reload):
```kotlin
dialog.setOnDismissListener {
    flushCookies()
    if (activePopupDialog === dialog) activePopupDialog = null
    try { popupWebView.destroy() } catch (_: Exception) {}
    webView?.let { mainView ->
        val dest = targetUrl ?: mainView.url ?: currentUrl
        if (dest.contains("arena.ai") || dest.contains("lmarena.ai")) mainView.loadUrl(dest)
        targetUrl = null
    }
}
```

**Verification:**
Complete a GitHub/Google login via popup; the main view lands on exactly one authenticated page with no flicker or double-load (verify with the WebView network inspector — one document request).

---

### Bug #8: Geolocation enabled but never handled (no permission, no callback)

**Severity:** 🟠 High
**Category:** UX / Best Practice
**File:** `WebViewManager.kt` Line ~217; `AndroidManifest.xml` (no `ACCESS_FINE_LOCATION`)

**Current Code:**
```kotlin
setGeolocationEnabled(true)
```

**Problem:**
`setGeolocationEnabled(true)` lets pages *request* geolocation, but:
- there is no `WebChromeClient.onGeolocationPermissionsShowPrompt()` override to actually grant it, and
- the manifest declares **no** `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION`.

So any geolocation request silently fails (or, on older WebViews, hangs). arena.ai does not need geolocation. Leaving it on is a dead, confusing feature that also looks suspicious to permission auditors.

**Impact:**
Broken geolocation UX; unnecessary privacy-sensitive capability exposed.

**Fix:**
```kotlin
setGeolocationEnabled(false)
```
*(Remove entirely or set false.)*

**Verification:**
`navigator.geolocation` calls from in-WebView JS fail fast instead of hanging.

---

### Bug #9: Popup WebView is created without the same error/renderer handling as the main WebView

**Severity:** 🟠 High
**Category:** Crash
**File:** `WebViewManager.kt` Line ~360–395 (popup `WebView(activity).apply{...}`)

**Current Code:**
The popup WebView sets `webViewClient` and `webChromeClient`, but defines **no** `onReceivedError` and **no** `onRenderProcessGone`. If the popup's renderer dies mid-OAuth, the popup silently freezes and `onCloseWindow` is never called → a zombie dialog that only the system back button can close.

**Impact:**
Frozen, undisposable popup after a renderer crash inside the OAuth flow.

**Fix:**
Add an `onRenderProcessGone` to the popup WebView that destroys the popup WebView and dismisses the dialog:
```kotlin
popupWebView.webViewClient = object : WebViewClient() {
    ...existing...
    override fun onRenderProcessGone(v: WebView?, d: RenderProcessGoneDetail?): Boolean {
        try { popupWebView.destroy() } catch (_: Exception) {}
        try { dialog.dismiss() } catch (_: Exception) {}
        return true
    }
}
```

**Verification:**
`adb shell am kill` / "Stop rendering" via chrome inspector on the popup → dialog closes cleanly instead of freezing.

---

# 🟡 MEDIUM — Performance & Best Practices

### Bug #10: `MIXED_CONTENT_COMPATIBILITY_MODE` instead of `NEVER_ALLOW`

**Severity:** 🟡 Medium
**Category:** Security
**File:** `WebViewManager.kt` Lines ~207 (main) and ~371 (popup)

**Problem:**
arena.ai is fully HTTPS and `network_security_config.xml` already blocks cleartext. `MIXED_CONTENT_COMPATIBILITY_MODE` still lets a page opt into loading `http://` sub-resources, which is an XSS/downgrade vector. Since everything should be HTTPS, prefer the strict mode.

**Fix:**
```kotlin
mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
```

**Verification:**
Load an HTTPS page that references an `http://` asset → the asset is blocked (no mixed-content warning).

---

### Bug #11: `mediaPlaybackRequiresUserGesture = false` (autoplay)

**Severity:** 🟡 Medium
**Category:** Security / UX
**File:** `WebViewManager.kt` Line ~213

**Problem:**
Allows pages to autoplay audio/video and (historically) programmatic navigation/`window.open` without a user gesture. arena.ai is a chat UI; autoplay is not needed.

**Fix:**
```kotlin
mediaPlaybackRequiresUserGesture = true
```

---

### Bug #12: `android:allowBackup="true"` can exfiltrate session cookies

**Severity:** 🟡 Medium
**Category:** Security
**File:** `AndroidManifest.xml` Line 28

**Problem:**
With `allowBackup="true"`, the WebView cookie jar / `WebView` data dir are included in `adb backup` and auto-backup. On a rooted device or via adb, an attacker can pull the persisted Arena session cookies. An auth-wrapping app should opt out.

**Fix:**
```xml
android:allowBackup="false"
android:dataExtractionRules="@xml/data_extraction_rules"   <!-- optional, explicit deny -->
```
*(At minimum set `allowBackup="false"`.)*

---

### Bug #13: `databaseEnabled` is deprecated and a no-op

**Severity:** 🟡 Medium
**Category:** Best Practice
**File:** `WebViewManager.kt` Lines ~206 and ~370

**Problem:**
`WebSettings.databaseEnabled` controlled the long-removed WebSQL DB; it has no effect on modern WebView (IndexedDB is gated by `domStorageEnabled`, which is already `true`). It misleads readers into thinking a database feature is configured.

**Fix:** remove the line (or set `false`).

---

### Bug #14: OAuth popup always uses the Light theme (ignores Dark Mode)

**Severity:** 🟡 Medium
**Category:** UX
**File:** `WebViewManager.kt` Line ~381

**Current Code:**
```kotlin
val dialog = Dialog(activity, android.R.style.Theme_DeviceDefault_Light_NoActionBar_Fullscreen).apply { ... }
```

**Problem:**
The app theme is `Theme.AppCompat.DayNight` (correctly dark in dark mode), but the popup is hard-wired to `…Light…`, so an OAuth popup is a bright white panel over a dark app — jarring and a battery hit on OLED.

**Fix:** add a DayNight dialog style and use it:
```xml
<!-- styles.xml -->
<style name="Arena.PopupDialog" parent="Theme.AppCompat.DayNight.NoActionBar">
    <item name="android:windowFullscreen">true</item>
    <item name="android:windowBackground">@android:color/black</item>
</style>
```
```kotlin
val dialog = Dialog(activity, R.style.Arena_PopupDialog).apply { ... }
```

---

### Bug #15: `onBackPressed()` is deprecated (no predictive back gesture)

**Severity:** 🟡 Medium
**Category:** Compatibility
**File:** `MainActivity.kt` Line 126

**Problem:**
`onBackPressed()` is deprecated from API 33+ and opts the app out of the Android 14+ predictive-back gesture (and may stop working in a future API).

**Fix:** migrate to `OnBackPressedDispatcher`:
```kotlin
override fun onCreate(...) {
    ...
    onBackPressedDispatcher.addCallback(this) {
        when {
            WebViewManager.dismissActivePopup() -> { /* consumed */ }
            WebViewManager.canGoBack() -> WebViewManager.goBack()
            else -> { isEnabled = false; onBackPressedDispatcher.onBackPressed() } // moveTaskToBack
        }
    }
}
// delete the old onBackPressed()
```
*(For `moveTaskToBack`, keep a small helper.)*

---

### Bug #16: No certificate pinning (MITM via a trusted CA)

**Severity:** 🟡 Medium
**Category:** Security
**File:** `network_security_config.xml`, WebView layer

**Problem:**
The NSC trusts **all system CAs** in release. Anyone who can install a CA on the device (corporate MDM, a malicious app with user CA, a compromised system root) can MITM the OAuth flow and capture session cookies. Pinning the arena.ai leaf/intermediate pins would prevent this. (Pinning in a WebView is harder than OkHttp — it requires a `WebViewClient` that re-checks the chain in `onReceivedSslError`/via a custom trust manager — so this is noted as a recommendation, not a trivial fix.)

**Impact:** Credential/session interception on devices with an attacker-controlled trusted CA.

**Fix (recommended):**
- Narrow NSC to a pinned set for `arena.ai` / `lmarena.ai`, or
- Add a `WebViewClient` that validates the server certificate chain against known pins and blocks mismatches.

---

# 🟢 LOW — Polish & Edge Cases

### Bug #17: `cleanUserAgent` has a redundant second replace and can leave a double space

**Severity:** 🟢 Low
**Category:** Best Practice
**File:** `WebViewManager.kt` Lines ~48–51

**Current Code:**
```kotlin
return rawUa
    .replace("; wv", "")
    .replace("; wv;", ";")     // <-- unreachable: "; wv" already gone
    .replace(Regex("Version/[0-9]+\\.[0-9]+\\s*"), "")
```

**Problem:** After `.replace("; wv", "")` runs globally, no `; wv;` remains, so line 2 is dead. Also, removing `; wv` from `…Pixel 7; wv) AppleWebKit…` is fine, but in `…; wv ;…` (space variants) it can leave a stray space. The fix collapses whitespace.

**Fix:**
```kotlin
return rawUa
    .replace("; wv", "")
    .replace(Regex("\\sVersion/[0-9]+\\.[0-9]+"), "")
    .replace(Regex("\\s{2,}"), " ")
    .trim()
```

---

### Bug #18: Unreachable `return START_NOT_STICKY` after `exitProcess(0)`

**Severity:** 🟢 Low
**Category:** Code Quality
**File:** `ArenaSessionService.kt` Line 41

**Problem:** `exitProcess(0)` never returns, so the following `return START_NOT_STICKY` is dead code and the compiler warns. (Fixed as part of Bug #3.)

---

### Bug #19: `build.gradle` references `proguard-rules.pro` but the file does not exist

**Severity:** 🟢 Low
**Category:** Build
**File:** `app/build.gradle` Line 42

**Problem:** `proguardFiles …, 'proguard-rules.pro'` points at a missing file. With `minifyEnabled false` it's silently ignored, but the moment anyone flips minification on, there are **no keep rules** for the WebView/JS layer and the build is unguarded. Also `minifyEnabled false` ships un-stripped, un-obfuscated release APKs.

**Fix:** create `app/proguard-rules.pro` (added) with WebView keep rules; (recommend) enable `minifyEnabled true` + the rules once validated.

---

### Bug #20: CI installs `android-36` platform / `build-tools;36.0.0` but the project `compileSdk` is 34

**Severity:** 🟢 Low
**Category:** CI
**File:** `.github/workflows/build-apk.yml`

**Problem:** The runner installs SDK 36 while the app compiles against 34. It builds, but it's inconsistent and means CI isn't actually pinning the SDK the app targets. Align them (install `platforms;android-34` + `build-tools;34.0.0`).

---

# Additional Checks

| Check | Result |
|-------|--------|
| Hardcoded secrets / API keys / tokens | ✅ None found. `README.md` confirms; signing via GitHub Secrets / local `keystore.properties` only. |
| `keystore.properties.example` | ✅ Placeholder only; no real key material. |
| Exported components | ✅ Only `MainActivity` is exported (launcher + verified App Links). Service is `exported="false"`. |
| Intent filters | ✅ App-Link hosts match the trusted domains; `autoVerify="true"` set. (Requires the real `assetlinks.json` to be hosted — `.well-known/assetlinks.json.example` is a template.) |
| `FOREGROUND_SERVICE_SPECIAL_USE` | ⚠️ Requires a Play-Console declaration + justification when distributing via Play; not a code bug. |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | ⚠️ Sensitive permission — Play may flag unless justified. |
| `WebView.setWebContentsDebuggingEnabled` | ✅ Correctly gated on `FLAG_DEBUGGABLE` (off in release). |
| `CookieManager` accept / third-party | ⚠️ Third-party cookies are globally enabled (needed for cross-origin OAuth). Acceptable trade-off; documented. |
| Broad multi-tenant allowlist (`*.supabase.co`, `*.auth0.com`, `*.hf.space`, `*.gradio.app`) | ⚠️ Intentional for auth/embed; note that Bug #2 makes this riskier, which is why the intent-scheme hardening matters. |

---

## Fixes applied on this branch

The following were implemented (see `git diff`):

- **#1** `allowFileAccess` → `false` (+ file/URL/universal access off) on both WebViews
- **#2** `intent://` parsing hardened (strip component/selector, force `BROWSABLE`, resolve before start)
- **#3** unreachable code removed; flush/teardown ordered cleanly
- **#4** new `res/drawable/ic_notification.xml`; notification uses it
- **#5** error page preserves failed URL; uses `loadDataWithBaseURL`; recursion guard
- **#6** `WebViewManager.detachFrom()`; `MainActivity.onDestroy` no longer re-creates the WebView
- **#7** popup double-navigation removed (single load on dismiss)
- **#8** geolocation disabled
- **#9** popup `onRenderProcessGone` added
- **#10** `MIXED_CONTENT_NEVER_ALLOW`
- **#11** `mediaPlaybackRequiresUserGesture = true`
- **#13** deprecated `databaseEnabled` removed
- **#14** DayNight popup dialog style
- **#17** UA cleaning simplified
- **#19** `app/proguard-rules.pro` created

Documented as follow-ups (require product/build decisions): **#12** `allowBackup=false`, **#15** back-press dispatcher migration, **#16** certificate pinning, **#20** CI SDK alignment, `minifyEnabled`.
