package com.testionic.nativestatusbar;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONObject;

import java.util.Locale;

/**
 * 状态栏插件（Android）。
 *
 * 实测结论（Android 10 真机 + Android 16 模拟器）：
 * - WebView 的内容区是从状态栏下方开始的（不是 edge-to-edge），所以网页的 body 背景色
 *   永远不会出现在状态栏里；
 * - 但窗口本身是从屏幕 y=0 开始的（DecorView 用内边距把内容顶到状态栏下方），
 *   而状态栏那一条是由窗口/主题画的，颜色来自主题（默认深灰）。
 * - window.setStatusBarColor() 在 Android 15+（targetSdk 35+）会被系统忽略，
 *   在 MIUI 上也实测无效——这就是官方插件"设了没反应"的原因。
 *
 * 所以本插件在窗口顶部自绘一条与状态栏等高的色块（用负 margin + 关闭 clipToPadding
 * 抵掉 DecorView 的内边距），同时仍然调用系统 API 作为旧设备的兜底。
 *
 * 注意：Capacitor 的插件方法运行在名为 "CapacitorPlugins" 的后台线程上，
 * 所有 View 操作必须切回主线程，否则会抛 CalledFromWrongThreadException 直接崩溃。
 */
@CapacitorPlugin(name = "NativeStatusBar")
public class NativeStatusBarPlugin extends Plugin {

    private static final String STYLE_DEFAULT = "default";
    private static final String STYLE_LIGHT = "light";
    private static final String STYLE_DARK = "dark";

    private String currentStyle = STYLE_DEFAULT;
    private Integer currentColor = null;
    private View overlayView = null;

    @PluginMethod
    public void setBackgroundColor(PluginCall call) {
        String value = call.getString("color");
        if (value == null) {
            call.reject("color is required");
            return;
        }

        final int color;
        try {
            color = parseColor(value);
        } catch (IllegalArgumentException ex) {
            call.reject(ex.getMessage());
            return;
        }

        final Activity activity = getActivity();
        if (activity == null) {
            call.reject("No activity");
            return;
        }

        activity.runOnUiThread(() -> {
            currentColor = color;
            applyBackgroundColor();

            JSObject ret = new JSObject();
            ret.put("color", toHex(color));
            ret.put("height", getStatusBarHeightDp());
            call.resolve(ret);
        });
    }

    @PluginMethod
    public void setStyle(PluginCall call) {
        String value = call.getString("style", STYLE_DEFAULT);
        final String normalized = normalizeStyle(value);
        if (normalized == null) {
            call.reject("style must be one of: light, dark, default");
            return;
        }

        final Activity activity = getActivity();
        if (activity == null) {
            call.reject("No activity");
            return;
        }

        activity.runOnUiThread(() -> {
            currentStyle = normalized;
            applyStyle();
            call.resolve();
        });
    }

    @PluginMethod
    public void getInfo(PluginCall call) {
        final Activity activity = getActivity();
        if (activity == null) {
            call.reject("No activity");
            return;
        }

        activity.runOnUiThread(() -> {
            JSObject ret = new JSObject();
            ret.put("color", currentColor == null ? JSONObject.NULL : toHex(currentColor));
            ret.put("style", currentStyle);
            ret.put("height", getStatusBarHeightDp());
            ret.put("usingOverlay", overlayView != null);
            call.resolve(ret);
        });
    }

    @Override
    protected void handleOnStart() {
        super.handleOnStart();
        runOnUiThreadSafely(() -> {
            if (currentColor != null) {
                applyBackgroundColor();
            }
            applyStyle();
        });
    }

    @Override
    protected void handleOnConfigurationChanged(Configuration newConfig) {
        super.handleOnConfigurationChanged(newConfig);
        // 旋转 / 深浅色切换后重新套用一次，避免 overlay 高度或图标颜色不对
        runOnUiThreadSafely(() -> {
            if (currentColor != null) {
                applyBackgroundColor();
            }
            if (STYLE_DEFAULT.equals(currentStyle)) {
                applyStyle();
            }
        });
    }

    private void runOnUiThreadSafely(Runnable action) {
        Activity activity = getActivity();
        if (activity != null) {
            activity.runOnUiThread(action);
        }
    }

    // ---------------------------------------------------------------- 背景色

    private void applyBackgroundColor() {
        Activity activity = getActivity();
        if (activity == null) {
            return;
        }

        Window window = activity.getWindow();
        View decorView = window.getDecorView();

        // 通道 1：系统 API。Android 15 以下、窗口没有铺进状态栏时，这一步就够了；
        // 但 Android 15+ 对 targetSdk 35+ 的应用会忽略它，MIUI 等 OEM 也可能忽略。
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(currentColor == null ? Color.TRANSPARENT : currentColor);

        // 通道 2：自绘色块。窗口本身从屏幕 y=0 开始（内容区被内边距顶到状态栏下方），
        // 所以把色块画在窗口顶部、用负 margin 抵掉内边距，就能盖住状态栏那一条。
        if (currentColor == null || Color.alpha(currentColor) == 0) {
            removeOverlay();
        } else {
            showOverlay(decorView, currentColor);
        }
    }

    private void showOverlay(View decorView, int color) {
        if (!(decorView instanceof ViewGroup)) {
            return;
        }
        ViewGroup root = (ViewGroup) decorView;

        if (overlayView == null || overlayView.getParent() != root) {
            removeOverlay();
            // DecorView 默认 clipToPadding=true，会把画在状态栏区域的子 View 裁掉
            root.setClipToPadding(false);
            overlayView = new View(getContext());
            overlayView.setClickable(false);
            overlayView.setFocusable(false);
            root.addView(overlayView, buildOverlayParams(root));
        }

        overlayView.setBackgroundColor(color);
        overlayView.setLayoutParams(buildOverlayParams(root));
        overlayView.bringToFront();
    }

    /**
     * DecorView 会按系统栏内边距给子 View 留白（实测 Pixel/Android 16 上是 128px），
     * 所以这里用负的 topMargin 把色块顶回屏幕最上方，正好盖住状态栏那一条。
     */
    private FrameLayout.LayoutParams buildOverlayParams(ViewGroup root) {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            getStatusBarHeightPx(),
            Gravity.TOP
        );
        params.topMargin = -root.getPaddingTop();
        return params;
    }

    private void removeOverlay() {
        if (overlayView != null && overlayView.getParent() instanceof ViewGroup) {
            ((ViewGroup) overlayView.getParent()).removeView(overlayView);
        }
        overlayView = null;
    }

    // ---------------------------------------------------------------- 样式

    private void applyStyle() {
        Activity activity = getActivity();
        if (activity == null) {
            return;
        }
        Window window = activity.getWindow();
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(window, window.getDecorView());
        controller.setAppearanceLightStatusBars(useDarkIcons());
    }

    /** true 表示用深色图标（适合浅色背景）。 */
    private boolean useDarkIcons() {
        if (STYLE_LIGHT.equals(currentStyle)) {
            return false;
        }
        if (STYLE_DARK.equals(currentStyle)) {
            return true;
        }
        int mode = getContext().getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode != Configuration.UI_MODE_NIGHT_YES;
    }

    private String normalizeStyle(String value) {
        if (value == null) {
            return null;
        }
        switch (value.trim().toLowerCase(Locale.ROOT)) {
            case STYLE_LIGHT:
                return STYLE_LIGHT;
            case STYLE_DARK:
                return STYLE_DARK;
            case STYLE_DEFAULT:
            case "":
                return STYLE_DEFAULT;
            default:
                return null;
        }
    }

    // ---------------------------------------------------------------- 工具

    private int getStatusBarHeightPx() {
        Activity activity = getActivity();
        if (activity == null) {
            return 0;
        }

        WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(activity.getWindow().getDecorView());
        if (insets != null) {
            int top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
            if (top > 0) {
                return top;
            }
        }

        int resourceId = getContext().getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resourceId > 0) {
            return getContext().getResources().getDimensionPixelSize(resourceId);
        }
        return 0;
    }

    private int getStatusBarHeightDp() {
        float density = getContext().getResources().getDisplayMetrics().density;
        if (density <= 0) {
            return 0;
        }
        return Math.round(getStatusBarHeightPx() / density);
    }

    private static int parseColor(String value) {
        String input = value.trim();
        if (input.equalsIgnoreCase("transparent")) {
            return Color.TRANSPARENT;
        }
        if (!input.startsWith("#")) {
            throw new IllegalArgumentException("Invalid color: " + value + " (expected #RGB, #RRGGBB, #AARRGGBB or transparent)");
        }

        String hex = input.substring(1);
        if (hex.length() == 3 || hex.length() == 4) {
            StringBuilder expanded = new StringBuilder(hex.length() * 2);
            for (char c : hex.toCharArray()) {
                expanded.append(c).append(c);
            }
            hex = expanded.toString();
        }
        if (hex.length() != 6 && hex.length() != 8) {
            throw new IllegalArgumentException("Invalid color: " + value + " (expected #RGB, #RRGGBB, #AARRGGBB or transparent)");
        }

        try {
            long color = Long.parseLong(hex, 16);
            if (hex.length() == 6) {
                color |= 0xFF000000L;
            }
            return (int) color;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Invalid color: " + value);
        }
    }

    private static String toHex(int color) {
        return String.format(Locale.US, "#%08X", color);
    }
}
