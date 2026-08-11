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

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;

/**
 * Prompts the user to exempt Arena AI from battery optimizations.
 *
 * <p>A Foreground Service with an ongoing notification is the standard, Play-Store-compliant way
 * to ask Android to keep a process alive. In practice, however, aggressive OEM battery managers
 * (Samsung's "Sleeping apps"/"Deep sleep", Xiaomi's MIUI battery saver, Huawei, OnePlus, etc.)
 * still kill or freeze foreground-service processes far more eagerly than stock Android does.
 * Disabling battery optimization for this app is the single most impactful thing a user can do
 * to make background persistence reliable on those devices. This is still not a 100% guarantee —
 * see README.md for the honest limitations.
 */
final class BatteryOptimizationHelper {

    private static final String TAG = "BatteryOptimization";

    private BatteryOptimizationHelper() {
    }

    static boolean isIgnoringBatteryOptimizations(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return powerManager != null && powerManager.isIgnoringBatteryOptimizations(context.getPackageName());
    }

    /**
     * Shows an explanatory dialog and, if the user agrees, opens the system dialog that lets
     * them exempt Arena AI from battery optimization. This never grants the exemption silently —
     * Android requires explicit user consent for {@code REQUEST_IGNORE_BATTERY_OPTIMIZATIONS}.
     *
     * @param onlyIfNotShownBefore when true (used for the automatic prompt on first launch),
     *                             the dialog is skipped if it was already shown in a previous
     *                             session, so returning users aren't nagged on every launch.
     *                             The manual "Disable battery optimization…" menu item always
     *                             passes false so the user can revisit it on demand.
     */
    static void maybeShowDialog(Context context, boolean onlyIfNotShownBefore) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }
        if (isIgnoringBatteryOptimizations(context)) {
            return;
        }

        SessionStateStore stateStore = new SessionStateStore(context);
        if (onlyIfNotShownBefore && stateStore.hasShownBatteryDialog()) {
            return;
        }
        stateStore.setBatteryDialogShown();

        new AlertDialog.Builder(context)
                .setTitle("Keep Arena AI running reliably")
                .setMessage("Android's battery optimization can pause or stop Arena AI in the "
                        + "background even while it's running as a foreground service, "
                        + "especially on Samsung, Xiaomi, and other custom Android skins.\n\n"
                        + "To keep your session alive when you switch apps or lock the screen, "
                        + "please allow Arena AI to run without battery restrictions on the next "
                        + "screen.")
                .setPositiveButton("Continue", (dialog, which) -> requestExemption(context))
                .setNegativeButton("Not now", (dialog, which) -> dialog.dismiss())
                .setCancelable(true)
                .show();
    }

    private static void requestExemption(Context context) {
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + context.getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            Log.w(TAG, "Direct battery optimization request not available, "
                    + "falling back to the general settings screen.", e);
            try {
                Intent fallback = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(fallback);
            } catch (Exception fallbackError) {
                Log.e(TAG, "No battery optimization settings screen available on this device.", fallbackError);
            }
        }
    }
}
