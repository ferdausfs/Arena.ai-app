package com.federal.arenaai;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.MutableContextWrapper;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.GeolocationPermissions;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewFeature;

/**
 * The single, process-wide {@link WebView} that holds the live arena.ai page.
 *
 * <h2>Why the WebView does not live in the Activity</h2>
 *
 * <p>A WebView created by an Activity dies with that Activity: rotate the device, get killed in
 * the background, or simply press Back, and the page — its JavaScript heap, its scroll position,
 * its half-typed message, its in-flight streaming response — is gone and has to be re-fetched.
 * That is exactly the reload behaviour this app exists to avoid.
 *
 * <p>So the WebView is created once, against the <em>application</em> context, and is owned by
 * this process-scoped singleton. {@link ArenaSessionService}, a foreground service, holds a
 * reference to it and keeps the process at foreground importance so Android is far less likely to
 * reclaim it. {@link MainActivity} does not own the page; it merely borrows the view, adds it to
 * its layout while it is on screen ({@link #attachTo}) and gives it back when it goes away
 * ({@link #detach}). The page keeps running the whole time — JavaScript timers, WebSockets and
 * fetches are never paused, because {@code WebView.onPause()} / {@code pauseTimers()} are never
 * called.
 *
 * <h2>How the Activity context is swapped safely</h2>
 *
 * <p>A WebView permanently keeps the context it was constructed with. Constructing it with an
 * Activity would leak that Activity for the lifetime of the process; constructing it with the
 * application context breaks anything that needs a window (spinners, {@code <select>} dropdowns,
 * JS dialogs). The standard solution, used here, is {@link MutableContextWrapper}: the WebView is
 * built against a wrapper whose base context is swapped to the current Activity while it is
 * attached and back to the application context when it is not. No Activity is ever retained.
 *
 * <h2>Threading</h2>
 *
 * <p>Every method must be called on the main thread — WebView is not thread-safe. Both the
 * Activity and the Service run their lifecycle callbacks on the main thread, so this is natural;
 * the contract is enforced with an explicit check rather than left to chance.
 */
public final class ArenaWebSession {

    private static final String TAG = "ArenaWebSession";

    /** Blank page used when tearing the WebView down, so no content survives in memory. */
    private static final String BLANK_URL = "about:blank";

    @SuppressLint("StaticFieldLeak") // Deliberate: the app context, never an Activity (see above).
    @Nullable
    private static ArenaWebSession sInstance;

    private final Context mAppContext;
    private final SessionStore mStore;
    private final MutableContextWrapper mContextWrapper;

    private WebView mWebView;

    /** The Activity-side callbacks, set while an Activity is attached. */
    @Nullable
    private Host mHost;

    /** The container the WebView is currently added to, if any. */
    @Nullable
    private ViewGroup mContainer;

    /** Progress of the current load, 0-100. Replayed to an Activity when it attaches. */
    private int mLastProgress = 100;

    /** Set when the main frame failed to load, cleared on the next successful load. */
    private boolean mHasLoadError;

    /** True once {@link #destroy()} has run; the instance must not be used afterwards. */
    private boolean mDestroyed;

    /**
     * Callbacks the attached Activity provides. Everything here needs a real window, an Activity
     * context or user-visible UI, which is precisely what the session itself must not hold on to.
     */
    public interface Host {

        /** The Activity currently showing the session. Never {@code null} while attached. */
        @NonNull
        Activity getActivity();

        /** Load progress, 0-100. Also replayed once immediately on attach. */
        void onProgressChanged(int progress);

        /** A main-frame load started. */
        void onPageStarted(@NonNull String url);

        /** A main-frame load finished (successfully or not — check {@link #hasLoadError()}). */
        void onPageFinished(@NonNull String url);

        /** The main frame failed to load. The Activity shows the retry UI. */
        void onPageLoadError(int errorCode, @NonNull String description, @NonNull String url);

        /** {@code <input type="file">} was tapped. Return {@code false} to cancel the request. */
        boolean onShowFileChooser(@NonNull ValueCallback<Uri[]> callback,
                                  @NonNull WebChromeClient.FileChooserParams params);

        /** HTML5 fullscreen (video) started. */
        void onShowCustomView(@NonNull View view,
                              @NonNull WebChromeClient.CustomViewCallback callback);

        /** HTML5 fullscreen ended. */
        void onHideCustomView();
    }

    /**
     * Returns the process-wide session, creating the WebView on first use.
     *
     * <p>Whoever gets here first wins — normally {@link ArenaSessionService}, because
     * {@link MainActivity} starts the service before touching the session.
     */
    @MainThread
    @NonNull
    public static ArenaWebSession getInstance(@NonNull Context context) {
        assertMainThread();
        if (sInstance == null || sInstance.mDestroyed) {
            sInstance = new ArenaWebSession(context.getApplicationContext());
        }
        return sInstance;
    }

    /** Returns the existing session without creating one. */
    @MainThread
    @Nullable
    public static ArenaWebSession peek() {
        assertMainThread();
        return (sInstance != null && !sInstance.mDestroyed) ? sInstance : null;
    }

    /** True when a live session exists in this process. */
    @MainThread
    public static boolean isAlive() {
        return peek() != null;
    }

    /** Destroys the process-wide session, if there is one. Safe to call repeatedly. */
    @MainThread
    public static void destroyInstance() {
        assertMainThread();
        if (sInstance != null) {
            sInstance.destroy();
            sInstance = null;
        }
    }

    private ArenaWebSession(@NonNull Context appContext) {
        mAppContext = appContext;
        mStore = new SessionStore(appContext);
        mContextWrapper = new MutableContextWrapper(appContext);
        mWebView = createWebView();
        Log.i(TAG, "Session created.");
    }

    // ---------------------------------------------------------------------------------------
    // WebView construction
    // ---------------------------------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled") // arena.ai is a JavaScript application; this is the point.
    @NonNull
    private WebView createWebView() {
        WebView webView = new WebView(mContextWrapper);
        webView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // Matches the splash/theme background so there is no white flash before first paint.
        webView.setBackgroundColor(mAppContext.getColor(R.color.background));

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        // localStorage / sessionStorage. IndexedDB, Web SQL's successor and the Cache API are
        // enabled together with DOM storage in modern WebView.
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setMediaPlaybackRequiresUserGesture(true);
        // target="_blank" links: routed through WebChromeClient.onCreateWindow() below.
        settings.setSupportMultipleWindows(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        // Responsive layout, like a normal mobile browser.
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setGeolocationEnabled(false);
        // Never silently downgrade https sub-resources to http.
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        // Standard HTTP caching; the page is a live app, not an offline bundle.
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);

        // Follow the system dark/light setting where the WebView supports it. Sites that ship
        // their own dark theme via prefers-color-scheme are respected first (DARK_MODE), and
        // algorithmic darkening is only applied to sites that do not (ALGORITHMIC_DARKENING).
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, true);
        }

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        // Required for OAuth/SSO round-trips, which set cookies on the provider's domain.
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        // Ask the platform to treat this renderer as important even while it is not visible.
        // This is the single most effective knob for "do not reclaim my page in the background".
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT,
                    /* waivedWhenNotVisible= */ false);
        }

        webView.setWebViewClient(new ArenaWebViewClient());
        webView.setWebChromeClient(new ArenaWebChromeClient());
        webView.setDownloadListener(new ArenaDownloadListener());

        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true);
        }
        return webView;
    }

    // ---------------------------------------------------------------------------------------
    // Attach / detach
    // ---------------------------------------------------------------------------------------

    /**
     * Puts the live WebView into {@code container} and routes UI callbacks to {@code host}.
     *
     * <p>Idempotent: attaching the same host twice, or attaching after a configuration change,
     * simply re-parents the same WebView instance. The page is never reloaded by this call.
     */
    @MainThread
    public void attachTo(@NonNull Host host, @NonNull ViewGroup container) {
        assertMainThread();
        assertUsable();

        mHost = host;
        mContainer = container;
        // From now on the WebView resolves themes, windows and dialogs through the Activity.
        mContextWrapper.setBaseContext(host.getActivity());

        ViewGroup currentParent = (ViewGroup) mWebView.getParent();
        if (currentParent != container) {
            if (currentParent != null) {
                currentParent.removeView(mWebView);
            }
            container.addView(mWebView);
        }

        // Undo any onPause() from a previous detach cycle; rendering resumes immediately.
        mWebView.onResume();

        // Replay the current state so a freshly created Activity shows the right UI at once.
        host.onProgressChanged(mLastProgress);
        Log.d(TAG, "Attached to " + host.getActivity().getClass().getSimpleName());
    }

    /**
     * Removes the WebView from the Activity's layout and drops every Activity reference.
     *
     * <p>The page keeps running: no {@code onPause()}, no {@code pauseTimers()}, no reload. Only
     * rendering stops, because the view is no longer in a window.
     */
    @MainThread
    public void detach() {
        assertMainThread();
        if (mDestroyed) {
            return;
        }
        ViewGroup parent = (ViewGroup) mWebView.getParent();
        if (parent != null) {
            parent.removeView(mWebView);
        }
        mContainer = null;
        mHost = null;
        // Back to the application context: no Activity is retained by the WebView.
        mContextWrapper.setBaseContext(mAppContext);
        // Persist where the user was, so a process kill can be recovered from.
        mStore.setLastUrl(mWebView.getUrl());
        Log.d(TAG, "Detached; page remains live in memory.");
    }

    /** True while an Activity is showing the session. */
    @MainThread
    public boolean isAttached() {
        return mHost != null;
    }

    // ---------------------------------------------------------------------------------------
    // Navigation
    // ---------------------------------------------------------------------------------------

    /**
     * Loads the resume URL, but only if nothing has been loaded yet.
     *
     * <p>This is what makes re-opening the app cheap: the second, third and hundredth launch find
     * a page already loaded and do nothing at all.
     */
    @MainThread
    public void ensureLoaded() {
        assertMainThread();
        assertUsable();
        String current = mWebView.getUrl();
        if (TextUtils.isEmpty(current) || BLANK_URL.equals(current)) {
            String url = mStore.getResumeUrl();
            Log.i(TAG, "No page loaded yet; loading " + url);
            mHasLoadError = false;
            mWebView.loadUrl(url);
        }
    }

    /** Navigates to {@code url} (used for arena.ai deep links delivered to the Activity). */
    @MainThread
    public void loadUrl(@NonNull String url) {
        assertMainThread();
        assertUsable();
        mHasLoadError = false;
        mWebView.loadUrl(url);
    }

    /** Reloads the current page, or loads the resume URL if the last attempt failed outright. */
    @MainThread
    public void reload() {
        assertMainThread();
        assertUsable();
        mHasLoadError = false;
        String current = mWebView.getUrl();
        if (TextUtils.isEmpty(current) || BLANK_URL.equals(current)) {
            mWebView.loadUrl(mStore.getResumeUrl());
        } else {
            mWebView.reload();
        }
    }

    /** True when the page can go back in its own history. */
    @MainThread
    public boolean canGoBack() {
        return !mDestroyed && mWebView.canGoBack();
    }

    /** Goes back in the page's history. */
    @MainThread
    public void goBack() {
        if (!mDestroyed && mWebView.canGoBack()) {
            mWebView.goBack();
        }
    }

    /** True when the last main-frame load failed. */
    @MainThread
    public boolean hasLoadError() {
        return mHasLoadError;
    }

    /** The URL currently displayed, or the resume URL if nothing is loaded. */
    @MainThread
    @NonNull
    public String getCurrentUrl() {
        String url = mDestroyed ? null : mWebView.getUrl();
        return TextUtils.isEmpty(url) ? mStore.getResumeUrl() : url;
    }

    // ---------------------------------------------------------------------------------------
    // Teardown
    // ---------------------------------------------------------------------------------------

    /**
     * Tears the WebView down for good. Called only when the user explicitly ends the session
     * (notification action, in-app "Stop session", or a task-removal shutdown).
     */
    @MainThread
    private void destroy() {
        assertMainThread();
        if (mDestroyed) {
            return;
        }
        mDestroyed = true;
        mStore.setLastUrl(mWebView.getUrl());
        mHost = null;
        mContainer = null;

        ViewGroup parent = (ViewGroup) mWebView.getParent();
        if (parent != null) {
            parent.removeView(mWebView);
        }
        mWebView.stopLoading();
        mWebView.setWebChromeClient(null);
        // A null WebViewClient restores the framework default; the app's client is released.
        mWebView.setWebViewClient(new WebViewClient());
        mWebView.setDownloadListener(null);
        mWebView.loadUrl(BLANK_URL);
        mWebView.clearHistory();
        mWebView.removeAllViews();
        mWebView.destroy();
        // Flush cookies written during the session so the next launch is still signed in.
        CookieManager.getInstance().flush();
        mContextWrapper.setBaseContext(mAppContext);
        Log.i(TAG, "Session destroyed.");
    }

    /**
     * Rebuilds the WebView after the renderer process was killed by the system.
     *
     * <p>When Android kills the sandboxed renderer, the old WebView object is permanently dead;
     * touching it throws. The only correct recovery is to drop it and build a new one, which is
     * what happens here — and then to reload the page the user was on. This is the "handle it
     * gracefully" path for OS-level reclamation that no app can fully prevent.
     */
    @MainThread
    private void recoverFromRendererCrash(boolean crashed) {
        String resumeUrl = mStore.getResumeUrl();
        Log.w(TAG, "Renderer process gone (crashed=" + crashed + "); rebuilding WebView at "
                + resumeUrl);

        Host host = mHost;
        ViewGroup container = mContainer;

        WebView dead = mWebView;
        ViewGroup parent = (ViewGroup) dead.getParent();
        if (parent != null) {
            parent.removeView(dead);
        }
        dead.setWebChromeClient(null);
        dead.setDownloadListener(null);
        dead.destroy();

        mWebView = createWebView();
        mHasLoadError = false;
        mLastProgress = 0;

        if (host != null && container != null) {
            container.addView(mWebView);
            mWebView.onResume();
            host.onProgressChanged(0);
        }
        mWebView.loadUrl(resumeUrl);
    }

    // ---------------------------------------------------------------------------------------
    // WebViewClient
    // ---------------------------------------------------------------------------------------

    private final class ArenaWebViewClient extends WebViewClient {

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return handleNavigation(request.getUrl());
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            mHasLoadError = false;
            mLastProgress = 0;
            Host host = mHost;
            if (host != null && url != null) {
                host.onPageStarted(url);
            }
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            mLastProgress = 100;
            if (url != null && !BLANK_URL.equals(url)) {
                mStore.setLastUrl(url);
            }
            Host host = mHost;
            if (host != null && url != null) {
                host.onPageFinished(url);
            }
        }

        @Override
        public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
            // Single-page apps navigate with the History API and never fire onPageFinished
            // again; this callback is what keeps the resume URL accurate for them.
            if (url != null && !BLANK_URL.equals(url)) {
                mStore.setLastUrl(url);
            }
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request,
                                    WebResourceError error) {
            // Sub-resource failures (an image, an analytics beacon) must not blank the app.
            if (!request.isForMainFrame()) {
                return;
            }
            int code = error != null ? error.getErrorCode() : ERROR_UNKNOWN;
            CharSequence description = error != null ? error.getDescription() : null;
            reportMainFrameError(code,
                    description != null ? description.toString() : "Unknown error",
                    request.getUrl() != null ? request.getUrl().toString() : "");
        }

        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                        android.webkit.WebResourceResponse errorResponse) {
            if (!request.isForMainFrame() || errorResponse == null) {
                return;
            }
            int status = errorResponse.getStatusCode();
            // 5xx means the site is down; 4xx pages usually still render useful content, so only
            // server errors are surfaced as an app-level failure.
            if (status >= 500) {
                reportMainFrameError(status, "HTTP " + status,
                        request.getUrl() != null ? request.getUrl().toString() : "");
            }
        }

        @Override
        public void onReceivedSslError(WebView view, android.webkit.SslErrorHandler handler,
                                       android.net.http.SslError error) {
            // Never proceed through a certificate error. A wrapper for a signed-in AI account is
            // exactly the kind of app where a MITM must fail closed.
            Log.e(TAG, "SSL error for " + (error != null ? error.getUrl() : "?")
                    + "; cancelling load.");
            handler.cancel();
            reportMainFrameError(ERROR_FAILED_SSL_HANDSHAKE, "SSL certificate error",
                    error != null && error.getUrl() != null ? error.getUrl() : "");
        }

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            boolean crashed = detail != null && detail.didCrash();
            recoverFromRendererCrash(crashed);
            // true = "handled"; returning false would kill the whole app process.
            return true;
        }
    }

    private void reportMainFrameError(int code, @NonNull String description, @NonNull String url) {
        mHasLoadError = true;
        mLastProgress = 100;
        Log.w(TAG, "Main-frame load failed (" + code + " " + description + ") for " + url);
        Host host = mHost;
        if (host != null) {
            host.onPageLoadError(code, description, url);
        }
    }

    // ---------------------------------------------------------------------------------------
    // WebChromeClient
    // ---------------------------------------------------------------------------------------

    private final class ArenaWebChromeClient extends WebChromeClient {

        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            mLastProgress = newProgress;
            Host host = mHost;
            if (host != null) {
                host.onProgressChanged(newProgress);
            }
        }

        @Override
        public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture,
                                      android.os.Message resultMsg) {
            // target="_blank" / window.open(). The destination URL is not known yet, so a
            // throwaway WebView is handed to the engine purely to capture it; the navigation is
            // then routed through the same policy as every other link.
            WebView probe = new WebView(mContextWrapper);
            probe.setWebViewClient(new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView probeView,
                                                        WebResourceRequest request) {
                    routePopup(probeView, request.getUrl());
                    return true;
                }
            });
            WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
            transport.setWebView(probe);
            resultMsg.sendToTarget();
            return true;
        }

        @Override
        public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback,
                                         FileChooserParams fileChooserParams) {
            Host host = mHost;
            if (host == null) {
                // No Activity on screen: the request cannot be fulfilled, and the page must be
                // told, otherwise the file input stays permanently stuck.
                filePathCallback.onReceiveValue(null);
                return true;
            }
            return host.onShowFileChooser(filePathCallback, fileChooserParams);
        }

        @Override
        public void onPermissionRequest(android.webkit.PermissionRequest request) {
            // The app declares no camera or microphone permission, so any such request from the
            // page is denied rather than silently hanging. See README ("Deliberate limitations").
            Log.i(TAG, "Denying web permission request: "
                    + java.util.Arrays.toString(request.getResources()));
            request.deny();
        }

        @Override
        public void onGeolocationPermissionsShowPrompt(String origin,
                                                       GeolocationPermissions.Callback callback) {
            // Geolocation is disabled in WebSettings; deny explicitly and never remember it.
            callback.invoke(origin, false, false);
        }

        @Override
        public void onShowCustomView(View view, CustomViewCallback callback) {
            Host host = mHost;
            if (host == null) {
                // Fullscreen video with no Activity attached: tell the page it did not happen.
                callback.onCustomViewHidden();
                return;
            }
            host.onShowCustomView(view, callback);
        }

        @Override
        public void onHideCustomView() {
            Host host = mHost;
            if (host != null) {
                host.onHideCustomView();
            }
        }
    }

    /** Handles a URL captured from a {@code window.open()} / {@code target="_blank"} popup. */
    private void routePopup(@NonNull WebView probe, @Nullable Uri url) {
        // The probe WebView exists only to report the URL; destroy it either way.
        probe.destroy();
        if (url == null) {
            return;
        }
        if (UrlPolicy.shouldHandleInternally(url.getScheme(), url.getHost())) {
            // Popups to our own site (or to a sign-in provider) continue in the same session
            // rather than opening a second, disconnected window.
            mWebView.loadUrl(url.toString());
        } else {
            openExternally(url);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Navigation policy + external links
    // ---------------------------------------------------------------------------------------

    /**
     * @return {@code true} when the navigation was consumed (sent elsewhere), {@code false} to let
     * the WebView load it.
     */
    private boolean handleNavigation(@Nullable Uri url) {
        if (url == null) {
            return false;
        }
        if (UrlPolicy.shouldHandleInternally(url.getScheme(), url.getHost())) {
            return false;
        }
        openExternally(url);
        return true;
    }

    /**
     * Sends a URL to whichever app handles it — the default browser for http(s), the mail app for
     * {@code mailto:}, the dialer for {@code tel:}, and so on.
     */
    private void openExternally(@NonNull Uri url) {
        Context launchContext = mHost != null ? mHost.getActivity() : mAppContext;
        Intent intent = new Intent(Intent.ACTION_VIEW, url);
        if (!(launchContext instanceof Activity)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        // This is only ever reached for hosts the policy rejected, so the app's own arena.ai
        // VIEW filter cannot match and bounce the link straight back into the session.
        try {
            launchContext.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "No app can open " + url.getScheme() + ":// links", e);
            toast(mAppContext.getString(R.string.error_no_app_for_link));
        } catch (SecurityException e) {
            Log.w(TAG, "Not allowed to open " + url, e);
            toast(mAppContext.getString(R.string.error_no_app_for_link));
        }
    }

    // ---------------------------------------------------------------------------------------
    // Downloads
    // ---------------------------------------------------------------------------------------

    private final class ArenaDownloadListener implements DownloadListener {

        @Override
        public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                    String mimeType, long contentLength) {
            if (url == null) {
                return;
            }
            if (!URLUtil.isNetworkUrl(url)) {
                // blob: and data: downloads cannot be handed to DownloadManager. They are rare on
                // this site; the user is told rather than left wondering why nothing happened.
                Log.w(TAG, "Unsupported download scheme for " + url);
                toast(mAppContext.getString(R.string.error_download_unsupported));
                return;
            }
            String fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);
            try {
                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                request.setMimeType(mimeType);
                request.addRequestHeader("User-Agent", userAgent);
                // Downloads are usually behind the session cookie; without it the request 401s.
                String cookie = CookieManager.getInstance().getCookie(url);
                if (!TextUtils.isEmpty(cookie)) {
                    request.addRequestHeader("Cookie", cookie);
                }
                request.setTitle(fileName);
                request.setDescription(mAppContext.getString(R.string.app_name));
                request.setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                request.setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS, fileName);

                DownloadManager manager =
                        (DownloadManager) mAppContext.getSystemService(Context.DOWNLOAD_SERVICE);
                if (manager == null) {
                    throw new IllegalStateException("DownloadManager unavailable");
                }
                manager.enqueue(request);
                toast(mAppContext.getString(R.string.download_started, fileName));
            } catch (RuntimeException e) {
                Log.e(TAG, "Download failed for " + url, e);
                toast(mAppContext.getString(R.string.error_download_failed));
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    private void toast(@NonNull String message) {
        Toast.makeText(mAppContext, message, Toast.LENGTH_SHORT).show();
    }

    private void assertUsable() {
        if (mDestroyed) {
            throw new IllegalStateException("ArenaWebSession has been destroyed");
        }
    }

    private static void assertMainThread() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException(
                    "ArenaWebSession must only be touched on the main thread");
        }
    }
}
