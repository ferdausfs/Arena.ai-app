package com.federal.arenaai;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

/**
 * The foreground service that keeps the Arena AI session alive.
 *
 * <h2>What it actually does</h2>
 *
 * <p>Android decides what to kill based on <em>process importance</em>. A process whose only
 * component is a stopped Activity is a "cached" process and is first in line to be reclaimed —
 * that is why a plain WebView app reloads its page after a while in the background. A process
 * running a foreground service sits near the top of the importance list, just below the visible
 * foreground app, and is one of the last things the system reclaims.
 *
 * <p>So the service does two things, and they are the whole design:
 *
 * <ol>
 *   <li>It holds a reference to {@link ArenaWebSession} — the process-wide WebView — so the page
 *       object stays reachable even when no Activity exists.
 *   <li>It keeps the process at foreground importance for as long as the user wants a session,
 *       with the ongoing notification Android requires in exchange.
 * </ol>
 *
 * <h2>Lifetime</h2>
 *
 * <p>Started by {@link MainActivity} when the app is first opened. It then keeps running across
 * Activity destruction, task removal, screen-off and app switching. It stops only when the user
 * says so — the notification's <b>Stop</b> action or the in-app <b>Stop session</b> menu item —
 * both of which arrive here as {@link #ACTION_STOP_SESSION}.
 *
 * <p>{@code onStartCommand()} returns {@link #START_STICKY} so that if Android does kill the
 * process under extreme memory pressure, the service is recreated and the notification comes back.
 * The page itself cannot be resurrected — the JavaScript heap died with the process — but the
 * session reloads at the URL the user was last on, and cookies/localStorage keep them signed in.
 */
public final class ArenaSessionService extends Service {

    private static final String TAG = "ArenaSessionService";

    /** Starts (or refreshes) the live session. */
    public static final String ACTION_START_SESSION = "com.federal.arenaai.action.START_SESSION";

    /** Explicit user request to end the session and remove the notification. */
    public static final String ACTION_STOP_SESSION = "com.federal.arenaai.action.STOP_SESSION";

    private static final String CHANNEL_ID = "arena_session";
    private static final int NOTIFICATION_ID = 1001;

    private static final int REQUEST_CODE_OPEN = 1;
    private static final int REQUEST_CODE_STOP = 2;

    /**
     * Whether the service is currently running. Written on the main thread from the service's own
     * lifecycle callbacks and read from the Activity's main-thread callbacks; {@code volatile} is
     * belt-and-braces for tooling that inspects it off-thread.
     */
    private static volatile boolean sRunning;

    private SessionStore mStore;

    /**
     * The live session. Held as a field for exactly one reason: to make the service's ownership of
     * the WebView explicit and to keep it strongly reachable for as long as the service lives.
     */
    @Nullable
    private ArenaWebSession mSession;

    // -------------------------------------------------------------------------------------
    // Static helpers used by the Activity
    // -------------------------------------------------------------------------------------

    /** True while the foreground service is running in this process. */
    public static boolean isRunning() {
        return sRunning;
    }

    /**
     * Starts the session service.
     *
     * <p>Called from {@link MainActivity} while it is in the foreground, so
     * {@code startForegroundService()} is always allowed — Android 12+ background-start
     * restrictions do not apply to a visible Activity.
     */
    public static void start(@NonNull Context context) {
        Intent intent = new Intent(context, ArenaSessionService.class)
                .setAction(ACTION_START_SESSION);
        ContextCompat.startForegroundService(context, intent);
    }

    /** Builds the intent that ends the session. Used by both the notification and the menu. */
    @NonNull
    public static Intent stopIntent(@NonNull Context context) {
        return new Intent(context, ArenaSessionService.class).setAction(ACTION_STOP_SESSION);
    }

    /** Asks the service to stop. Safe to call when it is not running. */
    public static void stop(@NonNull Context context) {
        try {
            context.startService(stopIntent(context));
        } catch (IllegalStateException e) {
            // Can only happen if the app is in the background on Android 8+ and the service is
            // not running — in which case there is nothing to stop anyway.
            Log.w(TAG, "Could not deliver stop intent; service is not running.", e);
        }
    }

    // -------------------------------------------------------------------------------------
    // Service lifecycle
    // -------------------------------------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        mStore = new SessionStore(this);
        createNotificationChannel();

        if (mStore.wasUncleanShutdown()) {
            // The previous process did not shut down through the user's stop action: the OS
            // reclaimed it. Recorded honestly in the log; the session simply resumes.
            Log.w(TAG, "Previous session ended without an explicit stop — the process was "
                    + "reclaimed by the system. Resuming at " + mStore.getResumeUrl());
        }
        Log.i(TAG, "Service created.");
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        Log.d(TAG, "onStartCommand action=" + action + " flags=" + flags);

        if (ACTION_STOP_SESSION.equals(action)) {
            shutdown();
            return START_NOT_STICKY;
        }

        // A null intent means the system restarted us after killing the process (START_STICKY).
        // Honour the user's last explicit choice: if they had stopped the session, do not
        // silently resurrect it.
        if (intent == null && !mStore.isSessionRequested()) {
            Log.i(TAG, "Restarted by the system but the user had stopped the session; exiting.");
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        // Promote to foreground before anything else: the platform gives a newly started service
        // a few seconds to call startForeground() and throws if it misses the window.
        startInForeground();
        sRunning = true;
        mStore.setSessionRequested(true);
        mStore.setUncleanShutdown(true);

        // Create/keep the WebView. Doing it here means the page exists and starts loading as soon
        // as the session is requested, and stays owned by the service rather than by an Activity.
        if (mSession == null) {
            mSession = ArenaWebSession.getInstance(this);
        }
        mSession.ensureLoaded();

        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // The user swiped the app off the recents screen. This is NOT an instruction to end the
        // session — it dismisses the window, not the app — so the service keeps running and the
        // page stays live, which is exactly the behaviour this rebuild exists to provide. The
        // ongoing notification remains as the way back in (and the way to actually stop it).
        Log.i(TAG, "Task removed; the session keeps running in the background.");
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "Service destroyed.");
        sRunning = false;
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        // Nothing binds to this service: the Activity reaches the WebView through
        // ArenaWebSession, which is process-scoped, so there is no cross-process boundary.
        return null;
    }

    // -------------------------------------------------------------------------------------
    // Start / stop
    // -------------------------------------------------------------------------------------

    private void startInForeground() {
        int type = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // "specialUse" is the correct type here: keeping a user-facing web session resident
            // is not media playback, a data sync or a location fix, and Android 14+ rejects a
            // mismatched type at runtime. The justification is declared in the manifest.
            type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type);
    }

    /** Ends the session for good: explicit user action only. */
    private void shutdown() {
        Log.i(TAG, "Stopping the session at the user's request.");
        // Recorded before teardown so a kill mid-shutdown still reads as a clean stop.
        mStore.setSessionRequested(false);
        mStore.setUncleanShutdown(false);
        mStore.clearLastUrl();

        ArenaWebSession.destroyInstance();
        mSession = null;
        sRunning = false;

        // Tell any visible Activity to close, so the user is not left staring at an empty shell
        // of a session they just ended.
        sendBroadcast(new Intent(MainActivity.ACTION_SESSION_STOPPED).setPackage(getPackageName()));

        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    // -------------------------------------------------------------------------------------
    // Notification
    // -------------------------------------------------------------------------------------

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null || manager.getNotificationChannel(CHANNEL_ID) != null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                // MIN keeps the ongoing notification silent and collapsed at the bottom of the
                // shade. It is a status indicator, not an alert.
                NotificationManager.IMPORTANCE_MIN);
        channel.setDescription(getString(R.string.notification_channel_description));
        channel.setShowBadge(false);
        channel.setSound(null, null);
        channel.enableVibration(false);
        channel.enableLights(false);
        channel.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
        manager.createNotificationChannel(channel);
    }

    @NonNull
    private Notification buildNotification() {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;

        Intent openIntent = new Intent(this, MainActivity.class)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                // Bring the existing task forward instead of stacking a new Activity on top.
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPendingIntent =
                PendingIntent.getActivity(this, REQUEST_CODE_OPEN, openIntent, flags);

        PendingIntent stopPendingIntent = PendingIntent.getService(
                this, REQUEST_CODE_STOP, stopIntent(this), flags);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_text))
                .setContentIntent(openPendingIntent)
                .addAction(0, getString(R.string.notification_action_stop), stopPendingIntent)
                // Ongoing + no swipe-away: the notification is the session's presence in the UI,
                // and Android requires a foreground service to show one.
                .setOngoing(true)
                .setSilent(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build();
    }
}
