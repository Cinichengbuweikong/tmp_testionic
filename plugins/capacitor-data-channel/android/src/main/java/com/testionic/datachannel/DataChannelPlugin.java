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
 * 本插件在原生侧启动一个**只监听 127.0.0.1 的 HTTP server**，让 WebView 用标准 fetch /
 * &lt;video&gt; / &lt;img&gt; 直接收发原始二进制，彻底绕开桥。
 *
 * ======================= 安全设计 =======================
 *
 * 1. 只绑定 127.0.0.1（外部设备/其他机器无法访问，只能本机 WebView 连）；
 * 2. 随机端口（默认 port=0 由系统分配）+ 每次启动随机 32 字节 Bearer token；
 *    token 可通过 `Authorization: Bearer <t>` 或 `?token=` 传递（后者给 &lt;video src&gt; 用），
 *    服务端用 MessageDigest.isEqual 做常量时间比较；
 * 3. Origin 白名单：只接受 WebView 的 origin（capacitor://localhost、https://localhost 等）；
 * 4. 数据路径限制在 App 私有目录下（canonical path 校验，拒绝 .. / 绝对路径 / 软链逃逸）；
 * 5. 请求体大小上限（默认 512MB，可配）+ 请求头大小上限 + socket 读超时；
 * 6. 上传先写 .part 再 rename，避免半截文件；日志**不记录 token**。
 *
 * ======================= 路由 =======================
 *
 * GET  /health                          免 token，返回 {"ok":true}
 * PUT/POST /upload?path=&append=0|1     原始二进制上传（流式写盘），返回 {path,bytes,size}
 * GET  /download?path=[&token=]         流式下载，支持 Range（206），视频拖动进度条必需
 * GET  /info?path=                      文件元信息
 * GET  /list?path=                      目录列表（为后续文件系统插件预留）
 * DELETE /file?path=                    删除文件/目录
 *
 * ======================= 线程 =======================
 *
 * start/stop/getInfo 都在主线程执行；HTTP 请求处理在独立线程池（DataChannelServer 内部）。
 */
@CapacitorPlugin(name = "DataChannel")
public class DataChannelPlugin extends Plugin {

    private static final String TAG = "DataChannel";
    private static final String EVENT_STATE_CHANGED = "channelStateChanged";
    private static final long DEFAULT_MAX_BODY_BYTES = 512L * 1024 * 1024; // 512MB

    private DataChannelServer server;
    private String token = "";
    private File root;
    private int port = 0;
    private long maxBodyBytes = DEFAULT_MAX_BODY_BYTES;
    private long startedAt = 0;

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
                String rootDir = call.getString("rootDir", "data-channel");
                Long configuredMax = call.getLong("maxBodyBytes", DEFAULT_MAX_BODY_BYTES);

                maxBodyBytes = configuredMax == null ? DEFAULT_MAX_BODY_BYTES : Math.max(1024L, configuredMax);

                File filesDir = getContext().getFilesDir();
                String dirName = rootDir == null || rootDir.isEmpty() ? "data-channel" : rootDir;
                root = new File(filesDir, dirName);
                if (!root.getCanonicalPath().startsWith(filesDir.getCanonicalPath() + File.separator)) {
                    call.reject("invalid rootDir: must stay inside app private files dir");
                    return;
                }

                token = generateToken();
                server = new DataChannelServer(root, token, maxBodyBytes, this::onRequestCompleted);
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

    @Override
    protected void handleOnStop() {
        super.handleOnStop();
        // App 进后台：继续服务（页面可能还在用），只记日志便于排查
        if (server != null && server.isRunning()) {
            Log.d(TAG, "app stopped, channel still running on port " + port);
        }
    }

    // ---------------------------------------------------------------- 内部

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
        ret.put("token", running ? token : "");
        ret.put("rootPath", root != null ? root.getAbsolutePath() : "");
        ret.put("maxBodyBytes", maxBodyBytes);
        ret.put("startedAt", running ? startedAt : 0);
        ret.put("activeConnections", server != null ? server.activeConnections() : 0);
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
