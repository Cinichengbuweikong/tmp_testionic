package com.testionic.backbutton;

import android.webkit.WebView;

import androidx.activity.OnBackPressedCallback;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * 返回键插件（BackButton）。
 *
 * 默认**不拦截**：Android 的返回键保持系统行为（WebView 能回退就回退，否则退出 App）。
 * 调用 `enable()` 后改用 AndroidX 的 {@link OnBackPressedCallback} 接管返回键：
 * 不再触发默认行为，而是派发 `backButton` 事件给 JS，由前端决定回路由 / 最小化 / 退出。
 */
@CapacitorPlugin(name = "BackButton")
public class BackButtonPlugin extends Plugin {

    private boolean enabled = false;
    private OnBackPressedCallback backCallback;

    @Override
    public void load() {
        backCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                // 回调在主线程，这里可以直接访问 WebView。
                WebView webView = getBridge().getWebView();
                JSObject data = new JSObject();
                data.put("canGoBack", webView != null && webView.canGoBack());
                notifyListeners("backButton", data);
            }
        };
        // LifecycleOwner 用 Activity 本身；Activity 销毁时 callback 会自动移除。
        getActivity().getOnBackPressedDispatcher().addCallback(getActivity(), backCallback);
    }

    @Override
    protected void handleOnDestroy() {
        if (backCallback != null) {
            backCallback.remove();
            backCallback = null;
        }
        super.handleOnDestroy();
    }

    @PluginMethod
    public void enable(PluginCall call) {
        enabled = true;
        if (backCallback != null) {
            backCallback.setEnabled(true);
        }
        resolveEnabled(call, true);
    }

    @PluginMethod
    public void disable(PluginCall call) {
        enabled = false;
        if (backCallback != null) {
            backCallback.setEnabled(false);
        }
        resolveEnabled(call, false);
    }

    @PluginMethod
    public void isEnabled(PluginCall call) {
        resolveEnabled(call, enabled);
    }

    @PluginMethod
    public void canGoBack(PluginCall call) {
        getActivity().runOnUiThread(() -> {
            WebView webView = getBridge().getWebView();
            JSObject ret = new JSObject();
            ret.put("canGoBack", webView != null && webView.canGoBack());
            call.resolve(ret);
        });
    }

    @PluginMethod
    public void goBack(PluginCall call) {
        getActivity().runOnUiThread(() -> {
            WebView webView = getBridge().getWebView();
            boolean wentBack = webView != null && webView.canGoBack();
            if (wentBack) {
                webView.goBack();
            }
            JSObject ret = new JSObject();
            ret.put("wentBack", wentBack);
            call.resolve(ret);
        });
    }

    @PluginMethod
    public void exitApp(PluginCall call) {
        getActivity().runOnUiThread(() -> {
            getActivity().finish();
            call.resolve();
        });
    }

    @PluginMethod
    public void minimizeApp(PluginCall call) {
        getActivity().runOnUiThread(() -> {
            getActivity().moveTaskToBack(true);
            call.resolve();
        });
    }

    /** iOS 专属能力；Android 无侧滑返回手势，固定返回 false。 */
    @PluginMethod
    public void setSwipeBackEnabled(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("enabled", false);
        call.resolve(ret);
    }

    /** iOS 专属能力；Android 固定返回 false。 */
    @PluginMethod
    public void getSwipeBackEnabled(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("enabled", false);
        call.resolve(ret);
    }

    private void resolveEnabled(PluginCall call, boolean value) {
        JSObject ret = new JSObject();
        ret.put("enabled", value);
        call.resolve(ret);
    }
}
