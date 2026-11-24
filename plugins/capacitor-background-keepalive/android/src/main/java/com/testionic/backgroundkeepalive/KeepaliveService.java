package com.testionic.backgroundkeepalive;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
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
 * - 系统重建时 **intent 为 null**（这是 Android 的约定），所以通知文案/类型/常亮开关会
 *   从 SharedPreferences 里的「上次配置」恢复（见 {@link #loadConfig()}）。
 *   如果没保存过配置（例如用户主动 stop 过），则直接结束服务 —— 否则会出现
 *   "被杀了又被拉起来、但没有通知"的僵尸服务。
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

    /** 保存「上次启动的配置」，供系统按 START_STICKY 重建（intent=null）时恢复。 */
    private static final String PREFS_NAME = "capacitor_keepalive_service";
    private static final String P_TITLE = "title";
    private static final String P_TEXT = "text";
    private static final String P_SUB_TEXT = "subText";
    private static final String P_SMALL_ICON = "smallIcon";
    private static final String P_CHANNEL_ID = "channelId";
    private static final String P_CHANNEL_NAME = "channelName";
    private static final String P_DISMISSIBLE = "dismissible";
    private static final String P_FGS_TYPE = "fgsType";
    private static final String P_KEEP_SCREEN_ON = "keepScreenOn";

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

    private PowerManager.WakeLock wakeLock;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 用户点了通知上的"停止"（或插件 stop()）：清掉保存的配置，别再被系统拉起来
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            clearSavedConfig();
            stopSelf();
            return START_NOT_STICKY;
        }

        boolean update = intent != null && ACTION_UPDATE.equals(intent.getAction());

        Config config;
        if (intent == null) {
            // 系统按 START_STICKY 重建服务时 intent 一定是 null：
            // 用上次的配置把前台服务重新拉起来（否则"常驻"就名存实亡）
            config = loadConfig();
            if (config == null) {
                Log.i(TAG, "restarted with null intent but no saved config; stopping");
                stopSelf();
                return START_NOT_STICKY;
            }
            Log.i(TAG, "restarted by system (null intent); restoring foreground service");
        } else if (update) {
            // 只改通知文案：必须**合并**到上次配置上，否则会把 fgsType / keepScreenOn 重置成默认值
            // （旧实现就是每次 updateNotification 都把 keepScreenOn 的 WakeLock 放掉）
            config = mergeUpdate(intent);
            saveConfig(config);
        } else {
            config = configFromIntent(intent);
            saveConfig(config);
        }

        createChannel(config.channelId, config.channelName);
        Notification notification = buildNotification(
                config.channelId, config.title, config.text, config.subText,
                config.dismissible, config.smallIcon);

        int type = resolveServiceType(config.fgsType);
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type);
        } catch (Exception e) {
            // Android 12+ 从后台启动前台服务会被拒（ForegroundServiceStartNotAllowedException）；
            // START_STICKY 的重建是系统豁免的，所以这里主要是"用户从后台调 start()"的场景
            Log.e(TAG, "startForeground failed", e);
            running = false;
            activeMode = "none";
            stopSelf();
            notifyListener();
            return START_NOT_STICKY;
        }

        running = true;
        activeMode = mapMode(config.fgsType);

        if (config.keepScreenOn) {
            acquireWakeLock();
        } else {
            releaseWakeLock();
        }
        notifyListener();

        // 更新通知内容 / 系统重建 都保持粘性
        return START_STICKY;
    }

    // ------------------------------------------------------------ 配置

    private static final class Config {
        String title;
        String text;
        String subText;
        String smallIcon;
        String channelId;
        String channelName;
        String fgsType;
        boolean dismissible;
        boolean keepScreenOn;
    }

    private Config configFromIntent(Intent intent) {
        Config config = new Config();
        config.title = intent.getStringExtra(EXTRA_TITLE);
        config.text = intent.getStringExtra(EXTRA_TEXT);
        config.subText = intent.getStringExtra(EXTRA_SUB_TEXT);
        config.smallIcon = intent.getStringExtra(EXTRA_SMALL_ICON);
        config.channelId = intent.getStringExtra(EXTRA_CHANNEL_ID);
        config.channelName = intent.getStringExtra(EXTRA_CHANNEL_NAME);
        config.dismissible = intent.getBooleanExtra(EXTRA_DISMISSIBLE, false);
        config.fgsType = intent.getStringExtra(EXTRA_FGS_TYPE);
        config.keepScreenOn = intent.getBooleanExtra(EXTRA_KEEP_SCREEN_ON, false);
        applyDefaults(config);
        return config;
    }

    /**
     * `ACTION_UPDATE`：在上次配置的基础上只覆盖**非空**的文案字段。
     * 这样 `updateNotification({ title })` 不会把 `fgsType` / `keepScreenOn` 重置成默认值。
     */
    private Config mergeUpdate(Intent intent) {
        Config config = loadConfig();
        if (config == null) {
            config = new Config();
        }
        String title = intent.getStringExtra(EXTRA_TITLE);
        if (title != null && !title.isEmpty()) {
            config.title = title;
        }
        String text = intent.getStringExtra(EXTRA_TEXT);
        if (text != null && !text.isEmpty()) {
            config.text = text;
        }
        if (intent.hasExtra(EXTRA_SUB_TEXT)) {
            config.subText = intent.getStringExtra(EXTRA_SUB_TEXT);
        }
        String smallIcon = intent.getStringExtra(EXTRA_SMALL_ICON);
        if (smallIcon != null && !smallIcon.isEmpty()) {
            config.smallIcon = smallIcon;
        }
        String channelId = intent.getStringExtra(EXTRA_CHANNEL_ID);
        if (channelId != null && !channelId.isEmpty()) {
            config.channelId = channelId;
        }
        String channelName = intent.getStringExtra(EXTRA_CHANNEL_NAME);
        if (channelName != null && !channelName.isEmpty()) {
            config.channelName = channelName;
        }
        if (intent.hasExtra(EXTRA_DISMISSIBLE)) {
            config.dismissible = intent.getBooleanExtra(EXTRA_DISMISSIBLE, config.dismissible);
        }
        applyDefaults(config);
        return config;
    }

    private void applyDefaults(Config config) {
        if (config.title == null || config.title.isEmpty()) {
            config.title = "应用正在后台运行";
        }
        if (config.text == null || config.text.isEmpty()) {
            config.text = "点击返回应用";
        }
        if (config.channelId == null || config.channelId.isEmpty()) {
            config.channelId = "capacitor_keepalive";
        }
        if (config.channelName == null || config.channelName.isEmpty()) {
            config.channelName = "后台运行";
        }
        if (config.fgsType == null || config.fgsType.isEmpty()) {
            config.fgsType = "specialUse";
        }
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private void saveConfig(Config config) {
        prefs().edit()
                .putString(P_TITLE, config.title)
                .putString(P_TEXT, config.text)
                .putString(P_SUB_TEXT, config.subText)
                .putString(P_SMALL_ICON, config.smallIcon)
                .putString(P_CHANNEL_ID, config.channelId)
                .putString(P_CHANNEL_NAME, config.channelName)
                .putBoolean(P_DISMISSIBLE, config.dismissible)
                .putString(P_FGS_TYPE, config.fgsType)
                .putBoolean(P_KEEP_SCREEN_ON, config.keepScreenOn)
                .apply();
    }

    /** 没有保存过配置时返回 null（表示"不应该恢复"）。 */
    @Nullable
    private Config loadConfig() {
        SharedPreferences prefs = prefs();
        String fgsType = prefs.getString(P_FGS_TYPE, null);
        if (fgsType == null) {
            return null;
        }
        Config config = new Config();
        config.title = prefs.getString(P_TITLE, null);
        config.text = prefs.getString(P_TEXT, null);
        config.subText = prefs.getString(P_SUB_TEXT, null);
        config.smallIcon = prefs.getString(P_SMALL_ICON, null);
        config.channelId = prefs.getString(P_CHANNEL_ID, null);
        config.channelName = prefs.getString(P_CHANNEL_NAME, null);
        config.dismissible = prefs.getBoolean(P_DISMISSIBLE, false);
        config.fgsType = fgsType;
        config.keepScreenOn = prefs.getBoolean(P_KEEP_SCREEN_ON, false);
        applyDefaults(config);
        return config;
    }

    private void clearSavedConfig() {
        prefs().edit().clear().apply();
    }

    // ------------------------------------------------------------ 通知

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

    // ------------------------------------------------------------ 屏幕常亮

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

    // ------------------------------------------------------------ 生命周期

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
        clearSavedConfig();
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
        // 注意：不要在这里 clearSavedConfig() —— 服务可能是被系统杀掉后重建的，
        // 配置要留到用户真正 stop() 时再清（ACTION_STOP 分支）。
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
