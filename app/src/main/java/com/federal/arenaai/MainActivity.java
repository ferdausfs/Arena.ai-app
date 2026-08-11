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

import android.Manifest;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

/**
 * Hosts the visible UI: a full-screen {@link WebView} showing https://arena.ai, a progress
 * indicator, and a minimal error view.
 *
 * <p>Ownership note: this Activity does NOT create or own the WebView instance. It starts and
 * binds to {@link ArenaSessionService}, which owns the single long-lived WebView, and simply
 * attaches that WebView into its layout while visible. When the Activity stops (backgrounded,
 * rotated away, task-switched), the WebView is detached from this Activity's view hierarchy but
 * is NOT destroyed and keeps running — {@link ArenaSessionService} keeps it alive independently
 * of this Activity's lifecycle, which is the whole point of this architecture.
 */
public class MainActivity extends AppCompatActivity implements
        ArenaSessionService.SessionUiCallback {

    private FrameLayout webViewContainer;
    private ProgressBar progressBar;
    private View errorView;
    private TextView errorText;

    @Nullable
    private ArenaSessionService session;
    private boolean bound = false;

    @Nullable
    private ValueCallback<Uri[]> pendingFileCallback;

    private final ActivityResultLauncher<String> notificationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                // Whether granted or not, we still start the service: on API < 33 the permission
                // does not exist, and on API 33+ a denied POST_NOTIFICATIONS permission means the
                // ongoing notification simply won't be visible, but the foreground service (and
                // therefore the background-persistence benefit) still works.
                startAndBindSessionService();
            });

    private final ActivityResultLauncher<Intent> fileChooserLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (pendingFileCallback == null) {
                    return;
                }
                Uri[] results = null;
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri data = result.getData().getData();
                    if (data != null) {
                        results = new Uri[]{data};
                    }
                }
                pendingFileCallback.onReceiveValue(results);
                pendingFileCallback = null;
            });

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            session = ((ArenaSessionService.SessionBinder) binder).getService();
            session.setUiCallback(MainActivity.this);
            attachWebView();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            session = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Swap the transient cold-start "launch theme" (splash background) for the real theme
        // now that the Activity is about to draw its actual content.
        setTheme(R.style.Theme_ArenaAi);
        setContentView(R.layout.activity_main);

        webViewContainer = findViewById(R.id.web_view_container);
        progressBar = findViewById(R.id.progress_bar);
        errorView = findViewById(R.id.error_view);
        errorText = findViewById(R.id.error_text);

        findViewById(R.id.error_retry_button).setOnClickListener(v -> {
            errorView.setVisibility(View.GONE);
            if (session != null) {
                session.reload();
            }
        });

        requestNotificationPermissionIfNeeded();
        BatteryOptimizationHelper.maybeShowDialog(this, /* onlyIfNotShownBefore= */ true);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!bound) {
            bindService(new Intent(this, ArenaSessionService.class), serviceConnection, Context.BIND_AUTO_CREATE);
            bound = true;
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Detach the WebView from this Activity's view hierarchy and unbind, but the service
        // keeps running (it was started with startForegroundService, not just bound) so the
        // WebView instance itself is untouched and keeps living inside ArenaSessionService.
        detachWebView();
        if (session != null) {
            session.setUiCallback(null);
        }
        if (bound) {
            unbindService(serviceConnection);
            bound = false;
        }
    }

    @Override
    public void onBackPressed() {
        if (session != null && session.canGoBack()) {
            session.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_reload) {
            if (session != null) session.reload();
            return true;
        } else if (id == R.id.action_battery) {
            BatteryOptimizationHelper.maybeShowDialog(this, /* onlyIfNotShownBefore= */ false);
            if (BatteryOptimizationHelper.isIgnoringBatteryOptimizations(this)) {
                Toast.makeText(this, "Battery optimization is already disabled for Arena AI.", Toast.LENGTH_SHORT).show();
            }
            return true;
        } else if (id == R.id.action_close_session) {
            confirmCloseSession();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void confirmCloseSession() {
        new AlertDialog.Builder(this)
                .setTitle("Close Arena AI session?")
                .setMessage("This stops the background service and ends your session. You'll need "
                        + "to reopen the app to use Arena AI again.")
                .setPositiveButton("Close", (dialog, which) -> {
                    if (session != null) {
                        session.stopSession();
                    } else {
                        startService(new Intent(this, ArenaSessionService.class)
                                .setAction(ArenaSessionService.ACTION_STOP));
                    }
                    finishAndRemoveTask();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // -------------------------------------------------------------------------------------
    // Service lifecycle plumbing
    // -------------------------------------------------------------------------------------

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
        } else {
            startAndBindSessionService();
        }
    }

    private void startAndBindSessionService() {
        Intent serviceIntent = new Intent(this, ArenaSessionService.class);
        serviceIntent.setAction(ArenaSessionService.ACTION_START);
        ContextCompat.startForegroundService(this, serviceIntent);
    }

    private void attachWebView() {
        if (session == null) {
            return;
        }
        WebView webView = session.getWebView();
        if (webView == null) {
            return;
        }
        ViewGroup currentParent = (ViewGroup) webView.getParent();
        if (currentParent != null && currentParent != webViewContainer) {
            currentParent.removeView(webView);
        }
        if (webView.getParent() == null) {
            webViewContainer.addView(webView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
    }

    private void detachWebView() {
        if (webViewContainer != null) {
            webViewContainer.removeAllViews();
        }
    }

    // -------------------------------------------------------------------------------------
    // ArenaSessionService.SessionUiCallback
    // -------------------------------------------------------------------------------------

    @Override
    public void onPageStarted(String url) {
        runOnUiThread(() -> {
            errorView.setVisibility(View.GONE);
            progressBar.setVisibility(View.VISIBLE);
        });
    }

    @Override
    public void onPageFinished(String url) {
        runOnUiThread(() -> progressBar.setVisibility(View.GONE));
    }

    @Override
    public void onLoadError(String url, int errorCode, String description) {
        runOnUiThread(() -> {
            progressBar.setVisibility(View.GONE);
            errorText.setText(getString(R.string.load_error_message, description));
            errorView.setVisibility(View.VISIBLE);
        });
    }

    @Override
    public void onProgressChanged(int progress) {
        runOnUiThread(() -> {
            progressBar.setProgress(progress);
            progressBar.setVisibility(progress >= 100 ? View.GONE : View.VISIBLE);
        });
    }

    @Override
    public void onReceivedTitle(String title) {
        runOnUiThread(() -> setTitle(title));
    }

    @Override
    public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback,
                                      WebChromeClient.FileChooserParams fileChooserParams) {
        pendingFileCallback = filePathCallback;
        try {
            Intent intent = fileChooserParams.createIntent();
            fileChooserLauncher.launch(intent);
        } catch (Exception e) {
            pendingFileCallback = null;
            return false;
        }
        return true;
    }

    @Override
    public void onPermissionRequest(PermissionRequest request) {
        // Grant only the resources the page actually asked for (e.g. microphone for voice
        // input), and only after the user has already installed/opened this app — WebView
        // permission requests are separate from, and layered on top of, Android runtime
        // permissions for the underlying hardware.
        runOnUiThread(() -> request.grant(request.getResources()));
    }
}
