package com.federal.arenaai;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Small, synchronous key/value store for everything the app needs to remember across process
 * deaths.
 *
 * <p>This is the "best effort" half of the persistence story. The live page (its JavaScript heap,
 * scroll position, in-flight streams) only survives while the process is alive; that is what the
 * foreground service is for. When Android does eventually kill the process — which it always can
 * under enough memory pressure — the values kept here let the app come back to the same URL, and
 * the WebView's own on-disk storage (cookies, localStorage, IndexedDB) keeps the user signed in.
 */
public final class SessionStore {

    private static final String PREFS_NAME = "arena_session";

    private static final String KEY_LAST_URL = "last_url";
    private static final String KEY_SESSION_REQUESTED = "session_requested";
    private static final String KEY_NOTIFICATION_PERMISSION_ASKED = "notification_permission_asked";
    private static final String KEY_BATTERY_PROMPT_DISMISSED = "battery_prompt_dismissed";
    private static final String KEY_UNCLEAN_SHUTDOWN = "unclean_shutdown";

    private final SharedPreferences mPrefs;

    public SessionStore(@NonNull Context context) {
        mPrefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** The last URL the live session was showing, or the configured start URL. */
    @NonNull
    public String getResumeUrl() {
        String url = mPrefs.getString(KEY_LAST_URL, null);
        return TextUtils.isEmpty(url) ? UrlPolicy.START_URL : url;
    }

    /** Remembers the URL to come back to if the process is killed and restarted. */
    public void setLastUrl(@Nullable String url) {
        if (TextUtils.isEmpty(url) || url.startsWith("about:")) {
            return;
        }
        mPrefs.edit().putString(KEY_LAST_URL, url).apply();
    }

    /** Clears the resume URL so the next launch starts at the site's entry point. */
    public void clearLastUrl() {
        mPrefs.edit().remove(KEY_LAST_URL).apply();
    }

    /**
     * True while the user wants a live session. Set when the session starts, cleared only when the
     * user explicitly stops it. It is what tells a system-restarted service whether it should keep
     * running or shut itself down.
     */
    public boolean isSessionRequested() {
        return mPrefs.getBoolean(KEY_SESSION_REQUESTED, false);
    }

    public void setSessionRequested(boolean requested) {
        // committed synchronously: the service may be killed immediately after this call.
        mPrefs.edit().putBoolean(KEY_SESSION_REQUESTED, requested).commit();
    }

    /**
     * True when the previous run of the process ended without the user stopping the session, i.e.
     * the OS reclaimed the app. Used purely for diagnostics/logging.
     */
    public boolean wasUncleanShutdown() {
        return mPrefs.getBoolean(KEY_UNCLEAN_SHUTDOWN, false);
    }

    public void setUncleanShutdown(boolean unclean) {
        mPrefs.edit().putBoolean(KEY_UNCLEAN_SHUTDOWN, unclean).commit();
    }

    /** POST_NOTIFICATIONS is only requested once; after that the user owns the decision. */
    public boolean wasNotificationPermissionAsked() {
        return mPrefs.getBoolean(KEY_NOTIFICATION_PERMISSION_ASKED, false);
    }

    public void setNotificationPermissionAsked() {
        mPrefs.edit().putBoolean(KEY_NOTIFICATION_PERMISSION_ASKED, true).apply();
    }

    /** True once the user has told the battery-optimization prompt not to come back. */
    public boolean isBatteryPromptDismissed() {
        return mPrefs.getBoolean(KEY_BATTERY_PROMPT_DISMISSED, false);
    }

    public void setBatteryPromptDismissed() {
        mPrefs.edit().putBoolean(KEY_BATTERY_PROMPT_DISMISSED, true).apply();
    }
}
