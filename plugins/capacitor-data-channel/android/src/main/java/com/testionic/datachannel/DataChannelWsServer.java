package com.testionic.datachannel;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * DataChannel **WebSocket** Server（Android，RFC 6455 子集，独立端口）。
 *
 * 与 HTTP server 完全独立：独立端口、独立 OTP 表、独立连接限流、可单独启停。
 *
 * ======================= 鉴权 =======================
 *
 * 握手时校验 OTP（一次性，WS 只支持一次性——双向含写入，reusable 不安全）：
 *   - `Sec-WebSocket-Protocol: otp.<otp>`（推荐；不回显到 URL）
 *   - 或 `?otp=<otp>`（兼容）
 *   - 校验失败返回 404（不泄露细节）
 *   - 成功后 101 响应里回显 `Sec-WebSocket-Protocol: otp.<otp>`
 *
 * ======================= 功能 =======================
 *
 * 默认 echo：收到什么回什么（便于自测与作为业务接入点）。
 * 支持文本/二进制帧、分片重组、ping/pong、close；单帧上限 64MB。
 * `broadcast(byte[])` 供 native 侧主动推送。
 *
 * ======================= 安全 =======================
 *
 * 只绑 127.0.0.1、随机端口、Origin 白名单（非法即断开）、两级连接限流
 * （未认证 4 / 已认证 maxConnections 默认 16）。
 */
public class DataChannelWsServer {

    public interface Listener {
        void onClientCountChanged(int count);
    }

    private static final String TAG = "DataChannelWs";
    private static final String WS_MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int MAX_HEADER_LINE = 8192;
    private static final int MAX_HEADER_LINES = 64;
    private static final int MAX_HEADER_TOTAL = 32 * 1024;
    private static final int SOCKET_TIMEOUT_MS = 10000;
    private static final int WS_MAX_FRAME_BYTES = 64 * 1024 * 1024;

    private static final String[] ALLOWED_ORIGINS = {
        "capacitor://localhost",
        "https://localhost",
        "http://localhost",
        "ionic://localhost",
    };

    private final OtpStore otpStore;
    private final boolean wsEcho;
    private final Listener listener;
    private final OtpStore.ConnectionLimiter limiter;

    private ServerSocket serverSocket;
    private Thread acceptThread;
    private ExecutorService workers;
    private volatile boolean running = false;
    private final Set<WsSession> sessions = ConcurrentHashMap.newKeySet();

    private static final class WsSession {
        final Socket socket;
        final OutputStream out;

        WsSession(Socket socket, OutputStream out) {
            this.socket = socket;
            this.out = out;
        }
    }

    public DataChannelWsServer(OtpStore otpStore, boolean wsEcho, int maxConnections, Listener listener) {
        this.otpStore = otpStore;
        this.wsEcho = wsEcho;
        this.listener = listener;
        this.limiter = new OtpStore.ConnectionLimiter(maxConnections);
    }

    public boolean isRunning() {
        return running;
    }

    public int port() {
        return serverSocket != null ? serverSocket.getLocalPort() : 0;
    }

    public int clientCount() {
        return sessions.size();
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

    public int start(int requestedPort) throws IOException {
        if (running) {
            return port();
        }
        serverSocket = new ServerSocket(requestedPort, 16, InetAddress.getByName("127.0.0.1"));
        workers = Executors.newCachedThreadPool();
        running = true;
        acceptThread = new Thread(this::acceptLoop, "DataChannelWsAccept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        return serverSocket.getLocalPort();
    }

    public void stop() {
        running = false;
        for (WsSession session : sessions) {
            try {
                session.socket.close();
            } catch (IOException ignored) {
                // 忽略
            }
        }
        sessions.clear();
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

    /** 向所有已连接的 WebSocket 客户端广播二进制帧（供 native 侧扩展使用）。 */
    public int broadcast(byte[] data) {
        int sent = 0;
        for (WsSession session : sessions) {
            try {
                sendFrame(session, 0x2, data);
                sent++;
            } catch (IOException ignored) {
                // 忽略单个连接失败
            }
        }
        return sent;
    }

    // ---------------------------------------------------------------- 接受连接

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                if (!limiter.tryAcquirePending()) {
                    Log.d(TAG, "reject connection: pending limit reached");
                    try {
                        socket.close();
                    } catch (IOException ignored) {
                        // 忽略
                    }
                    continue;
                }
                workers.execute(() -> runWebSocket(socket));
            } catch (IOException e) {
                if (!running) {
                    return;
                }
            }
        }
    }

    private void runWebSocket(Socket socket) {
        boolean authenticated = false;
        WsSession session = null;
        try {
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);
            InputStream in = new BufferedInputStream(socket.getInputStream(), 16 * 1024);
            OutputStream out = new BufferedOutputStream(socket.getOutputStream(), 16 * 1024);

            String requestLine = readLine(in, MAX_HEADER_LINE);
            if (requestLine == null || requestLine.isEmpty()) {
                return;
            }
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                writeSimpleResponse(out, 400, "Bad Request");
                return;
            }
            String target = parts[1];
            Map<String, String> headers = readHeaders(in);
            String origin = headers.get("origin");

            URI uri;
            try {
                uri = URI.create(target.startsWith("/") ? target : "/" + target);
            } catch (IllegalArgumentException e) {
                writeSimpleResponse(out, 400, "Bad Request");
                return;
            }
            String path = uri.getPath() == null ? "/" : uri.getPath();
            Map<String, String> query = parseQuery(uri.getRawQuery());

            if (origin != null && !isAllowedOrigin(origin)) {
                Log.d(TAG, "dropped ws connection with disallowed origin");
                return;
            }

            String key = headers.get("sec-websocket-key");
            if (key == null || key.isEmpty()) {
                writeSimpleResponse(out, 400, "Bad Request");
                return;
            }
            if (!"websocket".equalsIgnoreCase(headers.get("upgrade"))) {
                writeSimpleResponse(out, 400, "Bad Request");
                return;
            }
            if (!"/ws".equals(path)) {
                writeSimpleResponse(out, 404, "Not Found");
                return;
            }

            // ---- OTP 鉴权（一次性；WS 双向含写入，不允许 reusable）----
            String otp = extractOtp(headers, query);
            if (!otpStore.consume(otp, OtpStore.SCOPE_READWRITE, "")) {
                writeSimpleResponse(out, 404, "Not Found");
                return;
            }
            if (!limiter.promoteToActive()) {
                writeSimpleResponse(out, 404, "Not Found");
                return;
            }
            authenticated = true;

            // 101 握手（回显子协议，标准做法）
            String accept = computeWsAccept(key);
            StringBuilder handshake = new StringBuilder();
            handshake.append("HTTP/1.1 101 Switching Protocols\r\n");
            handshake.append("Upgrade: websocket\r\n");
            handshake.append("Connection: Upgrade\r\n");
            handshake.append("Sec-WebSocket-Accept: ").append(accept).append("\r\n");
            handshake.append("Sec-WebSocket-Protocol: otp.").append(otp).append("\r\n");
            if (origin != null && isAllowedOrigin(origin)) {
                handshake.append("Access-Control-Allow-Origin: ").append(origin).append("\r\n");
            }
            handshake.append("\r\n");
            out.write(handshake.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.flush();

            session = new WsSession(socket, out);
            sessions.add(session);
            if (listener != null) {
                listener.onClientCountChanged(sessions.size());
            }
            Log.d(TAG, "websocket client connected (echo=" + wsEcho + ", clients=" + sessions.size() + ")");

            socket.setSoTimeout(0); // 长连接
            socket.setTcpNoDelay(true);
            wsLoop(in, session);
        } catch (IOException ignored) {
            // 客户端断开
        } finally {
            if (session != null) {
                sessions.remove(session);
                if (listener != null) {
                    listener.onClientCountChanged(sessions.size());
                }
                Log.d(TAG, "websocket client disconnected (clients=" + sessions.size() + ")");
            }
            try {
                socket.close();
            } catch (IOException ignored) {
                // 忽略
            }
            limiter.release(authenticated);
        }
    }

    private String extractOtp(Map<String, String> headers, Map<String, String> query) {
        String protocol = headers.get("sec-websocket-protocol");
        if (protocol != null) {
            for (String item : protocol.split(",")) {
                String candidate = item.trim();
                if (candidate.startsWith("otp.")) {
                    return candidate.substring(4);
                }
            }
        }
        return query.get("otp");
    }

    // ---------------------------------------------------------------- 帧循环

    private void wsLoop(InputStream in, WsSession session) throws IOException {
        ByteArrayOutputStream assembled = new ByteArrayOutputStream();
        int assembledOpcode = -1;
        while (running) {
            int first = in.read();
            if (first < 0) {
                return;
            }
            int second = in.read();
            if (second < 0) {
                return;
            }
            boolean fin = (first & 0x80) != 0;
            int opcode = first & 0x0F;
            boolean masked = (second & 0x80) != 0;
            long length = second & 0x7F;
            if (length == 126) {
                length = readU16(in);
            } else if (length == 127) {
                length = readU64(in);
            }
            if (length < 0 || length > WS_MAX_FRAME_BYTES) {
                closeWs(session, 1009);
                return;
            }
            byte[] mask = masked ? readN(in, 4) : null;
            byte[] payload = readN(in, (int) length);
            if (mask != null) {
                for (int i = 0; i < payload.length; i++) {
                    payload[i] ^= mask[i & 3];
                }
            }

            switch (opcode) {
                case 0x9: // ping → pong
                    sendFrame(session, 0xA, payload);
                    break;
                case 0xA: // pong
                    break;
                case 0x8: // close
                    sendFrame(session, 0x8, new byte[0]);
                    return;
                case 0x1: // text（首帧）
                case 0x2: // binary（首帧）
                    assembledOpcode = opcode;
                    assembled.write(payload, 0, payload.length);
                    break;
                case 0x0: // continuation
                    assembled.write(payload, 0, payload.length);
                    break;
                default:
                    break;
            }

            if (fin && (opcode == 0x0 || opcode == 0x1 || opcode == 0x2)) {
                byte[] message = assembled.toByteArray();
                assembled.reset();
                if (wsEcho) {
                    sendFrame(session, assembledOpcode == 0x1 ? 0x1 : 0x2, message);
                }
                // 非 echo 模式：这里就是后续开发者接入业务逻辑的扩展点
            }
        }
    }

    private void sendFrame(WsSession session, int opcode, byte[] data) throws IOException {
        ByteArrayOutputStream frame = new ByteArrayOutputStream(data.length + 10);
        frame.write(0x80 | (opcode & 0x0F)); // FIN + opcode（服务端帧不加 mask）
        int len = data.length;
        if (len <= 125) {
            frame.write(len);
        } else if (len <= 65535) {
            frame.write(126);
            frame.write((len >> 8) & 0xFF);
            frame.write(len & 0xFF);
        } else {
            frame.write(127);
            for (int i = 7; i >= 0; i--) {
                frame.write((int) (((long) len) >> (8 * i)) & 0xFF);
            }
        }
        frame.write(data, 0, data.length);
        synchronized (session) {
            session.out.write(frame.toByteArray());
            session.out.flush();
        }
    }

    private void closeWs(WsSession session, int code) {
        try {
            sendFrame(session, 0x8, new byte[] { (byte) (code >> 8), (byte) (code & 0xFF) });
        } catch (IOException ignored) {
            // 忽略
        }
    }

    private String computeWsAccept(String key) throws IOException {
        try {
            java.security.MessageDigest sha1 = java.security.MessageDigest.getInstance("SHA-1");
            byte[] digest = sha1.digest((key + WS_MAGIC).getBytes(StandardCharsets.US_ASCII));
            return android.util.Base64.encodeToString(digest, android.util.Base64.NO_WRAP);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-1 unavailable", e);
        }
    }

    private long readU16(InputStream in) throws IOException {
        int b1 = in.read();
        int b2 = in.read();
        if (b1 < 0 || b2 < 0) {
            throw new IOException("unexpected EOF");
        }
        return ((long) b1 << 8) | b2;
    }

    private long readU64(InputStream in) throws IOException {
        long value = 0;
        for (int i = 0; i < 8; i++) {
            int b = in.read();
            if (b < 0) {
                throw new IOException("unexpected EOF");
            }
            value = (value << 8) | b;
        }
        return value;
    }

    private byte[] readN(InputStream in, int length) throws IOException {
        byte[] buf = new byte[length];
        int read = 0;
        while (read < length) {
            int n = in.read(buf, read, length - read);
            if (n < 0) {
                throw new IOException("unexpected EOF in frame");
            }
            read += n;
        }
        return buf;
    }

    // ---------------------------------------------------------------- HTTP 握手底层

    private void writeSimpleResponse(OutputStream out, int status, String reason) throws IOException {
        String response =
            "HTTP/1.1 " + status + " " + reason + "\r\n" +
            "Connection: close\r\n" +
            "Content-Length: 0\r\n" +
            "\r\n";
        out.write(response.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

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
                // 忽略
            }
        }
        return query;
    }

    private boolean isAllowedOrigin(String origin) {
        String normalized = origin.trim().toLowerCase(Locale.ROOT);
        for (String allowed : ALLOWED_ORIGINS) {
            if (allowed.equals(normalized)) {
                return true;
            }
        }
        return false;
    }
}
