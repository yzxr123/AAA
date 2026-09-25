package org.adguardian.app.service;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import org.adguardian.app.MainActivity;
import org.adguardian.app.R;
import org.adguardian.app.runtime.AccessibilityState;
import org.adguardian.app.runtime.RuntimeHealth;
import org.adguardian.app.settings.PreferenceStore;

/** Foreground status notification. It does not claim survival after the recent task is removed. */
public final class ProtectionService extends Service {
    public static final String ACTION_PAUSE = "org.adguardian.app.PAUSE_PROTECTION";
    private static final String CHANNEL = "ad_protection_status";
    private static final int NOTIFICATION = 7501;
    private static volatile ProtectionService owner;
    private static volatile long requestedAt;
    private static volatile String startProblem = "";

    private AccessibilityState.Watch watch;
    private String displayedText = "";
    private boolean displayedAllowed;

    public static boolean isRunning() {
        return owner != null;
    }

    public static String startProblem() {
        return startProblem;
    }

    public static boolean shouldRun(Context context) {
        AccessibilityState.Snapshot state = AccessibilityState.read(context);
        return PreferenceStore.isMasterEnabled(context)
                && PreferenceStore.isKeepAliveEnabled(context)
                && !PreferenceStore.isTaskRemoved(context)
                && (state.authorized || (!state.known && state.previouslyAuthorized));
    }

    public static void sync(Context context) {
        Intent intent = new Intent(context, ProtectionService.class);
        if (!shouldRun(context)) {
            requestedAt = 0L;
            try {
                context.stopService(intent);
            } catch (RuntimeException error) {
                startProblem = "系统未完成状态通知停止请求";
                RuntimeHealth.failure(context, "状态通知停止请求失败", error);
            }
            return;
        }
        if (owner != null) {
            AccessibilityState.changed();
            return;
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (requestedAt > 0L && now - requestedAt < 1500L) return;
        requestedAt = now;
        try {
            context.startForegroundService(intent);
            startProblem = "";
        } catch (RuntimeException error) {
            requestedAt = 0L;
            startProblem = "系统暂未允许启动状态通知，请重新打开本应用";
            AccessibilityState.changed();
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL, "广告保护状态", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("显示广告保护是否正在运行");
        channel.setShowBadge(false);
        try {
            if (manager != null) manager.createNotificationChannel(channel);
        } catch (RuntimeException unavailable) {
            startProblem = "系统暂时无法创建保护通知";
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_PAUSE.equals(intent.getAction())) {
            PreferenceStore.setMasterEnabled(this, false);
            AdAccessibilityService.notifySettingsChanged();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!shouldRun(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            Notification notification = notification();
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFICATION, notification);
            }
        } catch (RuntimeException error) {
            startProblem = "状态通知启动受限，请检查系统后台设置";
            RuntimeHealth.failure(this, "状态通知启动失败", error);
            stopSelf();
            AccessibilityState.changed();
            return START_NOT_STICKY;
        }
        owner = this;
        requestedAt = 0L;
        startProblem = "";
        RuntimeHealth.event(this, "后台窗口状态通知已启动");
        if (watch == null) watch = new AccessibilityState.Watch(this, this::refresh);
        AccessibilityState.changed();
        return START_STICKY;
    }

    private void refresh() {
        if (owner != this) return;
        if (!shouldRun(this)) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return;
        }
        String next = AccessibilityState.read(this).runtimeText();
        boolean allowed = notificationAllowed();
        if (next.equals(displayedText) && allowed == displayedAllowed) return;
        if (!allowed) {
            displayedAllowed = false;
            return;
        }
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        try {
            if (manager != null) manager.notify(NOTIFICATION, notification());
        } catch (RuntimeException unavailable) {
            startProblem = "保护通知暂时无法刷新";
        }
    }

    private boolean notificationAllowed() {
        return Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private Notification notification() {
        displayedAllowed = notificationAllowed();
        displayedText = AccessibilityState.read(this).runtimeText();
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent pause = new Intent(this, ProtectionService.class).setAction(ACTION_PAUSE);
        PendingIntent pauseIntent = PendingIntent.getService(this, 1, pause,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("去你的广告")
                .setContentText(displayedText)
                .setContentIntent(content)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(null, "暂停保护", pauseIntent).build())
                .build();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        PreferenceStore.markTaskRemoved(this);
        RuntimeHealth.event(this, "最近任务被移除，状态通知已停止");
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        if (owner == this) {
            owner = null;
            requestedAt = 0L;
            RuntimeHealth.event(this, "后台窗口状态通知已停止");
            stopForeground(STOP_FOREGROUND_REMOVE);
        }
        if (watch != null) watch.close();
        AccessibilityState.changed();
        super.onDestroy();
    }
}
