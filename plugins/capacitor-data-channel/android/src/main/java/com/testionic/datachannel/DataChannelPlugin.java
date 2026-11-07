package com.testionic.datachannel;

import android.content.Context;
import android.util.Log;

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
 * ======================= 职责边界（重要） =======================
 *
 * 本插件**只负责高速传输**。以下能力**不在本插件范围**，请用 `capacitor-native-files`：
 *  - 存储权限的查询/申请（`checkStorageAccess` / `requestStorageAccess`）；
 *  - 系统文件/文件夹选择器；
 *  - 目录列表、删除、重命名等文件管理操作。
 *
 * 本插件只提供 3 个 HTTP 路由（`/health`、`/upload`、`/download`）+ WebSocket（`/ws`）。
 *
 * ======================= 数据目录（rootDir） =======================
 *
 * 三种写法：
 *  1. **不传**（默认）：`/sdcard/Android/data/<包名>/files/dataChannel`
 *     （App 专属外部目录，**无需任何权限**，卸载 App 时随数据一起删除）；
 *  2. **相对路径**（如 `myData`）：App 私有内部目录 `files/myData`；
 *  3. **绝对路径**（如 `/sdcard/Download/myData`）：任意位置。
 *     - Android 11+（API 30+）需要"所有文件访问权限"（MANAGE_EXTERNAL_STORAGE）；
 *     - Android 10 及以下需要 WRITE_EXTERNAL_STORAGE + legacy storage；
 *     - **权限的查询与申请请用 `capacitor-native-files`**（本插件不再提供）；
 *     - 无权限时 `start()` 会返回明确错误（不会静默失败）。
 *
 * ======================= 安全设计 =======================
 *
 * 1. 只监听 127.0.0.1；随机端口 + 每次启动随机 32 字节 token（常量时间比较）；
 * 2. Origin 白名单；非法 Origin 静默断开；鉴权失败统一 404（隐藏服务存在）；
 * 3. 所有路径限制在 rootDir 内（canonical 校验，拒绝 .. / 绝对路径 / 软链逃逸）；
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
                } catch (IllegalArgumentException e) {
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

    // ---------------------------------------------------------------- 生命周期

    @Override
    protected void handleOnDestroy() {
        stopServer();
        super.handleOnDestroy();
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 解析 rootDir：null/空 → 默认外部专属目录；相对路径 → 内部私有目录；绝对路径 → 原样返回。
     *
     * 注意：绝对路径的**权限由外部负责**（`capacitor-native-files` 的权限 API 或系统设置）；
     * 这里只做路径合法性/可写性检查，失败时给出明确错误。
     */
    private File resolveRootDir(String requested) {
        Context context = getContext();

        if (requested == null || requested.trim().isEmpty()) {
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
            return new File(path);
        }

        // 相对路径：App 私有内部目录下
        File base = context.getFilesDir();
        File candidate = new File(base, trimmed);
        try {
            if (!candidate.getCanonicalPath().startsWith(base.getCanonicalPath() + File.separator)) {
                throw new IllegalArgumentException("rootDir escapes app private dir");
            }
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("invalid rootDir: " + e.getMessage());
        }
        return candidate;
    }

    /** 构造"无法创建/写入 rootDir"的诊断信息。 */
    private String buildCreateRootError(File target) {
        StringBuilder sb = new StringBuilder();
        sb.append("cannot create rootDir: ").append(target.getAbsolutePath());
        File parent = target.getParentFile();
        sb.append(" (parentExists=").append(parent != null && parent.exists()).append(")");
        if (!isAppOwnPath(target)) {
            sb
                .append(". Hint: writing outside the app's own directories requires storage permission — ")
                .append("grant it via capacitor-native-files (requestStorageAccess) or system settings, then retry.");
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
