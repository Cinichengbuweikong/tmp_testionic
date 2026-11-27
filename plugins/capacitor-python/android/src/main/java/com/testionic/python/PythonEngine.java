package com.testionic.python;

import android.content.Context;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * 原生 Python 引擎（Android）。
 *
 * - 解释器由 **Chaquopy** 提供（CPython 3.11，AAR 里自带 libpython + stdlib + 扩展模块），
 *   不依赖 WebView、不需要宿主导入任何 Python 工具链；
 * - 一个进程一个解释器；**会话 = 独立的 globals + 独立 stdin 队列**，
 *   执行时跑在独立 Java 线程上（GIL 串行 bytecode，但 Java 调用期间会释放 GIL）；
 * - stdout/stderr 由 `capython.runtime` 的代理对象按线程 → 会话转发，实时推到 JS；
 * - 中断用 `PyThreadState_SetAsyncExc`（通过 ctypes），只能在下一条 bytecode 生效。
 */
final class PythonEngine {

    private static final Object LOCK = new Object();
    private static final Map<String, PythonSession> SESSIONS = new ConcurrentHashMap<>();
    private static volatile boolean started;
    private static PyObject runtimeModule;

    /** Python 执行专用线程池：Capacitor 的插件线程不能被长任务占住。 */
    static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(new ThreadFactory() {
        private int counter;

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "capython-" + (++counter));
            thread.setDaemon(true);
            return thread;
        }
    });

    private PythonEngine() {}

    static void ensureStarted(Context context) {
        if (started) {
            return;
        }
        synchronized (LOCK) {
            if (started) {
                return;
            }
            if (!Python.isStarted()) {
                Python.start(new AndroidPlatform(context.getApplicationContext()));
            }
            runtimeModule = Python.getInstance().getModule("capython.runtime");
            runtimeModule.callAttr("install");
            started = true;
        }
    }

    static boolean isStarted() {
        return started;
    }

    static String pythonVersion() {
        return runtimeModule.callAttr("python_version").toString();
    }

    static PythonSession createSession(String name) {
        PythonSession session = new PythonSession(name == null || name.isEmpty() ? "session" : name);
        SESSIONS.put(session.id, session);
        PythonBridge.registerSession(session.id);
        return session;
    }

    static PythonSession require(String sessionId) {
        PythonSession session = SESSIONS.get(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("SESSION_NOT_FOUND: " + sessionId);
        }
        return session;
    }

    static List<PythonSession> listSessions() {
        return new ArrayList<>(SESSIONS.values());
    }

    static void terminateSession(String sessionId) {
        PythonSession session = SESSIONS.remove(sessionId);
        if (session == null) {
            return;
        }
        session.terminated = true;
        session.state = PythonSession.State.TERMINATED;
        PythonBridge.unregisterSession(sessionId);
        try {
            runtimeModule.callAttr("reset_session", sessionId);
        } catch (Exception ignored) {
            // 解释器可能已经在关闭，忽略
        }
    }

    static void terminateAll() {
        for (String id : new ArrayList<>(SESSIONS.keySet())) {
            terminateSession(id);
        }
    }

    /**
     * 执行代码（阻塞，必须在 EXECUTOR 上调用）。
     *
     * Python 侧返回 JSON 字符串（Chaquopy 不支持 dict → Map 的自动转换，会抛
     * `TypeError: Cannot convert dict object to java.util.Map`），这里解析成 JSONObject。
     */
    static org.json.JSONObject run(String sessionId, String code) {
        PythonSession session = require(sessionId);
        if (session.terminated) {
            throw new IllegalStateException("SESSION_TERMINATED: " + sessionId);
        }
        session.state = PythonSession.State.RUNNING;
        try {
            String json = runtimeModule.callAttr("run", sessionId, code == null ? "" : code).toString();
            try {
                return new org.json.JSONObject(json);
            } catch (org.json.JSONException parseError) {
                throw new IllegalStateException("PYTHON_RESULT_PARSE_FAILED: " + json, parseError);
            }
        } finally {
            if (!session.terminated) {
                session.state = PythonSession.State.IDLE;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMap(PyObject result) {
        Object javaValue = result.toJava(Map.class);
        Map<String, Object> out = new LinkedHashMap<>();
        if (javaValue instanceof Map) {
            for (Map.Entry<Object, Object> entry : ((Map<Object, Object>) javaValue).entrySet()) {
                out.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return out;
    }

    /**
     * 安装一个 wheel：下载/复制 → 解压到 `files/python-packages` → `site.addsitedir`。
     *
     * 只支持 **纯 Python** 的 wheel（`py3-none-any`）：原生扩展的 .so 必须构建期用
     * Chaquopy 的 `pip { install }` 打进 AAR（Android 无法运行 pip 本身）。
     */
    static void installWheel(Context context, String urlOrPath) throws IOException {
        ensureStarted(context);
        File target = new File(context.getFilesDir(), "python-packages");
        //noinspection ResultOfMethodCallIgnored
        target.mkdirs();

        File temp = File.createTempFile("capython-wheel", ".whl", context.getCacheDir());
        try {
            if (urlOrPath.startsWith("http://") || urlOrPath.startsWith("https://")) {
                download(urlOrPath, temp);
            } else {
                File source = new File(urlOrPath);
                if (!source.isFile()) {
                    throw new IOException("WHEEL_NOT_FOUND: " + urlOrPath);
                }
                copyFile(source, temp);
            }
            unzipSafely(temp, target);
            runtimeModule.callAttr("add_site_dir", target.getAbsolutePath());
        } finally {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
        }
    }

    private static void download(String url, File target) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(30_000);
        connection.setInstanceFollowRedirects(true);
        try {
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("DOWNLOAD_FAILED: HTTP " + status + " for " + url);
            }
            try (InputStream input = connection.getInputStream();
                 FileOutputStream output = new FileOutputStream(target)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) > 0) {
                    output.write(buffer, 0, read);
                }
            }
        } finally {
            connection.disconnect();
        }
    }

    private static void copyFile(File source, File target) throws IOException {
        try (InputStream input = new java.io.FileInputStream(source);
             FileOutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) > 0) {
                output.write(buffer, 0, read);
            }
        }
    }

    /** 解压并防 zip-slip（wheel 里出现 `../` 或绝对路径一律拒绝）。 */
    private static void unzipSafely(File zipFile, File targetDir) throws IOException {
        String canonicalTarget = targetDir.getCanonicalPath() + File.separator;
        try (java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(new java.io.FileInputStream(zipFile))) {
            java.util.zip.ZipEntry entry;
            byte[] buffer = new byte[64 * 1024];
            int entries = 0;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > 20_000) {
                    throw new IOException("WHEEL_TOO_MANY_ENTRIES");
                }
                File out = new File(targetDir, entry.getName());
                String canonicalOut = out.getCanonicalPath();
                if (!canonicalOut.startsWith(canonicalTarget)) {
                    throw new IOException("WHEEL_ZIP_SLIP: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    //noinspection ResultOfMethodCallIgnored
                    out.mkdirs();
                    continue;
                }
                //noinspection ResultOfMethodCallIgnored
                out.getParentFile().mkdirs();
                try (FileOutputStream output = new FileOutputStream(out)) {
                    int read;
                    while ((read = zip.read(buffer)) > 0) {
                        output.write(buffer, 0, read);
                    }
                }
            }
        }
    }
}
