package com.testionic.systeminfo;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Insets;
import android.graphics.Point;
import android.graphics.Rect;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.Surface;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.webkit.WebView;

import androidx.annotation.NonNull;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SystemInfo 插件（Android 侧，jsName = "SystemInfo"）。
 *
 * ======================= 设计原则 =======================
 *
 * 每种信息一个独立 API，按需调用。每个 API 的收集逻辑**完全独立**：
 *   - 只收集自己需要的数据，不多干一点活；
 *   - 每个方法单独 try/catch，某个 API 因系统差异失败不影响其他 API；
 *   - 共享的只有纯格式转换工具（不采集数据）。
 *
 * ======================= 关键实现点 =======================
 *
 * 1. 系统栏尺寸必须绕开 View 层：
 *    本项目的 webview-to-edge 插件会在 DecorView 上常驻一个
 *    OnApplyWindowInsetsListener 并把 systemBars 清零后往下传。
 *    因此 API 30+ 直接从 WindowManager.getMaximumWindowMetrics()（显示层）
 *    拿真实值；API 29 用 decorView.getRootWindowInsets()（原始系统值，
 *    不受 listener 返回值影响）。
 *
 * 2. 底部导航栏（API 29）：MIUI 等 ROM 在手势模式下 insets 仍假报旧三键
 *    高度（实测 130px），但窗口实际铺满全屏，因此用 getWindowVisibleDisplayFrame()
 *    反推真实占用。
 *
 * 3. 单位换算：物理 px / density = CSS px（dp）。WebView 的 CSS 视口
 *    就是 WebView 的 View 尺寸除以 density。
 *
 * 4. 线程：全部在主线程执行（WebView.getSettings() 非主线程会崩）。
 */

@CapacitorPlugin(name = "SystemInfo")
public class SystemInfoPlugin extends Plugin {

    // ---------------------------------------------------------------- 方法入口

    @PluginMethod
    public void getScreenInfo(PluginCall call) {
        answerOnMain(call, "getScreenInfo", this::collectScreen);
    }

    @PluginMethod
    public void getViewport(PluginCall call) {
        answerOnMain(call, "getViewport", this::collectViewport);
    }

    @PluginMethod
    public void getSafeAreaInsets(PluginCall call) {
        answerOnMain(call, "getSafeAreaInsets", this::collectSafeArea);
    }

    @PluginMethod
    public void getStatusBarInfo(PluginCall call) {
        answerOnMain(call, "getStatusBarInfo", this::collectStatusBar);
    }

    @PluginMethod
    public void getNavigationBarInfo(PluginCall call) {
        answerOnMain(call, "getNavigationBarInfo", this::collectNavigationBar);
    }

    @PluginMethod
    public void getCutoutInfo(PluginCall call) {
        answerOnMain(call, "getCutoutInfo", this::collectCutout);
    }

    @PluginMethod
    public void getSystemInfo(PluginCall call) {
        answerOnMain(call, "getSystemInfo", this::collectSystem);
    }

    @PluginMethod
    public void getWebViewInfo(PluginCall call) {
        answerOnMain(call, "getWebViewInfo", this::collectWebView);
    }

    @PluginMethod
    public void getMemoryInfo(PluginCall call) {
        answerOnMain(call, "getMemoryInfo", this::collectMemory);
    }

    @PluginMethod
    public void getStorageInfo(PluginCall call) {
        answerOnMain(call, "getStorageInfo", this::collectStorage);
    }

    @PluginMethod
    public void getBatteryInfo(PluginCall call) {
        answerOnMain(call, "getBatteryInfo", this::collectBattery);
    }

    @PluginMethod
    public void getNetworkInfo(PluginCall call) {
        answerOnMain(call, "getNetworkInfo", this::collectNetwork);
    }

    @PluginMethod
    public void getAppInfo(PluginCall call) {
        answerOnMain(call, "getAppInfo", this::collectApp);
    }

    // ---------------------------------------------------------------- 收集实现

    /** 屏幕：分辨率 / CSS 尺寸 / 密度 / 刷新率 / 朝向。 */
    @SuppressWarnings("deprecation")
    private JSObject collectScreen() {
        Context context = getContext();
        Resources res = context.getResources();
        DisplayMetrics dm = res.getDisplayMetrics();
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);

        int[] size = screenSizePx(wm);
        JSObject ret = new JSObject();
        ret.put("px", sizeJson(size[0], size[1]));
        ret.put("css", sizeJson(css(size[0], dm.density), css(size[1], dm.density)));
        ret.put(
            "orientation",
            res.getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE ? "landscape" : "portrait"
        );
        ret.put("rotationDegrees", rotationDegrees(wm));
        ret.put("density", (double) dm.density);
        ret.put("densityDpi", dm.densityDpi);
        ret.put("scaledDensity", (double) dm.scaledDensity);
        ret.put("refreshRate", Math.round(wm.getDefaultDisplay().getRefreshRate() * 10.0) / 10.0);
        return ret;
    }

    /** WebView 视口：CSS 里 100% 的大小。 */
    private JSObject collectViewport() {
        WebView webView = getBridge().getWebView();
        int w = webView != null ? webView.getWidth() : 0;
        int h = webView != null ? webView.getHeight() : 0;
        float density = getContext().getResources().getDisplayMetrics().density;

        JSObject ret = new JSObject();
        ret.put("px", sizeJson(w, h));
        ret.put("css", sizeJson(css(w, density), css(h, density)));
        return ret;
    }

    /** 安全区（iOS 语义）：系统栏与挖孔的并集，CSS px。 */
    @SuppressWarnings("deprecation")
    private JSObject collectSafeArea() {
        Context context = getContext();
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        Activity activity = getActivity();
        float density = context.getResources().getDisplayMetrics().density;

        int statusTop = 0;
        int navBottom = 0;
        int gestureBottom = 0;
        int systemLeft = 0;
        int systemRight = 0;
        DisplayCutout cutout = null;

        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsets insets = wm.getMaximumWindowMetrics().getWindowInsets();
            statusTop = insets.getInsets(WindowInsets.Type.statusBars()).top;
            navBottom = insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
            gestureBottom = insets.getInsets(WindowInsets.Type.systemGestures()).bottom;
            Insets sysBars = insets.getInsets(WindowInsets.Type.systemBars());
            systemLeft = sysBars.left;
            systemRight = sysBars.right;
            cutout = insets.getDisplayCutout();
        } else if (activity != null) {
            WindowInsets insets = activity.getWindow().getDecorView().getRootWindowInsets();
            if (insets != null) {
                statusTop = insets.getSystemWindowInsetTop();
                if (statusTop <= 0) {
                    statusTop = dimensionPx("status_bar_height", Math.round(24 * density));
                }
                systemLeft = insets.getSystemWindowInsetLeft();
                systemRight = insets.getSystemWindowInsetRight();
                if (Build.VERSION.SDK_INT >= 28) {
                    cutout = insets.getDisplayCutout();
                }
            }
            int[] screen = screenSizePx(wm);
            Rect visibleFrame = new Rect();
            activity.getWindow().getDecorView().getWindowVisibleDisplayFrame(visibleFrame);
            navBottom = Math.max(0, screen[1] - visibleFrame.bottom);
        }

        int cutoutTop = 0;
        int cutoutRight = 0;
        int cutoutBottom = 0;
        int cutoutLeft = 0;
        if (cutout != null) {
            cutoutTop = cutout.getSafeInsetTop();
            cutoutRight = cutout.getSafeInsetRight();
            cutoutBottom = cutout.getSafeInsetBottom();
            cutoutLeft = cutout.getSafeInsetLeft();
        }

        int top = Math.max(statusTop, cutoutTop);
        int bottom = Math.max(navBottom, Math.max(gestureBottom, cutoutBottom));
        int right = Math.max(systemRight, cutoutRight);
        int left = Math.max(systemLeft, cutoutLeft);
        return edgesJson(css(top, density), css(right, density), css(bottom, density), css(left, density));
    }

    /** 状态栏：宽 = 屏幕宽。 */
    @SuppressWarnings("deprecation")
    private JSObject collectStatusBar() {
        Context context = getContext();
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        float density = context.getResources().getDisplayMetrics().density;
        int[] screen = screenSizePx(wm);
        int height = statusBarTopPx();

        return barJson(screen[0], height, density);
    }

    /** 底部导航栏：宽 = 屏幕宽；高度按窗口实际占用反推。 */
    @SuppressWarnings("deprecation")
    private JSObject collectNavigationBar() {
        Context context = getContext();
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        Activity activity = getActivity();
        float density = context.getResources().getDisplayMetrics().density;
        int[] screen = screenSizePx(wm);

        int height = navigationBarBottomPx(activity, screen[1]);
        int stableNav = 0;
        int transientNav = 0;
        if (Build.VERSION.SDK_INT < 30 && activity != null) {
            WindowInsets insets = activity.getWindow().getDecorView().getRootWindowInsets();
            if (insets != null) {
                stableNav = insets.getStableInsetBottom();
                transientNav = insets.getSystemWindowInsetBottom();
            }
        }

        JSObject ret = barJson(screen[0], height, density);
        ret.put("mode", navMode(context, height, stableNav, transientNav));
        return ret;
    }

    /** 刘海 / 挖孔。 */
    private JSObject collectCutout() {
        DisplayCutout cutout = displayCutout();
        float density = getContext().getResources().getDisplayMetrics().density;

        JSObject ret = new JSObject();
        if (cutout == null) {
            ret.put("exists", false);
            return ret;
        }
        ret.put("exists", true);
        ret.put(
            "insetsCss",
            edgesJson(
                css(cutout.getSafeInsetTop(), density),
                css(cutout.getSafeInsetRight(), density),
                css(cutout.getSafeInsetBottom(), density),
                css(cutout.getSafeInsetLeft(), density)
            )
        );
        JSArray rects = new JSArray();
        List<Rect> bounds = cutout.getBoundingRects();
        if (bounds != null) {
            for (Rect r : bounds) {
                JSObject o = new JSObject();
                o.put("left", r.left);
                o.put("top", r.top);
                o.put("right", r.right);
                o.put("bottom", r.bottom);
                rects.put(o);
            }
        }
        ret.put("boundingRectsPx", rects);
        return ret;
    }

    /** 系统与设备信息。 */
    private JSObject collectSystem() {
        Context context = getContext();
        Resources res = context.getResources();

        JSObject ret = new JSObject();
        ret.put("platform", "android");
        ret.put("osVersion", safe(Build.VERSION.RELEASE));
        ret.put("sdkInt", Build.VERSION.SDK_INT);
        ret.put("manufacturer", safe(Build.MANUFACTURER));
        ret.put("brand", safe(Build.BRAND));
        ret.put("model", safe(Build.MODEL));
        ret.put("deviceCode", safe(Build.DEVICE));
        ret.put("isEmulator", isEmulator());

        JSArray abis = new JSArray();
        for (String abi : Build.SUPPORTED_ABIS) {
            abis.put(abi);
        }
        ret.put("cpuAbis", abis);

        ret.put("locale", Locale.getDefault().toLanguageTag());
        ret.put("language", Locale.getDefault().getLanguage());
        ret.put("timezone", TimeZone.getDefault().getID());
        ret.put("is24Hour", DateFormat.is24HourFormat(context));
        ret.put("fontScale", (double) res.getConfiguration().fontScale);
        boolean dark =
            (res.getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        ret.put("darkMode", dark);
        return ret;
    }

    /** WebView 引擎信息。 */
    private JSObject collectWebView() {
        WebView webView = getBridge().getWebView();
        String ua = webView != null ? webView.getSettings().getUserAgentString() : "";
        ua = ua == null ? "" : ua;

        JSObject ret = new JSObject();
        ret.put("userAgent", ua);

        String packageName = "";
        String version = "";
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                PackageInfo info = WebView.getCurrentWebViewPackage();
                if (info != null) {
                    packageName = safe(info.packageName);
                    version = safe(info.versionName);
                }
            } catch (Exception ignored) {
                // 个别 ROM 上会抛 UnsupportedOperationException，走 UA 解析
            }
        }
        if (version.isEmpty()) {
            version = firstMatch(ua, "Chrome/([\\d.]+)");
        }
        ret.put("packageName", packageName);
        ret.put("version", version);
        return ret;
    }

    /** 内存（字节）。 */
    private JSObject collectMemory() {
        ActivityManager am = (ActivityManager) getContext().getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);

        JSObject ret = new JSObject();
        ret.put("totalBytes", mi.totalMem);
        ret.put("availableBytes", mi.availMem);
        return ret;
    }

    /** 存储（数据分区，字节）。 */
    private JSObject collectStorage() {
        StatFs stat = new StatFs(Environment.getDataDirectory().getPath());

        JSObject ret = new JSObject();
        ret.put("totalBytes", stat.getTotalBytes());
        ret.put("freeBytes", stat.getAvailableBytes());
        return ret;
    }

    /** 电量。 */
    @SuppressWarnings("deprecation")
    private JSObject collectBattery() {
        Context context = getContext();
        int level = -1;
        boolean charging = false;

        BatteryManager bm = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
        if (bm != null) {
            int capacity = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            if (capacity >= 0 && capacity <= 100) {
                level = capacity;
            }
        }

        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent status;
        if (Build.VERSION.SDK_INT >= 33) {
            status = context.registerReceiver(null, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            status = context.registerReceiver(null, filter);
        }
        if (status != null) {
            int st = status.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL;
        }

        JSObject ret = new JSObject();
        ret.put("level", level);
        ret.put("charging", charging);
        return ret;
    }

    /** 网络。 */
    private JSObject collectNetwork() {
        ConnectivityManager cm = (ConnectivityManager) getContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        String type = "none";
        boolean connected = false;
        boolean metered = false;

        if (cm != null) {
            Network network = cm.getActiveNetwork();
            if (network != null) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                if (caps != null) {
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        type = "wifi";
                    } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                        type = "cellular";
                    } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                        type = "ethernet";
                    } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                        type = "vpn";
                    } else {
                        type = "unknown";
                    }
                    connected = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
                    metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
                }
            }
        }

        JSObject ret = new JSObject();
        ret.put("type", type);
        ret.put("connected", connected);
        ret.put("metered", metered);
        return ret;
    }

    /** 应用自身信息。 */
    private JSObject collectApp() {
        Context context = getContext();

        JSObject ret = new JSObject();
        ret.put("id", safe(getAppId()));

        // PluginConfig 只含插件自己的配置块，全局 appName 从 application label 读
        String appName = "";
        try {
            ApplicationInfo ai = context.getPackageManager().getApplicationInfo(context.getPackageName(), 0);
            CharSequence label = context.getPackageManager().getApplicationLabel(ai);
            appName = label != null ? label.toString() : "";
        } catch (PackageManager.NameNotFoundException ignored) {
            // 理论上不会发生
        }
        ret.put("name", appName);

        String version = "";
        String build = "";
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            version = safe(info.versionName);
            if (Build.VERSION.SDK_INT >= 28) {
                build = String.valueOf(info.getLongVersionCode());
            } else {
                build = String.valueOf(info.versionCode);
            }
        } catch (PackageManager.NameNotFoundException ignored) {
            // 理论上不会发生
        }
        ret.put("version", version);
        ret.put("build", build);
        return ret;
    }

    // ---------------------------------------------------------------- 小型采集工具
    // 每个工具只做一件事、只读一项数据；某个工具失效只影响用到它的 API。

    /** 屏幕物理分辨率（跟随当前朝向）。 */
    @SuppressWarnings("deprecation")
    private int[] screenSizePx(WindowManager wm) {
        if (Build.VERSION.SDK_INT >= 30) {
            Rect bounds = wm.getMaximumWindowMetrics().getBounds();
            return new int[] { bounds.width(), bounds.height() };
        }
        Display display = wm.getDefaultDisplay();
        Point size = new Point();
        display.getRealSize(size);
        return new int[] { size.x, size.y };
    }

    /** 状态栏真实高度（物理 px）。 */
    @SuppressWarnings("deprecation")
    private int statusBarTopPx() {
        WindowManager wm = (WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE);
        if (Build.VERSION.SDK_INT >= 30) {
            return wm.getMaximumWindowMetrics().getWindowInsets().getInsets(WindowInsets.Type.statusBars()).top;
        }
        Activity activity = getActivity();
        if (activity != null) {
            WindowInsets insets = activity.getWindow().getDecorView().getRootWindowInsets();
            if (insets != null) {
                int top = insets.getSystemWindowInsetTop();
                if (top > 0) {
                    return top;
                }
            }
        }
        float density = getContext().getResources().getDisplayMetrics().density;
        return dimensionPx("status_bar_height", Math.round(24 * density));
    }

    /** 底部导航栏真实占用（物理 px），用窗口实际可见区域反推。 */
    @SuppressWarnings("deprecation")
    private int navigationBarBottomPx(Activity activity, int screenH) {
        WindowManager wm = (WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE);
        if (Build.VERSION.SDK_INT >= 30) {
            return wm.getMaximumWindowMetrics().getWindowInsets().getInsets(WindowInsets.Type.navigationBars()).bottom;
        }
        if (activity != null) {
            Rect visibleFrame = new Rect();
            activity.getWindow().getDecorView().getWindowVisibleDisplayFrame(visibleFrame);
            return Math.max(0, screenH - visibleFrame.bottom);
        }
        return 0;
    }

    /** 刘海 / 挖孔信息。 */
    private DisplayCutout displayCutout() {
        WindowManager wm = (WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE);
        if (Build.VERSION.SDK_INT >= 30) {
            return wm.getMaximumWindowMetrics().getWindowInsets().getDisplayCutout();
        }
        if (Build.VERSION.SDK_INT >= 28) {
            Activity activity = getActivity();
            if (activity != null) {
                WindowInsets insets = activity.getWindow().getDecorView().getRootWindowInsets();
                if (insets != null) {
                    return insets.getDisplayCutout();
                }
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- 纯工具
    // 以下全部是纯格式转换 / 只读常量，无采集逻辑，可安全共享。

    @SuppressWarnings("deprecation")
    private int rotationDegrees(WindowManager wm) {
        int rotation = wm.getDefaultDisplay().getRotation();
        return rotation == Surface.ROTATION_90
            ? 90
            : rotation == Surface.ROTATION_180 ? 180 : rotation == Surface.ROTATION_270 ? 270 : 0;
    }

    /**
     * 系统导航方式：优先读 Settings（0=三键 1=两键 2=手势）；
     * 部分 ROM（如 MIUI）不提供该值，改用实测数据推断：
     * - 窗口实际让出了底部空间（navBottom > 0）→ 有持久导航栏（三键）；
     * - 系统 insets 报告了导航栏但窗口实际铺满 → 手势模式（MIUI 假报 insets 的情况）；
     * - 都没有 → none。
     */
    private String navMode(Context context, int navBottom, int stableNavBottom, int transientNavBottom) {
        try {
            int mode = Settings.Secure.getInt(context.getContentResolver(), "navigation_mode");
            if (mode == 0) {
                return "three_button";
            }
            if (mode == 1) {
                return "two_button";
            }
            if (mode == 2) {
                return "gesture";
            }
        } catch (Exception ignored) {
            // 落到下方推断
        }
        if (navBottom > 0) {
            return "three_button";
        }
        if (stableNavBottom > 0 || transientNavBottom > 0) {
            return "gesture";
        }
        return "none";
    }

    /** 物理 px → CSS px，保留两位小数。 */
    private double css(double px, float density) {
        return Math.round(px / density * 100.0) / 100.0;
    }

    private JSObject sizeJson(double width, double height) {
        JSObject s = new JSObject();
        s.put("width", width);
        s.put("height", height);
        return s;
    }

    private JSObject edgesJson(double top, double right, double bottom, double left) {
        JSObject e = new JSObject();
        e.put("top", top);
        e.put("right", right);
        e.put("bottom", bottom);
        e.put("left", left);
        return e;
    }

    private JSObject barJson(int widthPx, int heightPx, float density) {
        JSObject bar = new JSObject();
        bar.put("px", sizeJson(widthPx, heightPx));
        bar.put("css", sizeJson(css(widthPx, density), css(heightPx, density)));
        bar.put("visible", heightPx > 0);
        return bar;
    }

    private int dimensionPx(String name, int fallback) {
        int id = getContext().getResources().getIdentifier(name, "dimen", "android");
        return id > 0 ? getContext().getResources().getDimensionPixelSize(id) : fallback;
    }

    private boolean isEmulator() {
        return Build.FINGERPRINT.startsWith("generic")
            || Build.FINGERPRINT.contains("emulator")
            || Build.MODEL.contains("google_sdk")
            || Build.MODEL.contains("Emulator")
            || Build.MODEL.contains("Android SDK built for x86")
            || "google_sdk".equals(Build.PRODUCT)
            || Build.HARDWARE.contains("goldfish")
            || Build.HARDWARE.contains("ranchu");
    }

    @NonNull
    private String safe(String value) {
        return value == null ? "" : value;
    }

    @NonNull
    private String firstMatch(String input, String regex) {
        if (input == null) {
            return "";
        }
        Matcher m = Pattern.compile(regex).matcher(input);
        return m.find() ? m.group(1) : "";
    }

    // ---------------------------------------------------------------- 通用入口

    private interface MainTask {
        JSObject run();
    }

    /** 在主线程执行收集逻辑并 resolve；每个方法单独 try/catch，互不影响。 */
    private void answerOnMain(PluginCall call, String name, MainTask task) {
        getBridge().executeOnMainThread(() -> {
            try {
                call.resolve(task.run());
            } catch (Exception e) {
                call.reject(name + " failed: " + e.getMessage());
            }
        });
    }
}
