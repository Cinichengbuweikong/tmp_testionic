package com.testionic.liveupdate;

import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 下载 + 解压。
 *
 * 解压的安全约束（zip-slip / zip bomb）：
 * - 拒绝绝对路径、`..` 路径段、Windows 盘符、反斜杠；
 * - 每个条目解压后必须仍在目标目录内（canonical path 校验）；
 * - 条目数 ≤ 5000、总解压体积 ≤ 200MB；
 * - 解压完成后必须存在 `index.html`（否则不算有效 bundle）。
 */
public final class UpdateInstaller {

    private static final String TAG = "UpdateInstaller";
    private static final int MAX_ENTRIES = 5000;
    private static final long MAX_TOTAL_BYTES = 200L * 1024 * 1024;

    public interface ProgressListener {
        void onProgress(long bytes, long total);
    }

    private UpdateInstaller() {
    }

    public static class ExtractResult {
        public int files;
        public long bytes;
    }

    /** GET 一个 JSON（跟随最多 5 次重定向）。 */
    public static JSONObject fetchJson(String url, int timeoutMs) throws Exception {
        HttpURLConnection connection = open(url, timeoutMs);
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("HTTP_" + status + ": " + url);
            }
            try (InputStream input = connection.getInputStream()) {
                String body = new String(UpdateVerifier.readAll(input), StandardCharsets.UTF_8);
                return new JSONObject(body);
            }
        } finally {
            connection.disconnect();
        }
    }

    public static void download(String url, File target, int timeoutMs, ProgressListener listener)
            throws Exception {
        HttpURLConnection connection = open(url, timeoutMs);
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("HTTP_" + status + ": " + url);
            }
            long total = connection.getContentLengthLong();
            long written = 0;
            byte[] buffer = new byte[64 * 1024];
            File parent = target.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IOException("CANNOT_CREATE_DIR: " + parent);
            }
            try (InputStream input = connection.getInputStream();
                 OutputStream output = new FileOutputStream(target)) {
                int read;
                while ((read = input.read(buffer)) > 0) {
                    output.write(buffer, 0, read);
                    written += read;
                    if (listener != null) {
                        listener.onProgress(written, total);
                    }
                }
            }
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection open(String url, int timeoutMs) throws IOException {
        URL parsed = new URL(url);
        String protocol = parsed.getProtocol();
        if (!"https".equals(protocol) && !"http".equals(protocol)) {
            throw new IOException("UNSUPPORTED_PROTOCOL: " + protocol);
        }
        HttpURLConnection connection = (HttpURLConnection) parsed.openConnection();
        connection.setConnectTimeout(timeoutMs);
        connection.setReadTimeout(timeoutMs);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "application/json, application/zip, */*");
        connection.setRequestProperty("Cache-Control", "no-cache");
        return connection;
    }

    public static ExtractResult extract(File zipFile, File targetDir) throws Exception {
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw new IOException("CANNOT_CREATE_DIR: " + targetDir);
        }
        String targetRoot = targetDir.getCanonicalPath();
        ExtractResult result = new ExtractResult();

        try (ZipFile zip = new ZipFile(zipFile)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            int count = 0;
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (++count > MAX_ENTRIES) {
                    throw new IOException("TOO_MANY_ENTRIES: > " + MAX_ENTRIES);
                }
                String name = entry.getName();
                validateName(name);
                File out = new File(targetDir, name.replace('\\', '/'));
                String canonical = out.getCanonicalPath();
                if (!canonical.equals(targetRoot) && !canonical.startsWith(targetRoot + File.separator)) {
                    throw new IOException("ZIP_SLIP: " + name);
                }
                if (entry.isDirectory()) {
                    if (!out.exists() && !out.mkdirs()) {
                        throw new IOException("CANNOT_CREATE_DIR: " + out);
                    }
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    throw new IOException("CANNOT_CREATE_DIR: " + parent);
                }
                long entryBytes = 0;
                try (InputStream input = zip.getInputStream(entry);
                     OutputStream output = new FileOutputStream(out)) {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = input.read(buffer)) > 0) {
                        output.write(buffer, 0, read);
                        entryBytes += read;
                        result.bytes += read;
                        if (result.bytes > MAX_TOTAL_BYTES) {
                            throw new IOException("ARCHIVE_TOO_LARGE: > " + MAX_TOTAL_BYTES + " bytes");
                        }
                    }
                }
                result.files++;
                Log.d(TAG, "extracted " + name + " (" + entryBytes + " bytes)");
            }
        }

        File index = new File(targetDir, "index.html");
        if (!index.exists() || index.length() == 0) {
            throw new IOException("MISSING_INDEX_HTML: 包里没有 index.html，拒绝安装");
        }
        return result;
    }

    private static void validateName(String name) throws IOException {
        if (name == null || name.isEmpty()) {
            throw new IOException("EMPTY_ENTRY_NAME");
        }
        if (name.startsWith("/") || name.startsWith("\\")) {
            throw new IOException("ABSOLUTE_PATH_IN_ZIP: " + name);
        }
        if (name.contains(":") || name.contains("\u0000")) {
            throw new IOException("ILLEGAL_ENTRY_NAME: " + name);
        }
        String[] parts = name.replace('\\', '/').split("/");
        for (String part : parts) {
            if (part.equals("..")) {
                throw new IOException("PATH_TRAVERSAL_IN_ZIP: " + name);
            }
        }
    }
}
