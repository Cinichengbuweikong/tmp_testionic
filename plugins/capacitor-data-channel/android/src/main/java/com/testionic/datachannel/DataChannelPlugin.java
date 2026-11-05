package com.testionic.datachannel;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.File;
import java.security.SecureRandom;
import java.util.Locale;

/**
 * DataChannel 插件（Android 侧，jsName = "DataChannel"）。
 *
 * ======================= 这个插件解决什么 =======================
 *
 * Capacitor 的 JS↔Native 桥是 JSON 序列化（复杂类型必须 base64），大体积二进制传输代价极高。
 * 本插件在原生侧启动一个**只监听 127.0.0.1 的 HTTP + WebSocket server**：
 *  - HTTP：大文件上传/下载（流式、Range）；
 *  - WebSocket：与 WebView 实时双向二进制帧（`new WebSocket(wsUrl)`）。
 * 两者都完全绕开桥。
 *
 * ======================= 数据目录（rootDir） =======================
 *
 * 三种写法：
 *  1. **不传**（默认）：`/sdcard/Android/data/<包名>/files/dataChannel`
 *     （App 专属外部目录，**无需任何权限**，卸载 App 时随数据一起删除）；
 *  2. **相对路径**（如 `myData`）：App 私有内部目录 `files/myData`；
 *  3. **绝对路径**（如 `/sdcard/Download/myData`）：任意位置。
 *     - Android 11+（API 30+）需要 `MANAGE_EXTERNAL_STORAGE`，先调 `requestAllFilesAccess()` 跳系统设置授权；
 *     - Android 10 及以下需要 `WRITE_EXTERNAL_STORAGE` 运行时权限（同样调 `requestAllFilesAccess()` 触发弹窗）。
 *
 * ======================= 安全设计 =======================
 *
 * 1. 只监听 127.0.0.1；随机端口 + 每次启动随机 32 字节 token（常量时间比较）；
 * 2. Origin 白名单；非法 Origin 静默断开；鉴权失败统一 404（隐藏服务存在）；
 * 3. 所有 HTTP 路径限制在 rootDir 内（canonical 校验，拒绝 .. / 绝对路径 / 软链逃逸）；
 * 4. 请求体上限 + 请求头上限 + 读超时；上传先写 .part 再 rename；日志不记录 token。
 *
 * ======================= 线程 =======================
 *
 * start/stop/getInfo 在主线程；HTTP 请求在 4 线程池；WebSocket 长连接在独立 cached 线程池。
 */
@CapacitorPlugin(name = "DataChannel")
public class DataChannelPlugin extends Plugin {

    private static final String TAG = "DataChannel";
    private static final String EVENT_STATE_CHANGED = "channelStateChanged";
    private static final long DEFAULT_MAX_BODY_BYTES = 512L * 1024 * 1024; // 512MB
    private static final String DEFAULT_ROOT_NAME = "dataChannel";
    private static final String ERR_NEED_PERMISSION = "ABSOLUTE_PATH_PERMISSION_REQUIRED";

    private DataChannelServer server;
    private String token = "";
    private File root;
    private int port = 0;
    private long maxBodyBytes = DEFAULT_MAX_BODY_BYTES;
    private long startedAt = 0;
    private boolean webSocketEnabled = true;

    // ---------------------------------------------------------------- API

    @PluginMethod
    public void start(PluginCall call) {
        getBridge().executeOnMainThread(() -> {
            try {
                if (server != null && server.isRunning()) {
                    call.resolve(infoJson());
                    return;
                }

                Integer requestedPort = call.getInt("port", 0);
                String rootDirOption = call.getString("rootDir", null);
                Long configuredMax = call.getLong("maxBodyBytes", DEFAULT_MAX_BODY_BYTES);
                Boolean wsEchoOption = call.getBoolean("wsEcho", Boolean.TRUE);

                maxBodyBytes = configuredMax == null ? DEFAULT_MAX_BODY_BYTES : Math.max(1024L, configuredMax);
                webSocketEnabled = true;

                File resolvedRoot;
                try {
                    resolvedRoot = resolveRootDir(rootDirOption);
                } catch (SecurityException e) {
                    if (ERR_NEED_PERMISSION.equals(e.getMessage())) {
                        JSObject error = new JSObject();
                        error.put("code", ERR_NEED_PERMISSION);
                        error.put(
                            "message",
                            "Access to this absolute path requires storage permission. " +
                            "Call requestAllFilesAccess(), grant it, then retry start()."
                        );
                        call.reject(
                            ERR_NEED_PERMISSION + ": call requestAllFilesAccess() and retry",
                            error
                        );
                        return;
                    }
                    call.reject("invalid rootDir: " + e.getMessage());
                    return;
                }

                if (!resolvedRoot.exists() && !resolvedRoot.mkdirs()) {
                    call.reject(buildCreateRootError(resolvedRoot));
                    return;
                }
                if (!resolvedRoot.canWrite()) {
                    call.reject(buildCreateRootError(resolvedRoot) + " (directory exists but is not writable)");
                    return;
                }

                root = resolvedRoot;
                token = generateToken();
                boolean echo = wsEchoOption == null || wsEchoOption;
                server = new DataChannelServer(root, token, maxBodyBytes, echo, this::onRequestCompleted);
                port = server.start(requestedPort == null ? 0 : requestedPort);
                startedAt = System.currentTimeMillis();

                Log.i(TAG, "started on 127.0.0.1:" + port + " root=" + root.getAbsolutePath());
                JSObject info = infoJson();
                call.resolve(info);
                notifyListeners(EVENT_STATE_CHANGED, info);
            } catch (Exception e) {
                stopServer();
                call.reject("start failed: " + e.getMessage());
            }
        });
    }

    @PluginMethod
    public void stop(PluginCall call) {
        getBridge().executeOnMainThread(() -> {
            boolean wasRunning = server != null && server.isRunning();
            stopServer();
            JSObject info = infoJson();
            call.resolve(info);
            if (wasRunning) {
                notifyListeners(EVENT_STATE_CHANGED, info);
            }
        });
    }

    @PluginMethod
    public void getInfo(PluginCall call) {
        getBridge().executeOnMainThread(() -> call.resolve(infoJson()));
    }

    /**
     * 是否已获得"访问任意存储路径"的权限。
     * resolve: { granted, platform, sdkInt, mode }
     */
    @PluginMethod
    public void checkAllFilesAccess(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("granted", hasAllFilesAccess());
        ret.put("platform", "android");
        ret.put("sdkInt", Build.VERSION.SDK_INT);
        ret.put("mode", Build.VERSION.SDK_INT >= 30 ? "MANAGE_EXTERNAL_STORAGE" : "WRITE_EXTERNAL_STORAGE");
        call.resolve(ret);
    }

    /**
     * 申请"访问任意存储路径"的权限：
     *  - API 30+：跳转系统的"所有文件访问权限"设置页（用户手动开启）；
     *  - API < 30：弹运行时权限对话框（WRITE_EXTERNAL_STORAGE）。
     * resolve: { granted, opened, mode }
     */
    @PluginMethod
    public void requestAllFilesAccess(PluginCall call) {
        JSObject ret = new JSObject();
        if (Build.VERSION.SDK_INT >= 30) {
            ret.put("mode", "MANAGE_EXTERNAL_STORAGE");
            if (hasAllFilesAccess()) {
                ret.put("granted", true);
                ret.put("opened", false);
                call.resolve(ret);
                return;
            }
            try {
                Intent intent = new Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getContext().getPackageName())
                );
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(intent);
                ret.put("granted", false);
                ret.put("opened", true);
                call.resolve(ret);
            } catch (Exception e) {
                call.reject("cannot open settings: " + e.getMessage());
            }
            return;
        }

        ret.put("mode", "WRITE_EXTERNAL_STORAGE");
        Activity activity = getActivity();
        if (activity == null) {
            call.reject("no activity");
            return;
        }
        if (hasAllFilesAccess()) {
            ret.put("granted", true);
            ret.put("opened", false);
            call.resolve(ret);
            return;
        }
        ActivityCompat.requestPermissions(
            activity,
            new String[] { Manifest.permission.WRITE_EXTERNAL_STORAGE },
            0x1F2E
        );
        ret.put("granted", false);
        ret.put("opened", true);
        call.resolve(ret);
    }

    // ---------------------------------------------------------------- 生命周期

    @Override
    protected void handleOnDestroy() {
        stopServer();
        super.handleOnDestroy();
    }

    // ---------------------------------------------------------------- 内部

    private boolean hasAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            return Environment.isExternalStorageManager();
        }
        return (
            ContextCompat.checkSelfPermission(
                getContext(),
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) ==
            PackageManager.PERMISSION_GRANTED
        );
    }

    /**
     * 是否真的能写"公共目录"（如 /sdcard/Download）：
     *  - API 30+：需要 MANAGE_EXTERNAL_STORAGE；
     *  - API 29：只有 legacy storage 模式（targetSdk <= 29）才行，否则 scoped storage 阻止；
     *  - API <= 28：有 WRITE_EXTERNAL_STORAGE 即可。
     */
    private boolean canWritePublicStorage() {
        if (Build.VERSION.SDK_INT >= 30) {
            return Environment.isExternalStorageManager();
        }
        if (Build.VERSION.SDK_INT == 29) {
            // isExternalStorageLegacy() 仅在 API 29 上可用，需做版本守卫
            return Environment.isExternalStorageLegacy();
        }
        return (
            ContextCompat.checkSelfPermission(
                getContext(),
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) ==
            PackageManager.PERMISSION_GRANTED
        );
    }

    /**
     * 解析 rootDir：null/空 → 默认外部专属目录；相对路径 → 内部私有目录；绝对路径 → 任意位置（需权限）。
     */
    private File resolveRootDir(String requested) throws SecurityException {
        Context context = getContext();

        if (requested == null || requested.trim().isEmpty()) {
            // 默认：/sdcard/Android/data/<pkg>/files/dataChannel（无需权限）
            File external = context.getExternalFilesDir(DEFAULT_ROOT_NAME);
            if (external != null) {
                return external;
            }
            // 外部存储不可用时兜底到内部私有目录
            return new File(context.getFilesDir(), DEFAULT_ROOT_NAME);
        }

        String trimmed = requested.trim();
        if (trimmed.startsWith("/") || trimmed.startsWith("file://")) {
            String path = trimmed.startsWith("file://") ? trimmed.substring("file://".length()) : trimmed;
            File target = new File(path);
            if (!isAppOwnPath(target) && !canWritePublicStorage()) {
                if (Build.VERSION.SDK_INT >= 30) {
                    throw new SecurityException(ERR_NEED_PERMISSION);
                }
                // API 29 及以下：targetSdk >= 30 时 scoped storage 强制生效，公共目录不可写
                throw new SecurityException(
                    "SCOPED_STORAGE_BLOCKED: on this Android version, public directories " +
                    "(e.g. /sdcard/Download) are not writable when targetSdk >= 30. " +
                    "Use an app-specific directory (default) or an absolute path inside the app's own storage."
                );
            }
            return target;
        }

        // 相对路径：App 私有内部目录下
        File base = context.getFilesDir();
        File candidate = new File(base, trimmed);
        try {
            if (!candidate.getCanonicalPath().startsWith(base.getCanonicalPath() + File.separator)) {
                throw new SecurityException("rootDir escapes app private dir");
            }
        } catch (java.io.IOException e) {
            throw new SecurityException("invalid rootDir: " + e.getMessage());
        }
        return candidate;
    }

    /** 构造"无法创建/写入 rootDir"的诊断信息，帮助定位是权限还是平台限制。 */
    private String buildCreateRootError(File target) {
        StringBuilder sb = new StringBuilder();
        sb.append("cannot create rootDir: ").append(target.getAbsolutePath());
        File parent = target.getParentFile();
        sb.append(" (parentExists=").append(parent != null && parent.exists()).append(")");
        if (!isAppOwnPath(target)) {
            if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
                sb
                    .append(". Hint: public directories need MANAGE_EXTERNAL_STORAGE on Android 11+ ")
                    .append("— call requestAllFilesAccess(), grant it in system settings, then retry.");
            } else if (Build.VERSION.SDK_INT == 29) {
                sb
                    .append(". Hint: on Android 10 with targetSdk >= 30, public directories are blocked by ")
                    .append("scoped storage. Use an app-specific directory instead ")
                    .append("(default or e.g. /sdcard/Android/data/<pkg>/files/xxx).");
            } else {
                sb.append(". Hint: storage permission may be missing.");
            }
        }
        return sb.toString();
    }

    /** 判断路径是否位于 App 自己的可写目录（无需额外权限）。 */
    private boolean isAppOwnPath(File file) {
        try {
            String canonical = file.getCanonicalPath();
            File[] ownDirs = new File[] {
                getContext().getFilesDir(),
                getContext().getCacheDir(),
                getContext().getExternalFilesDir(null),
                getContext().getExternalCacheDir(),
            };
            for (File dir : ownDirs) {
                if (dir == null) {
                    continue;
                }
                String dirPath = dir.getCanonicalPath();
                if (canonical.equals(dirPath) || canonical.startsWith(dirPath + File.separator)) {
                    return true;
                }
            }
        } catch (java.io.IOException ignored) {
            // 忽略
        }
        return false;
    }

    private void stopServer() {
        if (server != null) {
            server.stop();
            server = null;
        }
        token = "";
        port = 0;
        startedAt = 0;
    }

    private JSObject infoJson() {
        boolean running = server != null && server.isRunning();
        JSObject ret = new JSObject();
        ret.put("running", running);
        ret.put("port", running ? port : 0);
        ret.put("baseUrl", running ? ("http://127.0.0.1:" + port) : "");
        ret.put("wsUrl", running ? ("ws://127.0.0.1:" + port + "/ws?token=" + token) : "");
        ret.put("token", running ? token : "");
        ret.put("rootPath", root != null ? root.getAbsolutePath() : "");
        ret.put("maxBodyBytes", maxBodyBytes);
        ret.put("startedAt", running ? startedAt : 0);
        ret.put("activeConnections", server != null ? server.activeConnections() : 0);
        ret.put("wsClients", server != null ? server.wsClientCount() : 0);
        ret.put("webSocketEnabled", webSocketEnabled);
        return ret;
    }

    private void onRequestCompleted(String method, String path, int status, long bytes, long durationMs) {
        // 只记录方法/路径/状态/耗时，绝不记录 token
        Log.d(TAG, method + " " + path + " -> " + status + " (" + durationMs + "ms)");
    }

    private static String generateToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format(Locale.US, "%02x", b));
        }
        return sb.toString();
    }
}
