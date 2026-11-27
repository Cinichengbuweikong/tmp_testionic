package com.testionic.python;

import android.os.Build;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * capacitor-python 的 Android 实现（原生 CPython，不是 WebAssembly）。
 *
 * 引擎：Chaquopy（CPython 3.11，随 AAR 打进 APK，宿主 app 零配置）。
 * 每个会话 = 独立 globals + 独立 stdin 队列；执行跑在独立线程，stdout/stderr 实时回传。
 *
 * 与 web 端的关系：web 端仍用 Pyodide（浏览器沙盒里唯一可行），
 * Android/iOS 走这里；TS 层是同一个 API（见 src/index.ts 的 facade）。
 */
@CapacitorPlugin(name = "Python")
public class PythonPlugin extends Plugin {

    private final AtomicLong sequence = new AtomicLong();

    @Override
    public void load() {
        PythonBridge.setSink((sessionId, stream, text) -> {
            JSObject event = new JSObject();
            event.put("sessionId", sessionId);
            event.put("stream", stream);
            event.put("data", text);
            notifyListeners("sessionOutput", event);
        });
    }

    @Override
    protected void handleOnDestroy() {
        PythonEngine.terminateAll();
    }

    // ------------------------------------------------------------------ 基础信息

    @PluginMethod
    public void getInfo(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("platform", "android");
        ret.put("engine", "native");
        ret.put("sdkInt", Build.VERSION.SDK_INT);
        // 原生引擎不依赖 WebView 版本，所有 Android 7+ 都能跑
        ret.put("supported", true);
        ret.put("note", "原生 CPython（Chaquopy），不依赖 WebView");
        if (PythonEngine.isStarted()) {
            ret.put("pythonVersion", PythonEngine.pythonVersion());
        }
        call.resolve(ret);
    }

    /** 启动解释器并返回版本信息（幂等）。 */
    @PluginMethod
    public void initialize(PluginCall call) {
        PythonEngine.EXECUTOR.execute(() -> {
            try {
                PythonEngine.ensureStarted(getContext());
                JSObject ret = new JSObject();
                ret.put("engine", "native");
                ret.put("pythonVersion", PythonEngine.pythonVersion());
                ret.put("platform", "android");
                ret.put("sdkInt", Build.VERSION.SDK_INT);
                call.resolve(ret);
            } catch (Exception error) {
                call.reject("PYTHON_START_FAILED: " + error.getMessage(), error);
            }
        });
    }

    // ------------------------------------------------------------------ 会话

    @PluginMethod
    public void createSession(PluginCall call) {
        PythonEngine.EXECUTOR.execute(() -> {
            try {
                PythonEngine.ensureStarted(getContext());
                String name = call.getString("name", "session");
                PythonSession session = PythonEngine.createSession(name);
                call.resolve(toInfo(session));
            } catch (Exception error) {
                call.reject("CREATE_SESSION_FAILED: " + error.getMessage(), error);
            }
        });
    }

    @PluginMethod
    public void listSessions(PluginCall call) {
        JSArray sessions = new JSArray();
        for (PythonSession session : PythonEngine.listSessions()) {
            sessions.put(toInfo(session));
        }
        JSObject ret = new JSObject();
        ret.put("sessions", sessions);
        call.resolve(ret);
    }

    @PluginMethod
    public void terminateSession(PluginCall call) {
        String sessionId = call.getString("sessionId");
        if (sessionId == null) {
            call.reject("sessionId is required");
            return;
        }
        // 放到执行线程：reset_session 要在解释器里跑，插件线程不能被它堵住
        PythonEngine.EXECUTOR.execute(() -> {
            PythonEngine.terminateSession(sessionId);
            JSObject ret = new JSObject();
            ret.put("terminated", true);
            call.resolve(ret);
        });
    }

    @PluginMethod
    public void terminateAll(PluginCall call) {
        PythonEngine.EXECUTOR.execute(() -> {
            PythonEngine.terminateAll();
            JSObject ret = new JSObject();
            ret.put("terminated", true);
            call.resolve(ret);
        });
    }

    @PluginMethod
    public void restartSession(PluginCall call) {
        String sessionId = call.getString("sessionId");
        if (sessionId == null) {
            call.reject("sessionId is required");
            return;
        }
        PythonEngine.EXECUTOR.execute(() -> {
            try {
                PythonSession old = PythonEngine.require(sessionId);
                String name = old.name;
                PythonEngine.terminateSession(sessionId);
                PythonSession session = PythonEngine.createSession(name);
                call.resolve(toInfo(session));
            } catch (Exception error) {
                call.reject("RESTART_FAILED: " + error.getMessage(), error);
            }
        });
    }

    // ------------------------------------------------------------------ 执行

    /**
     * 执行并等待结果。返回 {executionId, result, stdout, stderr}：
     * 这里 stdout/stderr 由 JS 侧从 sessionOutput 事件聚合（与 web 实现一致），
     * 原生侧只负责 result / error。
     */
    @PluginMethod
    public void exec(PluginCall call) {
        String sessionId = call.getString("sessionId");
        String code = call.getString("code", "");
        if (sessionId == null) {
            call.reject("sessionId is required");
            return;
        }
        String executionId = "exec-" + sequence.incrementAndGet();
        PythonEngine.EXECUTOR.execute(() -> {
            try {
                org.json.JSONObject result = PythonEngine.run(sessionId, code);
                boolean ok = result.optBoolean("ok", false);
                if (ok) {
                    JSObject ret = new JSObject();
                    ret.put("executionId", executionId);
                    ret.put("result", result.isNull("result") ? org.json.JSONObject.NULL : result.opt("result"));
                    call.resolve(ret);
                } else {
                    String type = result.optString("type", "Error");
                    String message = result.optString("message", "");
                    JSObject data = new JSObject();
                    data.put("executionId", executionId);
                    data.put("type", type);
                    data.put("traceback", result.optString("traceback", ""));
                    call.reject(type + ": " + message, type, data);
                }
            } catch (Exception error) {
                call.reject("PYTHON_EXEC_FAILED: " + error.getMessage(), error);
            }
        });
    }

    /** 触发执行但立即返回；完成时通过 sessionDone / sessionError 事件通知。 */
    @PluginMethod
    public void run(PluginCall call) {
        String sessionId = call.getString("sessionId");
        String code = call.getString("code", "");
        if (sessionId == null) {
            call.reject("sessionId is required");
            return;
        }
        String executionId = call.getString("executionId", "run-" + sequence.incrementAndGet());
        JSObject ret = new JSObject();
        ret.put("executionId", executionId);
        call.resolve(ret);

        PythonEngine.EXECUTOR.execute(() -> {
            try {
                org.json.JSONObject result = PythonEngine.run(sessionId, code);
                boolean ok = result.optBoolean("ok", false);
                JSObject event = new JSObject();
                event.put("sessionId", sessionId);
                event.put("executionId", executionId);
                if (ok) {
                    event.put("result", result.isNull("result") ? org.json.JSONObject.NULL : result.opt("result"));
                    notifyListeners("sessionDone", event);
                } else {
                    event.put("type", result.optString("type", "Error"));
                    event.put("message", result.optString("message", ""));
                    event.put("traceback", result.optString("traceback", ""));
                    notifyListeners("sessionError", event);
                }
            } catch (Exception error) {
                JSObject event = new JSObject();
                event.put("sessionId", sessionId);
                event.put("executionId", executionId);
                event.put("type", "Error");
                event.put("message", String.valueOf(error.getMessage()));
                notifyListeners("sessionError", event);
            }
        });
    }

    /** 向 stdin 推一行（Python 侧的 input()/readline() 会阻塞消费）。 */
    @PluginMethod
    public void writeStdin(PluginCall call) {
        String sessionId = call.getString("sessionId");
        String data = call.getString("data", "");
        if (sessionId == null) {
            call.reject("sessionId is required");
            return;
        }
        PythonBridge.pushStdin(sessionId, data);
        JSObject ret = new JSObject();
        ret.put("ok", true);
        call.resolve(ret);
    }

    // ------------------------------------------------------------------ 包

    /**
     * 安装 wheel。原生 Android 上只支持**纯 Python** wheel（`py3-none-any`）：
     * 下载 → 解压到 `files/python-packages` → `site.addsitedir`。
     * C 扩展包请用构建期 pip（插件目录的 `src/main/python-requirements.txt`，见 README）。
     */
    @PluginMethod
    public void installPackages(PluginCall call) {
        JSArray packages = call.getArray("packages");
        if (packages == null || packages.length() == 0) {
            call.reject("packages is empty");
            return;
        }
        PythonEngine.EXECUTOR.execute(() -> {
            try {
                PythonEngine.ensureStarted(getContext());
                List<Object> urls = packages.toList();
                for (Object item : urls) {
                    String spec = String.valueOf(item);
                    if (!spec.endsWith(".whl") && !spec.startsWith("http")) {
                        call.reject(
                                "NATIVE_PIP_UNSUPPORTED: 原生引擎不能按包名联网安装（" + spec + "）。" +
                                        "请传入 .whl 的 URL/本地路径，或用构建期 pip 打包。");
                        return;
                    }
                    PythonEngine.installWheel(getContext(), spec);
                }
                JSObject ret = new JSObject();
                JSArray installed = new JSArray();
                for (Object item : urls) {
                    installed.put(String.valueOf(item));
                }
                ret.put("installed", installed);
                call.resolve(ret);
            } catch (Exception error) {
                call.reject("INSTALL_FAILED: " + error.getMessage(), error);
            }
        });
    }

    // ------------------------------------------------------------------ helpers

    private JSObject toInfo(PythonSession session) {
        JSObject ret = new JSObject();
        ret.put("sessionId", session.id);
        ret.put("name", session.name);
        ret.put("state", session.state.wire);
        ret.put("stdinMode", "blocking");
        ret.put("createdAt", session.createdAt);
        if (PythonEngine.isStarted()) {
            ret.put("pythonVersion", PythonEngine.pythonVersion());
        }
        return ret;
    }
}
