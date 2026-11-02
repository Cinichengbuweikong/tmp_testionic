package com.testionic.setforcedark;

import android.app.Activity;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebSettings;
import android.webkit.WebView;

import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewFeature;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.WebViewListener;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.util.Locale;

/**
 * SetForceDark 插件（Android 侧，jsName = "SetForceDark"）。
 *
 * ============================ 插件职责（别和"系统深色模式"搞混） ============================
 *
 * 本插件解决的是「WebView 里这个页面显示成什么样」的问题：
 *   - 设置 / 关闭 WebView 的 forceDark（算法暗色），避免 ROM 把页面颜色反坏；
 *   - 把 App 期望的主题告诉页面（data-theme / color-scheme）；
 *   - 提供主题查询与变化监听。
 *
 * 它**不会**去改系统的深色模式开关，也不会影响系统 UI 或其它 App。
 *
 * ============================ 一、要解决的问题（实测） ============================
 *
 * 背景：Android 10 起系统提供 Force Dark（算法暗色）——App 没做深色适配时，
 * 系统会把界面"反色"成深色，WebView 也支持这套机制。
 *
 * 实测环境：Redmi K30S Ultra / Android 10 / MIUI 12 / WebView 87（系统为深色模式）
 *
 *   - 原生侧：Activity 的 Configuration 里确实是 `night`（App 已进入深色模式）；
 *   - WebView 里：`matchMedia('(prefers-color-scheme: dark)').matches` === **false**；
 *   - 于是页面按亮色渲染，MIUI 再对它做一层强制反色 → 颜色全错（白底变黑、红/黄/绿严重偏色）；
 *   - 调 `setForceDarkAllowed(false)` 后，反色消失，页面恢复成 CSS 的真实颜色。
 *
 * 结论：**不能指望 MIUI 的 WebView 报告 prefers-color-scheme**。所以本插件：
 *
 *   1. 默认关掉 WebView 的算法暗色（forceDark / algorithmic darkening）；
 *   2. 由插件把「当前应该是什么主题」注入页面：
 *        document.documentElement.dataset.theme = 'dark' | 'light'
 *        document.documentElement.style.colorScheme = 'dark' | 'light'
 *   3. 兼容老 CSS：把页面里 `@media (prefers-color-scheme: dark)` 的规则**重写**成
 *      `html[data-theme="dark"]` 选择器（首次注入时做一次），这样页面不用改 CSS
 *      也能在 MIUI 上正确变深色；
 *   4. 监听系统深浅色变化（onConfigurationChanged），重新注入并派发 themeChanged 事件。
 *
 * ⚠️ 版本提醒：以上 MIUI 相关结论基于 **MIUI 12 / Android 10 / WebView 87**。
 *    换 ROM 或 WebView 大版本后如果行为变了，先用 getInfo() 看 forceDark 的状态，
 *    再决定是否调整策略。
 *
 * ============================ 二、JS 侧用法 ============================
 *
 * <pre>
 *   import { SetForceDark } from 'capacitor-set-force-dark';
 *
 *   // 关闭 WebView 的算法暗色（插件加载时默认就是关的，这里只是显式声明）
 *   await SetForceDark.setForceDark({ enabled: false });
 *
 *   // 让 WebView 跟随系统深浅色
 *   await SetForceDark.setTheme({ theme: 'system' });
 *
 *   // 强制亮色 / 暗色
 *   await SetForceDark.setTheme({ theme: 'light' });
 *
 *   // 读取当前状态：requestedTheme / isDark / isForced
 *   const theme = await SetForceDark.getTheme();
 *
 *   // 监听变化
 *   const handle = await SetForceDark.addListener('themeChanged', (t) => {
 *     console.log(t.isDark);
 *   });
 * </pre>
 *
 * 页面里推荐直接用属性选择器（插件会把 data-theme 写到 html 上）：
 *
 * <pre>
 *   html[data-theme='dark'] body { background: #121212; color: #eee; }
 * </pre>
 *
 * ============================ 三、线程 ============================
 *
 * 与所有 Capacitor 插件一样，方法体运行在 "CapacitorPlugins" 后台线程，
 * 所有 WebView / View 操作都要切到主线程（webView.post）。
 */
@CapacitorPlugin(name = "SetForceDark")
public class SetForceDarkPlugin extends Plugin {

    private static final String THEME_SYSTEM = "system";
    private static final String THEME_LIGHT = "light";
    private static final String THEME_DARK = "dark";

    /** 当前请求的主题：system | light | dark。 */
    private String requestedTheme = THEME_SYSTEM;

    /** 是否允许 WebView 的算法暗色，默认关闭（见类头的实测说明）。 */
    private boolean forceDarkEnabled = false;

    /** 主题是否已经注入过页面。 */
    private boolean themeInjected = false;

    private WebViewListener pageListener;

    // ---------------------------------------------------------------- 生命周期

    @Override
    public void load() {
        super.load();

        applyForceDark();

        // 页面每次加载完成都重新注入：主题可能在页面加载期间变化，
        // 而且 WebView 重新加载后 data-theme 会丢。
        pageListener =
            new WebViewListener() {
                @Override
                public void onPageCommitVisible(WebView webView, String url) {
                    injectTheme();
                }

                @Override
                public void onPageLoaded(WebView webView) {
                    injectTheme();
                }
            };
        getBridge().addWebViewListener(pageListener);

        // 插件可能在页面加载完之后才被创建（例如懒加载），这里补几次，
        // 保证 App 启动后即使没人调用插件 API，页面也能拿到 data-theme。
        scheduleInitialInjection();
    }

    private void scheduleInitialInjection() {
        Handler handler = new Handler(Looper.getMainLooper());
        for (int delay : new int[] { 200, 800, 2000 }) {
            handler.postDelayed(this::injectTheme, delay);
        }
    }

    @Override
    protected void handleOnStart() {
        super.handleOnStart();
        injectTheme();
    }

    @Override
    protected void handleOnConfigurationChanged(Configuration newConfig) {
        super.handleOnConfigurationChanged(newConfig);

        // 系统深浅色切换：只有"跟随系统"时才需要重新注入
        if (THEME_SYSTEM.equals(requestedTheme)) {
            injectTheme();
            notifyThemeChanged();
        }
    }

    // ---------------------------------------------------------------- 对外方法

    /**
     * 设置 WebView 主题（system / light / dark）。
     * 会立即注入页面并派发 themeChanged。
     */
    @PluginMethod
    public void setTheme(PluginCall call) {
        String value = call.getString("theme", THEME_SYSTEM);
        final String normalized = normalizeTheme(value);
        if (normalized == null) {
            call.reject("theme must be one of: system, light, dark");
            return;
        }

        requestedTheme = normalized;
        injectTheme();
        notifyThemeChanged();
        call.resolve(toTheme().toJSObject());
    }

    /** 读取当前 WebView 主题状态（不是系统设置）。 */
    @PluginMethod
    public void getTheme(PluginCall call) {
        injectTheme();
        call.resolve(toTheme().toJSObject());
    }

    /**
     * 开关 WebView 的 forceDark / 算法暗色，默认关闭。
     *
     * 打开后 ROM 可能会对页面做算法反色 —— 在 MIUI 12 上会严重偏色，不建议开。
     */
    @PluginMethod
    public void setForceDark(PluginCall call) {
        Boolean enabled = call.getBoolean("enabled", false);
        forceDarkEnabled = Boolean.TRUE.equals(enabled);
        applyForceDark();
        call.resolve();
    }

    /**
     * 调试信息：主题、forceDark 状态、是否已注入、UA。
     *
     * 注意：`WebView.getSettings()` 也必须在主线程调用，否则会抛
     * "All WebView methods must be called on the same thread" 直接把 App 崩掉，
     * 所以这里把 resolve 也放进 webView.post 里。
     */
    @PluginMethod
    public void getInfo(PluginCall call) {
        injectTheme();

        final JSObject ret = toTheme().toJSObject();
        ret.put("forceDarkEnabled", forceDarkEnabled);
        ret.put("themeInjected", themeInjected);

        final WebView webView = getBridge() != null ? getBridge().getWebView() : null;
        if (webView == null) {
            ret.put("webViewUserAgent", "");
            call.resolve(ret);
            return;
        }

        webView.post(() -> {
            try {
                ret.put("webViewUserAgent", webView.getSettings().getUserAgentString());
            } catch (Exception ex) {
                ret.put("webViewUserAgent", "");
            }
            call.resolve(ret);
        });
    }

    // ---------------------------------------------------------------- 内部实现

    /** 解析后实际是否深色。 */
    private boolean isDark() {
        if (THEME_DARK.equals(requestedTheme)) {
            return true;
        }
        if (THEME_LIGHT.equals(requestedTheme)) {
            return false;
        }
        return isSystemDark();
    }

    private boolean isSystemDark() {
        Activity activity = getActivity();
        if (activity == null) {
            return false;
        }
        int nightMode = activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return nightMode == Configuration.UI_MODE_NIGHT_YES;
    }

    /**
     * 把当前主题注入页面。
     *
     * 注入的脚本做三件事：
     *   1. 写 data-theme / color-scheme；
     *   2. 首次注入时把 @media (prefers-color-scheme: dark) 的规则复制成 html[data-theme="dark"]，
     *      让没改过 CSS 的项目也能正确变深色；
     *   3. 记录 __capForceDarkPatched，避免重复插入样式。
     */
    private void injectTheme() {
        if (getBridge() == null) {
            return;
        }
        WebView webView = getBridge().getWebView();
        if (webView == null) {
            return;
        }

        final String theme = isDark() ? "dark" : "light";
        final String js = INJECT_JS.replace("__THEME__", theme);

        webView.post(() -> {
            webView.evaluateJavascript(js, null);
            themeInjected = true;
        });
    }

    /** 开关 WebView 的算法暗色。 */
    private void applyForceDark() {
        if (getBridge() == null) {
            return;
        }
        WebView webView = getBridge().getWebView();
        if (webView == null) {
            return;
        }

        webView.post(() -> {
            // API 29+ 的 View 级开关，实测在 MIUI 12 上能直接干掉强制反色
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                webView.setForceDarkAllowed(forceDarkEnabled);
            }

            WebSettings settings = webView.getSettings();
            if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, forceDarkEnabled);
            } else if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
                WebSettingsCompat.setForceDark(
                    settings,
                    forceDarkEnabled ? WebSettingsCompat.FORCE_DARK_ON : WebSettingsCompat.FORCE_DARK_OFF
                );
            }
        });
    }

    private void notifyThemeChanged() {
        notifyListeners("themeChanged", toTheme().toJSObject());
    }

    private ThemeState toTheme() {
        ThemeState state = new ThemeState();
        state.requestedTheme = requestedTheme;
        state.isDark = isDark();
        state.isForced = !THEME_SYSTEM.equals(requestedTheme);
        return state;
    }

    private String normalizeTheme(String value) {
        if (value == null) {
            return THEME_SYSTEM;
        }
        switch (value.trim().toLowerCase(Locale.ROOT)) {
            case THEME_LIGHT:
                return THEME_LIGHT;
            case THEME_DARK:
                return THEME_DARK;
            case THEME_SYSTEM:
            case "":
                return THEME_SYSTEM;
            default:
                return null;
        }
    }

    // ---------------------------------------------------------------- 数据结构

    private static class ThemeState {

        String requestedTheme;
        boolean isDark;
        boolean isForced;

        JSObject toJSObject() {
            JSObject ret = new JSObject();
            ret.put("requestedTheme", requestedTheme);
            ret.put("isDark", isDark);
            ret.put("isForced", isForced);
            return ret;
        }
    }

    // ---------------------------------------------------------------- 注入脚本

    /**
     * 注意：这是嵌在 Java 里的 JS，`\s` / `\b` 在 Java 字符串里要写成 `\\s` / `\\b`。
     */
    private static final String INJECT_JS =
        "(function () {\n" +
        "  var theme = '__THEME__';\n" +
        "  var root = document.documentElement;\n" +
        "  if (!root) { return; }\n" +
        "  root.setAttribute('data-theme', theme);\n" +
        "  root.style.colorScheme = theme;\n" +
        "  if (window.__capForceDarkPatched) { return; }\n" +
        "  var css = [];\n" +
        "  try {\n" +
        "    var sheets = document.styleSheets || [];\n" +
        "    for (var i = 0; i < sheets.length; i++) {\n" +
        "      var rules = null;\n" +
        "      try { rules = sheets[i].cssRules; } catch (e) { continue; }\n" +
        "      if (!rules) { continue; }\n" +
        "      for (var j = 0; j < rules.length; j++) {\n" +
        "        var rule = rules[j];\n" +
        "        if (rule.type !== 4) { continue; }\n" +
        "        var cond = rule.conditionText || (rule.media && rule.media.mediaText) || '';\n" +
        "        if (!/prefers-color-scheme\\s*:\\s*dark/i.test(cond)) { continue; }\n" +
        "        var inner = rule.cssRules || [];\n" +
        "        for (var k = 0; k < inner.length; k++) {\n" +
        "          var r = inner[k];\n" +
        "          if (!r.selectorText || !r.style) { continue; }\n" +
        "          var sels = r.selectorText.split(',').map(function (s) {\n" +
        "            s = s.trim();\n" +
        "            if (/^(:root|html)\\b/.test(s)) { return s.replace(/^(:root|html)/, 'html[data-theme=\"dark\"]'); }\n" +
        "            return 'html[data-theme=\"dark\"] ' + s;\n" +
        "          });\n" +
        "          css.push(sels.join(',') + '{' + r.style.cssText + '}');\n" +
        "        }\n" +
        "      }\n" +
        "    }\n" +
        "  } catch (e) { /* 跨域样式表读不到，忽略 */ }\n" +
        "  if (!css.length) { return; }\n" +
        "  var style = document.createElement('style');\n" +
        "  style.id = 'cap-force-dark-patch';\n" +
        "  style.textContent = css.join('\\n');\n" +
        "  (document.head || document.documentElement).appendChild(style);\n" +
        "  window.__capForceDarkPatched = true;\n" +
        "})();\n";
}
