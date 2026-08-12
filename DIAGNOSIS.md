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
