package com.testionic.keyboardavoiding;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.webkit.WebView;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * KeyboardAvoiding 插件（Android 侧，jsName = "KeyboardAvoiding"）。
 *
 * ======================= 这个插件解决什么 =======================
 *
 * 1. 监听键盘弹出/隐藏，派发 keyboardChanged 事件（带键盘高度、当前可用视口高度），
 *    页面据此自己实现 KeyboardAvoidingView 式布局；
 * 2. 可选的自动避让（enable()）：保证 WebView 内容不被键盘遮挡。
 *
 * ======================= 关键实现（实测结论） =======================
 *
 * 真机 Redmi K30S Ultra / MIUI 12 / Android 10 实测：
 *   键盘弹出前 WebView 高度 2310px、innerHeight 840css；
 *   键盘弹出后 WebView 高度 1382px、innerHeight 503css（键盘 928px / 337css）。
 * 也就是说**系统已经自动把 WebView 缩到键盘上方了**（adjustResize）。
 *
 * 所以自动避让**不能盲加 padding**，否则会双重缩小。这里的策略是"补差"：
 *   1. 用 ViewCompat.getRootWindowInsets(decorView).getInsets(ime) 读键盘高度
 *      —— 只用 getRootWindowInsets 读原始 insets，**不注册 OnApplyWindowInsetsListener**，
 *      因此不会和 webview-to-edge / Capacitor SystemBars 的监听器互相覆盖（本项目踩过这个坑）；
 *   2. autoAvoid 开启时，用 WebView 的 bottomMargin 补齐"系统没让出的那部分"：
 *      反推出未加 margin 时的 WebView 底部，与键盘顶边比较得到 deficit，margin = baseline + deficit。
 *      系统已避让时 deficit=0（不会重复缩小）；系统没避让时 deficit=键盘高度（正好补齐）。
 *      计算基于"反推的原始布局"，多次调用结果一致，不会抖动。
 *
 * 事件来源：ViewTreeObserver.OnGlobalLayoutListener（键盘弹出/收起都会引起布局变化）+
 * 每次布局时读当前 insets 的 ime 值。去重后派发。
 *
 * 线程：所有 View 操作/insets 读取都在主线程执行。
 */
@CapacitorPlugin(name = "KeyboardAvoiding")
public class KeyboardAvoidingPlugin extends Plugin {

    private static final String EVENT_KEYBOARD_CHANGED = "keyboardChanged";

    /** 是否开启自动避让（默认关闭：系统通常已经避让，纯监听模式更安全）。 */
    private boolean autoAvoid = false;

    private boolean keyboardVisible = false;
    private int keyboardHeightPx = 0;

    /** WebView 原始的 bottomMargin（未避让时），-1 表示尚未记录。 */
    private int baselineBottomMargin = -1;

    private ViewTreeObserver.OnGlobalLayoutListener layoutListener;
    private View observedDecorView;

    // ---------------------------------------------------------------- 生命周期

    @Override
    public void load() {
        super.load();
        getBridge().executeOnMainThread(this::installLayoutListener);
    }

    @Override
    protected void handleOnStart() {
        super.handleOnStart();
        // 回到前台后重新挂监听（防某些 ROM 在后台时移除监听/布局重建）
        getBridge().executeOnMainThread(this::installLayoutListener);
    }

    @Override
    protected void handleOnDestroy() {
        super.handleOnDestroy();
        removeLayoutListener();
    }

    // ---------------------------------------------------------------- API

    @PluginMethod
    public void enable(PluginCall call) {
        setAutoAvoid(true, call);
    }

    @PluginMethod
    public void disable(PluginCall call) {
        setAutoAvoid(false, call);
    }

    @PluginMethod
    public void setEnabled(PluginCall call) {
        Boolean enabled = call.getBoolean("enabled", false);
        setAutoAvoid(Boolean.TRUE.equals(enabled), call);
    }

    @PluginMethod
    public void isEnabled(PluginCall call) {
        getBridge().executeOnMainThread(() -> call.resolve(stateJson()));
    }

    @PluginMethod
    public void getState(PluginCall call) {
        getBridge().executeOnMainThread(() -> call.resolve(stateJson()));
    }

    // ---------------------------------------------------------------- 内部实现

    private void setAutoAvoid(boolean enabled, PluginCall call) {
        getBridge().executeOnMainThread(() -> {
            try {
                autoAvoid = enabled;
                if (enabled) {
                    // 立即应用当前键盘状态（例如键盘已弹出时开启，也要马上避让）
                    evaluateKeyboard();
                } else {
                    restoreBaselineMargin();
                }
                call.resolve(stateJson());
            } catch (Exception e) {
                call.reject("set auto avoid failed: " + e.getMessage());
            }
        });
    }

    private void installLayoutListener() {
        Activity activity = getActivity();
        if (activity == null) {
            return;
        }
        View decorView = activity.getWindow().getDecorView();
        if (observedDecorView != decorView) {
            removeLayoutListener();
            observedDecorView = decorView;
            if (layoutListener == null) {
                layoutListener = this::evaluateKeyboard;
            }
        }
        observedDecorView.getViewTreeObserver().addOnGlobalLayoutListener(layoutListener);
        evaluateKeyboard();
    }

    @SuppressWarnings("deprecation")
    private void removeLayoutListener() {
        if (observedDecorView != null && layoutListener != null) {
            ViewTreeObserver observer = observedDecorView.getViewTreeObserver();
            if (observer.isAlive()) {
                observer.removeOnGlobalLayoutListener(layoutListener);
            }
        }
        observedDecorView = null;
        layoutListener = null;
    }

    /** 读取当前 IME 状态；变化时派发事件，必要时应用自动避让。 */
    private void evaluateKeyboard() {
        View decorView = observedDecorView;
        if (decorView == null) {
            Activity activity = getActivity();
            if (activity == null) {
                return;
            }
            decorView = activity.getWindow().getDecorView();
            observedDecorView = decorView;
        }

        WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(decorView);
        if (insets == null) {
            return;
        }

        boolean visible = insets.isVisible(WindowInsetsCompat.Type.ime());
        int imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
        int heightPx = visible ? imeBottom : 0;

        boolean changed = visible != keyboardVisible || heightPx != keyboardHeightPx;
        keyboardVisible = visible;
        keyboardHeightPx = heightPx;

        if (autoAvoid) {
            applyAutoAvoid(heightPx);
        }

        if (changed) {
            notifyListeners(EVENT_KEYBOARD_CHANGED, stateJson());
        }
    }

    /**
     * 自动避让：把 WebView 底部抬高到键盘上方，只补"系统还没让出"的部分。
     */
    private void applyAutoAvoid(int imeHeightPx) {
        WebView webView = getBridge() != null ? getBridge().getWebView() : null;
        if (webView == null) {
            return;
        }
        ViewGroup.LayoutParams rawParams = webView.getLayoutParams();
        if (!(rawParams instanceof ViewGroup.MarginLayoutParams)) {
            return;
        }
        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) rawParams;
        if (baselineBottomMargin < 0) {
            baselineBottomMargin = params.bottomMargin;
        }

        int target = baselineBottomMargin + keyboardDeficitPx(webView, params, imeHeightPx);
        if (params.bottomMargin != target) {
            params.bottomMargin = target;
            webView.setLayoutParams(params);
        }
    }

    /**
     * 计算"还差多少没被系统让出"：
     * 用当前 margin 反推出未加避让时的 WebView 底部，再与键盘顶边比较。
     * 由于是反推原始布局，多次调用结果一致（幂等），不会出现"加了又减"的抖动。
     */
    private int keyboardDeficitPx(WebView webView, ViewGroup.MarginLayoutParams params, int imeHeightPx) {
        if (imeHeightPx <= 0) {
            return 0;
        }
        Activity activity = getActivity();
        if (activity == null) {
            return 0;
        }
        int[] location = new int[2];
        webView.getLocationOnScreen(location);
        int currentBottom = location[1] + webView.getHeight();
        int rawBottom = currentBottom - params.bottomMargin;
        int keyboardTop = screenHeightPx(activity) - imeHeightPx;
        return Math.max(0, rawBottom - keyboardTop);
    }

    private void restoreBaselineMargin() {
        WebView webView = getBridge() != null ? getBridge().getWebView() : null;
        if (webView == null || baselineBottomMargin < 0) {
            return;
        }
        ViewGroup.LayoutParams rawParams = webView.getLayoutParams();
        if (rawParams instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) rawParams;
            if (params.bottomMargin != baselineBottomMargin) {
                params.bottomMargin = baselineBottomMargin;
                webView.setLayoutParams(params);
            }
        }
    }

    @SuppressWarnings("deprecation")
    private int screenHeightPx(Activity activity) {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowManager wm = (WindowManager) activity.getSystemService(Context.WINDOW_SERVICE);
            return wm.getMaximumWindowMetrics().getBounds().height();
        }
        DisplayMetrics metrics = new DisplayMetrics();
        activity.getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
        return metrics.heightPixels;
    }

    private JSObject stateJson() {
        float density = getContext().getResources().getDisplayMetrics().density;
        WebView webView = getBridge() != null ? getBridge().getWebView() : null;
        int viewportPx = webView != null ? webView.getHeight() : 0;

        JSObject ret = new JSObject();
        ret.put("visible", keyboardVisible);
        ret.put("height", css(keyboardHeightPx, density));
        ret.put("heightPx", keyboardHeightPx);
        ret.put("viewportHeight", css(viewportPx, density));
        ret.put("viewportHeightPx", viewportPx);
        ret.put("autoAvoid", autoAvoid);
        ret.put("animationDuration", 0);
        return ret;
    }

    private double css(int px, float density) {
        if (density <= 0) {
            return px;
        }
        return Math.round(px / density * 100.0) / 100.0;
    }
}
