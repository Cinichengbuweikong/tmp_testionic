package com.testionic.datachannel;

import android.content.Context;
import android.util.Log;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.File;

/**
 * DataChannel 插件（Android 侧，jsName = "DataChannel"）。
 *
 * ======================= 能力 =======================
 *
 * 两个**完全独立**的本地 server，可分别启停：
 *  - **HTTP**（`startHttp/stopHttp`）：大文件上传/下载（流式、Range）；
 *  - **WebSocket**（`startWebSocket/stopWebSocket`）：实时双向二进制帧。
 *
 * ======================= 鉴权：OTP（不是长期 token）=======================
 *
 * 长期 token 一旦被抓包拿到就等于全权限。改用 **OTP**：
 *  - OTP 通过 **Capacitor 桥**下发（`createHttpOtp` / `createWsOtp`），桥是进程内通信，**抓包看不到**；
 *  - **一次性**（默认）：用一次作废，无时间限制；
 *  - **可复用**（仅 `scope=read`，用于媒体持续读取）：**必须用完 revoke**，否则在 server
 *    运行期间永久有效（严重安全问题）；
 *  - 写操作**不允许 reusable**（每次写必须新 OTP）；
 *  - 无 TTL：等价于"server 生命周期内有效"，server 停止即全部失效。
 *
 * ======================= 连接限流 =======================
 *
 * 两级：未认证连接 4（防扫描/DoS）/ 已认证连接 maxConnections（默认 16，保证视频多 Range 并发）。
 * 身份识别靠 OTP（连接来源无法识别），限流只用于防滥用。
 *
 * ======================= 职责边界 =======================
 *
 * 本插件**只负责高速传输**。存储权限、系统选择器、目录列表/删除/重命名等请用
 * `capacitor-native-files`。
 */
@CapacitorPlugin(name = "DataChannel")
public class DataChannelPlugin extends Plugin {

    private static final String TAG = "DataChannel";
    private static final long DEFAULT_MAX_BODY_BYTES = 512L * 1024 * 1024; // 512MB
    private static final int DEFAULT_MAX_CONNECTIONS = 16;
    private static final String DEFAULT_ROOT_NAME = "dataChannel";

    // HTTP
    private DataChannelServer httpServer;
    private OtpStore httpOtps;
    private File httpRoot;
    private int httpPort = 0;
    private long httpStartedAt = 0;
    private long maxBodyBytes = DEFAULT_MAX_BODY_BYTES;
    private int httpMaxConnections = DEFAULT_MAX_CONNECTIONS;

    // WebSocket
    private DataChannelWsServer wsServer;
    private OtpStore wsOtps;
    private int wsPort = 0;
    private long wsStartedAt = 0;
    private boolean wsEcho = true;
    private int wsMaxConnections = DEFAULT_MAX_CONNECTIONS;

    // ---------------------------------------------------------------- HTTP

    @PluginMethod
    public void startHttp(PluginCall call) {
        getBridge().executeOnMainThread(() -> {
            try {
                if (httpServer != null && httpServer.isRunning()) {
                    call.resolve(httpInfo());
                    return;
                }

                Integer requestedPort = call.getInt("port", 0);
                String rootDirOption = call.getString("rootDir", null);
                Long configuredMax = call.getLong("maxBodyBytes", DEFAULT_MAX_BODY_BYTES);
                Integer configuredConnections = call.getInt("maxConnections", DEFAULT_MAX_CONNECTIONS);

                maxBodyBytes = configuredMax == null ? DEFAULT_MAX_BODY_BYTES : Math.max(1024L, configuredMax);
                httpMaxConnections = configuredConnections == null
                    ? DEFAULT_MAX_CONNECTIONS
                    : Math.max(1, configuredConnections);

                File resolvedRoot = resolveRootDir(rootDirOption);
                if (!resolvedRoot.exists() && !resolvedRoot.mkdirs()) {
                    call.reject(buildCreateRootError(resolvedRoot));
                    return;
                }
                if (!resolvedRoot.canWrite()) {
                    call.reject(buildCreateRootError(resolvedRoot) + " (directory exists but is not writable)");
                    return;
                }

                httpOtps = new OtpStore();
                httpRoot = resolvedRoot;
                httpServer = new DataChannelServer(
                    httpRoot,
                    httpOtps,
                    maxBodyBytes,
                    httpMaxConnections,
                    this::onHttpRequestCompleted
                );
                httpPort = httpServer.start(requestedPort == null ? 0 : requestedPort);
                httpStartedAt = System.currentTimeMillis();

                Log.i(TAG, "HTTP started on 127.0.0.1:" + httpPort + " root=" + httpRoot.getAbsolutePath());
                call.resolve(httpInfo());
            } catch (Exception e) {
                stopHttpInternal();
                call.reject("startHttp failed: " + e.getMessage());
            }
        });
    }

    @PluginMethod
    public void stopHttp(PluginCall call) {
        getBridge().executeOnMainThread(() -> {
            stopHttpInternal();
            call.resolve(httpInfo());
        });
    }

    // ---------------------------------------------------------------- WebSocket

    @PluginMethod
    public void startWebSocket(PluginCall call) {
        getBridge().executeOnMainThread(() -> {
            try {
                if (wsServer != null && wsServer.isRunning()) {
                    call.resolve(wsInfo());
                    return;
                }

                Integer requestedPort = call.getInt("port", 0);
                Boolean echoOption = call.getBoolean("wsEcho", Boolean.TRUE);
                Integer configuredConnections = call.getInt("maxConnections", DEFAULT_MAX_CONNECTIONS);

                wsEcho = echoOption == null || echoOption;
                wsMaxConnections = configuredConnections == null
                    ? DEFAULT_MAX_CONNECTIONS
                    : Math.max(1, configuredConnections);

                wsOtps = new OtpStore();
                wsServer = new DataChannelWsServer(wsOtps, wsEcho, wsMaxConnections, this::onWsClientCountChanged);
                wsPort = wsServer.start(requestedPort == null ? 0 : requestedPort);
                wsStartedAt = System.currentTimeMillis();

                Log.i(TAG, "WebSocket started on 127.0.0.1:" + wsPort + " (echo=" + wsEcho + ")");
                call.resolve(wsInfo());
            } catch (Exception e) {
                stopWebSocketInternal();
                call.reject("startWebSocket failed: " + e.getMessage());
            }
        });
    }

    @PluginMethod
    public void stopWebSocket(PluginCall call) {
        getBridge().executeOnMainThread(() -> {
            stopWebSocketInternal();
            call.resolve(wsInfo());
        });
    }

    // ---------------------------------------------------------------- 状态

    @PluginMethod
    public void getInfo(PluginCall call) {
        getBridge().executeOnMainThread(() -> {
            JSObject ret = new JSObject();
            boolean httpRunning = httpServer != null && httpServer.isRunning();
            boolean wsRunning = wsServer != null && wsServer.isRunning();
            // 用 JSONObject.NULL 显式写入 null（JSObject.put(key, null) 会被跳过）
            ret.put("http", httpRunning ? httpInfo() : org.json.JSONObject.NULL);
            ret.put("webSocket", wsRunning ? wsInfo() : org.json.JSONObject.NULL);
            call.resolve(ret);
        });
    }

    // ---------------------------------------------------------------- OTP

    /**
     * 创建 HTTP OTP（通过桥下发，抓包看不到）。
     * 参数：{ scope?: 'read'|'write'|'readwrite'(默认 read), path: string(必填), reusable?: boolean(默认 false) }
     */
    @PluginMethod
    public void createHttpOtp(PluginCall call) {
        if (httpServer == null || !httpServer.isRunning() || httpOtps == null) {
            call.reject("HTTP server is not running; call startHttp() first");
            return;
        }
        String scope = call.getString("scope", OtpStore.SCOPE_READ);
        String path = call.getString("path", null);
        if (path == null || path.trim().isEmpty()) {
            call.reject("missing path: an HTTP OTP must be bound to a specific file/folder");
            return;
        }
        Boolean reusableOption = call.getBoolean("reusable", Boolean.FALSE);
        boolean reusable = reusableOption != null && reusableOption;
        String effectiveScope = (scope == null || scope.trim().isEmpty()) ? OtpStore.SCOPE_READ : scope.trim();
        try {
            String otp = httpOtps.create(effectiveScope, path, reusable);
            JSObject ret = new JSObject();
            ret.put("otp", otp);
            ret.put("scope", effectiveScope);
            ret.put("path", OtpStore.normalizePath(path));
            ret.put("reusable", reusable);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject(e.getMessage());
        }
    }

    /** 创建 WebSocket OTP（一次性；WS 双向含写入，不允许 reusable）。 */
    @PluginMethod
    public void createWsOtp(PluginCall call) {
        if (wsServer == null || !wsServer.isRunning() || wsOtps == null) {
            call.reject("WebSocket server is not running; call startWebSocket() first");
            return;
        }
        try {
            String otp = wsOtps.create(OtpStore.SCOPE_READWRITE, "", false);
            JSObject ret = new JSObject();
            ret.put("otp", otp);
            ret.put("reusable", false);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject(e.getMessage());
        }
    }

    @PluginMethod
    public void revokeOtp(PluginCall call) {
        String otp = call.getString("otp", null);
        if (otp == null || otp.isEmpty()) {
            call.reject("missing otp");
            return;
        }
        boolean revoked = false;
        if (httpOtps != null) {
            revoked = httpOtps.revoke(otp) || revoked;
        }
        if (wsOtps != null) {
            revoked = wsOtps.revoke(otp) || revoked;
        }
        JSObject ret = new JSObject();
        ret.put("revoked", revoked);
        call.resolve(ret);
    }

    /** 批量撤销：{ server?: 'http' | 'ws' }；不传 = 两者都撤销。 */
    @PluginMethod
    public void revokeAllOtps(PluginCall call) {
        String server = call.getString("server", null);
        int revoked = 0;
        if (httpOtps != null && (server == null || "http".equalsIgnoreCase(server))) {
            revoked += httpOtps.revokeAll();
        }
        if (wsOtps != null && (server == null || "ws".equalsIgnoreCase(server) || "websocket".equalsIgnoreCase(server))) {
            revoked += wsOtps.revokeAll();
        }
        JSObject ret = new JSObject();
        ret.put("revoked", revoked);
        call.resolve(ret);
    }

    // ---------------------------------------------------------------- 生命周期

    @Override
    protected void handleOnDestroy() {
        stopHttpInternal();
        stopWebSocketInternal();
        super.handleOnDestroy();
    }

    // ---------------------------------------------------------------- 内部

    private void stopHttpInternal() {
        if (httpServer != null) {
            httpServer.stop();
            httpServer = null;
        }
        if (httpOtps != null) {
            httpOtps.revokeAll();
            httpOtps = null;
        }
        httpPort = 0;
        httpStartedAt = 0;
    }

    private void stopWebSocketInternal() {
        if (wsServer != null) {
            wsServer.stop();
            wsServer = null;
        }
        if (wsOtps != null) {
            wsOtps.revokeAll();
            wsOtps = null;
        }
        wsPort = 0;
        wsStartedAt = 0;
    }

    /**
     * 解析 rootDir：null/空 → App 专属外部目录；相对路径 → 内部私有目录；绝对路径 → 原样返回。
     * 绝对路径的权限由外部（capacitor-native-files 或系统设置）负责。
     */
    private File resolveRootDir(String requested) {
        Context context = getContext();
        if (requested == null || requested.trim().isEmpty()) {
            File external = context.getExternalFilesDir(DEFAULT_ROOT_NAME);
            if (external != null) {
                return external;
            }
            return new File(context.getFilesDir(), DEFAULT_ROOT_NAME);
        }
        String trimmed = requested.trim();
        if (trimmed.startsWith("/") || trimmed.startsWith("file://")) {
            String path = trimmed.startsWith("file://") ? trimmed.substring("file://".length()) : trimmed;
            return new File(path);
        }
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

    private JSObject httpInfo() {
        boolean running = httpServer != null && httpServer.isRunning();
        JSObject ret = new JSObject();
        ret.put("running", running);
        ret.put("port", running ? httpPort : 0);
        ret.put("baseUrl", running ? ("http://127.0.0.1:" + httpPort) : "");
        ret.put("rootPath", httpRoot != null ? httpRoot.getAbsolutePath() : "");
        ret.put("maxBodyBytes", maxBodyBytes);
        ret.put("maxConnections", httpMaxConnections);
        ret.put("activeConnections", running ? httpServer.activeConnections() : 0);
        ret.put("pendingConnections", running ? httpServer.pendingConnections() : 0);
        ret.put("otpCount", httpOtps != null ? httpOtps.size() : 0);
        ret.put("startedAt", running ? httpStartedAt : 0);
        return ret;
    }

    private JSObject wsInfo() {
        boolean running = wsServer != null && wsServer.isRunning();
        JSObject ret = new JSObject();
        ret.put("running", running);
        ret.put("port", running ? wsPort : 0);
        ret.put("wsUrl", running ? ("ws://127.0.0.1:" + wsPort + "/ws") : "");
        ret.put("wsEcho", wsEcho);
        ret.put("maxConnections", wsMaxConnections);
        ret.put("activeConnections", running ? wsServer.activeConnections() : 0);
        ret.put("pendingConnections", running ? wsServer.pendingConnections() : 0);
        ret.put("clients", running ? wsServer.clientCount() : 0);
        ret.put("otpCount", wsOtps != null ? wsOtps.size() : 0);
        ret.put("startedAt", running ? wsStartedAt : 0);
        return ret;
    }

    private void onHttpRequestCompleted(String method, String path, int status, long bytes, long durationMs) {
        Log.d(TAG, "HTTP " + method + " " + path + " -> " + status + " (" + durationMs + "ms)");
    }

    private void onWsClientCountChanged(int count) {
        Log.d(TAG, "WS clients=" + count);
    }
}
