package com.testionic.cppbridge;

import android.util.Log;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CppBridge —— Android 侧插件（jsName = "CppBridge"）。
 *
 * 这是 **C/C++ 桥模板**：它只负责把 WebView 的调用转发到 native，再转发到共享 C/C++ 核心。
 * 真正的业务逻辑请写在 `shared/core/` 里（Android/iOS 共用同一份源码）。
 *
 * 分层：
 *
 *   JS  →  CppBridgePlugin.java（本文件，参数校验/handle 管理）
 *       →  native 方法（bridge.cpp，JNI 薄桥）
 *       →  shared/core（C++ 与纯 C 编译单元）
 *
 * ⚠️ 句柄 API（allocBuffer/fillBuffer/sumBuffer/freeBuffer）是 **danger** 的：
 *    - handle 是 native 指针的数值，**必须**在用完后调 `freeBuffer`，否则泄漏；
 *    - handle 不能跨进程重启使用；
 *    - 不要并发对同一 handle 做写操作（没有加锁）；
 *    - 模板只在内存里记录「已分配的 handle」，用于统计和双重释放检查，不做 GC。
 */
@CapacitorPlugin(name = "CppBridge")
public class CppBridgePlugin extends Plugin {

    private static final String TAG = "CppBridge";
    private static final String LIBRARY = "cppbridge";

    private static boolean libraryLoaded = false;
    private static String libraryError = null;

    /** handle（native 指针数值） → 分配时的 size（字节）。用于统计与双重释放检查。 */
    private final Map<Long, Long> buffers = new ConcurrentHashMap<>();
    private long allocatedBytes = 0;

    // ================================================================ 生命周期

    @Override
    public void load() {
        if (!libraryLoaded) {
            try {
                System.loadLibrary(LIBRARY);
                libraryLoaded = true;
                Log.i(TAG, "loaded lib" + LIBRARY + ".so");
            } catch (Throwable error) {
                libraryError = error.getClass().getSimpleName() + ": " + error.getMessage();
                Log.e(TAG, "failed to load lib" + LIBRARY + ".so", error);
            }
        }
    }

    private boolean ensureLoaded(PluginCall call) {
        if (libraryLoaded) {
            return true;
        }
        call.reject("NATIVE_LIB_NOT_LOADED: " + libraryError + "（请确认已用 NDK 构建出 lib" + LIBRARY + ".so）");
        return false;
    }

    // ================================================================ 示例 API

    /** 返回 native 库的构建信息（验证 .so 已加载、C++/C 都能跑）。 */
    @PluginMethod
    public void getCoreInfo(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("platform", "android");
        ret.put("loaded", libraryLoaded);
        ret.put("library", "lib" + LIBRARY + ".so");
        if (libraryLoaded) {
            ret.put("buildInfo", nativeBuildInfo());
        } else {
            ret.put("error", libraryError);
        }
        call.resolve(ret);
    }

    /** 两数相加（C++ 实现）。 */
    @PluginMethod
    public void add(PluginCall call) {
        if (!ensureLoaded(call)) {
            return;
        }
        Integer a = call.getInt("a", 0);
        Integer b = call.getInt("b", 0);
        JSObject ret = new JSObject();
        ret.put("a", a);
        ret.put("b", b);
        ret.put("result", nativeAdd(a == null ? 0 : a, b == null ? 0 : b));
        ret.put("impl", "cpp");
        call.resolve(ret);
    }

    /** 两数相加（**纯 C** 实现，验证 C 编译单元也在同一个 .so 里）。 */
    @PluginMethod
    public void cAdd(PluginCall call) {
        if (!ensureLoaded(call)) {
            return;
        }
        Integer a = call.getInt("a", 0);
        Integer b = call.getInt("b", 0);
        JSObject ret = new JSObject();
        ret.put("a", a);
        ret.put("b", b);
        ret.put("result", nativeCAdd(a == null ? 0 : a, b == null ? 0 : b));
        ret.put("impl", "c");
        call.resolve(ret);
    }

    /** 字符串长度（纯 C 实现，验证 C 侧处理 char*）。 */
    @PluginMethod
    public void cStrlen(PluginCall call) {
        if (!ensureLoaded(call)) {
            return;
        }
        String text = call.getString("text", "");
        JSObject ret = new JSObject();
        ret.put("text", text);
        ret.put("length", nativeCStrlen(text == null ? "" : text));
        ret.put("impl", "c");
        call.resolve(ret);
    }

    // ================================================================ ⚠️ danger：句柄 API

    /**
     * ⚠️ DANGER：分配一块 native 内存，返回一个**句柄**（native 指针数值）。
     *
     * `handle` 必须交给 `freeBuffer` 释放；否则这块内存会一直泄漏到进程退出。
     */
    @PluginMethod
    public void allocBuffer(PluginCall call) {
        if (!ensureLoaded(call)) {
            return;
        }
        Long sizeOption = readLong(call, "size");
        long size = sizeOption == null ? 0 : sizeOption;
        if (size <= 0 || (size % 4) != 0) {
            call.reject("INVALID_SIZE: size must be a positive multiple of 4");
            return;
        }
        long handle = nativeBufferAlloc(size);
        if (handle == 0) {
            call.reject("ALLOC_FAILED: native malloc returned null (size=" + size + ")");
            return;
        }
        buffers.put(handle, size);
        allocatedBytes += size;

        JSObject ret = new JSObject();
        ret.put("handle", handle);
        ret.put("size", size);
        ret.put("totalBuffers", buffers.size());
        ret.put("totalBytes", allocatedBytes);
        call.resolve(ret);
    }

    /** ⚠️ DANGER：把 buffer 的每个 32 位整数填成 `value`（数据不过桥，纯 native 内存操作）。 */
    @PluginMethod
    public void fillBuffer(PluginCall call) {
        if (!ensureLoaded(call)) {
            return;
        }
        Long handle = readLong(call, "handle");
        Long size = handle == null ? null : buffers.get(handle);
        if (handle == null || size == null) {
            call.reject("UNKNOWN_HANDLE: 句柄不存在或已释放（是否忘记 freeBuffer 之外还重复使用？）");
            return;
        }
        Integer valueOption = call.getInt("value", 0);
        int value = valueOption == null ? 0 : valueOption;
        try {
            nativeBufferFill(handle, size, value);
        } catch (IllegalArgumentException error) {
            call.reject("FILL_FAILED: " + error.getMessage());
            return;
        }
        JSObject ret = new JSObject();
        ret.put("ok", true);
        ret.put("handle", handle);
        ret.put("size", size);
        ret.put("value", value);
        call.resolve(ret);
    }

    /** ⚠️ DANGER：对 buffer 求和（数据不过桥）。 */
    @PluginMethod
    public void sumBuffer(PluginCall call) {
        if (!ensureLoaded(call)) {
            return;
        }
        Long handle = readLong(call, "handle");
        Long size = handle == null ? null : buffers.get(handle);
        if (handle == null || size == null) {
            call.reject("UNKNOWN_HANDLE: 句柄不存在或已释放");
            return;
        }
        long sum;
        try {
            sum = nativeBufferSum(handle, size);
        } catch (IllegalArgumentException error) {
            call.reject("SUM_FAILED: " + error.getMessage());
            return;
        }
        JSObject ret = new JSObject();
        ret.put("handle", handle);
        ret.put("size", size);
        ret.put("sum", sum);
        call.resolve(ret);
    }

    /** ⚠️ DANGER：释放句柄。重复释放会返回 `freed:false`（不会崩溃）。 */
    @PluginMethod
    public void freeBuffer(PluginCall call) {
        if (!ensureLoaded(call)) {
            return;
        }
        Long handle = readLong(call, "handle");
        if (handle == null) {
            call.reject("missing handle");
            return;
        }
        Long size = buffers.remove(handle);
        if (size == null) {
            JSObject ret = new JSObject();
            ret.put("freed", false);
            ret.put("reason", "UNKNOWN_OR_ALREADY_FREED");
            call.resolve(ret);
            return;
        }
        nativeBufferFree(handle);
        allocatedBytes -= size;
        JSObject ret = new JSObject();
        ret.put("freed", true);
        ret.put("size", size);
        ret.put("totalBuffers", buffers.size());
        ret.put("totalBytes", allocatedBytes);
        call.resolve(ret);
    }

    /** 当前仍在使用的句柄统计（用来检查有没有忘记 free）。 */
    @PluginMethod
    public void getBufferStats(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("count", buffers.size());
        ret.put("bytes", allocatedBytes);
        call.resolve(ret);
    }

    /**
     * 读取 long 参数。
     *
     * Capacitor 的 `PluginCall.getLong()` 只接受 `Long` 类型的 JSON 值；JS 传小整数时
     * org.json 会解析成 `Integer` → 静默返回默认值。句柄可能大到超过 int，所以统一走这里。
     */
    private Long readLong(PluginCall call, String key) {
        Object raw = call.getData().opt(key);
        if (raw instanceof Number) {
            return ((Number) raw).longValue();
        }
        if (raw instanceof String) {
            try {
                return Long.parseLong(((String) raw).trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    // ================================================================ native 声明

    private native String nativeBuildInfo();

    private native int nativeAdd(int a, int b);

    private native int nativeCAdd(int a, int b);

    private native int nativeCStrlen(String text);

    private native long nativeBufferAlloc(long size);

    private native void nativeBufferFill(long handle, long size, int value);

    private native long nativeBufferSum(long handle, long size);

    private native void nativeBufferFree(long handle);
}
