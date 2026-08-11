package com.federal.arenaai;

import android.app.Application;
import android.content.ComponentCallbacks2;
import android.os.Build;
import android.util.Log;
import android.webkit.WebView;

import androidx.annotation.NonNull;

/**
 * Application entry point.
 *
 * <p>Two jobs, both of which have to happen before anything else touches a WebView:
 *
 * <ol>
 *   <li>Give the WebView its own data directory suffix when the app runs in more than one
 *       process. Two processes sharing one WebView data directory is a hard crash on API 28+, and
 *       it is the kind of thing that only shows up on a user's phone.
 *   <li>Log memory-pressure callbacks. This app deliberately does <em>not</em> free the WebView
 *       when the system asks for memory — releasing it is exactly the reload the app exists to
 *       prevent — so the trims are recorded instead, which is what makes an eventual OS kill
 *       explainable in a bug report rather than mysterious.
 * </ol>
 */
public final class ArenaApplication extends Application {

    private static final String TAG = "ArenaApplication";

    @Override
    public void onCreate() {
        super.onCreate();
        applyWebViewDataDirectorySuffix();
        Log.i(TAG, "Arena AI " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE
                + ") starting; target " + BuildConfig.ARENA_START_URL);
    }

    /**
     * Since API 28 a single WebView data directory may only be used by one process. The app runs
     * everything in the default process today, but calling this makes the guarantee explicit and
     * keeps a future multi-process change from crashing on first launch.
     */
    private void applyWebViewDataDirectorySuffix() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return;
        }
        String processName = getProcessName();
        String packageName = getPackageName();
        if (processName == null || processName.equals(packageName)) {
            // Default process: the default data directory is correct.
            return;
        }
        String suffix = processName.replace(packageName, "").replace(":", "_");
        try {
            WebView.setDataDirectorySuffix(suffix);
            Log.i(TAG, "WebView data directory suffix set to '" + suffix + "'");
        } catch (IllegalStateException e) {
            // Already set, or a WebView was already created in this process. Not fatal.
            Log.w(TAG, "Could not set WebView data directory suffix", e);
        }
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        // Recorded, not acted on: see the class comment.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            Log.w(TAG, "onTrimMemory(level=" + describeTrimLevel(level) + "). The session is "
                    + "intentionally retained; if the system needs more it will kill the process.");
        }
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        Log.w(TAG, "onLowMemory(): the device is under memory pressure. The session is retained; "
                + "an OS kill from here is possible and is handled on the next launch by "
                + "resuming at the last URL.");
    }

    @NonNull
    private static String describeTrimLevel(int level) {
        switch (level) {
            case ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW:
                return "RUNNING_LOW";
            case ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL:
                return "RUNNING_CRITICAL";
            case ComponentCallbacks2.TRIM_MEMORY_BACKGROUND:
                return "BACKGROUND";
            case ComponentCallbacks2.TRIM_MEMORY_MODERATE:
                return "MODERATE";
            case ComponentCallbacks2.TRIM_MEMORY_COMPLETE:
                return "COMPLETE";
            default:
                return String.valueOf(level);
        }
    }
}
