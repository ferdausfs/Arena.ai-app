package com.federal.arenaai;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;

/**
 * Asks the user to exempt Arena AI from battery optimization.
 *
 * <h2>Why this is necessary</h2>
 *
 * <p>A foreground service raises the app's importance in the eyes of stock Android, but it does
 * not override the aggressive, non-standard task-killers that most OEMs ship — Samsung's "Put
 * unused apps to sleep", Xiaomi's MIUI battery saver, Huawei's protected apps, OnePlus's deep
 * optimization, and so on. On those devices a backgrounded app can be frozen or killed regardless
 * of its foreground service unless the user has explicitly excluded it from battery optimization.
 *
 * <p>So this is not an optimization; on a large fraction of real phones it is the difference
 * between the session surviving and the page reloading.
 *
 * <h2>How it is asked for</h2>
 *
 * <p>{@code REQUEST_IGNORE_BATTERY_OPTIMIZATIONS} — the permission that lets an app show the
 * one-tap system dialog — is restricted by Google Play to a short list of app categories, and a
 * web wrapper is not one of them. Declaring it would put the app at risk of rejection. Instead the
 * app explains the problem in its own dialog and sends the user to the relevant system settings
 * screen, where the exemption is two taps away. That is allowed for any app and is the honest
 * approach: the user grants it knowingly.
 */
public final class BatteryOptimizationHelper {

    private static final String TAG = "BatteryOptimization";

    private BatteryOptimizationHelper() {
        // No instances.
    }

    /** True when the app is already exempt from battery optimization (or the OS has no such idea). */
    public static boolean isIgnoringBatteryOptimizations(@NonNull Context context) {
        PowerManager powerManager = context.getSystemService(PowerManager.class);
        if (powerManager == null) {
            return true;
        }
        return powerManager.isIgnoringBatteryOptimizations(context.getPackageName());
    }

    /**
     * Shows the explanation dialog if it is both needed and wanted.
     *
     * <p>Skipped when the app is already exempt, or when the user has previously chosen
     * "Don't ask again". The dialog is always reachable afterwards from the app's overflow menu.
     *
     * @param activity the visible Activity to show the dialog on
     * @param store    used to remember a "Don't ask again" choice
     */
    public static void maybePrompt(@NonNull Activity activity, @NonNull SessionStore store) {
        if (activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        if (isIgnoringBatteryOptimizations(activity) || store.isBatteryPromptDismissed()) {
            return;
        }
        showDialog(activity, store);
    }

    /**
     * Shows the explanation dialog unconditionally (overflow menu → "Battery settings"), including
     * a confirmation when the exemption is already in place.
     */
    public static void promptNow(@NonNull Activity activity, @NonNull SessionStore store) {
        if (activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        if (isIgnoringBatteryOptimizations(activity)) {
            new AlertDialog.Builder(activity)
                    .setTitle(R.string.battery_dialog_title)
                    .setMessage(R.string.battery_dialog_message_already_exempt)
                    .setPositiveButton(R.string.action_ok, null)
                    .setNeutralButton(R.string.battery_dialog_open_settings, (dialog, which) ->
                            openBatteryOptimizationSettings(activity))
                    .show();
            return;
        }
        showDialog(activity, store);
    }

    private static void showDialog(@NonNull Activity activity, @NonNull SessionStore store) {
        new AlertDialog.Builder(activity)
                .setTitle(R.string.battery_dialog_title)
                .setMessage(R.string.battery_dialog_message)
                .setPositiveButton(R.string.battery_dialog_open_settings, (dialog, which) ->
                        openBatteryOptimizationSettings(activity))
                .setNegativeButton(R.string.battery_dialog_later, null)
                .setNeutralButton(R.string.battery_dialog_never, (dialog, which) ->
                        store.setBatteryPromptDismissed())
                .show();
    }

    /**
     * Opens the system screen where the exemption can be granted.
     *
     * <p>Three levels of fallback, because OEM skins are inconsistent about which of these screens
     * exist:
     *
     * <ol>
     *   <li>the "Battery optimization" list, filtered to all apps;
     *   <li>this app's own detail page in system settings (Battery is one tap in from there);
     *   <li>a toast telling the user where to go, if even that is unavailable.
     * </ol>
     */
    public static void openBatteryOptimizationSettings(@NonNull Context context) {
        Intent listIntent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
        if (!(context instanceof Activity)) {
            listIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        try {
            context.startActivity(listIntent);
            Toast.makeText(context, R.string.battery_toast_find_app, Toast.LENGTH_LONG).show();
            return;
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(TAG, "Battery optimization settings screen unavailable; falling back.", e);
        }

        Intent appDetailsIntent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.getPackageName(), null));
        if (!(context instanceof Activity)) {
            appDetailsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        try {
            context.startActivity(appDetailsIntent);
            Toast.makeText(context, R.string.battery_toast_app_details, Toast.LENGTH_LONG).show();
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(TAG, "App details settings screen unavailable.", e);
            Toast.makeText(context, R.string.battery_toast_manual, Toast.LENGTH_LONG).show();
        }
    }
}
