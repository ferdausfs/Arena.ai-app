/*
 * Copyright 2020 Google Inc.
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

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

/**
 * Launcher Activity for the Arena AI Trusted Web Activity.
 *
 * <p>Besides the stock Bubblewrap behaviour this class implements a "single live session"
 * policy:
 *
 * <ul>
 *   <li>The activity is declared with {@code launchMode="singleTask"} (see AndroidManifest.xml),
 *       so re-launching the app from the launcher icon (or from an external intent) never creates
 *       a second task/activity: the intent is routed to the existing task and, if this activity
 *       instance is still alive, to {@link #onNewIntent(Intent)}.
 *   <li>If a re-launch intent arrives while the TWA is already running (this instance is not the
 *       root of the task and the intent carries no URL), we simply finish this transparent
 *       trampoline instance without firing a second TWA launch. Firing a second launch is what
 *       makes Chrome navigate, i.e. reload the page.
 *   <li>{@link #getLaunchingUrl()} is stable: once the app has been launched, re-launches keep
 *       using the original URL unless an explicit {@code https://arena.ai} deep link is delivered.
 * </ul>
 */
public class LauncherActivity
        extends com.google.androidbrowserhelper.trusted.LauncherActivity {

    private static final String TAG = "ArenaAILauncher";

    /** Whether this instance has launched (or been asked to launch) the TWA. */
    private boolean mTwaLaunchRequested;

    /** Memoized default launch URL, so re-launches never change the original URL. */
    private Uri mDefaultLaunchUrl;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Setting an orientation crashes the app due to the transparent background on Android 8.0
        // Oreo and below. We only set the orientation on Oreo and above. This only affects the
        // splash screen and Chrome will still respect the orientation.
        // See https://github.com/GoogleChromeLabs/bubblewrap/issues/496 for details.
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.O) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT);
        } else {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        }

        // Plain re-entry while the TWA is already running in this task. With launchMode
        // "singleTask" the system creates this instance on top of the existing task instead of
        // creating a new task. There is nothing to launch: the running TWA is already below us
        // (and this launch has already brought the task to the foreground). Finish without
        // firing a second TWA launch, which would make Chrome navigate/reload the page.
        if (isPlainLauncherReentry()) {
            Log.d(TAG, "TWA already running in this task; finishing re-entry instance.");
            finish();
        }
    }

    /**
     * The library launches the TWA from its {@code onCreate()} via
     * {@link #shouldLaunchImmediately()}. Suppress that launch for a plain launcher re-entry:
     * the TWA is already running, and launching again would navigate (reload) the page.
     */
    @Override
    protected boolean shouldLaunchImmediately() {
        if (isPlainLauncherReentry()) {
            return false;
        }
        return super.shouldLaunchImmediately();
    }

    /**
     * With {@code launchMode="singleTask"}, a new intent (launcher icon re-press, external
     * {@code VIEW} intent) that matches this activity is routed to this existing instance via
     * {@code onNewIntent()}, and the system first finishes the activities above it (the browser's
     * TWA activity). We therefore restore the TWA session here instead of letting a duplicate
     * activity or task be created:
     *
     * <ul>
     *   <li>Intent with a URL (external deep link): forward the URL to the TWA so it navigates
     *       there. The single task is preserved — no new activity, no new task.
     *   <li>Intent without a URL (launcher re-press): keep the current state. {@code getIntent()}
     *       is deliberately NOT updated, so {@link #getLaunchingUrl()} keeps returning the
     *       original launch URL, and the TWA session is restored with it.
     * </ul>
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);

        if (intent == null || isFinishing()) {
            return;
        }

        boolean hasData = intent.getData() != null;
        if (hasData) {
            // A real URL was delivered (external link / VIEW intent): forward it to the TWA.
            Log.d(TAG, "onNewIntent with url=" + intent.getData() + "; forwarding to the TWA.");
            setIntent(intent);
        } else {
            // Plain launcher re-press: keep the original intent (and therefore the original
            // launch URL) untouched.
            Log.d(TAG, "onNewIntent without url; restoring TWA with the original URL.");
        }

        if (!hasData && !mTwaLaunchRequested) {
            // No TWA was launched from this instance yet and there is no URL to forward — the
            // onCreate() path is in charge (it will finish this re-entry instance).
            return;
        }

        // The singleTask routing has torn down the TWA activity above this instance, so the
        // session must be restored. Chrome reuses its existing tab/session where possible and
        // the page state (chat history, UI) is preserved unless Chrome's process was killed.
        launchTwa();
    }

    /**
     * Returns the URL the TWA should be launched with.
     *
     * <p>The original launch URL is memoized and never changes: re-launches (launcher icon,
     * recents) keep loading the exact same URL the app was first opened with. An explicit
     * {@code https://arena.ai} deep link delivered via a {@code VIEW} intent is honoured instead.
     */
    @Override
    protected Uri getLaunchingUrl() {
        Uri intentUrl = getIntent().getData();
        if (intentUrl != null && isSupportedLaunchUrl(intentUrl)) {
            // Explicit URL delivered with this launch (cold-start deep link or a deep link
            // forwarded through onNewIntent): launch the TWA at that URL.
            return super.getLaunchingUrl();
        }

        if (mDefaultLaunchUrl == null) {
            // The library returns the DEFAULT_URL manifest metadata when the intent has no data.
            mDefaultLaunchUrl = super.getLaunchingUrl();
        }
        return mDefaultLaunchUrl;
    }

    @Override
    protected void launchTwa() {
        mTwaLaunchRequested = true;
        super.launchTwa();
    }

    /**
     * True when this activity instance was created only to bring an already-running TWA task to
     * the foreground: the task already contains the TWA (we are not its root activity) and the
     * incoming intent carries no URL (or share payload) to act on.
     */
    private boolean isPlainLauncherReentry() {
        if (isTaskRoot()) {
            // Fresh launch of the app (or the previous task no longer exists).
            return false;
        }

        Intent intent = getIntent();
        if (intent == null) {
            return false;
        }

        if (intent.getData() != null) {
            // Deep link: forward it to the running TWA instead of treating it as a re-entry.
            return false;
        }

        String action = intent.getAction();
        if (action != null && (Intent.ACTION_SEND.equals(action)
                || Intent.ACTION_SENDTO.equals(action)
                || Intent.ACTION_SEND_MULTIPLE.equals(action))) {
            // Share-style intent: let the library handle it.
            return false;
        }

        // This app only exposes MAIN/LAUNCHER and https://arena.ai VIEW intent filters, so a
        // data-less, non-share intent in a non-root position can only be a launcher re-entry.
        return true;
    }

    /**
     * Returns true only for URLs this app is allowed to open in the TWA: https URLs on the
     * configured host ({@code arena.ai}). Anything else falls back to the default launch URL so
     * the running session is never yanked to an unverified origin.
     */
    private boolean isSupportedLaunchUrl(Uri url) {
        if (url == null || !"https".equalsIgnoreCase(url.getScheme())) {
            return false;
        }
        String host = url.getHost();
        return host != null && host.equalsIgnoreCase(getString(R.string.hostName));
    }
}
