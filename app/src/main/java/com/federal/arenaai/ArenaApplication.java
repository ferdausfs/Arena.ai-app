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

import android.util.Log;

/**
 * Application entry point.
 *
 * <p>This class does not hold any UI or WebView state itself (that responsibility belongs to
 * {@link ArenaSessionService}, which outlives any single {@link MainActivity} instance). Its
 * only job here is to log memory-pressure signals for diagnostics: if Android ever kills this
 * process under extreme memory pressure, these log lines (visible via {@code adb logcat} before
 * the kill, or in a bug report) are the "best effort" visibility this app can offer — no app,
 * TWA, WebView-based or otherwise, can prevent the OS from reclaiming memory when the system is
 * critically low on it. See README.md for the full, honest explanation of this limit.
 */
public class ArenaApplication extends android.app.Application {

    private static final String TAG = "ArenaApplication";

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "Arena AI process created (pid=" + android.os.Process.myPid() + ")");
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_RUNNING_CRITICAL) {
            Log.w(TAG, "onTrimMemory(" + level + "): system memory is critically low. "
                    + "Android may kill this process soon regardless of the foreground service; "
                    + "the last-known URL has already been persisted by ArenaSessionService as a "
                    + "best-effort recovery measure.");
        } else if (level >= TRIM_MEMORY_BACKGROUND) {
            Log.i(TAG, "onTrimMemory(" + level + "): app is in the background and the system is "
                    + "reclaiming memory from cached processes.");
        }
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        Log.w(TAG, "onLowMemory(): the whole system is low on memory. This process may be "
                + "killed by Android at any time; this is an OS-level guarantee that cannot be "
                + "fully bypassed by any app.");
    }
}
