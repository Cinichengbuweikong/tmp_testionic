package com.testionic.fullscreen;

import android.app.Activity;
import android.view.Window;

import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * Fullscreen 插件（Android 侧，jsName = "Fullscreen"）。
 *
 * ======================= 这个插件解决什么 =======================
 *
 * 沉浸式全屏：隐藏状态栏 + 导航栏，让 WebView 内容完整铺满屏幕。
 * （区别：capacitor-webview-to-edge 是内容延伸到系统栏下面，系统栏仍可见；
 *  本插件是系统栏完全隐藏。）
 *
 * ======================= 实现要点 =======================
 *
 * 1. 用 androidx 的 WindowInsetsControllerCompat.hide(systemBars()) 隐藏系统栏，
 *    与 Capacitor 8 内置 SystemBars 插件同一套 API，行为一致；
 *    并设置 BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE（从屏幕边缘滑动可临时唤出系统栏，
 *    几秒后自动隐藏——即 immersive sticky 的手感）。
 *
 * 2. 布局：系统栏隐藏后 insets 变为 0，Capacitor（或 webview-to-edge 插件）的
 *    insets 监听会自动把 WebView padding 置 0，内容自然铺满；退出全屏时 padding 自动还原。
 *    **本插件不需要手动操作 WebView 布局。**
 *
 * 3. 状态：插件自己维护 fullscreen 状态（初始 false），只在状态真正变化时派发
 *    fullscreenChanged 事件（重复 enable/disable 不重复派发）。
 *
 * 4. 线程：所有 Window/View 操作在主线程执行。
 */
@CapacitorPlugin(name = "Fullscreen")
public class FullscreenPlugin extends Plugin {

    private static final String EVENT_FULLSCREEN_CHANGED = "fullscreenChanged";

    /** 当前是否处于全屏（插件维护的逻辑状态）。 */
    private boolean fullscreen = false;

    // ---------------------------------------------------------------- API

    @PluginMethod
    public void enable(PluginCall call) {
        apply(true, call);
    }

    @PluginMethod
    public void disable(PluginCall call) {
        apply(false, call);
    }

    @PluginMethod
    public void setFullscreen(PluginCall call) {
        Boolean enabled = call.getBoolean("enabled", false);
        apply(Boolean.TRUE.equals(enabled), call);
    }

    @PluginMethod
    public void toggle(PluginCall call) {
        apply(!fullscreen, call);
    }

    @PluginMethod
    public void isFullscreen(PluginCall call) {
        getBridge().executeOnMainThread(() -> call.resolve(stateJson()));
    }

    // ---------------------------------------------------------------- 内部

    private void apply(boolean enabled, PluginCall call) {
        getBridge().executeOnMainThread(() -> {
            try {
                Activity activity = getActivity();
                if (activity == null) {
                    call.reject("No activity available");
                    return;
                }
                Window window = activity.getWindow();
                WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(
                    window,
                    window.getDecorView()
                );
                if (controller == null) {
                    call.reject("WindowInsetsController unavailable");
                    return;
                }

                boolean changed = fullscreen != enabled;
                if (enabled) {
                    // 滑动屏幕边缘时短暂显示系统栏，之后自动隐藏（immersive sticky 手感）
                    controller.setSystemBarsBehavior(
                        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    );
                    controller.hide(WindowInsetsCompat.Type.systemBars());
                } else {
                    controller.show(WindowInsetsCompat.Type.systemBars());
                }
                fullscreen = enabled;

                JSObject state = stateJson();
                if (changed) {
                    notifyListeners(EVENT_FULLSCREEN_CHANGED, state);
                }
                call.resolve(state);
            } catch (Exception e) {
                call.reject("apply fullscreen failed: " + e.getMessage());
            }
        });
    }

    /**
     * 状态输出。
     * Android 上状态栏与导航栏总是一起隐藏/恢复，所以两个字段与 fullscreen 同步。
     */
    private JSObject stateJson() {
        JSObject ret = new JSObject();
        ret.put("fullscreen", fullscreen);
        ret.put("statusBarHidden", fullscreen);
        ret.put("navigationBarHidden", fullscreen);
        return ret;
    }
}
