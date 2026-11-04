package com.testionic.datachannel;

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
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import android.util.Log;

/**
 * DataChannel 本地 HTTP Server（Android，纯 Java 实现，零第三方依赖）。
 *
 * 设计目标：让 WebView 用标准 fetch / &lt;video&gt; / &lt;img&gt; 直接收发**原始二进制**，
 * 绕开 Capacitor 的 JSON/base64 桥。
 *
 * 安全设计（详见 README）：
 *  - 只绑定 127.0.0.1（外部网络/其他机器无法访问）；
 *  - 随机端口 + 每次启动随机 Bearer token（header 或 ?token=，常量时间比较）；
 *  - CORS 只放行 WebView origin（capacitor://localhost / https://localhost 等）；
 *  - 路径限制在数据根目录内（canonical path 校验，拒绝 ..、绝对路径、软链逃逸）；
 *  - 请求头/请求体大小限制 + 读超时；
 *  - 上传先写 .part 再 rename（原子性），支持 append 分片续传。
 *
 * HTTP 协议子集：HTTP/1.1 单请求单连接（Connection: close）、Content-Length 请求体、
 * 支持下载 Range（视频拖动进度条必需）。不支持 chunked 请求体（fetch 上传 Blob/File 时
 * 会带 Content-Length，满足需求）。
 */
public class DataChannelServer {

    public interface Listener {
        void onRequestCompleted(String method, String path, int status, long bytes, long durationMs);
    }

    private static final int MAX_HEADER_LINE = 8192;
    private static final int MAX_HEADER_LINES = 64;
    private static final int MAX_HEADER_TOTAL = 32 * 1024;
    private static final int SOCKET_TIMEOUT_MS = 20000;
    private static final int COPY_BUFFER = 64 * 1024;

    private final File root;
    private final String token;
    private final long maxBodyBytes;
    private final Listener listener;

    private ServerSocket serverSocket;
    private Thread acceptThread;
    private ExecutorService workers;
    private volatile boolean running = false;
    private final AtomicInteger activeConnections = new AtomicInteger();

    public DataChannelServer(File root, String token, long maxBodyBytes, Listener listener) {
        this.root = root;
        this.token = token;
        this.maxBodyBytes = maxBodyBytes;
        this.listener = listener;
    }

    public boolean isRunning() {
        return running;
    }

    public int activeConnections() {
        return activeConnections.get();
    }

    /** 启动并返回实际监听端口。 */
    public int start(int requestedPort) throws IOException {
        if (running) {
            return serverSocket != null ? serverSocket.getLocalPort() : 0;
        }
        if (!root.exists() && !root.mkdirs()) {
            throw new IOException("cannot create data root: " + root);
        }
        // 只绑定回环地址：外部设备无法连接
        serverSocket = new ServerSocket(requestedPort, 16, InetAddress.getByName("127.0.0.1"));
        workers = Executors.newFixedThreadPool(4);
        running = true;
        acceptThread = new Thread(this::acceptLoop, "DataChannelAccept");
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
            // 关闭时忽略
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
                activeConnections.incrementAndGet();
                workers.execute(() -> {
                    try {
                        handleRequest(socket);
                    } finally {
                        activeConnections.decrementAndGet();
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

    // ---------------------------------------------------------------- 请求分发

    private void handleRequest(Socket socket) {
        long startedAt = System.nanoTime();
        String method = "-";
        String path = "-";
        int status = 500;

        try (Socket s = socket) {
            s.setSoTimeout(SOCKET_TIMEOUT_MS);
            InputStream in = new BufferedInputStream(s.getInputStream(), 16 * 1024);
            OutputStream out = new BufferedOutputStream(s.getOutputStream(), 16 * 1024);

            String requestLine = readLine(in, MAX_HEADER_LINE);
            if (requestLine == null || requestLine.isEmpty()) {
                return;
            }
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                status = 400;
                writeError(out, 400, "bad request line", null);
                return;
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
                return;
            }
            path = uri.getPath() == null ? "/" : uri.getPath();
            Map<String, String> query = parseQuery(uri.getRawQuery());

            // CORS 预检（不需要 token：浏览器发预检时不会带 Authorization）
            if ("OPTIONS".equals(method)) {
                status = 204;
                writePreflight(out, origin);
                return;
            }

            // Origin 白名单：非白名单 Origin **静默断开**（不返回任何响应，避免泄露服务存在）
            if (origin != null && !isAllowedOrigin(origin)) {
                status = 0;
                Log.d("DataChannel", "dropped connection with disallowed origin");
                return;
            }

            // 健康检查（免 token，便于页面启动自检；只返回极简信息）
            if ("GET".equals(method) && "/health".equals(path)) {
                status = 200;
                writeJson(out, 200, "{\"ok\":true,\"name\":\"capacitor-data-channel\"}", origin);
                return;
            }

            if (!isAuthorized(headers, query)) {
                // 鉴权失败统一返回 404（与未知路由不可区分），不泄露"这里有个需要认证的服务"
                status = 404;
                writeError(out, 404, "not found", origin);
                return;
            }

            String route = method + " " + path;
            switch (route) {
                case "PUT /upload":
                case "POST /upload":
                    status = handleUpload(in, out, headers, query, origin);
                    return;
                case "GET /download":
                    status = handleDownload(out, headers, query, origin);
                    return;
                case "GET /info":
                    status = handleInfo(out, query, origin);
                    return;
                case "GET /list":
                    status = handleList(out, query, origin);
                    return;
                case "DELETE /file":
                case "POST /delete":
                    status = handleDelete(out, query, origin);
                    return;
                default:
                    status = 404;
                    writeError(out, 404, "not found: " + route, origin);
                    return;
            }
        } catch (IOException e) {
            status = 500;
        } catch (Exception e) {
            status = 500;
        } finally {
            if (listener != null) {
                long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
                try {
                    listener.onRequestCompleted(method, path, status, 0, durationMs);
                } catch (Exception ignored) {
                    // 监听回调异常不影响 server
                }
            }
        }
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
            // 先写 .part 再 rename：避免半截文件被读到
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

    private int handleInfo(OutputStream out, Map<String, String> query, String origin) throws IOException {
        final File file;
        try {
            file = resolveSafePath(query.get("path"));
        } catch (IllegalArgumentException e) {
            writeError(out, 400, e.getMessage(), origin);
            return 400;
        }
        String body =
            "{\"path\":" +
            jsonString(query.get("path")) +
            ",\"exists\":" +
            file.exists() +
            ",\"isDir\":" +
            file.isDirectory() +
            ",\"size\":" +
            (file.exists() ? file.length() : 0) +
            ",\"mimeType\":" +
            jsonString(file.isFile() ? mimeType(file.getName()) : "") +
            "}";
        writeJson(out, 200, body, origin);
        return 200;
    }

    private int handleList(OutputStream out, Map<String, String> query, String origin) throws IOException {
        final File dir;
        try {
            String rel = query.get("path");
            dir = resolveSafePath(rel == null ? "" : rel);
        } catch (IllegalArgumentException e) {
            writeError(out, 400, e.getMessage(), origin);
            return 400;
        }
        if (!dir.isDirectory()) {
            writeError(out, 404, "not a directory", origin);
            return 404;
        }
        File[] children = dir.listFiles();
        List<File> sorted = new ArrayList<>();
        if (children != null) {
            sorted.addAll(Arrays.asList(children));
        }
        sorted.sort(Comparator.comparing(File::getName));

        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < sorted.size(); i++) {
            File f = sorted.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb
                .append("{\"name\":")
                .append(jsonString(f.getName()))
                .append(",\"isDir\":")
                .append(f.isDirectory())
                .append(",\"size\":")
                .append(f.length())
                .append(",\"lastModified\":")
                .append(f.lastModified())
                .append('}');
        }
        sb.append(']');
        writeJson(out, 200, sb.toString(), origin);
        return 200;
    }

    private int handleDelete(OutputStream out, Map<String, String> query, String origin) throws IOException {
        final File file;
        try {
            file = resolveSafePath(query.get("path"));
        } catch (IllegalArgumentException e) {
            writeError(out, 400, e.getMessage(), origin);
            return 400;
        }
        if (file.equals(root)) {
            writeError(out, 400, "cannot delete root directory", origin);
            return 400;
        }
        if (!file.exists()) {
            writeError(out, 404, "not found", origin);
            return 404;
        }
        boolean deleted = deleteRecursively(file);
        if (!deleted) {
            writeError(out, 500, "delete failed", origin);
            return 500;
        }
        writeJson(out, 200, "{\"deleted\":true}", origin);
        return 200;
    }

    private boolean deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (!deleteRecursively(child)) {
                        return false;
                    }
                }
            }
        }
        return file.delete();
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
            return root;
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

    // ---------------------------------------------------------------- 鉴权

    private boolean isAuthorized(Map<String, String> headers, Map<String, String> query) {
        String provided = null;
        String auth = headers.get("authorization");
        if (auth != null && auth.length() > 7 && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            provided = auth.substring(7).trim();
        }
        if (provided == null || provided.isEmpty()) {
            provided = query.get("token");
        }
        if (provided == null || provided.isEmpty()) {
            return false;
        }
        return MessageDigest.isEqual(
            provided.getBytes(StandardCharsets.UTF_8),
            token.getBytes(StandardCharsets.UTF_8)
        );
    }

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
            sb.append("Access-Control-Allow-Methods: GET, PUT, POST, DELETE, OPTIONS\r\n");
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
            case 201:
                return "Created";
            case 204:
                return "No Content";
            case 206:
                return "Partial Content";
            case 400:
                return "Bad Request";
            case 401:
                return "Unauthorized";
            case 403:
                return "Forbidden";
            case 404:
                return "Not Found";
            case 405:
                return "Method Not Allowed";
            case 411:
                return "Length Required";
            case 413:
                return "Payload Too Large";
            case 416:
                return "Range Not Satisfiable";
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
