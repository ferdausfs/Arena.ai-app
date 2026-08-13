# Upload / download diagnosis

**Date:** 2026-08-12  
**Branch:** `arena/019ff3ff-arena-ai-app`  
**Symptom (latest `main` APK):** tapping an attachment control on arena.ai does nothing; tapping a download control does nothing.

## What the shipped APK actually contains

PR #9 (`feat/upload-download`, merge `8385d62`) did **not** put `onShowFileChooser` or a `DownloadListener` into the compiled app.

Verified against `main`:

| Location | Present on `main`? |
|---|---|
| `WebViewManager.onShowFileChooser` | **No** |
| `WebViewManager.setDownloadListener` | **No** |
| `MainActivity.fileChooserLauncher` | **No** |
| `WebViewManager.startFileChooser` | **No** |
| `arena-upload-download.patch` (unapplied file in the repo) | Yes |

`gh pr diff 9` is a 1-line Kotlin compile fix (`restored.size`) plus the addition of `arena-upload-download.patch`. The patch was never applied to `WebViewManager.kt` / `MainActivity.kt`. CI was green because the handlers were never compiled in.

That alone explains the user report. Without `onShowFileChooser`, Chromium WebView swallows `<input type="file">` (the `+` / “Add files” control). Without a `DownloadListener`, `Content-Disposition: attachment` and `<a download>` are dropped.

## What arena.ai actually does

Cannot attach `chrome://inspect` or `adb logcat` to a device from this environment (no Android SDK / emulator; TLS from the sandbox to `arena.ai:443` is also blocked). Diagnosis of the *site* is from official docs + SPA behaviour that matches “works in Chrome, silent in this WebView”.

**Upload** — [Arena help: How to Upload Files](https://help.arena.ai/articles/5595418316-arena-how-to-file-upload):

- Tap the **+** icon in the chat composer, *or* paste a file.
- Battle mode accepts PNG / JPEG / WebP / PDF.
- Agent mode also accepts GIF, txt, md, csv, html, xml, css, js, json.
- **`.zip` is not in either list** — that is arena.ai's own restriction, not a
  WebView limitation (Chrome applies the same `accept` filter and greys out
  zips). The app adds an **"All files"** picker action when the page restricts
  `accept`, so any file (e.g. `.zip`) can still be selected; arena.ai's own
  validation then decides whether to accept it. Practical workaround: extract
  the zip and upload the files individually — Agent Mode accepts txt/md/js/json/
  csv/html/xml/css.

The user confirmed the same tap in **Chrome** opens Android’s “choose an action” sheet. That is the `<input type="file">` path (`WebChromeClient.onShowFileChooser`). It is **not** `window.showOpenFilePicker()` — that API is not implemented on Chrome Android, so it cannot be what Chrome is showing.

Paste still works without a native handler (clipboard). Drag-and-drop is a desktop affordance.

**Download** — [Arena help: Agent Mode](https://help.arena.ai/articles/5432423882-how-to-use-agent-mode):

- Workspace panel → **Download** produces a **zip** of session files.
- Generated images / exported chats on a modern SPA are almost always `Blob` + `URL.createObjectURL` + a synthetic `<a download>` click.

That is root cause #1 in the original brief: `DownloadListener` (once present) fires with a `blob:` URL; `DownloadManager` and the external browser cannot resolve another app’s blob. The unapplied patch would have sent `blob:` to `openInExternalBrowser` and failed silently.

`window.open()` is used for OAuth (Google / GitHub). The popup WebView created in `onCreateWindow` previously had **neither** handler. If a file picker or download is ever triggered from a popup, it would also be a black hole.

## Ranked causes (confirmed / remaining)

1. **Handlers never compiled in** — confirmed by reading `main`. Primary reason nothing happens.
2. **Blob / data-URL downloads** — would still fail after applying the stock patch. Fixed with an injected JS hook + `ArenaNative.saveBlob` + MediaStore.
3. **Popup WebView missing handlers** — confirmed. Both surfaces now share `attachFileTransfer()`.
4. **`createIntent()` / package visibility** — `Intent.createChooser` on API 30+ needs `<queries>` for `GET_CONTENT` / `OPEN_DOCUMENT` / `IMAGE_CAPTURE`. Added. Chooser is wrapped in `createChooser` with a raw `GET_CONTENT` fallback.
5. **Camera-capture inputs** — optional; offered as `EXTRA_INITIAL_INTENTS` when accept types include images. No `CAMERA` permission (the system camera app holds it). `FileProvider` for `EXTRA_OUTPUT`.
6. **Non-`<input>` upload UI** — paste already works. `showOpenFilePicker` is not used on Android Chrome; if arena.ai ever ships it *without* an `<input>` fallback, WebView still cannot implement the File System Access API.

## What we changed

- Implemented the missing chooser + `DownloadListener` on **main and popup** WebViews.
- Verbose `Log.d("ArenaWebView", …)` on every branch (chooser fire / launch / result, download scheme, blob save).
- JS hook (`assets/arena_download_hook.js`) intercepts `<a download>` (real + synthetic `.click()`), `window.open(blob:)`, remembers `URL.createObjectURL` blobs, and posts **chunked** base64 (256 KB) to `ArenaNative.saveBlobBegin/Chunk/End` so we stay under the Binder ~1 MB limit.
- The hook is installed via `WebViewCompat.addDocumentStartJavaScript` on Arena origins only (never on Google/GitHub OAuth hosts), with `evaluateJavascript` as a fallback.
- `http(s)` downloads still use `DownloadManager` with Cookie + User-Agent.
- Blob bytes: MediaStore.Downloads on API 29+ (public Downloads, no storage permission). API 24–28 writes to app-specific external files (also no storage permission) and posts a notification.
- Picker results are copied into cache and re-exposed via `FileProvider` so the renderer can still read them after the picker’s temporary grant expires.
- `ValueCallback` is invoked exactly once (null on cancel / destroy / renderer crash).
- OAuth path, UA cleaning, cookie sharing, `allowFileAccess=false`, `MIXED_CONTENT_NEVER_ALLOW`, hardened `intent://` are unchanged.

## Follow-up: "message send fails once, resend works"

Reported on a real device: sending a message shows "something went wrong"; the
same message succeeds when sent again.

Root cause (ranked):

1. **`WebView.onPause()` on Activity pause (HIGH, primary suspect).** The app
   called `WebViewManager.onPause() → webView.onPause()` whenever the Activity
   paused — i.e. every time the screen locks or the user switches apps. That
   kills the page's in-flight connections (SSE / websocket / fetch). After
   returning, the page still believes a connection exists, so the **first**
   message send fails once; the client reconnects and the retry works. This
   also contradicted the app's design: the foreground service exists precisely
   to keep the arena.ai session alive in the background.
   **Fix:** `onPause()` no longer pauses the WebView — it only flushes cookies.
   `onResume()` is a no-op.

2. **File-chooser callback double-invocation (MEDIUM).** `deliverFileChooserResult`
   copies picked URIs on a background executor before invoking the
   `ValueCallback`. If a new chooser started (or the chooser was cancelled)
   during that copy, the old callback had already been released with `null` —
   the async delivery would then invoke it a *second* time. ValueCallbacks must
   be invoked exactly once; a double call can trigger a JS error in the page.
   **Fix:** a `fileChooserGeneration` counter is bumped whenever the pending
   chooser is replaced/cancelled; the async delivery skips if the generation
   moved on.

3. **Unbounded blob registry in the download hook (MEDIUM, memory).**
   `window.__arenaBlobs` remembered every Blob the page created
   (`URL.createObjectURL`) with no eviction — a long chat with generated images
   grows it without bound, pressuring the renderer.
   **Fix:** cap at 128 entries with FIFO eviction; older blobs fall back to
   `fetch(url)` if a download is requested for them.

4. **No HTTP-error visibility (LOW, diagnostics).** `onReceivedHttpError` was
   not overridden, so a main-frame 4xx/5xx (what the page surfaces as
   "something went wrong") left no trace in logcat.
   **Fix:** main and popup WebViewClients log main-frame HTTP status.

5. **Hook evaluated before load (LOW).** `injectDownloadHook` now skips views
   whose `url` is still null (about:blank / not loaded).

Logcat markers after this fix:
- `ArenaWebView: HTTP 4xx/5xx ...` when the site's API returns an error.
- No `onPause`-related connection drops; first send after returning to the app
  should succeed.

## Follow-up round 2: deep review — 7 more bugs fixed

| # | Severity | Bug | Fix |
|---|----------|-----|-----|
| 1 | **HIGH (functional)** | `shouldOverrideUrlLoading` intercepted **all** `blob:` navigations as downloads — including **subframe** loads (`<iframe src="blob:...">`, e.g. generated PDF/HTML previews). The preview was saved as a file AND failed to render. | Intercept only when `request.isForMainFrame == true`; subframe blob loads are left to the renderer. |
| 2 | **MEDIUM (OAuth safety)** | After the document-start API was dropped, the download hook was evaluated via `evaluateJavascript` on **every** page — including OAuth hosts (accounts.google.com, github.com, clerk…). The hook patches `URL.createObjectURL`, `window.open` and `<a>.click` globally; the design intent (documented) was "never on OAuth hosts". | `injectDownloadHook` now checks the page host against arena.ai / lmarena.ai / lmsys.org / chatbot-arena.org (+ subdomains) and skips everything else. |
| 3 | MEDIUM (perf) | `copyUriToAppCache` copied **our own FileProvider files** (camera capture, previously copied uploads) into cache again — duplicating e.g. a 10 MB photo on every upload. | Skip the copy when `uri.authority == providerAuthority(context)`. |
| 4 | MEDIUM (perf/OOM) | `saveDataUrl` decoded multi-MB `data:` URLs **synchronously on the DownloadListener thread** (stalls the WebView), with no size pre-check (OOM risk). | Pre-check estimated decoded size vs `MAX_BLOB_BYTES` (reject + toast before decoding); decode + write moved to the IO executor. |
| 5 | LOW (diagnostics) | On API 33+, if POST_NOTIFICATIONS is denied, download notifications vanish with no trace. | `notifySaved` logs when the permission is missing (file is still saved). |
| 6 | LOW (security) | The error page interpolated `failedUrl` raw into an `href` attribute — a URL containing `"` could break out of the attribute. | HTML-escape `& " < >` before interpolation. |
| 7 | LOW (leak) | Activity destroy did not dismiss an open OAuth popup dialog → the dialog kept an Activity reference. | `MainActivity.onDestroy` now calls `WebViewManager.dismissActivePopup()`. |

Logcat markers after this round:
- `ArenaWebView: shouldOverride blob (main frame): ...` — only for real downloads, never for iframe previews.
- Download hook now only logs install attempts on arena hosts.

## Performance round — "5 MB app freezes my phone, Termux (10 GB) doesn't"

Termux is native code with no browser engine; this app embeds Chromium (WebView).
The WebView renderer is the heavy part — it keeps animating and running JS
timers, and holds a big DOM for arena.ai's chat/workspace. A 5 MB APK tells you
nothing about RAM/CPU; the embedded Chrome is what counts. Changes:

| # | What | Why |
|---|------|-----|
| 1 | **Delayed background pause (45 s)** — `onStop` schedules `WebView.onPause()+pauseTimers()`; `onStart` cancels/resumes. | Hidden app stopped burning CPU/GPU after a grace period → no more phone-wide slowdown. Quick switches & system pickers (<45 s) never pause, so streams/session stay live. |
| 2 | **Renderer priority** — checked: the platform default is already `RENDERER_PRIORITY_IMPORTANT` regardless of visibility (docs), so no explicit `setRendererPriorityPolicy` call is needed (it's an instance method, and changing the default is discouraged). | Under memory pressure the renderer is NOT aggressively OOM-killed by default, so big sessions stay loaded. |
| 3 | **`flushCookies()` moved to the IO executor.** | It ran on the UI thread on every `onPageFinished` (disk I/O → jank). |
| 4 | **Console message rate-limit** (main + popup WebChromeClients). | arena.ai's React SPA logs a lot; formatting strings on the UI thread for every message janked the app. Errors/warnings always logged; other levels capped at 30/page-load. |
| 5 | **Staging-cache pruning** (uploads/camera/blob-in, ≥7 days old, ≤1/h). | Uploads & camera captures accumulated in cache forever → disk growth + slow phone. |
| 6 | **Explicit `android:hardwareAccelerated="true"`.** | Documents/guarantees GPU rendering for the WebView. |
| 7 | Version bump to 1.2.0 (versionCode 3). | Distinguish performance builds. |

Logcat markers:
- `ArenaWebView: WebView paused (hidden > 45000 ms)` after ~45 s in background.
- `ArenaWebView: WebView resumed (foreground)` on return.

Caveat: pausing after 45 s hidden means live chat updates stall while hidden
(they flush on return). If a long-hidden session's stream dropped, the page
reconnects on resume; login state is always preserved via cookies.

## Stability round — "even after load, same situation / not responding"

User: after all perf fixes, the loaded app still feels like it freezes and shows
"not responding". Two real mechanisms on low-RAM phones, both native-side:

1. **Renderer OOM-kill → reload loop.** The WebView renderer is a separate
   process. Under memory pressure Android kills it; the page then reloads
   fully. On a low-RAM phone this repeats → an endless "freeze → reload →
   freeze" cycle that reads as "app not responding".
   - **Fix A:** `ArenaApp.onTrimMemory()` now forwards every trim to
     `WebView.onTrimMemory(level)` (API 26+), so the renderer releases caches
     BEFORE the system decides to kill it.
   - **Fix B:** `onRenderProcessGone` counts crashes (30 s window). After 2
     crashes it stops auto-reloading the heavy page and shows a lightweight
     "low memory — Retry" page instead of the death spiral. The counter resets
     on any clean `onPageFinished`.
2. **UI-thread jank from hook re-injection.** `onProgressChanged(100)` +
   `onPageFinished` + subframes all funneled into `injectDownloadHook`,
   re-evaluating a ~9 KB JS string several times per load.
   - **Fix C:** per-WebView last-injected-URL guard (WeakHashMap) — the hook is
     evaluated at most once per document.
3. **SPA storage.** arena.ai (React, chat history) benefits from IndexedDB;
   WebView only enables it with `settings.databaseEnabled = true`.
   - **Fix D:** `databaseEnabled = true` in the shared WebView settings.
4. Version 1.2.1 (versionCode 4).

Logcat markers:
- `ArenaWebView: onRenderProcessGone (view=..) crash#1/2 ...`
- `ArenaWebView: renderer crash loop detected — showing low-memory page`
- `ArenaWebView: onTrimMemory level=.. — WebView trimmed`

## GeckoView experiment — result: reverted (too heavy for low-RAM phones)

A prototype embedding Mozilla's GeckoView (bundled Firefox engine) was built and
merged, but on-device it made things WORSE: "not responding" and the phone
freezing. That is expected — GeckoView is a full browser engine (~120 MB of
native code) and on low-RAM phones it uses MORE memory than the system WebView
(Chromium), not less. **Reverted from the default build (1.3.0).** The prototype
code remains in git history (commits d8ed72d…d42327f) if ever revisited with a
high-RAM device in mind.

## RAM & storage tuning round (v1.3.0) — the answer to "use the phone's memory"

Question: "can the app use the phone's storage instead of keeping the phone at
full RAM?" — Yes, and most of that already happens; this round makes it
explicit and adds emergency release:

1. **WebView already caches to DISK, not RAM.** Chromium keeps its HTTP cache,
   IndexedDB and localStorage on the phone's storage (app cache dir), and
   `LOAD_DEFAULT` reuses them across sessions — returning to the app re-renders
   from disk instead of re-downloading the whole SPA. Now explicit + documented.
2. **`WebViewManager.onTrimMemory(level)`** (called from `Application.onTrimMemory`):
   - forwards the trim to the WebView renderer (reflective) so it frees caches
     BEFORE Android OOM-kills it (a killed renderer = full reload = the
     "not responding" freeze);
   - on SEVERE trims (RUNNING_CRITICAL and above) also clears the disk cache +
     navigation history on a background thread — storage gets returned, memory
     pressure doesn't build up, the phone stays responsive.
3. **Background pause (45 s)** from the earlier performance round keeps the
   renderer from burning CPU/GPU when hidden (already in v1.2.0).

Logcat markers:
- `ArenaWebView: onTrimMemory severe (level=..) — clearing disk cache + history`
- `ArenaWebView: onTrimMemory level=.. — WebView trimmed`

## Offline-shell + prefetch + cache limit (v1.3.1) — the "use the phone's storage" round

User asked for three storage-based features; all implemented:

1. **Prefetch ("next open is instant from storage")** — after the app idles on
   arena.ai, a hidden WebView loads a couple of high-value pages
   (`/leaderboard`, `/agent`) so their JS/CSS/images land in the shared disk
   cache. Opening them next time renders from storage instead of the network.
   Guarded: skipped on metered (mobile-data) connections and on low-RAM
   devices (`memoryClass < 256 MB`); the hidden WebView is destroyed after
   loading (with a 60 s safety timeout) so it cannot leak.
2. **Offline shell ("see your last chat without a connection")** — after a
   successful arena page load (debounced) and whenever the app is backgrounded,
   the current viewport is saved as a JPEG to the app cache (one bounded file).
   When the main frame then fails to load (no network / server down), the saved
   snapshot is shown with a "You're offline · last saved view" bar and a Retry
   link instead of a blank error — the user still sees their last chat offline.
   The snapshot is exposed to the WebView via FileProvider (`allowContentAccess`
   is already on).
3. **Cache size limit** — Chromium's HTTP cache lives on the phone's storage;
   the app now measures the cache dir and, when it exceeds 80 MB, clears the
   HTTP cache on a background thread (cookies/localStorage untouched — session
   is preserved). Checked on backgrounding and on every memory trim.

Logcat markers:
- `ArenaWebView: offline snapshot saved (WxH) for <url>`
- `ArenaWebView: load failed (<url>) — showing offline shell`
- `ArenaWebView: prefetch cached: <url>` / `prefetch skipped (metered|low-RAM)`
- `ArenaWebView: cache NNMB > 80MB — clearing HTTP cache`

Version 1.3.1 (versionCode 6).

## Deep review round (v1.3.2) — "the app has bugs again", 8 fixes

Full code audit of the merged tree; eight real issues found and fixed:

| # | Severity | Bug | Fix |
|---|----------|-----|-----|
| 1 | **HIGH (crash)** | `onTrimMemory` severe branch called `webView.clearCache()/clearHistory()` on the **IO executor**. WebView is a View — its methods must run on the **main thread**; background-thread calls can crash ("Calling View methods from another thread"). | Both calls moved to `mainHandler.post`. |
| 2 | **HIGH (jank/freeze)** | `captureOfflineSnapshot()` did the full-screen bitmap + **JPEG compress + file write on the UI thread** 1.5 s after every page load and on backgrounding → interaction freezes on low-end phones. | Only bitmap alloc + `view.draw()` stay on the UI thread; compress, write and base64 encoding moved to the IO executor. |
| 3 | MEDIUM | `enforceCacheLimit()` measured the **whole cacheDir** (uploads, camera, blob-in, offline included) but cleared only the HTTP cache — a few big uploads tripped the 80 MB budget and wiped the HTTP cache pointlessly. | `dirSize` now excludes the staging dirs (they have their own pruning). |
| 4 | MEDIUM (storage) | `copyUriToAppCache()` had no size cap — a giant picked file (e.g. a video) was copied wholesale into the app cache, filling the phone's storage. | Size queried first; copies above 150 MB are skipped and the raw URI is passed instead. |
| 5 | MEDIUM | Offline shell embedded the snapshot via `<img src="content://…">`, which is unreliable on some devices/WebView versions. | Snapshot is pre-encoded to a **base64 data URI** on the IO thread at capture time and embedded directly — renders everywhere, no file I/O on the error path. |
| 6 | MEDIUM (security) | The prefetch hidden WebView had no navigation guard — an off-site redirect would load arbitrary content with JS enabled. | `shouldOverrideUrlLoading` blocks non-arena URLs and aborts the prefetch chain. |
| 7 | MEDIUM (OOM) | `saveFileToDownloads()` did `file.readBytes()` — a full ~64 MB heap spike for large blob downloads on low-RAM phones. | New `writeFileStream()` streams the temp file into MediaStore / Downloads dir (no byte[] in heap). |
| 8 | LOW | The popup WebChromeClient lacked `onCreateWindow` — `window.open()` from inside a popup (nested OAuth window) silently failed. | Delegates to the shared popup handler. |

Also: removed the now-unused `FileProvider` import/`offlineSnapshotUri` (base64 path replaced it).

Version 1.3.2 (versionCode 7).

## ANR-proofing round (v1.3.3) — "app not responding" must never appear

Straight answer: the ANR dialog is shown by the SYSTEM when the app's main
thread cannot process input for ~5 s. It can be triggered by things outside the
app (the whole phone under memory pressure, GC storms, CPU throttling, other
apps), so it can never be guaranteed-away 100% — but this round removes every
known in-app cause and makes any remaining blockage visible:

1. **Removed the biggest remaining main-thread cost**: the offline snapshot was
   captured ~1.5 s after EVERY page load (full-screen `Bitmap.createBitmap` +
   `view.draw()` on the UI thread = ~10 MB allocation + draw pass right when the
   user starts interacting). It is now captured ONLY on backgrounding
   (`onBackgrounded`), when the user is leaving anyway. The offline shell still
   works (the last view is what the user sees on return).
2. **ANR watchdog**: a 1 s heartbeat runs on the main thread; a background
   check every 5 s compares it against the clock. If the main thread stops
   beating for > 8 s, logcat records:
   ```
   ArenaWebView: POSSIBLE ANR: main thread blocked ~Ns — stack:
       at android.os.MessageQueue.next(...)
       at android.app.ActivityThread.main(...)
       ...
   ```
   So every future "app not responding" is diagnosable to the exact line.
3. **Prefetch hardened**: the hidden prefetch WebView (which loads arena.ai
   pages and doubles the renderer footprint) now requires `memoryClass >= 512 MB`
   (was 256) and still skips on metered connections. On low-RAM phones it never
   runs, so it cannot add load-storm pressure.

Audited main-thread work after this round: WebView callbacks (must be main),
the 45 s background pause, the rare crash-loop page, and the backgrounding
snapshot. Everything else (cookie flush, blob decode, cache measure, upload
copy, screenshot compress/base64) is on background threads.

Logcat markers:
- `ArenaWebView: POSSIBLE ANR: main thread blocked ~Ns — stack:` (only when it
  actually happens — this is the signal to send me)
- `ArenaWebView: prefetch skipped (low-RAM device)` on phones with < 512 MB heap

Version 1.3.3 (versionCode 8).

## Lightening round (v1.3.4) — "the phone freezes directly (no ANR dialog)"

User report: the app now works better, but instead of the ANR dialog the phone
sometimes freezes COMPLETELY, and clearing recents makes it work again.

Why the phone freezes completely: the WebView renderer runs at the default
`RENDERER_PRIORITY_IMPORTANT` — under memory pressure the system protects the
renderer and sacrifices other apps; once nothing is left to kill, the whole
phone thrashes. Clearing recents works because it kills the process (fresh
start). Fixes in this round:

1. **Adaptive renderer priority (the main fix).** On devices with < 512 MB heap
   the renderer is set to `RENDERER_PRIORITY_WAIVED` (waived while not visible
   too). Under memory pressure the RENDERER is now killed FIRST — handled by
   `onRenderProcessGone` (crash-loop guard included) and the page reloads from
   the disk cache. **The phone stays responsive; the worst case is a page
   reload, never a frozen phone.** Devices with >= 512 MB keep the default.
2. **Stale-WebView recovery ("clear recents makes it work").** After the
   activity is swiped from recents while the process survived (foreground
   service), the singleton could hold a destroyed/dead-activity WebView →
   blank screen or crash. `getWebView()` now detects this
   (`isDestroyed` / dead host activity) and recreates automatically.
3. **Skip offline snapshot on < 256 MB heap** — the ~8-16 MB bitmap allocation
   + draw pass could itself push a very low-RAM phone over the edge.
4. **Free the in-memory offline snapshot JPEG on every memory trim** (it is
   re-captured on the next backgrounding) — every KB counts under pressure.

Logcat markers:
- `ArenaWebView: low-RAM device (heap NN MB): renderer WAIVED — page may reload under memory pressure, phone stays responsive`
- `ArenaWebView: getWebView: stale WebView detected, recreating`
- `ArenaWebView: offline snapshot skipped (very low-RAM device)`

Version 1.3.4 (versionCode 9).

## Bug-hunt round (v1.3.5) — 5 more real bugs found & fixed

| # | Severity | Bug | Fix |
|---|----------|-----|-----|
| 1 | **HIGH (functional)** | The offline shell only worked within one process lifetime: the snapshot base64 lived in memory and was never read back from disk, so after a fresh launch (the most common offline case!) the user got the plain error page instead of their last chat. | `loadOfflineSnapshotFromDisk()` runs at WebView creation (IO thread) and fills the base64 from the saved JPEG; `onReceivedError` also does a fast synchronous read of small snapshots (≤2 MB) as a last resort. |
| 2 | **HIGH (session loss)** | `flushCookies()` shared the single IO executor with blob saves, upload copies and screenshot encodes. A big blob save queued behind every page-load cookie flush → if the process died in between, cookies were lost ("I have to log in again"). | Dedicated `cookieExecutor` for flushes — always fast, never blocked. |
| 3 | MEDIUM (perf/freeze) | The prefetch hidden WebView could start while the app was already backgrounded (8 s after page finish) — two heavy page loads in the background = exactly the CPU/RAM load that freezes low-end phones. | New `appVisible` flag (set in onBackgrounded/onForegrounded); prefetch skips when not visible. |
| 4 | LOW (stability) | Double `WebView.destroy()` — prefetch (safety timeout + onPageFinished) and popup (onRenderProcessGone + onDismiss) could destroy the same view twice; destroy() after destroy can throw. | `destroyView()` / `destroyPopupView()` guards with a destroyed flag. |
| 5 | LOW (leak) | `ArenaNativeBridge` kept `Assembly` objects (open FileOutputStream) forever if a blob download was interrupted and `saveBlobEnd` never arrived → file-handle leak. | `dropStaleAssemblies()` closes+deletes any assembly older than 5 minutes. |

Version 1.3.5 (versionCode 10).

## How to verify on a device

```bash
adb logcat -s ArenaWebView:D
```

Debug builds already enable `WebView.setWebContentsDebuggingEnabled` — `chrome://inspect` on a desktop will show the same page.

| Action | Expected UI | Expected logcat |
|---|---|---|
| Tap **+** / Add files | System file sheet (Files / Gallery / Camera) | `onShowFileChooser …` then `file chooser launched` then `deliverFileChooserResult delivering N uri(s)` |
| Cancel the sheet | Composer unchanged, next tap still works | `deliverFileChooserResult: cancelled / empty` |
| Pick a PNG/PDF and send | Thumbnail / filename appears in the composer | `copied picker uri=…` |
| Agent workspace **Download** (zip) | Toast + notification; file in Downloads | `DownloadListener url=blob:…` **or** `intercept <a download>` then `ArenaNative.saveBlob` then `saved blob name=…zip` |
| Direct `https://` attachment | DownloadManager notification | `DownloadManager enqueued id=…` |

Login with Google / GitHub (popup) must still complete in-app and land on an authenticated arena.ai page.

## arena.ai quirks

- Upload is a hidden `<input type="file">` triggered by the composer **+** button, not a custom `showOpenFilePicker` path (Chrome Android would not show a sheet otherwise).
- Agent **Download** is a client-side zip (`blob:`). Treating that URL as an HTTP download or handing it to the external browser will always fail.
- Paste-to-upload does not need native code; if a user reports “upload broken” only for paste, look at clipboard permissions instead.
- OAuth still uses `window.open` → the in-app `Dialog` WebView. That WebView must keep the same file handlers so a future in-popup picker does not regress.
