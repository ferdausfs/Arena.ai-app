package com.federal.arenaai;

import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Looper;
import android.util.Log;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebViewRenderProcessGoneDetail;

import java.lang.ref.WeakReference;

public final class ArenaWebViewManager {
    public interface Callback {
        void onPageStarted(String url);
        void onPageFinished(String url);
        void onProgressChanged(int progress);
        void onMainFrameError(String description);
        void onRenderProcessGone();
    }

    private static final String TAG = "ArenaWebViewManager";
    private static final String PREFS = "arena_session";
    private static final String KEY_LAST_URL = "last_url";
    private static final String ARENA_HOST = "arena.ai";
    private static final String ARENA_URL = "https://arena.ai/";

    private static Context appContext;
    private static WebView webView;
    private static WeakReference<Callback> callbackRef = new WeakReference<>(null);

    private ArenaWebViewManager() {
    }

    public static synchronized void initialize(Context context) {
        if (appContext == null) {
            appContext = context.getApplicationContext();
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    public static synchronized WebView getOrCreate(Context context) {
        ensureMainThread();
        initialize(context);

        if (webView != null) {
            return webView;
        }

        webView = new WebView(appContext);
        webView.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setSaveFormData(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        }
        CookieManager.getInstance().setAcceptCookie(true);

        webView.setWebViewClient(new ArenaWebViewClient());
        webView.setWebChromeClient(new ArenaWebChromeClient());

        String url = getLastUrl(appContext);
        if (!isArenaUrl(url)) {
            url = ARENA_URL;
        }
        Log.i(TAG, "Creating Arena WebView session at " + url);
        webView.loadUrl(url);
        return webView;
    }

    public static synchronized WebView getExisting() {
        return webView;
    }

    public static synchronized void setCallback(Callback callback) {
        callbackRef = new WeakReference<>(callback);
    }

    public static synchronized void clearCallback(Callback callback) {
        Callback current = callbackRef.get();
        if (current == callback) {
            callbackRef = new WeakReference<>(null);
        }
    }

    public static synchronized void detachFromParent() {
        ensureMainThread();
        if (webView != null && webView.getParent() instanceof ViewGroup) {
            ((ViewGroup) webView.getParent()).removeView(webView);
        }
    }

    public static synchronized void destroySession(Context context) {
        ensureMainThread();
        initialize(context);
        if (webView == null) {
            clearLastUrl(appContext);
            return;
        }

        try {
            if (webView.getUrl() != null) {
                saveLastUrl(appContext, webView.getUrl());
            }
            detachFromParent();
            webView.stopLoading();
            webView.loadUrl("about:blank");
            webView.clearHistory();
            webView.removeAllViews();
            webView.destroy();
        } finally {
            webView = null;
            callbackRef = new WeakReference<>(null);
            clearLastUrl(appContext);
        }
    }

    public static boolean canGoBack() {
        return webView != null && webView.canGoBack();
    }

    public static void goBack() {
        if (webView != null) {
            webView.goBack();
        }
    }

    public static void reload(Context context) {
        WebView view = getOrCreate(context);
        view.reload();
    }

    public static String currentUrl() {
        return webView != null ? webView.getUrl() : ARENA_URL;
    }

    public static String homeUrl() {
        return ARENA_URL;
    }

    private static Callback callback() {
        return callbackRef.get();
    }

    private static void saveLastUrl(Context context, String url) {
        if (context == null || !isArenaUrl(url)) {
            return;
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LAST_URL, url)
                .apply();
    }

    private static String getLastUrl(Context context) {
        if (context == null) {
            return ARENA_URL;
        }
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_LAST_URL, ARENA_URL);
    }

    private static void clearLastUrl(Context context) {
        if (context == null) {
            return;
        }
        SharedPreferences.Editor editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        editor.remove(KEY_LAST_URL).apply();
    }

    private static boolean isArenaUrl(String url) {
        if (url == null) {
            return false;
        }
        Uri uri = Uri.parse(url);
        String scheme = uri.getScheme();
        String host = uri.getHost();
        return "https".equalsIgnoreCase(scheme)
                && host != null
                && (ARENA_HOST.equalsIgnoreCase(host) || host.toLowerCase().endsWith("." + ARENA_HOST));
    }

    private static void openExternally(Context context, Uri uri) {
        Intent intent = new Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "No application can open external URL: " + uri, e);
        }
    }

    private static void ensureMainThread() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("WebView session must be accessed on the main thread");
        }
    }

    private static final class ArenaWebViewClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            if (request == null || request.getUrl() == null) {
                return false;
            }

            Uri uri = request.getUrl();
            String url = uri.toString();
            if (isArenaUrl(url)) {
                return false;
            }

            if (request.isForMainFrame()) {
                openExternally(view.getContext(), uri);
                return true;
            }
            return false;
        }

        @Override
        @SuppressWarnings("deprecation")
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            if (isArenaUrl(url)) {
                return false;
            }
            openExternally(view.getContext(), Uri.parse(url));
            return true;
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            Callback callback = callback();
            if (callback != null) {
                callback.onPageStarted(url);
            }
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            saveLastUrl(view.getContext().getApplicationContext(), url);
            Callback callback = callback();
            if (callback != null) {
                callback.onPageFinished(url);
            }
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request != null && request.isForMainFrame()) {
                String description = error != null ? String.valueOf(error.getDescription()) : "Unknown error";
                Callback callback = callback();
                if (callback != null) {
                    callback.onMainFrameError(description);
                }
            }
        }

        @Override
        @SuppressWarnings("deprecation")
        public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
            Callback callback = callback();
            if (callback != null) {
                callback.onMainFrameError(description != null ? description : "Unknown error");
            }
        }

        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
            if (request != null && request.isForMainFrame() && errorResponse != null) {
                Callback callback = callback();
                if (callback != null) {
                    callback.onMainFrameError("HTTP " + errorResponse.getStatusCode());
                }
            }
        }

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            Log.w(TAG, "SSL error loading Arena AI: " + error);
            if (handler != null) {
                handler.cancel();
            }
            Callback callback = callback();
            if (callback != null) {
                callback.onMainFrameError("SSL error");
            }
        }

        @Override
        public boolean onRenderProcessGone(WebView view, WebViewRenderProcessGoneDetail detail) {
            Log.w(TAG, "WebView renderer process was killed. Recreating session when Activity returns.");
            Callback callback = callback();
            synchronized (ArenaWebViewManager.class) {
                if (webView == view) {
                    detachFromParent();
                    webView = null;
                }
            }
            if (callback != null) {
                callback.onRenderProcessGone();
            }
            return true;
        }
    }

    private static final class ArenaWebChromeClient extends WebChromeClient {
        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            Callback callback = callback();
            if (callback != null) {
                callback.onProgressChanged(newProgress);
            }
        }
    }
}
