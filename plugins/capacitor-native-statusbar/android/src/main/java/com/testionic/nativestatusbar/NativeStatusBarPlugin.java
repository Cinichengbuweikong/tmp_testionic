package com.testionic.nativestatusbar;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
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
 * 状态栏插件（Android 侧实现）。
 *
 * ============================ 一、背景色为什么要自绘 ============================
 *
 * 实测设备/版本：Redmi K30S Ultra（Android 10 / MIUI 12）、Pixel 6 模拟器（Android 16）。
 *
 * 1) 布局真相（用 `uiautomator dump` 看到的 View 层级，不是猜的）：
 *    - 窗口根布局 DecorView 的 bounds 是 [0,0][宽,高]，也就是**窗口从屏幕 y=0 开始**；
 *    - 但内容区（LinearLayout / WebView）的 bounds 是从状态栏下方开始的
 *      （真机 y=90px、模拟器 y=128px），说明 DecorView 用**内边距**把内容顶到了状态栏下面。
 *    - 由此可以得出两个结论：
 *      a. 网页里 `body { background }` 的颜色**永远不会**出现在状态栏里（状态栏不在 WebView 内）；
 *      b. 状态栏那一条是「窗口 / 主题」画的，所以可以在窗口顶部自己盖一层。
 *
 * 2) 为什么不用现成的 `window.setStatusBarColor()`：
 *    - Android 15+（targetSdk 35+）系统会忽略这个调用（官方文档已标注 deprecated）；
 *    - 在 MIUI 上实测同样无效：调用后 `getInfo()` 能读到颜色，`dumpsys window` 里也确实是新颜色，
 *      但屏幕上那条依旧是主题默认的深灰——这就是官方 @capacitor/status-bar 插件
 *      "设了背景色但没有效果"的根因。
 *
 * 3) 所以这里的做法：在 DecorView 顶部加一条与状态栏等高的色块（overlay），
 *    用**负的 topMargin** 抵消 DecorView 的内边距、并关闭 `clipToPadding`
 *    （否则画在 padding 区域的子 View 会被裁掉，表现为"色块完全看不见"）。
 *    只有在 overlay 加不上时（DecorView 不是 ViewGroup 的极端情况）才退回 setStatusBarColor。
 *
 * ============================ 二、线程模型（必读） ============================
 *
 * Capacitor 的插件方法**不在主线程**执行，而是在名为 "CapacitorPlugins" 的 HandlerThread 上。
 * 任何 View 操作（addView / setLayoutParams / setSystemUiVisibility / setStatusBarColor ...）
 * 都必须切回主线程，否则会抛：
 *     CalledFromWrongThreadException: Only the original thread that created a view hierarchy can touch its views
 * 并且是**直接崩溃整个 App**（不是 reject 一个调用）。首版就在真机上这样崩过一次。
 *
 * ============================ 三、图标亮暗：标准做法 + MIUI 的限制 ============================
 *
 * 标准做法（已实现）：
 *   1. `WindowInsetsControllerCompat.setAppearanceLightStatusBars(darkIcons)`
 *      （内部在 API < 30 时退化为旧的 SYSTEM_UI_FLAG_LIGHT_STATUS_BAR）；
 *   2. 再显式设置一次 `decorView.setSystemUiVisibility(... | LIGHT_STATUS_BAR)`，
 *      兼容只认旧 flag 的 ROM；
 *   3. 350ms 后再压一次，防止某些 ROM 在状态栏重绘后把标志覆盖掉。
 *
 * MIUI 的实测限制（**重要，别当成 bug 再查一遍**）：
 *   - MIUI 会按「状态栏实际背景色」自动决定图标明暗，并**无视**我们设置的标志位：
 *       蓝色背景 #2196F3 → 图标自动变白
 *       白色背景 #FFFFFF → 图标自动变深
 *   - 我们确认过标志位确实写进了窗口：
 *       vsysui=LAYOUT_STABLE LAYOUT_FULLSCREEN LIGHT_STATUS_BAR   （传入 dark 样式时）
 *       vsysui=LAYOUT_STABLE LAYOUT_FULLSCREEN                    （传入 light 样式时）
 *     但屏幕上图标一个像素都不变。
 *   - 对照实验：在同一个 App、同一状态下直接调用**官方插件** `@capacitor/status-bar` 的
 *     `setStyle({style: 'LIGHT'})` / `'DARK'`，两张截图逐像素比对，
 *     右上角信号/电池图标区域差异 **0 / 5320**——说明官方插件在这台机器上同样改不了图标。
 *   - 结论：这不是本插件的实现问题，而是 MIUI 的「对比度自适应」行为。
 *     在对比度正常的组合下（浅底配深图标、深底配浅图标），自动结果与用户期望一致；
 *     强行要求低对比度组合（如浅底配白图标）时 MIUI 会拒绝。
 *   - 如果以后要突破，方向是"骗过 MIUI 的采样"或使用 MIUI 私有接口，目前未实现。
 *
 * ============================ 四、MIUI 的 forceDark 注入 ============================
 *
 * 同一台真机上网页 `getComputedStyle(document.body).backgroundColor` 是 `rgb(255,255,255)`，
 * 但屏幕显示为近黑色——MIUI 在 WebView 渲染层注入了强制反色（forceDark）。
 * 本插件在 `load()` 里对窗口调用 `setForceDarkAllowed(false)`（API 29+），实测可以关掉它，
 * 页面会恢复成 CSS 里真正的颜色。如果不需要这个副作用，删掉 `load()` 里的那行即可。
 *
 * ============================ 五、其它 ============================
 *
 * - 颜色支持 `#RGB` / `#RRGGBB` / `#AARRGGBB`（ARGB，与 Android 一致）/ `transparent`；
 * - `height` 返回的是 dp（≈ CSS px），方便 Web 层做布局；
 * - `transparent` 会移除色块，恢复"内容直接透出"的效果。
 */
@CapacitorPlugin(name = "NativeStatusBar")
public class NativeStatusBarPlugin extends Plugin {

    private static final String STYLE_DEFAULT = "default";
    private static final String STYLE_LIGHT = "light";
    private static final String STYLE_DARK = "dark";

    /** 当前样式，取值 light / dark / default（default = 跟随系统深浅色）。 */
    private String currentStyle = STYLE_DEFAULT;

    /** 当前背景色（ARGB int）；null 表示从未设置过。 */
    private Integer currentColor = null;

    /** 自绘的状态栏色块；null 表示当前没有色块（未设置颜色或颜色是 transparent）。 */
    private View overlayView = null;

    /**
     * 设置状态栏背景色。
     *
     * 注意线程：本方法体在 CapacitorPlugins 线程上执行，真正碰 View 的活儿全部丢进 runOnUiThread，
     * 并且把 call.resolve() 也放在 UI 线程里，保证"颜色已经画上去"之后才回调 JS。
     */
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

    /**
     * 读取当前状态，主要给调试和自动化测试用。
     *
     * `usingOverlay` 表示这次是用"自绘色块"呈现的（Android 上基本恒为 true）——
     * 它在自动化测试里用来确认插件走的是哪条路径。
     */
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

    /**
     * 插件加载时做一些一次性处理。
     *
     * 这里的 setForceDarkAllowed(false) 是为了干掉 MIUI 的强制反色：
     * 实测同一台真机上网页算出 `rgb(255,255,255)` 却被显示成近黑色，
     * 关掉之后页面恢复成 CSS 里真正的颜色。不需要这个行为可以删掉本方法。
     */
    @Override
    public void load() {
        super.load();
        Activity activity = getActivity();
        if (activity != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            activity.runOnUiThread(() -> activity.getWindow().getDecorView().setForceDarkAllowed(false));
        }
    }

    /** App 回到前台时补一次：有些 ROM 会在后台/切前台时重置系统栏状态。 */
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

    /**
     * 把当前颜色"画"到状态栏上。
     *
     * 两条通道：
     *  1) 系统 API（setStatusBarColor）：只在 Android 15 以下、且窗口没有铺进状态栏时有效；
     *  2) 自绘色块：主要通道。窗口从 y=0 开始，内容被内边距顶到状态栏下方，
     *     所以在窗口顶部盖一条等高的 View 就能当成状态栏背景。
     *
     * 注意：一旦色块存在，就**不要**再调 setStatusBarColor —— 实测 MIUI 会因此触发
     * "按背景色重新自适应图标"，把用户刚设置的图标亮暗覆盖掉。
     */
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

        // 通道 2：自绘色块。窗口本身从屏幕 y=0 开始（内容区被内边距顶到状态栏下方），
        // 所以把色块画在窗口顶部、用负 margin 抵掉内边距，就能盖住状态栏那一条。
        if (currentColor == null || Color.alpha(currentColor) == 0) {
            removeOverlay();
        } else {
            showOverlay(decorView, currentColor);
        }

        // 有自绘色块时不要再调 setStatusBarColor：实测 MIUI 会因此触发"按背景色重新自适应图标"，
        // 把用户设置的图标样式覆盖掉。只有在没画出色块（极端情况）时才退回系统 API。
        if (overlayView == null) {
            window.setStatusBarColor(currentColor == null ? Color.TRANSPARENT : currentColor);
        }
    }

    /**
     * 创建/更新状态栏色块。
     *
     * 两个坑：
     *  1) DecorView 默认 `clipToPadding = true`，画在 padding 区域（也就是状态栏那块）的子 View
     *     会被直接裁掉，表现为"代码执行了但屏幕上什么都没有" —— 必须关掉；
     *  2) 色块必须 `bringToFront()`，否则会被 Capacitor 的 WebView 盖住。
     */
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

    private final Runnable reapplyStyleRunnable = this::applyStyleFlags;

    /**
     * 应用图标亮暗样式。
     *
     * 除了立即设置，还会在 350ms 后再压一次：部分 ROM 会在系统栏重绘后
     * 把我们的标志位改回去（MIUI 尤其明显）。
     *
     * ⚠️ 已知限制：MIUI 最终仍会按状态栏背景色做「对比度自适应」，
     * 深色底强制白图标、浅色底强制深图标，显式 light/dark 会被它覆盖。
     * 详细实测数据见类头注释第三节；这不是本插件能绕过的。
     */
    private void applyStyle() {
        if (getActivity() == null) {
            return;
        }
        applyStyleFlags();

        // 部分 ROM（MIUI）会在状态栏颜色变化后按背景色重新自适应图标颜色，
        // 把我们的设置覆盖掉，所以延迟再压一次。
        View decorView = getActivity().getWindow().getDecorView();
        decorView.removeCallbacks(reapplyStyleRunnable);
        decorView.postDelayed(reapplyStyleRunnable, 350);
    }

    /**
     * 真正写系统标志位的地方，双管齐下：
     *  - androidx 的 WindowInsetsControllerCompat（标准、API 30+ 走 insets controller）；
     *  - 旧版 SYSTEM_UI_FLAG_LIGHT_STATUS_BAR（API 23~29 以及只认旧 flag 的 ROM）。
     */
    private void applyStyleFlags() {
        Activity activity = getActivity();
        if (activity == null) {
            return;
        }

        Window window = activity.getWindow();
        View decorView = window.getDecorView();
        boolean darkIcons = useDarkIcons();

        // 标准做法
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(window, decorView);
        controller.setAppearanceLightStatusBars(darkIcons);

        // 兼容做法：MIUI 等国产 ROM 只认旧的 systemUiVisibility 标志
        int flags = decorView.getSystemUiVisibility();
        if (darkIcons) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        } else {
            flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        }
        decorView.setSystemUiVisibility(flags);
    }

    /**
     * 是否使用「深色图标」。
     *
     * 命名很容易绕晕，记住 Android 的语义：
     *   setAppearanceLightStatusBars(true)  ==  状态栏背景是浅色  ==  图标/文字用**深色**
     * 所以：
     *   style = 'light'（亮色图标，适合深色背景）→ 返回 false
     *   style = 'dark' （暗色图标，适合浅色背景）→ 返回 true
     *   style = 'default' → 跟随系统深浅色
     */
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
