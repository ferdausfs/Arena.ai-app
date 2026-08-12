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
