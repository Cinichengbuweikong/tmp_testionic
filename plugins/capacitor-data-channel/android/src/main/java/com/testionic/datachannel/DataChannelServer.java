package com.testionic.datachannel;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * DataChannel **HTTP** Server（Android，纯 Java，零第三方依赖）。
 *
 * 只提供三个路由：
 *   GET  /health            免 OTP（连通性自检）
 *   PUT/POST /upload?path=  需要 write OTP（流式写盘，append=1 追加）
 *   GET  /download?path=    需要 read OTP（流式下载，支持 Range → 206）
 *   OPTIONS *               免 OTP（CORS 预检）
 *
 * **鉴权用 OTP**（不是长期 token）：
 *   - Header: `Authorization: OTP <otp>`
 *   - Query:  `?otp=<otp>`（媒体 `<video src>` 用）
 *   - OTP 通过 Capacitor 桥下发（抓包拿不到生成过程），一次性/可复用只在 read 时允许；
 *   - 校验失败统一 404（不泄露"需要 OTP"）。
 *
 * **两级连接限流**（见 {@link OtpStore.ConnectionLimiter}）：
 *   - 未认证连接 4（防扫描/DoS）；
 *   - 已认证连接 maxConnections（默认 16，保证视频多 Range / 多图并发）。
 *
 * 安全：只绑 127.0.0.1、随机端口、Origin 白名单（非法即断开）、路径限制在 rootDir、
 *      请求大小/头/超时限制、上传 .part 原子写、日志不记 OTP。
 */
public class DataChannelServer {

    public interface Listener {
        void onRequestCompleted(String method, String path, int status, long bytes, long durationMs);
    }

    private static final String TAG = "DataChannelHttp";
    private static final int MAX_HEADER_LINE = 8192;
    private static final int MAX_HEADER_LINES = 64;
    private static final int MAX_HEADER_TOTAL = 32 * 1024;
    private static final int SOCKET_TIMEOUT_MS = 20000;
    private static final int COPY_BUFFER = 64 * 1024;

    private final File root;
    private final OtpStore otpStore;
    private final long maxBodyBytes;
    private final Listener listener;
    private final OtpStore.ConnectionLimiter limiter;

    private ServerSocket serverSocket;
    private Thread acceptThread;
    private ExecutorService workers;
    private volatile boolean running = false;

    public DataChannelServer(File root, OtpStore otpStore, long maxBodyBytes, int maxConnections, Listener listener) {
        this.root = root;
        this.otpStore = otpStore;
        this.maxBodyBytes = maxBodyBytes;
        this.listener = listener;
        this.limiter = new OtpStore.ConnectionLimiter(maxConnections);
    }

    public boolean isRunning() {
        return running;
    }

    public int activeConnections() {
        return limiter.activeCount();
    }

    public int pendingConnections() {
        return limiter.pendingCount();
    }

    public int maxConnections() {
        return limiter.maxActive();
    }

    public int port() {
        return serverSocket != null ? serverSocket.getLocalPort() : 0;
    }

    public int start(int requestedPort) throws IOException {
        if (running) {
            return port();
        }
        if (!root.exists() && !root.mkdirs()) {
            throw new IOException("cannot create data root: " + root);
        }
        serverSocket = new ServerSocket(requestedPort, 16, InetAddress.getByName("127.0.0.1"));
        workers = Executors.newFixedThreadPool(Math.max(2, limiter.maxActive()));
        running = true;
        acceptThread = new Thread(this::acceptLoop, "DataChannelHttpAccept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        return serverSocket.getLocalPort();
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
            // 忽略
        }
        serverSocket = null;
        if (acceptThread != null) {
            acceptThread.interrupt();
            acceptThread = null;
        }
        if (workers != null) {
            workers.shutdownNow();
            try {
                workers.awaitTermination(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            workers = null;
        }
    }

    // ---------------------------------------------------------------- 接受连接

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                if (!limiter.tryAcquirePending()) {
                    // 未认证并发已满：直接关闭（防扫描/DoS）
                    Log.d(TAG, "reject connection: pending limit reached");
                    try {
                        socket.close();
                    } catch (IOException ignored) {
                        // 忽略
                    }
                    continue;
                }
                workers.execute(() -> {
                    boolean authenticated = false;
                    try {
                        authenticated = handleRequest(socket);
                    } finally {
                        limiter.release(authenticated);
                    }
                });
            } catch (IOException e) {
                if (!running) {
                    return;
                }
                // accept 失败（如连接被重置）不影响后续接受
            }
        }
    }

    // ---------------------------------------------------------------- 请求处理

    /** 返回 true 表示该连接已通过 OTP 认证（用于正确的连接计数释放）。 */
    private boolean handleRequest(Socket socket) {
        long startedAt = System.nanoTime();
        String method = "-";
        String path = "-";
        int status = 500;
        boolean authenticated = false;

        try {
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);
            InputStream in = new BufferedInputStream(socket.getInputStream(), 16 * 1024);
            OutputStream out = new BufferedOutputStream(socket.getOutputStream(), 16 * 1024);

            String requestLine = readLine(in, MAX_HEADER_LINE);
            if (requestLine == null || requestLine.isEmpty()) {
                return false;
            }
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                status = 400;
                writeError(out, 400, "bad request line", null);
                return false;
            }
            method = parts[0].toUpperCase(Locale.ROOT);
            String target = parts[1];
            Map<String, String> headers = readHeaders(in);
            String origin = headers.get("origin");

            URI uri;
            try {
                uri = URI.create(target.startsWith("/") ? target : "/" + target);
            } catch (IllegalArgumentException e) {
                status = 400;
                writeError(out, 400, "invalid url", null);
                return false;
            }
            path = uri.getPath() == null ? "/" : uri.getPath();
            Map<String, String> query = parseQuery(uri.getRawQuery());

            // CORS 预检（浏览器预检不带 Authorization，必须免鉴权）
            if ("OPTIONS".equals(method)) {
                status = 204;
                writePreflight(out, origin);
                return false;
            }

            // Origin 白名单：非白名单 Origin 静默断开（不返回任何响应）
            if (origin != null && !isAllowedOrigin(origin)) {
                status = 0;
                Log.d(TAG, "dropped connection with disallowed origin");
                return false;
            }

            // 健康检查（免 OTP，无敏感信息）
            if ("GET".equals(method) && "/health".equals(path)) {
                status = 200;
                writeJson(out, 200, "{\"ok\":true,\"name\":\"capacitor-data-channel\"}", origin);
                return false;
            }

            // ---- OTP 鉴权 ----
            String otp = extractOtp(headers, query);
            String requestPath = query.get("path");
            String neededScope = "PUT".equals(method) || "POST".equals(method) ? OtpStore.SCOPE_WRITE : OtpStore.SCOPE_READ;
            if (!otpStore.consume(otp, neededScope, requestPath == null ? "" : requestPath)) {
                status = 404;
                writeError(out, 404, "not found", origin);
                return false;
            }
            if (!limiter.promoteToActive()) {
                status = 404;
                writeError(out, 404, "not found", origin);
                return false;
            }
            authenticated = true;

            String route = method + " " + path;
            switch (route) {
                case "PUT /upload":
                case "POST /upload":
                    status = handleUpload(in, out, headers, query, origin);
                    return true;
                case "GET /download":
                    status = handleDownload(out, headers, query, origin);
                    return true;
                default:
                    status = 404;
                    writeError(out, 404, "not found: " + route, origin);
                    return true;
            }
        } catch (IOException e) {
            status = 500;
        } catch (Exception e) {
            status = 500;
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
                // 忽略
            }
            if (listener != null) {
                long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
                try {
                    listener.onRequestCompleted(method, path, status, 0, durationMs);
                } catch (Exception ignored) {
                    // 监听回调异常不影响 server
                }
            }
        }
        return authenticated;
    }

    private String extractOtp(Map<String, String> headers, Map<String, String> query) {
        String auth = headers.get("authorization");
        if (auth != null && auth.length() > 4 && auth.regionMatches(true, 0, "OTP ", 0, 4)) {
            return auth.substring(4).trim();
        }
        return query.get("otp");
    }

    // ---------------------------------------------------------------- 路由实现

    private int handleUpload(
        InputStream in,
        OutputStream out,
        Map<String, String> headers,
        Map<String, String> query,
        String origin
    ) throws IOException {
        final File target;
        try {
            target = resolveSafePath(query.get("path"));
        } catch (IllegalArgumentException e) {
            writeError(out, 400, e.getMessage(), origin);
            return 400;
        }

        long contentLength;
        String rawLength = headers.get("content-length");
        try {
            contentLength = rawLength == null ? -1 : Long.parseLong(rawLength.trim());
        } catch (NumberFormatException e) {
            contentLength = -1;
        }
        if (contentLength < 0) {
            writeError(out, 411, "Content-Length required (chunked body not supported)", origin);
            return 411;
        }
        if (contentLength > maxBodyBytes) {
            writeError(out, 413, "payload too large: " + contentLength + " > " + maxBodyBytes, origin);
            return 413;
        }

        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            writeError(out, 500, "cannot create parent directory", origin);
            return 500;
        }

        boolean append = "1".equals(query.get("append")) || "true".equalsIgnoreCase(query.get("append"));
        long written;
        if (append) {
            try (FileOutputStream fos = new FileOutputStream(target, true)) {
                written = copyExactly(in, fos, contentLength);
                fos.flush();
            }
        } else {
            File part = new File(target.getPath() + ".part");
            try (FileOutputStream fos = new FileOutputStream(part)) {
                written = copyExactly(in, fos, contentLength);
                fos.flush();
            }
            if (target.exists() && !target.delete()) {
                part.delete();
                writeError(out, 500, "cannot replace existing file", origin);
                return 500;
            }
            if (!part.renameTo(target)) {
                part.delete();
                writeError(out, 500, "rename failed", origin);
                return 500;
            }
        }

        String body =
            "{\"path\":" +
            jsonString(query.get("path")) +
            ",\"bytes\":" +
            written +
            ",\"size\":" +
            target.length() +
            ",\"appended\":" +
            append +
            "}";
        writeJson(out, 200, body, origin);
        return 200;
    }

    private int handleDownload(
        OutputStream out,
        Map<String, String> headers,
        Map<String, String> query,
        String origin
    ) throws IOException {
        final File file;
        try {
            file = resolveSafePath(query.get("path"));
        } catch (IllegalArgumentException e) {
            writeError(out, 400, e.getMessage(), origin);
            return 400;
        }
        if (!file.isFile()) {
            writeError(out, 404, "file not found", origin);
            return 404;
        }

        long size = file.length();
        long start = 0;
        long end = size - 1;
        boolean partial = false;
        long[] range = parseRange(headers.get("range"), size);
        if (range != null) {
            start = range[0];
            end = range[1];
            partial = true;
        }
        long length = end - start + 1;

        Map<String, String> extra = new HashMap<>();
        extra.put("Content-Type", mimeType(file.getName()));
        extra.put("Content-Length", Long.toString(length));
        extra.put("Accept-Ranges", "bytes");
        if (partial) {
            extra.put("Content-Range", "bytes " + start + "-" + end + "/" + size);
        }

        writeStatusAndHeaders(out, partial ? 206 : 200, partial ? "Partial Content" : "OK", extra, origin);

        try (FileInputStream fis = new FileInputStream(file)) {
            long skipped = 0;
            while (skipped < start) {
                long n = fis.skip(start - skipped);
                if (n <= 0) {
                    break;
                }
                skipped += n;
            }
            copyExactly(fis, out, length);
        }
        out.flush();
        return partial ? 206 : 200;
    }

    // ---------------------------------------------------------------- 安全：路径解析

    /**
     * 把相对路径解析到数据根目录下，并做三层校验：
     * 1) 拒绝空 NUL / 绝对路径 / ~ / 盘符；
     * 2) 拒绝包含 .. 的路径段；
     * 3) canonical path 必须仍在根目录内（防软链逃逸）。
     */
    private File resolveSafePath(String relPath) throws IOException {
        String rel = relPath == null ? "" : relPath.trim();
        if (rel.isEmpty()) {
            throw new IllegalArgumentException("missing path");
        }
        if (rel.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("invalid path: contains NUL");
        }
        rel = rel.replace('\\', '/');
        while (rel.startsWith("/")) {
            rel = rel.substring(1);
        }
        if (rel.startsWith("~")) {
            throw new IllegalArgumentException("invalid path: absolute/tilde not allowed");
        }
        String[] segments = rel.split("/");
        for (String segment : segments) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                throw new IllegalArgumentException("invalid path: '..' not allowed");
            }
        }

        File candidate = new File(root, rel);
        String rootCanonical = root.getCanonicalPath();
        String candidateCanonical = candidate.getCanonicalPath();
        if (!candidateCanonical.equals(rootCanonical) && !candidateCanonical.startsWith(rootCanonical + File.separator)) {
            throw new IllegalArgumentException("invalid path: escapes data root");
        }
        return new File(candidateCanonical);
    }

    // ---------------------------------------------------------------- Origin

    private static final String[] ALLOWED_ORIGINS = {
        "capacitor://localhost",
        "https://localhost",
        "http://localhost",
        "ionic://localhost",
    };

    private boolean isAllowedOrigin(String origin) {
        String normalized = origin.trim().toLowerCase(Locale.ROOT);
        for (String allowed : ALLOWED_ORIGINS) {
            if (allowed.equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- HTTP 底层

    private String readLine(InputStream in, int maxLen) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                byte[] bytes = buf.toByteArray();
                int len = bytes.length;
                if (len > 0 && bytes[len - 1] == '\r') {
                    len--;
                }
                return new String(bytes, 0, len, StandardCharsets.ISO_8859_1);
            }
            buf.write(b);
            if (buf.size() > maxLen) {
                throw new IOException("header line too long");
            }
        }
        return buf.size() == 0 ? null : new String(buf.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    private Map<String, String> readHeaders(InputStream in) throws IOException {
        Map<String, String> headers = new HashMap<>();
        int total = 0;
        for (int i = 0; i < MAX_HEADER_LINES; i++) {
            String line = readLine(in, MAX_HEADER_LINE);
            if (line == null) {
                throw new IOException("unexpected EOF in headers");
            }
            if (line.isEmpty()) {
                return headers;
            }
            total += line.length();
            if (total > MAX_HEADER_TOTAL) {
                throw new IOException("headers too large");
            }
            int idx = line.indexOf(':');
            if (idx <= 0) {
                continue;
            }
            headers.put(line.substring(0, idx).trim().toLowerCase(Locale.ROOT), line.substring(idx + 1).trim());
        }
        throw new IOException("too many headers");
    }

    private Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> query = new HashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return query;
        }
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int idx = pair.indexOf('=');
            String key = idx >= 0 ? pair.substring(0, idx) : pair;
            String value = idx >= 0 ? pair.substring(idx + 1) : "";
            try {
                query.put(URLDecoder.decode(key, "UTF-8"), URLDecoder.decode(value, "UTF-8"));
            } catch (Exception ignored) {
                // 非法编码参数直接忽略
            }
        }
        return query;
    }

    private long copyExactly(InputStream in, OutputStream out, long length) throws IOException {
        byte[] buffer = new byte[COPY_BUFFER];
        long remaining = length;
        long total = 0;
        while (remaining > 0) {
            int toRead = (int) Math.min(buffer.length, remaining);
            int read = in.read(buffer, 0, toRead);
            if (read == -1) {
                throw new IOException("unexpected EOF: " + total + "/" + length);
            }
            out.write(buffer, 0, read);
            total += read;
            remaining -= read;
        }
        return total;
    }

    private void writePreflight(OutputStream out, String origin) throws IOException {
        writeStatusAndHeaders(out, 204, "No Content", null, origin);
        out.flush();
    }

    private void writeJson(OutputStream out, int status, String body, String origin) throws IOException {
        Map<String, String> extra = new HashMap<>();
        extra.put("Content-Type", "application/json; charset=utf-8");
        extra.put("Content-Length", Integer.toString(body.getBytes(StandardCharsets.UTF_8).length));
        writeStatusAndHeaders(out, status, statusText(status), extra, origin);
        out.write(body.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private void writeError(OutputStream out, int status, String message, String origin) throws IOException {
        writeJson(out, status, "{\"error\":" + jsonString(message) + "}", origin);
    }

    private void writeStatusAndHeaders(
        OutputStream out,
        int status,
        String reason,
        Map<String, String> extra,
        String origin
    ) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n");
        sb.append("Connection: close\r\n");
        sb.append("Cache-Control: no-store\r\n");
        sb.append("X-Content-Type-Options: nosniff\r\n");
        sb.append("Referrer-Policy: no-referrer\r\n");
        if (origin != null && isAllowedOrigin(origin)) {
            sb.append("Access-Control-Allow-Origin: ").append(origin).append("\r\n");
            sb.append("Access-Control-Allow-Methods: GET, PUT, POST, OPTIONS\r\n");
            sb.append("Access-Control-Allow-Headers: Authorization, Content-Type, Range\r\n");
            sb.append("Access-Control-Expose-Headers: Content-Length, Content-Range, Accept-Ranges\r\n");
        }
        if (extra != null) {
            for (Map.Entry<String, String> entry : extra.entrySet()) {
                sb.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
            }
        }
        sb.append("\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
    }

    private String statusText(int status) {
        switch (status) {
            case 200:
                return "OK";
            case 204:
                return "No Content";
            case 206:
                return "Partial Content";
            case 400:
                return "Bad Request";
            case 404:
                return "Not Found";
            case 411:
                return "Length Required";
            case 413:
                return "Payload Too Large";
            default:
                return "Internal Server Error";
        }
    }

    private long[] parseRange(String header, long size) {
        if (header == null || size <= 0) {
            return null;
        }
        String value = header.trim();
        if (!value.toLowerCase(Locale.ROOT).startsWith("bytes=")) {
            return null;
        }
        String spec = value.substring(6).trim();
        if (spec.indexOf(',') >= 0) {
            return null; // 多段 range 不支持
        }
        int dash = spec.indexOf('-');
        if (dash < 0) {
            return null;
        }
        try {
            long start;
            long end;
            if (dash == 0) {
                long suffix = Long.parseLong(spec.substring(1));
                if (suffix <= 0) {
                    return null;
                }
                start = Math.max(0, size - suffix);
                end = size - 1;
            } else {
                start = Long.parseLong(spec.substring(0, dash));
                String endPart = spec.substring(dash + 1);
                end = endPart.isEmpty() ? size - 1 : Long.parseLong(endPart);
            }
            if (start < 0 || start >= size || end < start) {
                return null;
            }
            end = Math.min(end, size - 1);
            return new long[] { start, end };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String mimeType(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".mp4") || lower.endsWith(".m4v")) {
            return "video/mp4";
        }
        if (lower.endsWith(".webm")) {
            return "video/webm";
        }
        if (lower.endsWith(".mp3")) {
            return "audio/mpeg";
        }
        if (lower.endsWith(".m4a")) {
            return "audio/mp4";
        }
        if (lower.endsWith(".wav")) {
            return "audio/wav";
        }
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        if (lower.endsWith(".json")) {
            return "application/json; charset=utf-8";
        }
        if (lower.endsWith(".txt")) {
            return "text/plain; charset=utf-8";
        }
        if (lower.endsWith(".pdf")) {
            return "application/pdf";
        }
        return "application/octet-stream";
    }

    private static String jsonString(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(value.length() + 8);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format(Locale.US, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
