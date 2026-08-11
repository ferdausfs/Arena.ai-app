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
import android.content.SharedPreferences;

/**
 * Tiny SharedPreferences wrapper used for the "best effort" state restoration described in the
 * README: if Android kills this process under memory pressure, the WebView's own on-disk storage
 * (cookies, localStorage, IndexedDB) already survives the kill because it is written to disk by
 * the WebView engine itself. The one thing that is NOT persisted anywhere else is which page the
 * user was on, so we remember that here and use it to restore the session to the same place
 * (rather than always the arena.ai homepage) the next time the process starts.
 */
final class SessionStateStore {

    private static final String PREFS_NAME = "arena_session_state";
    private static final String KEY_LAST_URL = "last_url";
    private static final String KEY_BATTERY_DIALOG_SHOWN = "battery_dialog_shown";
    private static final String KEY_SESSION_ACTIVE = "session_active";

    static final String DEFAULT_URL = "https://arena.ai/";

    private final SharedPreferences prefs;

    SessionStateStore(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    String getLastUrl() {
        return prefs.getString(KEY_LAST_URL, DEFAULT_URL);
    }

    void setLastUrl(String url) {
        if (url == null || url.isEmpty()) {
            return;
        }
        prefs.edit().putString(KEY_LAST_URL, url).apply();
    }

    boolean hasShownBatteryDialog() {
        return prefs.getBoolean(KEY_BATTERY_DIALOG_SHOWN, false);
    }

    void setBatteryDialogShown() {
        prefs.edit().putBoolean(KEY_BATTERY_DIALOG_SHOWN, true).apply();
    }

    boolean isSessionActive() {
        return prefs.getBoolean(KEY_SESSION_ACTIVE, false);
    }

    void setSessionActive(boolean active) {
        prefs.edit().putBoolean(KEY_SESSION_ACTIVE, active).apply();
    }
}
