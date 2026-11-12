package com.testionic.bootautostart;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import androidx.core.app.NotificationManagerCompat;

import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

/**
 * 开机自启插件（Android）。
 *
 * 能力：开启后注册 BOOT_COMPLETED → 开机启动前台服务 → 保持进程存活 →
 * 用户点图标时热启动（不再冷启动）。
 *
 * 受系统限制、**做不到**的事：
 * - 开机自动把界面弹到前台（Android 10+ 禁止后台启动 Activity）；
 * - 在所有厂商 ROM 上自动生效（MIUI/HyperOS 等需要用户手动授权"自启动"）。
 */
@CapacitorPlugin(
    name = "BootAutostart",
    permissions = {
        @Permission(alias = "notifications", strings = { Manifest.permission.POST_NOTIFICATIONS })
    }
)
public class BootAutostartPlugin extends Plugin {

    @PluginMethod
    public void getStatus(PluginCall call) {
        call.resolve(buildStatus());
    }

    @PluginMethod
    public void enable(PluginCall call) {
        Context context = getContext();
        BootAutostartStore.setEnabled(context, true);
        setReceiverEnabled(context, true);
        startBootService(context);
        // 服务是异步启动的：稍等一小会儿再读取真实状态，避免 enable() 立刻返回 serviceRunning=false
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            notifyServiceState(BootService.isRunning());
            call.resolve(buildStatus());
        }, 400);
    }

    @PluginMethod
    public void disable(PluginCall call) {
        Context context = getContext();
        BootAutostartStore.setEnabled(context, false);
        setReceiverEnabled(context, false);
        context.stopService(new Intent(context, BootService.class));
        notifyServiceState(false);
        call.resolve(buildStatus());
    }

    @PluginMethod
    public void requestNotificationPermission(PluginCall call) {
        if (Build.VERSION.SDK_INT >= 33 && getPermissionState("notifications") != PermissionState.GRANTED) {
            requestPermissionForAlias("notifications", call, "notificationsCallback");
        } else {
            call.resolve(buildStatus());
        }
    }

    @PermissionCallback
    private void notificationsCallback(PluginCall call) {
        call.resolve(buildStatus());
    }

    @PluginMethod
    public void requestIgnoreBatteryOptimizations(PluginCall call) {
        Context context = getContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            boolean ignored = powerManager != null && powerManager.isIgnoringBatteryOptimizations(context.getPackageName());
            if (!ignored) {
                try {
                    Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(Uri.parse("package:" + context.getPackageName()));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(intent);
                } catch (Exception ignoredError) {
                    // 部分 ROM 没有该页面，忽略
                }
            }
        }
        call.resolve(buildStatus());
    }

    @PluginMethod
    public void openAppSettings(PluginCall call) {
        Context context = getContext();
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.parse("package:" + context.getPackageName()));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
        call.resolve();
    }

    @PluginMethod
    public void openAutostartSettings(PluginCall call) {
        Context context = getContext();
        String[][] candidates = new String[][] {
            { "miui.intent.action.OP_AUTO_START", null },
            { "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity", "com.huawei.systemmanager" },
            { "com.coloros.safecenter.permission.startup.StartupAppListActivity", "com.coloros.safecenter" },
            { "com.vivo.permissionmanager.activity.BgStartUpManagerActivity", "com.vivo.permissionmanager" }
        };
        for (String[] candidate : candidates) {
            try {
                Intent intent = new Intent(candidate[0]);
                if (candidate[1] != null) {
                    intent.setClassName(candidate[1], candidate[0]);
                }
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (intent.resolveActivity(context.getPackageManager()) != null) {
                    context.startActivity(intent);
                    call.resolve();
                    return;
                }
            } catch (Exception ignored) {
                // 尝试下一个
            }
        }
        // 都不可用则回退到应用详情页
        openAppSettings(call);
    }

    private void notifyServiceState(boolean running) {
        JSObject data = new JSObject();
        data.put("running", running);
        notifyListeners("serviceStateChanged", data);
    }

    static void setReceiverEnabled(Context context, boolean enabled) {
        ComponentName component = new ComponentName(context, BootReceiver.class);
        int state = enabled
            ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        context.getPackageManager().setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP);
    }

    static boolean isReceiverEnabled(Context context) {
        int state = context.getPackageManager().getComponentEnabledSetting(
            new ComponentName(context, BootReceiver.class)
        );
        if (state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
            return false;
        }
        // DEFAULT/ENABLED 都视为启用（manifest 里默认 enabled=true）
        return true;
    }

    static void startBootService(Context context) {
        Intent intent = new Intent(context, BootService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    private JSObject buildStatus() {
        Context context = getContext();
        JSObject ret = new JSObject();
        ret.put("supported", true);
        ret.put("enabled", BootAutostartStore.isEnabled(context));
        ret.put("receiverEnabled", isReceiverEnabled(context));
        ret.put("serviceRunning", BootService.isRunning());
        ret.put("notificationsEnabled", NotificationManagerCompat.from(context).areNotificationsEnabled());
        boolean ignored = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            ignored = powerManager != null && powerManager.isIgnoringBatteryOptimizations(context.getPackageName());
        }
        ret.put("batteryOptimizationIgnored", ignored);
        ret.put("sdk", Build.VERSION.SDK_INT);
        return ret;
    }
}
