package com.testionic.python;

import android.os.Build;
import android.webkit.WebSettings;
import android.webkit.WebView;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Python 插件的 Android 原生壳。
 *
 * 为什么这里几乎没有逻辑：
 * Python 引擎是 Pyodide（CPython → WebAssembly），跑在 WebView 的 Web Worker 里，
 * 所以真正的会话/stdio/pip 逻辑在 JS 侧（src/engine/*）。原生壳负责两件事：
 *   1. 告诉 JS 侧当前 WebView 的版本，用于判断是否满足 Pyodide 的最低要求；
 *   2. 预留未来"原生 CPython 引擎"的挂载点（Chaquopy 等），届时同一份 JS API 可以切引擎。
 */
@CapacitorPlugin(name = "Python")
public class PythonPlugin extends Plugin {

    /** Pyodide 314.x 要求 Android WebView（Chrome）主版本 ≥ 90。 */
    private static final int MIN_CHROME_MAJOR = 90;

    @Override
    public void load() {
        // 触发一次 WebView 初始化，确保能拿到 default user agent
        if (getActivity() != null) {
            try {
                new WebView(getActivity());
            } catch (Exception ignored) {
                // ignore：取不到时 getInfo 会返回 supported=null
            }
        }
    }

    @PluginMethod
    public void getInfo(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("platform", "android");
        ret.put("engine", "pyodide");
        ret.put("sdkInt", Build.VERSION.SDK_INT);

        int chromeMajor = chromeMajor();
        if (chromeMajor > 0) {
            ret.put("webViewVersion", String.valueOf(chromeMajor));
            ret.put("supported", chromeMajor >= MIN_CHROME_MAJOR);
            if (chromeMajor < MIN_CHROME_MAJOR) {
                ret.put("note", "Android System WebView 版本过低（Chrome/" + chromeMajor
                        + "），Pyodide 需要 ≥ Chrome/" + MIN_CHROME_MAJOR
                        + "。请在应用商店更新 Android System WebView。");
            }
        } else {
            ret.put("supported", null);
            ret.put("note", "无法解析 WebView 版本（user agent 不可用）");
        }
        call.resolve(ret);
    }

    /** 从 WebView 的默认 User-Agent 里解析 Chrome 主版本号，失败返回 -1。 */
    private int chromeMajor() {
        try {
            String ua;
            if (getActivity() != null) {
                ua = WebSettings.getDefaultUserAgent(getActivity());
            } else {
                ua = System.getProperty("http.agent", "");
            }
            Matcher m = Pattern.compile("Chrome/(\\d+)").matcher(ua == null ? "" : ua);
            if (m.find()) {
                return Integer.parseInt(m.group(1));
            }
        } catch (Exception ignored) {
            // fall through
        }
        return -1;
    }
}
