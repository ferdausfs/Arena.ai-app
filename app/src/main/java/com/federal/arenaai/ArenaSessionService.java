/*
 * Copyright 2024 Arena AI
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.federal.arenaai;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

/**
 * Foreground Service that owns the single, long-lived {@link WebView} instance used to render
 * https://arena.ai.
 *
 * <h2>Why this exists</h2>
 *
 * A plain {@code Activity} is destroyed (and, with it, any WebView it owns) whenever Android
 * decides to reclaim memory from a backgrounded app — there is no way for an Activity alone to
 * prevent that. Moving WebView ownership into a {@link Service} that calls
 * {@link #startForeground(int, Notification)} tells Android "this process is doing something the
 * user actively cares about right now", which makes the process a much lower priority target for
 * the low-memory killer, and keeps the WebView object (and therefore its in-memory JS state, open
 * connections, etc.) alive independently of whether {@link MainActivity} is currently on screen.
 *
 * <p>{@link MainActivity} attaches to and detaches from this WebView as it starts/stops, but never
 * destroys it and never stops this service just because it was backgrounded. The service is only
 * ever stopped by an explicit user action: the notification's "Stop" action, or "Close session"
 * in the app's overflow menu (see {@link MainActivity}).
 *
 * <h2>Honest limitation</h2>
 *
 * This dramatically improves survival odds but is <b>not a guarantee</b>: under severe,
 * system-wide memory pressure Android can still kill any process, foreground service or not, and
 * some OEM battery managers (Samsung, Xiaomi, etc.) apply their own, more aggressive policies on
 * top of stock Android. See README.md for the full explanation and the battery-optimization
 * exemption this app requests to mitigate it.
 */
public class ArenaSessionService extends Service {

    private static final String TAG = "ArenaSessionService";
    private static final String CHANNEL_ID = "arena_session_channel";
    private static final int NOTIFICATION_ID = 1001;

    static final String ACTION_STOP = "com.federal.arenaai.action.STOP_SESSION";
    static final String ACTION_START = "com.federal.arenaai.action.START_SESSION";

    /** Bridges WebView/Chrome events to whichever Activity is currently attached, if any. */
    interface SessionUiCallback {
        void onPageStarted(String url);
        void onPageFinished(String url);
        void onLoadError(String url, int errorCode, String description);
        void onProgressChanged(int progress);
        void onReceivedTitle(String title);
        boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback,
                                   WebChromeClient.FileChooserParams fileChooserParams);
        void onPermissionRequest(PermissionRequest request);
    }

    /** Local binder handed to {@link MainActivity} via {@code bindService}. */
    class SessionBinder extends Binder {
        ArenaSessionService getService() {
            return ArenaSessionService.this;
        }
    }

    private final SessionBinder binder = new SessionBinder();

    @Nullable
    private WebView webView;
    @Nullable
    private SessionUiCallback uiCallback;
    private SessionStateStore stateStore;
    private boolean webViewInitialized = false;

    @Override
    public void onCreate() {
        super.onCreate();
        stateStore = new SessionStateStore(this);
        createNotificationChannel();
        Log.i(TAG, "ArenaSessionService created (pid=" + android.os.Process.myPid() + ")");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            Log.i(TAG, "Explicit stop requested by the user.");
            stopSession();
            return START_NOT_STICKY;
        }

        // Always (re)promote to foreground immediately: the service must post the ongoing
        // notification within the OS-enforced window after startForegroundService() is called,
        // or the system will throw and kill the app. ServiceCompat.startForeground lets us pass
        // the FOREGROUND_SERVICE_TYPE_SPECIAL_USE type explicitly (recommended by Google's
        // migration guide for Android 14+) while still working unmodified on older API levels,
        // where the type parameter is simply ignored.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, buildNotification());
        }
        ensureWebViewCreated();
        stateStore.setSessionActive(true);

        // START_STICKY: if the OS kills this service under memory pressure, ask it to recreate
        // the service (without redelivering the original Intent) once resources free up again.
        // This is a best-effort recovery aid, not a guarantee — see the class-level javadoc.
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        ensureWebViewCreated();
        return binder;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "ArenaSessionService destroyed.");
        stateStore.setSessionActive(false);
        destroyWebView();
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // Swiping the app away from Recents must NOT stop the session — that would defeat the
        // whole point of this service. Explicitly do nothing here so the service (and the
        // notification) keep running until the user stops it on purpose.
        super.onTaskRemoved(rootIntent);
        Log.i(TAG, "Task removed from Recents; keeping the session alive (by design).");
    }

    // -------------------------------------------------------------------------------------
    // WebView lifecycle
    // -------------------------------------------------------------------------------------

    @SuppressWarnings("SetJavaScriptEnabled")
    private void ensureWebViewCreated() {
        if (webViewInitialized) {
            return;
        }
        webViewInitialized = true;

        // Deliberately constructed with the application Context (not an Activity Context) so
        // this WebView's lifetime is tied to the process/service, not to any single Activity
        // instance. This is what allows it to keep running headless while MainActivity is
        // destroyed/backgrounded.
        webView = new WebView(getApplicationContext());
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setSupportMultipleWindows(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setUserAgentString(settings.getUserAgentString() + " ArenaAI-Android/1.0");

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new ArenaWebViewClient(this, new ArenaWebViewClient.Listener() {
            @Override
            public void onPageStarted(String url) {
                stateStore.setLastUrl(url);
                if (uiCallback != null) uiCallback.onPageStarted(url);
            }

            @Override
            public void onPageFinished(String url) {
                stateStore.setLastUrl(url);
                if (uiCallback != null) uiCallback.onPageFinished(url);
            }

            @Override
            public void onLoadError(String failingUrl, int errorCode, String description) {
                Log.w(TAG, "Load error [" + errorCode + "] " + description + " for " + failingUrl);
                if (uiCallback != null) uiCallback.onLoadError(failingUrl, errorCode, description);
            }
        }));

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                super.onProgressChanged(view, newProgress);
                if (uiCallback != null) uiCallback.onProgressChanged(newProgress);
            }

            @Override
            public void onReceivedTitle(WebView view, String title) {
                super.onReceivedTitle(view, title);
                if (title != null && !title.isEmpty() && uiCallback != null) {
                    uiCallback.onReceivedTitle(title);
                }
            }

            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback,
                                              FileChooserParams fileChooserParams) {
                if (uiCallback != null) {
                    return uiCallback.onShowFileChooser(webView, filePathCallback, fileChooserParams);
                }
                return false;
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                if (uiCallback != null) {
                    uiCallback.onPermissionRequest(request);
                } else {
                    // No Activity is currently attached to approve this on the user's behalf;
                    // deny by default rather than silently granting camera/mic access.
                    request.deny();
                }
            }
        });

        String startUrl = stateStore.getLastUrl();
        Log.i(TAG, "Loading initial URL: " + startUrl);
        webView.loadUrl(startUrl);
    }

    private void destroyWebView() {
        if (webView != null) {
            ViewGroup parent = (ViewGroup) webView.getParent();
            if (parent != null) {
                parent.removeView(webView);
            }
            webView.stopLoading();
            webView.loadUrl("about:blank");
            webView.clearHistory();
            webView.removeAllViews();
            webView.destroy();
            webView = null;
        }
        webViewInitialized = false;
    }

    // -------------------------------------------------------------------------------------
    // Public API consumed by MainActivity
    // -------------------------------------------------------------------------------------

    @Nullable
    WebView getWebView() {
        ensureWebViewCreated();
        return webView;
    }

    void setUiCallback(@Nullable SessionUiCallback callback) {
        this.uiCallback = callback;
    }

    String getCurrentUrl() {
        if (webView != null && webView.getUrl() != null) {
            return webView.getUrl();
        }
        return stateStore.getLastUrl();
    }

    void reload() {
        if (webView != null) {
            webView.reload();
        }
    }

    boolean canGoBack() {
        return webView != null && webView.canGoBack();
    }

    void goBack() {
        if (webView != null) {
            webView.goBack();
        }
    }

    /** Explicit, user-initiated shutdown: stops the foreground notification and this service. */
    void stopSession() {
        stateStore.setSessionActive(false);
        stopForeground(true);
        stopSelf();
    }

    // -------------------------------------------------------------------------------------
    // Notification
    // -------------------------------------------------------------------------------------

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Arena AI session",
                    NotificationManager.IMPORTANCE_LOW // low priority: no sound/heads-up popup
            );
            channel.setDescription("Keeps your Arena AI session alive in the background.");
            channel.setShowBadge(false);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification() {
        Intent openAppIntent = new Intent(this, MainActivity.class);
        openAppIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pendingIntentFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent contentIntent = PendingIntent.getActivity(
                this, 0, openAppIntent, pendingIntentFlags);

        Intent stopIntent = new Intent(this, ArenaSessionService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this, 0, stopIntent, pendingIntentFlags);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Arena AI is running")
                .setContentText("Your session stays active in the background. Tap to open, or Stop to end it.")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setContentIntent(contentIntent)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
                .build();
    }
}
