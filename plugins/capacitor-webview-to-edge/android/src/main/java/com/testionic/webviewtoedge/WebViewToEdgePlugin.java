package com.testionic.webviewtoedge;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.WebView;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.WebViewListener;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * WebViewToEdge 插件（Android 侧，jsName = "WebViewToEdge"）。
 *
 * ============================ 这个插件解决什么 ============================
 *
 * 需求：让 WebView 的内容**铺满整屏**、延伸到状态栏（以及底部导航栏）下面，
 * 类似 React Native 里 `StatusBar translucent` 的效果（注意：不是隐藏系统栏的"全屏模式"）。
 *
 * 为什么 Capacitor 默认不是这样：
 *   Capacitor 8 的核心插件 `SystemBars` 会在 DecorView 上装一个 insets 监听器，
 *   当 WebView 主版本 < 140（或者页面没写 viewport-fit=cover）时，它给 DecorView
 *   加上状态栏高度的 padding，内容自然就落在状态栏下方了。
 *   实测：Redmi K30S Ultra（WebView 87）内容从 y=90px 开始，Pixel 6 模拟器（WebView 133）从 y=128px 开始。
 *
 * 本插件的做法（**纯运行时 API，不需要改 capacitor.config**）：
 *   1. 在 DecorView 上装自己的 OnApplyWindowInsetsListener（后装会覆盖 SystemBars 的）；
 *   2. enabled 时：padding 全部置 0（只在键盘弹出时保留底部 IME padding，避免键盘挡住输入框）；
 *      disabled 时：padding 恢复成系统栏高度（等价 Capacitor 默认行为）；
 *   3. 每次 insets 变化，把安全区写进页面的 CSS 变量：
 *        --safe-area-inset-top / right / bottom / left（单位 px）
 *      并派发 insetsChanged 事件。
 *
 * 为什么要自己注入 CSS 变量：
 *   实测老 WebView（87）里 `env(safe-area-inset-top)` 恒为 0；
 *   WebView 133 + Android 16 上则是正常的 49px。为了两端行为一致，统一走插件变量。
 *   同时我们把返回给 WebView 的 insets 清零（和 Capacitor 的做法一致），避免二次叠加。
 *
 * ============================ 使用方式 ============================
 *
 * <pre>
 *   import { WebViewToEdge } from 'capacitor-webview-to-edge';
 *
 *   await WebViewToEdge.enable();   // 内容铺满整屏
 *   await WebViewToEdge.disable();  // 恢复默认（内容让出状态栏）
 *   const insets = await WebViewToEdge.getInsets(); // { top, right, bottom, left }
 * </pre>
 *
 * 页面里给顶部内容留空间：
 *
 * <pre>
 *   .header {
 *     padding-top: var(--safe-area-inset-top, env(safe-area-inset-top, 0px));
 *   }
 * </pre>
 *
 * ============================ 线程 ============================
 *
 * Capacitor 插件方法运行在 "CapacitorPlugins" 后台线程，
 * 所有 View / WebView 操作都必须切主线程（本类统一用 executeOnMainThread / webView.post）。
 */
@CapacitorPlugin(name = "WebViewToEdge")
public class WebViewToEdgePlugin extends Plugin {

    /** 是否已开启"内容延伸到屏幕边缘"。默认关闭（与 Capacitor 默认行为一致）。 */
    private boolean enabled = false;

    /** 缓存的安全区，单位 dp（≈ CSS px）。 */
    private int insetTop = 0;
    private int insetRight = 0;
    private int insetBottom = 0;
    private int insetLeft = 0;
    private boolean hasInsets = false;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private WebViewListener pageListener;

    // ---------------------------------------------------------------- 生命周期

    @Override
    public void load() {
        super.load();

        getBridge().executeOnMainThread(this::installInsetsListener);

        // 页面每次加载完成后补一次 CSS 变量（WebView 重载后 <html> 上的变量会丢）
        pageListener =
            new WebViewListener() {
                @Override
                public void onPageCommitVisible(WebView webView, String url) {
                    injectCssVariables();
                }

                @Override
                public void onPageLoaded(WebView webView) {
                    injectCssVariables();
                }
            };
        getBridge().addWebViewListener(pageListener);
    }

    /**
     * 接管 DecorView 的 insets 处理。
     *
     * 注意：必须在 UI 线程执行；后设置的 listener 会覆盖 Capacitor SystemBars 的那个。
     */
    private void installInsetsListener() {
        Activity activity = getActivity();
        if (activity == null) {
            return;
        }

        final View decorView = activity.getWindow().getDecorView();
        ViewCompat.setOnApplyWindowInsetsListener(decorView, (view, insets) -> {
            Insets bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout()
            );
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            boolean keyboardVisible = insets.isVisible(WindowInsetsCompat.Type.ime());

            // 开启：四边都贴边；关闭：让出系统栏（等价 Capacitor 默认）
            int paddingLeft = enabled ? 0 : bars.left;
            int paddingTop = enabled ? 0 : bars.top;
            int paddingRight = enabled ? 0 : bars.right;
            // 键盘弹出时始终给底部留出 IME 高度，否则输入框会被键盘挡住
            int paddingBottom = keyboardVisible ? ime.bottom : (enabled ? 0 : bars.bottom);
            view.setPadding(paddingLeft, paddingTop, paddingRight, paddingBottom);

            updateInsets(bars);

            // 与 Capacitor 一致：把系统栏 insets 清零后再往下传，避免 WebView 再叠一层安全区。
            // 页面统一使用插件注入的 --safe-area-inset-* 变量。
            return new WindowInsetsCompat.Builder(insets)
                .setInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout(),
                    Insets.of(0, 0, 0, 0)
                )
                .build();
        });

        ViewCompat.requestApplyInsets(decorView);
    }

    // ---------------------------------------------------------------- 对外方法

    @PluginMethod
    public void enable(PluginCall call) {
        applyEnabled(true);
        call.resolve(stateJson());
    }

    @PluginMethod
    public void disable(PluginCall call) {
        applyEnabled(false);
        call.resolve(stateJson());
    }

    @PluginMethod
    public void setEnabled(PluginCall call) {
        Boolean value = call.getBoolean("enabled", false);
        applyEnabled(Boolean.TRUE.equals(value));
        call.resolve(stateJson());
    }

    @PluginMethod
    public void isEnabled(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("enabled", enabled);
        call.resolve(ret);
    }

    @PluginMethod
    public void getInsets(PluginCall call) {
        getBridge().executeOnMainThread(() -> {
            ensureInsetsMeasured();
            call.resolve(insetsJson());
        });
    }

    // ---------------------------------------------------------------- 内部实现

    /** 切换开关并触发一次重新布局（padding 会在下一次 insets 分发时更新）。 */
    private void applyEnabled(boolean value) {
        enabled = value;
        getBridge().executeOnMainThread(() -> {
            Activity activity = getActivity();
            if (activity != null) {
                ViewCompat.requestApplyInsets(activity.getWindow().getDecorView());
            }
        });
    }

    private void ensureInsetsMeasured() {
        if (hasInsets) {
            return;
        }
        Activity activity = getActivity();
        if (activity == null) {
            return;
        }
        WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(activity.getWindow().getDecorView());
        if (insets != null) {
            updateInsets(insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout()));
        }
    }

    private void updateInsets(Insets bars) {
        float density = getContext().getResources().getDisplayMetrics().density;
        int newTop = Math.round(bars.top / density);
        int newRight = Math.round(bars.right / density);
        int newBottom = Math.round(bars.bottom / density);
        int newLeft = Math.round(bars.left / density);

        boolean changed = !hasInsets
            || newTop != insetTop || newRight != insetRight || newBottom != insetBottom || newLeft != insetLeft;

        insetTop = newTop;
        insetRight = newRight;
        insetBottom = newBottom;
        insetLeft = newLeft;
        hasInsets = true;

        injectCssVariables();
        if (changed) {
            notifyListeners("insetsChanged", insetsJson());
        }
    }

    /** 把安全区写进页面的 CSS 变量（页面重载/旋转/主题变化后都会重写）。 */
    private void injectCssVariables() {
        WebView webView = getBridge() != null ? getBridge().getWebView() : null;
        if (webView == null) {
            return;
        }
        String js =
            "(function(){var r=document.documentElement;if(!r){return;}" +
            "r.style.setProperty('--safe-area-inset-top','" + insetTop + "px');" +
            "r.style.setProperty('--safe-area-inset-right','" + insetRight + "px');" +
            "r.style.setProperty('--safe-area-inset-bottom','" + insetBottom + "px');" +
            "r.style.setProperty('--safe-area-inset-left','" + insetLeft + "px');" +
            "})();";
        webView.post(() -> webView.evaluateJavascript(js, null));
    }

    private JSObject insetsJson() {
        JSObject ret = new JSObject();
        ret.put("top", insetTop);
        ret.put("right", insetRight);
        ret.put("bottom", insetBottom);
        ret.put("left", insetLeft);
        return ret;
    }

    private JSObject stateJson() {
        JSObject ret = new JSObject();
        ret.put("enabled", enabled);
        ret.put("insets", insetsJson());
        return ret;
    }
}
