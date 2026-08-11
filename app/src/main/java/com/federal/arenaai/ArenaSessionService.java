package com.federal.arenaai;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

public class ArenaSessionService extends Service {
    public static final String ACTION_START = "com.federal.arenaai.action.START_SESSION";
    public static final String ACTION_STOP = "com.federal.arenaai.action.STOP_SESSION";

    private static final String TAG = "ArenaSessionService";
    private static final String CHANNEL_ID = "arena_ai_session";
    private static final int NOTIFICATION_ID = 1001;

    public static void start(Context context) {
        Intent intent = new Intent(context, ArenaSessionService.class).setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stop(Context context) {
        Intent intent = new Intent(context, ArenaSessionService.class).setAction(ACTION_STOP);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
        ArenaWebViewManager.getOrCreate(this);
        Log.i(TAG, "Arena foreground session service started.");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_START;
        if (ACTION_STOP.equals(action)) {
            Log.i(TAG, "Explicit stop requested; destroying Arena WebView session.");
            ArenaWebViewManager.destroySession(this);
            stopForegroundCompat();
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID, buildNotification());
        ArenaWebViewManager.getOrCreate(this);
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // Deliberately do not stop. The foreground service is the user's active session.
        Log.i(TAG, "Task removed; keeping foreground Arena session alive.");
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "Arena foreground session service destroyed.");
        super.onDestroy();
    }

    private Notification buildNotification() {
        Intent launchIntent = new Intent(this, MainActivity.class)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent launchPendingIntent = PendingIntent.getActivity(
                this,
                0,
                launchIntent,
                pendingIntentFlags(false)
        );

        Intent stopIntent = new Intent(this, ArenaSessionService.class).setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this,
                1,
                stopIntent,
                pendingIntentFlags(false)
        );

        Notification.Action stopAction = new Notification.Action.Builder(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.notification_stop),
                stopPendingIntent
        ).build();

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        return builder
                .setSmallIcon(R.drawable.ic_stat_arena)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_text))
                .setStyle(new Notification.BigTextStyle().bigText(getString(R.string.notification_text)))
                .setContentIntent(launchPendingIntent)
                .setOngoing(true)
                .setShowWhen(false)
                .setPriority(Notification.PRIORITY_LOW)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(stopAction)
                .build();
    }

    private int pendingIntentFlags(boolean mutable) {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags |= mutable ? PendingIntent.FLAG_MUTABLE : PendingIntent.FLAG_IMMUTABLE;
        }
        return flags;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }

        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription(getString(R.string.notification_channel_description));
        channel.setShowBadge(false);

        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    private void stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
    }
}
