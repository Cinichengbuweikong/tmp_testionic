package com.testionic.rotation;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.os.Build;
import android.view.Display;
import android.view.Surface;
import android.view.WindowManager;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * Rotation 插件（Android 侧，jsName = "Rotation"）。
 *
 * ======================= 这个插件解决什么 =======================
 *
 * 1. 监听屏幕旋转：设备旋转后派发 `rotationChanged` 事件给 WebView，
 *    并在事件载荷里带上当前方向（portrait / landscape、角度 0/90/180/270）。
 * 2. 主动控制方向：lock({ orientation: 'landscape' | 'portrait' | 'auto' }) /
 *    unlock() —— 典型用途是视频「全屏播放」：锁定横屏 + 全屏，退出时解锁。
 *
 * ======================= 实现要点 =======================
 *
 * 1. 旋转事件的来源是 Activity.onConfigurationChanged → Capacitor 桥 →
 *    Plugin.handleOnConfigurationChanged。前提是宿主 MainActivity 的
 *    android:configChanges 里包含 orientation|screenSize（Capacitor 模板默认包含），
 *    这样旋转不会重建 Activity，回调才会走到插件里。
 *    锁定方向同样走这条链路：setRequestedOrientation 触发旋转 → 配置变化 → 事件派发。
 *
 * 2. 方向锁定用 Activity.setRequestedOrientation：
 *    - landscape → SCREEN_ORIENTATION_SENSOR_LANDSCAPE（允许左右两个横屏方向，视频体验）；
 *    - portrait  → SCREEN_ORIENTATION_SENSOR_PORTRAIT；
 *    - auto      → SCREEN_ORIENTATION_UNSPECIFIED（恢复系统自动旋转）。
 *
 * 3. 去重：onConfigurationChanged 也会因键盘/字体/主题等变化触发，
 *    只有「方向或角度真的变了」才派发事件（记录上次值对比）。
 *
 * 4. 监听/解除监听是 Capacitor 桥的内置能力（JS 侧 addListener/removeListener），
 *    原生只需要在变化时 notifyListeners，没有 listener 时广播是无害的。
 */
@CapacitorPlugin(name = "Rotation")
public class RotationPlugin extends Plugin {

    private static final String EVENT_ROTATION_CHANGED = "rotationChanged";

    /** 上次派发的状态，用于去重。 */
    private String lastOrientation = null;
    private int lastRotationDegrees = -1;

    /** 当前方向锁定：landscape / portrait / auto（auto = 跟随系统）。 */
    private String lockedOrientation = "auto";

    @Override
    public void load() {
        super.load();
        // 记录初始值，避免首次配置回调误报
        JSObject state = currentState();
        lastOrientation = state.getString("orientation", "unknown");
        lastRotationDegrees = state.optInt("rotationDegrees", 0);
    }

    /**
     * 屏幕旋转（以及其他配置变化）都会走到这里，去重后派发事件。
     * 注意：方法跑在桥线程，读 Display/Configuration 要切主线程。
     */
    @Override
    public void handleOnConfigurationChanged(Configuration newConfig) {
        super.handleOnConfigurationChanged(newConfig);
        getBridge().executeOnMainThread(() -> {
            JSObject state = currentState();
            String orientation = state.getString("orientation", "unknown");
            int degrees = state.optInt("rotationDegrees", 0);
            if (orientation.equals(lastOrientation) && degrees == lastRotationDegrees) {
                return; // 非方向变化（键盘、字体等），不派发
            }
            lastOrientation = orientation;
            lastRotationDegrees = degrees;
            notifyListeners(EVENT_ROTATION_CHANGED, state);
        });
    }

    /** 读取当前旋转状态。 */
    @PluginMethod
    public void getRotation(PluginCall call) {
        getBridge().executeOnMainThread(() -> {
            try {
                call.resolve(currentState());
            } catch (Exception e) {
                call.reject("getRotation failed: " + e.getMessage());
            }
        });
    }

    // ---------------------------------------------------------------- 方向锁定

    /**
     * 锁定屏幕方向（视频全屏播放等场景）。
     *
     * landscape → SENSOR_LANDSCAPE（允许左右两个横屏方向）；
     * portrait  → SENSOR_PORTRAIT；
     * auto      → 恢复系统自动旋转。
     */
    @PluginMethod
    public void lock(PluginCall call) {
        String orientation = call.getString("orientation", "landscape");
        final String normalized;
        final int requested;
        if ("landscape".equals(orientation)) {
            normalized = "landscape";
            requested = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
        } else if ("portrait".equals(orientation)) {
            normalized = "portrait";
            requested = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT;
        } else if ("auto".equals(orientation)) {
            normalized = "auto";
            requested = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
        } else {
            call.reject("orientation must be one of: landscape, portrait, auto");
            return;
        }

        getBridge().executeOnMainThread(() -> {
            Activity activity = getActivity();
            if (activity == null) {
                call.reject("No activity available");
                return;
            }
            try {
                lockedOrientation = normalized;
                activity.setRequestedOrientation(requested);
                call.resolve(lockStateJson());
            } catch (Exception e) {
                call.reject("lock failed: " + e.getMessage());
            }
        });
    }

    /** 解除方向锁定，恢复系统自动旋转。 */
    @PluginMethod
    public void unlock(PluginCall call) {
        getBridge().executeOnMainThread(() -> {
            Activity activity = getActivity();
            if (activity == null) {
                call.reject("No activity available");
                return;
            }
            try {
                lockedOrientation = "auto";
                activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
                call.resolve(lockStateJson());
            } catch (Exception e) {
                call.reject("unlock failed: " + e.getMessage());
            }
        });
    }

    /** 读取当前方向锁定状态。 */
    @PluginMethod
    public void getLock(PluginCall call) {
        call.resolve(lockStateJson());
    }

    private JSObject lockStateJson() {
        JSObject ret = new JSObject();
        ret.put("orientation", lockedOrientation);
        ret.put("locked", !"auto".equals(lockedOrientation));
        return ret;
    }

    // ---------------------------------------------------------------- 内部实现

    @SuppressWarnings("deprecation")
    private JSObject currentState() {
        Context context = getContext();
        Configuration config = context.getResources().getConfiguration();

        String orientation;
        if (config.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            orientation = "landscape";
        } else if (config.orientation == Configuration.ORIENTATION_PORTRAIT) {
            orientation = "portrait";
        } else {
            orientation = "unknown";
        }

        int degrees = 0;
        Display display = null;
        if (Build.VERSION.SDK_INT >= 30 && getActivity() != null) {
            display = getActivity().getDisplay();
        } else {
            WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            if (wm != null) {
                display = wm.getDefaultDisplay();
            }
        }
        if (display != null) {
            int rotation = display.getRotation();
            degrees = rotation == Surface.ROTATION_90
                ? 90
                : rotation == Surface.ROTATION_180 ? 180 : rotation == Surface.ROTATION_270 ? 270 : 0;
        }

        JSObject ret = new JSObject();
        ret.put("orientation", orientation);
        ret.put("rotationDegrees", degrees);
        ret.put("isPortrait", "portrait".equals(orientation));
        ret.put("isLandscape", "landscape".equals(orientation));
        return ret;
    }
}
