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

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.util.Log;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

/**
 * Keeps navigation confined to the arena.ai domain, opening anything else (other sites,
 * mailto:/tel: links, etc.) in the user's default browser/app instead of loading it inside this
 * app's WebView. Also drives the loading indicator and error UI callbacks in {@link Listener}.
 */
class ArenaWebViewClient extends WebViewClient {

    private static final String TAG = "ArenaWebViewClient";

    /** The only host this app is allowed to render inside its own WebView. */
    private static final String ALLOWED_HOST = "arena.ai";

    interface Listener {
        void onPageStarted(String url);
        void onPageFinished(String url);
        void onLoadError(String failingUrl, int errorCode, String description);
    }

    private final Context appContext;
    private final Listener listener;

    ArenaWebViewClient(Context context, Listener listener) {
        this.appContext = context.getApplicationContext();
        this.listener = listener;
    }

    private static boolean isArenaHost(@Nullable String host) {
        if (host == null) {
            return false;
        }
        String h = host.toLowerCase(java.util.Locale.US);
        return h.equals(ALLOWED_HOST) || h.endsWith("." + ALLOWED_HOST);
    }

    private boolean handleUri(Uri uri) {
        if (uri == null) {
            return false;
        }
        String scheme = uri.getScheme();
        boolean isHttp = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);

        if (isHttp && isArenaHost(uri.getHost())) {
            // Stay inside the app's own WebView.
            return false;
        }

        // Anything else (external domains, mailto:, tel:, intent:, market:, etc.) is handed off
        // to the system, which opens the user's default browser or the appropriate app.
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            appContext.startActivity(intent);
        } catch (Exception e) {
            Log.w(TAG, "No app available to handle external link: " + uri, e);
        }
        return true;
    }

    @RequiresApi(Build.VERSION_CODES.N)
    @Override
    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        return handleUri(request.getUrl());
    }

    @SuppressWarnings("deprecation")
    @Override
    public boolean shouldOverrideUrlLoading(WebView view, String url) {
        // Only reached on API < 24; the WebResourceRequest overload above handles modern
        // devices.
        return handleUri(Uri.parse(url));
    }

    @Override
    public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
        super.onPageStarted(view, url, favicon);
        listener.onPageStarted(url);
    }

    @Override
    public void onPageFinished(WebView view, String url) {
        super.onPageFinished(view, url);
        listener.onPageFinished(url);
    }

    @RequiresApi(Build.VERSION_CODES.M)
    @Override
    public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
        super.onReceivedError(view, request, error);
        if (request.isForMainFrame()) {
            listener.onLoadError(request.getUrl().toString(), error.getErrorCode(),
                    String.valueOf(error.getDescription()));
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
        super.onReceivedError(view, errorCode, description, failingUrl);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            listener.onLoadError(failingUrl, errorCode, description);
        }
    }

    @Override
    public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
        // Never silently proceed past a certificate error: cancel the load and surface it.
        Log.e(TAG, "SSL error loading arena.ai: " + error);
        handler.cancel();
        listener.onLoadError(error.getUrl(), -1, "SSL certificate error");
    }
}
