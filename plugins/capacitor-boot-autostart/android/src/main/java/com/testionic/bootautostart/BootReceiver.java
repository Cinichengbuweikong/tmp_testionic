package com.testionic.bootautostart;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 开机广播接收器：收到 BOOT_COMPLETED 后，若用户开启了开机自启，
 * 就启动前台服务（{@link BootService}）保持进程存活。
 *
 * 注意：Android 10+ 禁止后台启动 Activity，所以这里**不能**直接把界面弹出来；
 * 只能保持进程，用户点图标时热启动。
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String ACTION_QUICKBOOT = "android.intent.action.QUICKBOOT_POWERON";
    private static final String ACTION_HTC_QUICKBOOT = "com.htc.intent.action.QUICKBOOT_POWERON";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
            && !ACTION_QUICKBOOT.equals(action)
            && !ACTION_HTC_QUICKBOOT.equals(action)) {
            return;
        }
        if (!BootAutostartStore.isEnabled(context)) {
            return;
        }
        try {
            BootAutostartPlugin.startBootService(context);
        } catch (Exception ignored) {
            // 部分 ROM 在开机阶段不允许启动前台服务；忽略即可，用户手动打开 App 后仍会生效
        }
    }
}
