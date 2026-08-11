package com.federal.arenaai;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity implements ArenaWebViewManager.Callback {
    private static final int REQUEST_POST_NOTIFICATIONS = 33;
    private static final int MENU_RELOAD = 1;
    private static final int MENU_OPEN_BROWSER = 2;
    private static final int MENU_CLOSE_SESSION = 3;

    private FrameLayout webContainer;
    private LinearLayout errorView;
    private TextView errorDescription;
    private ProgressBar progressBar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureWindow();
        buildContentView();
        ArenaWebViewManager.initialize(getApplicationContext());
        requestNotificationPermissionIfNeeded();
        ArenaSessionService.start(this);
        attachWebView();
        maybePromptForBatteryOptimizationExemption();
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        ArenaSessionService.start(this);
        attachWebView();
        handleIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        ArenaSessionService.start(this);
        attachWebView();
    }

    @Override
    protected void onDestroy() {
        ArenaWebViewManager.clearCallback(this);
        ArenaWebViewManager.detachFromParent();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (ArenaWebViewManager.canGoBack()) {
            ArenaWebViewManager.goBack();
        } else {
            moveTaskToBack(true);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(Menu.NONE, MENU_RELOAD, Menu.NONE, R.string.action_reload)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        menu.add(Menu.NONE, MENU_OPEN_BROWSER, Menu.NONE, R.string.action_open_browser)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        menu.add(Menu.NONE, MENU_CLOSE_SESSION, Menu.NONE, R.string.action_close_session)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == MENU_RELOAD) {
            showError(false, null);
            ArenaWebViewManager.reload(this);
            return true;
        } else if (item.getItemId() == MENU_OPEN_BROWSER) {
            openInBrowser(ArenaWebViewManager.currentUrl());
            return true;
        } else if (item.getItemId() == MENU_CLOSE_SESSION) {
            ArenaSessionService.stop(this);
            finishAndRemoveTaskCompat();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onPageStarted(String url) {
        showError(false, null);
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setProgress(0);
    }

    @Override
    public void onPageFinished(String url) {
        progressBar.setProgress(100);
        progressBar.setVisibility(View.GONE);
    }

    @Override
    public void onProgressChanged(int progress) {
        progressBar.setProgress(progress);
        progressBar.setVisibility(progress >= 100 ? View.GONE : View.VISIBLE);
    }

    @Override
    public void onMainFrameError(String description) {
        progressBar.setVisibility(View.GONE);
        showError(true, description);
    }

    @Override
    public void onRenderProcessGone() {
        showError(true, "Android reclaimed the WebView renderer. Reload to restore the session.");
    }

    private void configureWindow() {
        Window window = getWindow();
        window.setStatusBarColor(getColorCompat(R.color.arena_primary_dark));
        window.setNavigationBarColor(Color.BLACK);
    }

    private void buildContentView() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(getColorCompat(R.color.arena_background));

        webContainer = new FrameLayout(this);
        root.addView(webContainer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setVisibility(View.GONE);
        FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(3),
                Gravity.TOP
        );
        root.addView(progressBar, progressParams);

        errorView = new LinearLayout(this);
        errorView.setOrientation(LinearLayout.VERTICAL);
        errorView.setGravity(Gravity.CENTER);
        errorView.setPadding(dp(32), dp(32), dp(32), dp(32));
        errorView.setBackgroundColor(getColorCompat(R.color.arena_background));
        errorView.setVisibility(View.GONE);

        TextView title = new TextView(this);
        title.setText(R.string.error_title);
        title.setTextColor(getColorCompat(R.color.arena_text));
        title.setTextSize(20);
        title.setGravity(Gravity.CENTER);
        errorView.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        errorDescription = new TextView(this);
        errorDescription.setText(R.string.error_message);
        errorDescription.setTextColor(getColorCompat(R.color.arena_muted_text));
        errorDescription.setTextSize(15);
        errorDescription.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams descriptionParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        descriptionParams.setMargins(0, dp(12), 0, dp(20));
        errorView.addView(errorDescription, descriptionParams);

        Button reloadButton = new Button(this);
        reloadButton.setText(R.string.action_reload);
        reloadButton.setOnClickListener(v -> {
            showError(false, null);
            ArenaWebViewManager.reload(this);
        });
        errorView.addView(reloadButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        root.addView(errorView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        setContentView(root);
    }

    private void attachWebView() {
        WebView webView = ArenaWebViewManager.getOrCreate(this);
        ArenaWebViewManager.setCallback(this);
        ArenaWebViewManager.detachFromParent();
        webContainer.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        webView.requestFocus();
    }

    private void handleIntent(Intent intent) {
        if (intent == null || intent.getData() == null) {
            return;
        }
        Uri data = intent.getData();
        if (isArenaUrl(data)) {
            WebView webView = ArenaWebViewManager.getOrCreate(this);
            webView.loadUrl(data.toString());
        } else {
            openInBrowser(data.toString());
        }
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return;
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_POST_NOTIFICATIONS);
    }

    private void maybePromptForBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager == null || powerManager.isIgnoringBatteryOptimizations(getPackageName())) {
            return;
        }
        if (getPreferences(MODE_PRIVATE).getBoolean("battery_prompt_shown_once", false)) {
            return;
        }
        getPreferences(MODE_PRIVATE).edit().putBoolean("battery_prompt_shown_once", true).apply();

        new AlertDialog.Builder(this)
                .setTitle(R.string.battery_dialog_title)
                .setMessage(R.string.battery_dialog_message)
                .setPositiveButton(R.string.battery_dialog_open_settings, (dialog, which) -> openBatteryOptimizationSettings())
                .setNegativeButton(R.string.battery_dialog_later, null)
                .show();
    }

    private void openBatteryOptimizationSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }

        Intent requestIntent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:" + getPackageName()));
        try {
            startActivity(requestIntent);
            return;
        } catch (ActivityNotFoundException ignored) {
            // Some OEM builds omit this specific request screen. Fall through to the generic page.
        }

        try {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.battery_dialog_message, Toast.LENGTH_LONG).show();
        }
    }

    private void showError(boolean show, String description) {
        errorView.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            String message = getString(R.string.error_message);
            if (description != null && !description.trim().isEmpty()) {
                message = message + "\n\n" + description;
            }
            errorDescription.setText(message);
        }
    }

    private void openInBrowser(String url) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, url, Toast.LENGTH_LONG).show();
        }
    }

    private boolean isArenaUrl(Uri uri) {
        if (uri == null) {
            return false;
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        return "https".equalsIgnoreCase(scheme)
                && host != null
                && ("arena.ai".equalsIgnoreCase(host) || host.toLowerCase().endsWith(".arena.ai"));
    }

    private int getColorCompat(int resId) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return getColor(resId);
        }
        return getResources().getColor(resId);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void finishAndRemoveTaskCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            finishAndRemoveTask();
        } else {
            finish();
        }
    }
}
