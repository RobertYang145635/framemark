package com.framemark.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

/**
 * S4: keeps a batch export alive while the screen is off / the app is in the
 * background.
 *
 * A foreground service with type `dataSync` raises the process importance to
 * FOREGROUND (the cached-app freezer must not touch it) and a PARTIAL_WAKE_LOCK
 * keeps the CPU running so the WebView can keep rendering. The notification is
 * the user-visible contract: it shows `正在导出 3/15 · name` and disappears when
 * the export ends.
 *
 * Lifecycle: start -> startForeground + wakelock, update* -> notification only
 * (no second startService, which would be a background start on Android 12+),
 * stop -> stopForeground + stopSelf, wakelock released in onDestroy.
 */
public class ExportService extends Service {

    public static final String ACTION_START = "com.framemark.app.action.EXPORT_START";
    public static final String ACTION_UPDATE = "com.framemark.app.action.EXPORT_UPDATE";
    public static final String ACTION_STOP = "com.framemark.app.action.EXPORT_STOP";

    public static final String EXTRA_TOTAL = "total";
    public static final String EXTRA_DONE = "done";
    public static final String EXTRA_CURRENT = "current";

    private static final String CHANNEL_ID = "framemark_export";
    private static final String CHANNEL_NAME = "导出进度";
    private static final int NOTIFICATION_ID = 4201;
    private static final String WAKE_LOCK_TAG = "FrameMark:ExportService";

    private static volatile ExportService instance;

    private final Handler main = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wakeLock;
    private int total = 0;
    private int done = 0;
    private String current = "";
    private boolean inForeground = false;

    /** The live service, or null when it is not running. Used by the plugin. */
    public static ExportService getInstance() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            stopExport();
            return START_NOT_STICKY;
        }
        if (intent != null) {
            total = Math.max(0, intent.getIntExtra(EXTRA_TOTAL, total));
            done = Math.max(0, intent.getIntExtra(EXTRA_DONE, done));
            String next = intent.getStringExtra(EXTRA_CURRENT);
            if (next != null) current = next;
        }
        acquireWakeLock();
        if (!inForeground) {
            startForegroundCompat();
        }
        updateNotification();
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** Called by ExportForegroundPlugin while the batch is running. */
    public void updateProgress(final int nextDone, final int nextTotal, final String nextCurrent) {
        main.post(() -> {
            if (!inForeground) return;
            done = Math.max(0, nextDone);
            total = Math.max(0, nextTotal);
            if (nextCurrent != null) current = nextCurrent;
            updateNotification();
        });
    }

    /** Called by ExportForegroundPlugin on stop() and by ACTION_STOP. */
    public void stopExport() {
        main.post(() -> {
            if (inForeground) {
                inForeground = false;
                stopForeground(STOP_FOREGROUND_REMOVE);
            }
            releaseWakeLock();
            stopSelf();
        });
    }

    @Override
    public void onDestroy() {
        if (inForeground) {
            inForeground = false;
            stopForeground(STOP_FOREGROUND_REMOVE);
        }
        releaseWakeLock();
        instance = null;
        super.onDestroy();
    }

    // ---------------------------------------------------------------- internals

    private void startForegroundCompat() {
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        inForeground = true;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("批量导出期间的进度提示");
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private void updateNotification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        manager.notify(NOTIFICATION_ID, buildNotification());
    }

    private String statusText() {
        if (total > 0) {
            String base = "正在导出 " + Math.min(done, total) + "/" + total;
            if (current != null && !current.isEmpty()) {
                String name = current.length() > 42 ? current.substring(0, 42) + "…" : current;
                return base + " · " + name;
            }
            return base;
        }
        return "正在导出";
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, open, piFlags);

        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(statusText())
                .setContentText(total > 0 ? Math.min(done, total) + "/" + total : "")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setContentIntent(contentIntent)
                .setProgress(total > 0 ? total : 0, Math.min(done, total), total <= 0);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return builder.build();
    }

    private void acquireWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) return;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm == null) return;
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG);
        wakeLock.setReferenceCounted(false);
        // Released in stopExport()/onDestroy(); a process kill drops it with us.
        wakeLock.acquire();
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        wakeLock = null;
    }
}
