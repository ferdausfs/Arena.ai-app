package com.federal.arenaai;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.splashscreen.SplashScreen;

/**
 * The window onto the live session.
 *
 * <p>This Activity deliberately owns very little. It starts {@link ArenaSessionService}, borrows
 * the process-wide {@link ArenaWebSession} while it is on screen, and hands it back when it is
 * not. Destroying this Activity — Back, rotation, the system reclaiming the window — does not
 * touch the page: the WebView is not a child of this Activity's lifecycle, only a guest in its
 * view hierarchy.
 *
 * <p>The Activity is also where everything that genuinely needs a window lives: the loading
 * indicator, the error screen, runtime permission prompts, the file chooser, fullscreen video and
 * the battery-optimization dialog.
 */
public final class MainActivity extends AppCompatActivity implements ArenaWebSession.Host {

    private static final String TAG = "ArenaMainActivity";

    /** Broadcast by the service when the user stops the session, so this Activity can close. */
    public static final String ACTION_SESSION_STOPPED = "com.federal.arenaai.action.SESSION_STOPPED";

    private SessionStore mStore;

    private FrameLayout mWebViewContainer;
    private ProgressBar mProgressBar;
    private View mErrorView;
    private TextView mErrorDetail;
    private FrameLayout mFullscreenContainer;

    /** The view supplied by the page for HTML5 fullscreen (video), while one is showing. */
    @Nullable
    private View mCustomView;
    @Nullable
    private WebChromeClient.CustomViewCallback mCustomViewCallback;
    private int mOriginalOrientation;

    /** Pending {@code <input type="file">} result, while a chooser is open. */
    @Nullable
    private ValueCallback<Uri[]> mFilePathCallback;

    private ActivityResultLauncher<Intent> mFileChooserLauncher;
    private ActivityResultLauncher<String> mNotificationPermissionLauncher;

    /** True once the page has finished loading at least once, so the splash can be dismissed. */
    private boolean mReadyToDraw;

    private final BroadcastReceiver mSessionStoppedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_SESSION_STOPPED.equals(intent.getAction())) {
                Log.i(TAG, "Session stopped by the user; closing the Activity.");
                finishAndRemoveTask();
            }
        }
    };

    // -------------------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------------------

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        // Must be called before super.onCreate(). The splash stays up until the page has painted
        // (or a load has failed), so the user never sees an empty window.
        SplashScreen splashScreen = SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);

        mStore = new SessionStore(this);
        setContentView(R.layout.activity_main);

        mWebViewContainer = findViewById(R.id.web_view_container);
        mProgressBar = findViewById(R.id.progress_bar);
        mErrorView = findViewById(R.id.error_view);
        mErrorDetail = findViewById(R.id.error_detail);
        mFullscreenContainer = findViewById(R.id.fullscreen_container);
        findViewById(R.id.error_retry).setOnClickListener(v -> retry());

        // If a session already exists (the app was merely re-opened), there is nothing to wait
        // for — show it immediately instead of flashing the splash again.
        mReadyToDraw = ArenaWebSession.isAlive();
        splashScreen.setKeepOnScreenCondition(() -> !mReadyToDraw);

        registerLaunchers();
        setupBackHandling();

        ContextCompat.registerReceiver(this, mSessionStoppedReceiver,
                new IntentFilter(ACTION_SESSION_STOPPED), ContextCompat.RECEIVER_NOT_EXPORTED);

        // Start the service first: it is what owns the session, and starting it from a visible
        // Activity is always permitted.
        ArenaSessionService.start(this);

        maybeRequestNotificationPermission();
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // launchMode="singleTask": re-launches and deep links arrive here instead of creating a
        // second Activity, so the running session is never duplicated or restarted.
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onStart() {
        super.onStart();
        ArenaWebSession session = ArenaWebSession.getInstance(this);
        session.attachTo(this, mWebViewContainer);
        session.ensureLoaded();
        // A load that failed while the Activity was away must still show its error screen.
        if (session.hasLoadError()) {
            showError(getString(R.string.error_generic_detail));
        } else {
            hideError();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Asked here rather than in onCreate() so it never collides with the notification
        // permission dialog, which the user sees first.
        if (!shouldDeferBatteryPrompt()) {
            BatteryOptimizationHelper.maybePrompt(this, mStore);
        }
    }

    @Override
    protected void onStop() {
        // Leave fullscreen video cleanly; a custom view must never outlive the window.
        if (mCustomView != null) {
            onHideCustomView();
        }
        // Hand the WebView back to the service. The page keeps running.
        ArenaWebSession session = ArenaWebSession.peek();
        if (session != null && session.isAttached()) {
            session.detach();
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(mSessionStoppedReceiver);
        // Deliberately NOT destroying the session here: that is the whole point of this app. The
        // session ends only through the user's explicit stop action, handled by the service.
        super.onDestroy();
    }

    // -------------------------------------------------------------------------------------
    // Intent handling
    // -------------------------------------------------------------------------------------

    private void handleIntent(@Nullable Intent intent) {
        if (intent == null) {
            return;
        }
        Uri data = intent.getData();
        if (data == null) {
            // Plain launcher tap: keep showing whatever the session is already on.
            return;
        }
        if (!UrlPolicy.shouldHandleInternally(data.getScheme(), data.getHost())) {
            Log.w(TAG, "Ignoring deep link outside the allowed hosts: " + data);
            return;
        }
        Log.i(TAG, "Following deep link: " + data);
        ArenaWebSession.getInstance(this).loadUrl(data.toString());
        // Consume it, so a configuration change does not re-navigate the session.
        intent.setData(null);
        setIntent(intent);
    }

    // -------------------------------------------------------------------------------------
    // Back handling
    // -------------------------------------------------------------------------------------

    private void setupBackHandling() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (mCustomView != null) {
                    onHideCustomView();
                    return;
                }
                ArenaWebSession session = ArenaWebSession.peek();
                if (session != null && session.canGoBack()) {
                    session.goBack();
                    return;
                }
                // Nothing left in the page's history: behave like the Home button rather than
                // finishing. The session stays live and the app returns instantly next time.
                moveTaskToBack(true);
            }
        });
    }

    // -------------------------------------------------------------------------------------
    // Menu
    // -------------------------------------------------------------------------------------

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_reload) {
            retry();
            return true;
        }
        if (id == R.id.action_battery) {
            BatteryOptimizationHelper.promptNow(this, mStore);
            return true;
        }
        if (id == R.id.action_stop_session) {
            confirmStopSession();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /** Ending the session throws away the live page, so it is confirmed first. */
    private void confirmStopSession() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.stop_dialog_title)
                .setMessage(R.string.stop_dialog_message)
                .setPositiveButton(R.string.stop_dialog_confirm, (dialog, which) ->
                        ArenaSessionService.stop(this))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void retry() {
        hideError();
        mProgressBar.setVisibility(View.VISIBLE);
        ArenaWebSession.getInstance(this).reload();
    }

    // -------------------------------------------------------------------------------------
    // Runtime permissions
    // -------------------------------------------------------------------------------------

    private void registerLaunchers() {
        mFileChooserLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    ValueCallback<Uri[]> callback = mFilePathCallback;
                    mFilePathCallback = null;
                    if (callback == null) {
                        return;
                    }
                    // The page must always get an answer, even a null one, or the file input
                    // stays disabled forever.
                    callback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(
                            result.getResultCode(), result.getData()));
                });

        mNotificationPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> {
                    Log.i(TAG, "POST_NOTIFICATIONS granted=" + granted);
                    if (!granted) {
                        // Denial is survivable: the service still runs and still keeps the
                        // session alive; the ongoing notification is simply hidden by the system.
                        showNotificationRationale();
                    }
                    BatteryOptimizationHelper.maybePrompt(this, mStore);
                });
    }

    /**
     * Requests POST_NOTIFICATIONS, required from Android 13 for the foreground service's ongoing
     * notification to be visible. Asked once; after that the user's decision stands.
     */
    private void maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        if (mStore.wasNotificationPermissionAsked()) {
            return;
        }
        mStore.setNotificationPermissionAsked();
        mNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
    }

    /** True while a permission dialog is (or is about to be) on screen. */
    private boolean shouldDeferBatteryPrompt() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return false;
        }
        // On the very first launch the notification prompt is shown first; the battery prompt is
        // then chained off its result so the user is never shown two dialogs at once.
        return ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
                && !mStore.wasNotificationPermissionAsked();
    }

    private void showNotificationRationale() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.notification_rationale_title)
                .setMessage(R.string.notification_rationale_message)
                .setPositiveButton(R.string.action_ok, null)
                .show();
    }

    // -------------------------------------------------------------------------------------
    // ArenaWebSession.Host
    // -------------------------------------------------------------------------------------

    @NonNull
    @Override
    public android.app.Activity getActivity() {
        return this;
    }

    @Override
    public void onProgressChanged(int progress) {
        mProgressBar.setProgress(progress);
        mProgressBar.setVisibility(progress >= 100 ? View.GONE : View.VISIBLE);
        if (progress >= 100) {
            markReadyToDraw();
        }
    }

    @Override
    public void onPageStarted(@NonNull String url) {
        hideError();
        mProgressBar.setVisibility(View.VISIBLE);
    }

    @Override
    public void onPageFinished(@NonNull String url) {
        mProgressBar.setVisibility(View.GONE);
        markReadyToDraw();
    }

    @Override
    public void onPageLoadError(int errorCode, @NonNull String description, @NonNull String url) {
        mProgressBar.setVisibility(View.GONE);
        showError(getString(R.string.error_detail_format, description, errorCode));
        markReadyToDraw();
    }

    @Override
    public boolean onShowFileChooser(@NonNull ValueCallback<Uri[]> callback,
                                     @NonNull WebChromeClient.FileChooserParams params) {
        // Cancel any previous, still-pending request before starting a new one.
        if (mFilePathCallback != null) {
            mFilePathCallback.onReceiveValue(null);
        }
        mFilePathCallback = callback;
        try {
            mFileChooserLauncher.launch(params.createIntent());
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "No file chooser available", e);
            mFilePathCallback = null;
            return false;
        }
    }

    @Override
    public void onShowCustomView(@NonNull View view,
                                 @NonNull WebChromeClient.CustomViewCallback callback) {
        if (mCustomView != null) {
            // Only one fullscreen view can exist at a time.
            callback.onCustomViewHidden();
            return;
        }
        mCustomView = view;
        mCustomViewCallback = callback;
        mOriginalOrientation = getRequestedOrientation();

        mFullscreenContainer.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mFullscreenContainer.setVisibility(View.VISIBLE);
        mWebViewContainer.setVisibility(View.GONE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }
    }

    @Override
    public void onHideCustomView() {
        if (mCustomView == null) {
            return;
        }
        mFullscreenContainer.removeView(mCustomView);
        mFullscreenContainer.setVisibility(View.GONE);
        mWebViewContainer.setVisibility(View.VISIBLE);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setRequestedOrientation(mOriginalOrientation);
        if (getSupportActionBar() != null) {
            getSupportActionBar().show();
        }
        mCustomView = null;
        if (mCustomViewCallback != null) {
            mCustomViewCallback.onCustomViewHidden();
            mCustomViewCallback = null;
        }
    }

    // -------------------------------------------------------------------------------------
    // Error UI
    // -------------------------------------------------------------------------------------

    private void showError(@NonNull String detail) {
        mErrorDetail.setText(detail);
        mErrorView.setVisibility(View.VISIBLE);
        mWebViewContainer.setVisibility(View.GONE);
    }

    private void hideError() {
        mErrorView.setVisibility(View.GONE);
        mWebViewContainer.setVisibility(View.VISIBLE);
    }

    private void markReadyToDraw() {
        if (!mReadyToDraw) {
            mReadyToDraw = true;
            // Force the splash's keep-on-screen condition to be re-evaluated on the next frame.
            View content = findViewById(android.R.id.content);
            if (content != null) {
                content.invalidate();
            }
        }
    }
}
