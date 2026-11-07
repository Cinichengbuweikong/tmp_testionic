package com.testionic.datachannel;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * OTP（一次性凭证）存储 + 连接限流（Android）。
 *
 * ======================= 为什么用 OTP =======================
 *
 * 长期 token 一旦被抓包拿到就等于全权限。OTP 通过 Capacitor 桥（postMessage，不经过网络）
 * 下发，抓包软件拿不到"生成过程"，只能看到已经用掉的凭证 —— 重放无效。
 *
 * ======================= 规则 =======================
 *
 * - **一次性**（默认）：用一次即作废，无时间限制；
 * - **可复用**（仅允许 read）：用于媒体（video/audio）持续读取，**必须用完 revoke**，
 *   否则在 server 运行期间永久有效（严重安全问题）；
 * - **写功能不允许 reusable**（由调用方保证：create 时 scope 含 write 且 reusable → 拒绝）；
 * - 无 TTL：等价于"server 生命周期内有效"，server 停止即全部失效。
 *
 * ======================= 性能 =======================
 *
 * 校验 = ConcurrentHashMap 查找（O(1)、无锁），对传输性能可忽略。
 */
public class OtpStore {

    public static final String SCOPE_READ = "read";
    public static final String SCOPE_WRITE = "write";
    public static final String SCOPE_READWRITE = "readwrite";

    private static final int MAX_OTPS = 256;

    public static final class Record {
        public final String scope;
        public final String path;
        public final boolean reusable;

        Record(String scope, String path, boolean reusable) {
            this.scope = scope;
            this.path = path;
            this.reusable = reusable;
        }
    }

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Record> otps = new ConcurrentHashMap<>();

    /** 生成并登记一个 OTP（32 字节随机，Base64URL）。 */
    public String create(String scope, String path, boolean reusable) {
        if (otps.size() >= MAX_OTPS) {
            throw new IllegalStateException("too many active OTPs (max " + MAX_OTPS + "); revoke unused ones first");
        }
        String normalizedScope = normalizeScope(scope);
        String normalizedPath = normalizePath(path);
        if (reusable && !SCOPE_READ.equals(normalizedScope)) {
            throw new IllegalArgumentException("reusable OTP is only allowed with scope=read (write must use a fresh one-time OTP)");
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String otp = toBase64Url(bytes);
        otps.put(otp, new Record(normalizedScope, normalizedPath, reusable));
        return otp;
    }

    /**
     * 批量生成 N 个 OTP（一次桥调用）。
     *
     * 目的：Capacitor 的插件调用是**串行**的（Android 单 HandlerThread / iOS 串行 queue），
     * 每条消息单独 createWsOtp 会把往返延迟累加；批量生成把 N 次调用压成 1 次，
     * 大幅降低高频小包场景的桥开销。
     *
     * @param count 1..MAX_OTPS（且与当前活跃数之和不得超过 MAX_OTPS）
     */
    public java.util.List<String> createBatch(int count, String scope, String path, boolean reusable) {
        if (count < 1) {
            throw new IllegalArgumentException("count must be >= 1");
        }
        String normalizedScope = normalizeScope(scope);
        String normalizedPath = normalizePath(path);
        if (reusable && !SCOPE_READ.equals(normalizedScope)) {
            throw new IllegalArgumentException("reusable OTP is only allowed with scope=read (write must use a fresh one-time OTP)");
        }
        synchronized (this) {
            if (otps.size() + count > MAX_OTPS) {
                throw new IllegalStateException(
                    "not enough OTP capacity: active=" + otps.size() + ", requested=" + count + ", max=" + MAX_OTPS
                );
            }
            java.util.List<String> created = new java.util.ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                byte[] bytes = new byte[32];
                random.nextBytes(bytes);
                String otp = toBase64Url(bytes);
                otps.put(otp, new Record(normalizedScope, normalizedPath, reusable));
                created.add(otp);
            }
            return created;
        }
    }

    /**
     * 校验并消费 OTP。
     *
     * @param otp         请求携带的 OTP
     * @param neededScope 本次操作需要的权限（read/write）
     * @param requestPath 请求要访问的路径（相对 rootDir）
     * @return true = 通过（一次性 OTP 已删除；reusable 仅计数）
     */
    public boolean consume(String otp, String neededScope, String requestPath) {
        if (otp == null || otp.isEmpty()) {
            return false;
        }
        Record record = otps.get(otp);
        if (record == null) {
            return false;
        }
        // scope 检查（readwrite 同时满足 read/write）
        boolean scopeOk =
            SCOPE_READWRITE.equals(record.scope) ||
            record.scope.equals(neededScope);
        if (!scopeOk) {
            return false;
        }
        // path 必须精确相等（不是前缀匹配，防 videos/a.mp4 误放行 videos/a.mp4.bak）
        if (!record.path.isEmpty() && !record.path.equals(normalizePath(requestPath))) {
            return false;
        }
        if (record.reusable) {
            return true; // 可复用：不移除（调用方负责 revoke）
        }
        // 一次性：原子删除，只有第一个删除成功的请求通过（并发下也不会重放）
        return otps.remove(otp) != null;
    }

    /** 撤销单个 OTP。 */
    public boolean revoke(String otp) {
        if (otp == null) {
            return false;
        }
        return otps.remove(otp) != null;
    }

    /** 撤销全部 OTP（server 停止/切后台时兜底调用）。 */
    public int revokeAll() {
        int count = otps.size();
        otps.clear();
        return count;
    }

    public int size() {
        return otps.size();
    }

    // ---------------------------------------------------------------- 工具

    private String normalizeScope(String scope) {
        if (scope == null || scope.trim().isEmpty()) {
            return SCOPE_READ;
        }
        String lower = scope.trim().toLowerCase(Locale.ROOT);
        switch (lower) {
            case SCOPE_READ:
            case SCOPE_WRITE:
            case SCOPE_READWRITE:
                return lower;
            default:
                throw new IllegalArgumentException("invalid scope: " + scope + " (read|write|readwrite)");
        }
    }

    /** 规范化相对路径：去掉开头的 / 与反斜杠，拒绝 .. 穿越。 */
    public static String normalizePath(String path) {
        if (path == null) {
            return "";
        }
        String rel = path.trim().replace('\\', '/');
        while (rel.startsWith("/")) {
            rel = rel.substring(1);
        }
        for (String segment : rel.split("/")) {
            if (segment.equals("..")) {
                throw new IllegalArgumentException("invalid path: '..' not allowed");
            }
        }
        return rel;
    }

    private static String toBase64Url(byte[] bytes) {
        return android.util.Base64.encodeToString(
            bytes,
            android.util.Base64.URL_SAFE |
            android.util.Base64.NO_WRAP |
            android.util.Base64.NO_PADDING
        );
    }

    // ---------------------------------------------------------------- 连接限流

    /**
     * 两级连接限流：
     *  - 未认证连接上限（默认 4）：防扫描/DoS（沉默连接很快被读超时关闭）；
     *  - 已认证连接上限（默认 16，可配）：保证视频多 Range / 多图并发不被误伤。
     */
    public static final class ConnectionLimiter {

        private final AtomicInteger pending = new AtomicInteger();
        private final AtomicInteger active = new AtomicInteger();
        private final int maxPending;
        private final int maxActive;

        public ConnectionLimiter(int maxActive) {
            this.maxActive = Math.max(1, maxActive);
            this.maxPending = Math.min(4, this.maxActive);
        }

        /** 连接建立时调用；返回 false 表示拒绝（关闭连接）。 */
        public boolean tryAcquirePending() {
            int now = pending.incrementAndGet();
            if (now > maxPending) {
                pending.decrementAndGet();
                return false;
            }
            return true;
        }

        /** OTP 校验通过时调用；返回 false 表示已认证并发超限（拒绝）。 */
        public boolean promoteToActive() {
            int now = active.incrementAndGet();
            if (now > maxActive) {
                active.decrementAndGet();
                return false;
            }
            pending.decrementAndGet();
            return true;
        }

        public void release(boolean authenticated) {
            if (authenticated) {
                active.updateAndGet(value -> Math.max(0, value - 1));
            } else {
                pending.updateAndGet(value -> Math.max(0, value - 1));
            }
        }

        public int pendingCount() {
            return pending.get();
        }

        public int activeCount() {
            return active.get();
        }

        public int maxActive() {
            return maxActive;
        }
    }
}
