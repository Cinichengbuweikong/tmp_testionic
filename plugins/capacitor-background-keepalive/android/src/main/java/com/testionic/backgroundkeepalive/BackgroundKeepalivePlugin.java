package com.testionic.backgroundkeepalive;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import androidx.core.content.ContextCompat;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * 后台保活（Android）：前台服务 + 常驻通知 + 电池优化豁免。
 *
 * 注意：Android 12+ 不允许从后台启动前台服务，所以 `start()` 必须在 App 处于前台时调用
 * （插件直接调用 `startForegroundService`，如果系统拒绝会 reject）。
 */
@CapacitorPlugin(name = "BackgroundKeepalive")
public class BackgroundKeepalivePlugin extends Plugin {

    private KeepaliveService.Listener serviceListener;

    @Override
    public void load() {
        serviceListener = () -> {
            JSObject status = statusObject();
            notifyListeners("keepaliveChanged", status);
        };
        KeepaliveService.setListener(serviceListener);
    }

    @Override
    protected void handleOnDestroy() {
        KeepaliveService.setListener(null);
        super.handleOnDestroy();
    }

    // -------------------------------------------------------------- API

    @PluginMethod
    public void start(PluginCall call) {
        String mode = call.getString("mode", "specialUse");
        String fgsType = mapFgsType(mode);
        if (fgsType == null) {
            call.reject("UNSUPPORTED_MODE: Android 只支持 specialUse / mediaPlayback / dataSync", "UNSUPPORTED_MODE");
            return;
        }

        Intent intent = new Intent(getContext(), KeepaliveService.class)
                .setAction(KeepaliveService.ACTION_START)
                .putExtra(KeepaliveService.EXTRA_FGS_TYPE, fgsType);
        applyNotificationExtras(call, intent);
        intent.putExtra(KeepaliveService.EXTRA_KEEP_SCREEN_ON, Boolean.TRUE.equals(call.getBoolean("keepScreenOn", false)));

        try {
            ContextCompat.startForegroundService(getContext(), intent);
        } catch (Exception e) {
            call.reject("FOREGROUND_SERVICE_START_FAILED: " + e.getMessage(), e);
            return;
        }

        // startForegroundService 是异步的：等服务真正调完 startForeground 再回报状态
        boolean started = waitForRunning(true, 1500);
        JSObject status = statusObject();
        if (!started) {
            status.put("note", "前台服务启动超时：Android 12+ 不允许从后台启动前台服务，"
                    + "请在 App 处于前台时调用 start()，并确认没有开启省电模式限制。");
        }
        call.resolve(status);
    }

    @PluginMethod
    public void stop(PluginCall call) {
        Intent intent = new Intent(getContext(), KeepaliveService.class)
                .setAction(KeepaliveService.ACTION_STOP);
        try {
            getContext().startService(intent);
        } catch (Exception e) {
            // 服务没在跑时 startService 在部分 ROM 上会抛错，直接当成已停止
        }
        waitForRunning(false, 1000);
        call.resolve(statusObject());
    }

    @PluginMethod
    public void isRunning(PluginCall call) {
        call.resolve(statusObject());
    }

    @PluginMethod
    public void updateNotification(PluginCall call) {
        if (!KeepaliveService.isRunning()) {
            call.reject("NOT_RUNNING: 保活服务没有运行", "NOT_RUNNING");
            return;
        }
        Intent intent = new Intent(getContext(), KeepaliveService.class)
                .setAction(KeepaliveService.ACTION_UPDATE);
        applyNotificationExtras(call, intent);
        try {
            getContext().startService(intent);
        } catch (Exception e) {
            call.reject("UPDATE_FAILED: " + e.getMessage(), e);
            return;
        }
        call.resolve(statusObject());
    }

    @PluginMethod
    public void getCapabilities(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("platform", "android");
        ret.put("continuous", true);
        ret.put("note", "Android 用前台服务（常驻通知）保持进程存活；这是系统承认的唯一方式。");
        org.json.JSONArray modes = new org.json.JSONArray();
        modes.put(modeCapability(
                "specialUse",
                true,
                new String[]{
                        "FOREGROUND_SERVICE",
                        "FOREGROUND_SERVICE_SPECIAL_USE",
                        "POST_NOTIFICATIONS (Android 13+, 通知才显示)",
                        "manifest: foregroundServiceType=\"specialUse\" + PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
                },
                "进程长期存活，没有时长上限；Google Play 要求说明 specialUse 用途",
                true));
        modes.put(modeCapability(
                "mediaPlayback",
                true,
                new String[]{"FOREGROUND_SERVICE_MEDIA_PLAYBACK", "manifest: foregroundServiceType=\"mediaPlayback\""},
                "进程长期存活；只适合真的在播放音频/视频的 App",
                false));
        modes.put(modeCapability(
                "dataSync",
                true,
                new String[]{"FOREGROUND_SERVICE_DATA_SYNC", "manifest: foregroundServiceType=\"dataSync\""},
                "Android 14+ 限制为 24 小时内累计 6 小时（超时收到 onTimeout），不适合长期保活",
                false));
        ret.put("modes", modes);
        call.resolve(ret);
    }

    @PluginMethod
    public void isBatteryOptimizationIgnored(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("applicable", true);
        ret.put("ignored", isIgnoringBatteryOptimizations());
        call.resolve(ret);
    }

    @PluginMethod
    public void requestBatteryOptimizationExemption(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("applicable", true);
        if (isIgnoringBatteryOptimizations()) {
            ret.put("ignored", true);
            ret.put("requested", false);
            call.resolve(ret);
            return;
        }
        boolean requested = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            String packageName = getContext().getPackageName();
            Intent direct = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:" + packageName));
            try {
                getActivity().startActivity(direct);
                requested = true;
            } catch (Exception e) {
                Intent list = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                try {
                    getActivity().startActivity(list);
                    requested = true;
                } catch (Exception ignored) {
                    requested = false;
                }
            }
        }
        ret.put("ignored", isIgnoringBatteryOptimizations());
        ret.put("requested", requested);
        call.resolve(ret);
    }

    @PluginMethod
    public void openBatteryOptimizationSettings(PluginCall call) {
        JSObject ret = new JSObject();
        boolean opened = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                getActivity().startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                opened = true;
            } catch (Exception ignored) {
                opened = false;
            }
        }
        ret.put("opened", opened);
        call.resolve(ret);
    }

    // -------------------------------------------------------------- 内部

    private String mapFgsType(String mode) {
        if (mode == null) {
            return "specialUse";
        }
        switch (mode) {
            case "specialUse":
            case "auto":
            case "audio":
            case "location":
            case "backgroundTask":
            case "background-task":
            case "none":
                // Android 上 audio/location 是 iOS 的模式名：这里只用前台服务保活，
                // 不要冒充 mediaPlayback/location 类型（Google Play 会核对类型与真实功能）。
                return "specialUse";
            case "mediaPlayback":
                return "mediaPlayback";
            case "dataSync":
                return "dataSync";
            default:
                return null;
        }
    }

    private void applyNotificationExtras(PluginCall call, Intent intent) {
        com.getcapacitor.JSObject notification = call.getObject("notification");
        if (notification != null) {
            intent.putExtra(KeepaliveService.EXTRA_TITLE, notification.getString("title"));
            intent.putExtra(KeepaliveService.EXTRA_TEXT, notification.getString("text"));
            intent.putExtra(KeepaliveService.EXTRA_SUB_TEXT, notification.getString("subText"));
            intent.putExtra(KeepaliveService.EXTRA_SMALL_ICON, notification.getString("smallIcon"));
            intent.putExtra(KeepaliveService.EXTRA_CHANNEL_ID, notification.getString("channelId"));
            intent.putExtra(KeepaliveService.EXTRA_CHANNEL_NAME, notification.getString("channelName"));
            intent.putExtra(KeepaliveService.EXTRA_DISMISSIBLE,
                    Boolean.TRUE.equals(notification.getBool("dismissible")));
        }
        // updateNotification({title}) 这种顶层写法也支持
        if (call.getString("title") != null) {
            intent.putExtra(KeepaliveService.EXTRA_TITLE, call.getString("title"));
        }
        if (call.getString("text") != null) {
            intent.putExtra(KeepaliveService.EXTRA_TEXT, call.getString("text"));
        }
        if (call.getString("subText") != null) {
            intent.putExtra(KeepaliveService.EXTRA_SUB_TEXT, call.getString("subText"));
        }
    }

    private boolean waitForRunning(boolean expected, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (KeepaliveService.isRunning() == expected) {
                return true;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return KeepaliveService.isRunning() == expected;
    }

    private boolean isIgnoringBatteryOptimizations() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        PowerManager pm = (PowerManager) getContext().getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(getContext().getPackageName());
    }

    private boolean hasNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true;
        }
        return ContextCompat.checkSelfPermission(getContext(), Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private JSObject statusObject() {
        JSObject ret = new JSObject();
        boolean foreground = KeepaliveService.isRunning();
        boolean notificationPermission = hasNotificationPermission();
        ret.put("running", foreground);
        ret.put("platform", "android");
        ret.put("mode", foreground ? "foreground-service" : "none");
        ret.put("foregroundService", foreground);
        ret.put("notificationPermissionGranted", notificationPermission);
        ret.put("notificationVisible", foreground && notificationPermission);
        ret.put("batteryOptimizationIgnored", isIgnoringBatteryOptimizations());
        ret.put("fgsType", KeepaliveService.activeMode());
        if (foreground && !notificationPermission) {
            ret.put("note", "前台服务在运行，但 Android 13+ 没有 POST_NOTIFICATIONS 时通知不会显示；"
                    + "建议用 capacitor-permissions 申请 notifications 权限。");
        }
        return ret;
    }

    private JSObject modeCapability(String mode, boolean supported, String[] requires,
                                    String effect, boolean recommended) {
        JSObject ret = new JSObject();
        ret.put("mode", mode);
        ret.put("supported", supported);
        org.json.JSONArray list = new org.json.JSONArray();
        for (String item : requires) {
            list.put(item);
        }
        ret.put("requires", list);
        ret.put("effect", effect);
        ret.put("recommended", recommended);
        return ret;
    }
}
