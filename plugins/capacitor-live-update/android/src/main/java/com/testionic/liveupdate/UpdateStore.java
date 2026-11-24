package com.testionic.liveupdate;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 热更新的持久化状态与路径约定。
 *
 * 目录结构（App 私有目录，不需要任何权限）：
 * ```
 * filesDir/live-update/bundles/<bundleId>/index.html ...
 * ```
 *
 * Capacitor 的 WebView 根路径持久化：
 * - Android 读 `SharedPreferences("CapWebViewSettings").getString("serverBasePath")`，
 *   启动时如果目录存在就把 WebView 指过去（见 `Bridge.java`）。本插件直接写这个 key，
 *   这样**下次冷启动**第一帧就是新包，不会闪一下旧页面。
 */
public class UpdateStore {

    public static final String PREFS = "capacitor_live_update";
    private static final String CAP_PREFS = "CapWebViewSettings";
    private static final String CAP_SERVER_PATH = "serverBasePath";

    private final Context context;

    public UpdateStore(Context context) {
        this.context = context.getApplicationContext();
    }

    SharedPreferences prefs() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    File rootDir() {
        File dir = new File(context.getFilesDir(), "live-update");
        if (!dir.exists() && !dir.mkdirs()) {
            // 目录建不出来时后续操作会失败并报错给 JS
        }
        return dir;
    }

    File bundlesDir() {
        File dir = new File(rootDir(), "bundles");
        if (!dir.exists() && !dir.mkdirs()) {
            // ignore
        }
        return dir;
    }

    File bundleDir(String bundleId) {
        return new File(bundlesDir(), bundleId);
    }

    // ------------------------------------------------------------ 配置

    String getServerUrl() {
        return prefs().getString("cfg_server_url", null);
    }

    String getPublicKey() {
        return prefs().getString("cfg_public_key", null);
    }

    boolean isAllowUnsigned() {
        return prefs().getBoolean("cfg_allow_unsigned", false);
    }

    String getChannel() {
        return prefs().getString("cfg_channel", "production");
    }

    String getAppId() {
        return prefs().getString("cfg_app_id", context.getPackageName());
    }

    boolean isAllowDowngrade() {
        return prefs().getBoolean("cfg_allow_downgrade", false);
    }

    int getTimeoutMs() {
        return prefs().getInt("cfg_timeout_ms", 30000);
    }

    void saveConfig(String serverUrl, String publicKey, boolean allowUnsigned, String channel,
                    String appId, boolean allowDowngrade, int timeoutMs) {
        prefs().edit()
                .putString("cfg_server_url", serverUrl)
                .putString("cfg_public_key", publicKey)
                .putBoolean("cfg_allow_unsigned", allowUnsigned)
                .putString("cfg_channel", channel)
                .putString("cfg_app_id", appId)
                .putBoolean("cfg_allow_downgrade", allowDowngrade)
                .putInt("cfg_timeout_ms", timeoutMs)
                .apply();
    }

    // ------------------------------------------------------------ 状态

    String getCurrentBundleId() {
        return prefs().getString("st_current", null);
    }

    void setCurrentBundleId(String bundleId) {
        prefs().edit().putString("st_current", bundleId).apply();
    }

    String getPreviousBundleId() {
        return prefs().getString("st_previous", null);
    }

    void setPreviousBundleId(String bundleId) {
        prefs().edit().putString("st_previous", bundleId).apply();
    }

    boolean isPending() {
        return prefs().getBoolean("st_pending", false);
    }

    void setPending(boolean pending) {
        prefs().edit().putBoolean("st_pending", pending).apply();
    }

    int getBootAttempts() {
        return prefs().getInt("st_boot_attempts", 0);
    }

    void setBootAttempts(int attempts) {
        prefs().edit().putInt("st_boot_attempts", attempts).apply();
    }

    long getInstalledAppVersion() {
        return prefs().getLong("st_app_version", -1);
    }

    void setInstalledAppVersion(long version) {
        prefs().edit().putLong("st_app_version", version).apply();
    }

    long getLastCheckAt() {
        return prefs().getLong("st_last_check", 0);
    }

    void setLastCheckAt(long value) {
        prefs().edit().putLong("st_last_check", value).apply();
    }

    void clearState() {
        prefs().edit()
                .remove("st_current")
                .remove("st_current_version")
                .remove("st_previous")
                .remove("st_previous_version")
                .remove("st_pending")
                .remove("st_boot_attempts")
                .apply();
    }

    /** 最近一次自愈/回滚的说明，展示在 `getState().note` 里。 */
    String getLastNote() {
        return prefs().getString("st_last_note", null);
    }

    void setLastNote(String note) {
        if (note == null) {
            prefs().edit().remove("st_last_note").apply();
        } else {
            prefs().edit().putString("st_last_note", note).apply();
        }
    }

    // ------------------------------------------------------------ Capacitor 根路径

    /** 让 Capacitor 下次冷启动直接用这个目录（写它自己的 SharedPreferences）。 */
    void persistCapacitorBasePath(String path) {
        SharedPreferences cap = context.getSharedPreferences(CAP_PREFS, Context.MODE_PRIVATE);
        cap.edit().putString(CAP_SERVER_PATH, path).apply();
    }

    void clearCapacitorBasePath() {
        SharedPreferences cap = context.getSharedPreferences(CAP_PREFS, Context.MODE_PRIVATE);
        cap.edit().remove(CAP_SERVER_PATH).apply();
    }

    // ------------------------------------------------------------ 版本

    String builtinVersion() {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return info.versionName != null ? info.versionName : "0";
        } catch (PackageManager.NameNotFoundException e) {
            return "0";
        }
    }

    long appVersionCode() {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return info.getLongVersionCode();
            }
            return info.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return -1;
        }
    }

    // ------------------------------------------------------------ 包列表

    List<File> listBundleDirs() {
        List<File> result = new ArrayList<>();
        File[] files = bundlesDir().listFiles();
        if (files == null) {
            return result;
        }
        for (File file : files) {
            if (file.isDirectory() && !file.getName().startsWith(".")) {
                result.add(file);
            }
        }
        return result;
    }

    long dirSize(File dir) {
        long total = 0;
        File[] files = dir.listFiles();
        if (files == null) {
            return 0;
        }
        for (File file : files) {
            total += file.isDirectory() ? dirSize(file) : file.length();
        }
        return total;
    }

    /** 只保留最近 3 个包，避免无限占空间。 */
    void pruneOldBundles(String keep1, String keep2) {
        List<File> dirs = listBundleDirs();
        for (File dir : dirs) {
            String name = dir.getName();
            if (name.equals(keep1) || name.equals(keep2)) {
                continue;
            }
            deleteRecursively(dir);
        }
    }

    static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
