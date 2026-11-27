package com.testionic.python;

import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Python ↔ Java ↔ JS 的桥。
 *
 * Python 侧（`capython.runtime`）通过 Chaquopy 的 `jclass` 直接调用这里的**静态方法**：
 *   - `emit(sessionId, stream, text)`：stdout/stderr 实时转发；
 *   - `readLine(sessionId)`：阻塞式读取 stdin（JS 通过 `writeStdin` 把行推进队列）。
 *
 * 为什么 stdin 用 BlockingQueue + 轮询而不是"永久阻塞"：
 *   会话被 terminate 时，阻塞在 readLine 里的 Python 线程必须能退出，否则线程泄漏。
 *   所以每 200ms 醒一次检查会话是否还活着。
 */
public final class PythonBridge {

    /** 输出回调（由 PythonEngine 安装，最终转到 Capacitor 的 notifyListeners）。 */
    public interface Sink {
        void emit(String sessionId, String stream, String text);
    }

    private static volatile Sink sink;
    private static final Map<String, BlockingQueue<String>> STDIN = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> ACTIVE = new ConcurrentHashMap<>();

    private PythonBridge() {}

    public static void setSink(Sink value) {
        sink = value;
    }

    /** Python 侧调用：转发一段输出。 */
    public static void emit(String sessionId, String stream, String text) {
        Sink current = sink;
        if (current != null && sessionId != null && text != null) {
            current.emit(sessionId, stream, text);
        }
    }

    public static void registerSession(String sessionId) {
        ACTIVE.put(sessionId, Boolean.TRUE);
        STDIN.computeIfAbsent(sessionId, key -> new LinkedBlockingQueue<>());
    }

    public static void unregisterSession(String sessionId) {
        ACTIVE.remove(sessionId);
        STDIN.remove(sessionId);
    }

    /** JS 侧调用：把一行（不含换行，由 Python 侧补）放进队列。 */
    public static void pushStdin(String sessionId, String line) {
        BlockingQueue<String> queue = STDIN.get(sessionId);
        if (queue != null) {
            queue.add(line);
        }
    }

    /**
     * Python 侧调用（阻塞）：取下一行。
     *
     * @return 一行文本（带 `\n`）；会话结束或超时无输入时返回 null → Python 侧按 EOF 处理
     */
    public static String readLine(String sessionId) {
        BlockingQueue<String> queue = STDIN.get(sessionId);
        if (queue == null) {
            return null;
        }
        // 最多等 30s：既能让 input() 真正"等用户输入"，又不会让被终止的会话永久挂住
        long deadline = System.currentTimeMillis() + 30_000L;
        while (ACTIVE.containsKey(sessionId) && System.currentTimeMillis() < deadline) {
            try {
                String line = queue.poll(200, TimeUnit.MILLISECONDS);
                if (line != null) {
                    return line.endsWith("\n") ? line : line + "\n";
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }
}
