package com.testionic.backgroundkeepalive;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

/**
 * 前台服务：常驻通知 + 让进程活下来。
 *
 * 这是 Android 上唯一被系统承认的"后台长期存活"手段（普通 `notify()` 不会阻止进程被杀）。
 *
 * - `START_STICKY`：服务被系统杀掉后会尝试重建；
 * - 通知是 `ongoing`（默认不可划掉），带一个"停止"动作；
 * - Android 14+ 前台服务类型必须在 manifest 里声明，且不同类型有不同的时长/权限要求：
 *   `specialUse` 没有 6 小时限制（上架需要说明用途），`dataSync` 在 Android 15 上被限制为
 *   24 小时内 6 小时，`mediaPlayback` 只应该给真的在播放媒体的 App 用。
 */
public class KeepaliveService extends Service {

    private static final String TAG = "KeepaliveService";

    public static final String ACTION_START = "com.testionic.backgroundkeepalive.START";
    public static final String ACTION_STOP = "com.testionic.backgroundkeepalive.STOP";
    public static final String ACTION_UPDATE = "com.testionic.backgroundkeepalive.UPDATE";

    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_TEXT = "text";
    public static final String EXTRA_SUB_TEXT = "subText";
    public static final String EXTRA_SMALL_ICON = "smallIcon";
    public static final String EXTRA_CHANNEL_ID = "channelId";
    public static final String EXTRA_CHANNEL_NAME = "channelName";
    public static final String EXTRA_DISMISSIBLE = "dismissible";
    public static final String EXTRA_FGS_TYPE = "fgsType";
    public static final String EXTRA_KEEP_SCREEN_ON = "keepScreenOn";

    private static final int NOTIFICATION_ID = 4301;

    private static volatile boolean running = false;
    private static volatile String activeMode = "none";

    /** 让插件能把状态变化转成 JS 事件。 */
    public interface Listener {
        void onStateChanged();
    }

    private static volatile Listener listener;

    public static void setListener(Listener value) {
        listener = value;
    }

    public static boolean isRunning() {
        return running;
    }

    public static String activeMode() {
        return activeMode;
    }

    private Notification.Builder ongoingNotification;
    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        String action = intent.getAction();
        boolean update = ACTION_UPDATE.equals(action);

        String title = intent.getStringExtra(EXTRA_TITLE);
        String text = intent.getStringExtra(EXTRA_TEXT);
        String subText = intent.getStringExtra(EXTRA_SUB_TEXT);
        String channelId = intent.getStringExtra(EXTRA_CHANNEL_ID);
        String channelName = intent.getStringExtra(EXTRA_CHANNEL_NAME);
        boolean dismissible = intent.getBooleanExtra(EXTRA_DISMISSIBLE, false);
        String fgsType = intent.getStringExtra(EXTRA_FGS_TYPE);
        boolean keepScreenOn = intent.getBooleanExtra(EXTRA_KEEP_SCREEN_ON, false);

        if (title == null || title.isEmpty()) {
            title = "应用正在后台运行";
        }
        if (text == null || text.isEmpty()) {
            text = "点击返回应用";
        }
        if (channelId == null || channelId.isEmpty()) {
            channelId = "capacitor_keepalive";
        }
        if (channelName == null || channelName.isEmpty()) {
            channelName = "后台运行";
        }
        if (fgsType == null || fgsType.isEmpty()) {
            fgsType = "specialUse";
        }

        createChannel(channelId, channelName);
        Notification notification = buildNotification(
                channelId, title, text, subText, dismissible,
                intent.getStringExtra(EXTRA_SMALL_ICON));

        int type = resolveServiceType(fgsType);
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type);
        } catch (Exception e) {
            // Android 12+ 从后台启动前台服务会被拒（ForegroundServiceStartNotAllowedException）
            Log.e(TAG, "startForeground failed", e);
            running = false;
            activeMode = "none";
            stopSelf();
            notifyListener();
            return START_NOT_STICKY;
        }

        running = true;
        activeMode = mapMode(fgsType);

        if (keepScreenOn) {
            acquireWakeLock();
        } else {
            releaseWakeLock();
        }
        notifyListener();

        if (update) {
            // 只更新通知内容，不影响服务状态
            return START_STICKY;
        }
        return START_STICKY;
    }

    private String mapMode(String fgsType) {
        switch (fgsType) {
            case "mediaPlayback":
                return "mediaPlayback";
            case "dataSync":
                return "dataSync";
            default:
                return "specialUse";
        }
    }

    private int resolveServiceType(String fgsType) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return 0;
        }
        switch (fgsType) {
            case "mediaPlayback":
                return ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK;
            case "dataSync":
                return ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
            default:
                return ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
        }
    }

    private void createChannel(String channelId, String channelName) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null || manager.getNotificationChannel(channelId) != null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                channelId, channelName, NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("保持应用在后台运行");
        channel.setShowBadge(false);
        channel.setSound(null, null);
        manager.createNotificationChannel(channel);
    }

    private Notification buildNotification(String channelId, String title, String text,
                                           String subText, boolean dismissible, String iconName) {
        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent contentIntent = null;
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            contentIntent = PendingIntent.getActivity(
                    this, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        }
        Intent stopIntent = new Intent(this, KeepaliveService.class).setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(
                this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, channelId)
                .setSmallIcon(resolveSmallIcon(iconName))
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(!dismissible)
                .setSilent(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                // Android 12+ 默认会把前台服务通知延后 10 秒；IMMEDIATE 让它立刻显示
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .addAction(0, "停止", stopPending);
        if (subText != null && !subText.isEmpty()) {
            builder.setSubText(subText);
        }
        if (contentIntent != null) {
            builder.setContentIntent(contentIntent);
        }
        return builder.build();
    }

    private int resolveSmallIcon(String iconName) {
        if (iconName != null && !iconName.isEmpty()) {
            int resId = getResources().getIdentifier(iconName, "drawable", getPackageName());
            if (resId != 0) {
                return resId;
            }
            resId = getResources().getIdentifier(iconName, "mipmap", getPackageName());
            if (resId != 0) {
                return resId;
            }
        }
        try {
            int appIcon = getApplicationInfo().icon;
            if (appIcon != 0) {
                return appIcon;
            }
        } catch (Exception ignored) {
            // ignore
        }
        return android.R.drawable.stat_notify_sync;
    }

    private void acquireWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            return;
        }
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm == null) {
            return;
        }
        wakeLock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "capacitor-keepalive:screen");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        wakeLock = null;
    }

    /**
     * Android 15+：按类型的前台服务超时回调（`dataSync` / `mediaProcessing` 为 24 小时内 6 小时）。
     *
     * 系统给几秒宽限期，必须在这之前停掉前台服务，否则 App 会被杀。
     */
    @Override
    public void onTimeout(int startId, int fgsType) {
        Log.w(TAG, "foreground service type timeout, fgsType=" + fgsType);
        running = false;
        activeMode = "none";
        releaseWakeLock();
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        notifyListener();
        stopSelf();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // 用户从最近任务里划掉 App：START_STICKY 的服务仍会被重建，这里不做额外处理
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        running = false;
        activeMode = "none";
        releaseWakeLock();
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        notifyListener();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void notifyListener() {
        Listener current = listener;
        if (current != null) {
            try {
                current.onStateChanged();
            } catch (Exception e) {
                Log.w(TAG, "listener failed", e);
            }
        }
    }
}
