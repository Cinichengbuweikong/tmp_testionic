package com.testionic.liveupdate;

import android.content.Context;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.List;

/**
 * 热更新（Android）。
 *
 * 流程：`checkForUpdate` → `downloadAndInstall` → 校验 SHA-256 + ECDSA 签名 → 解压到
 * `filesDir/live-update/bundles/<bundleId>` → 写 `CapWebViewSettings/serverBasePath`
 * （下次冷启动直接进新包）→ reload。
 *
 * 安全/可靠性要点见 `src/definitions.ts` 顶部注释与 README。
 */
@CapacitorPlugin(name = "LiveUpdate")
public class LiveUpdatePlugin extends Plugin {

    private UpdateStore store;
    /** 上一次 `checkForUpdate` 的结果，供 `downloadAndInstall()` 缺省使用 */
    private JSONObject lastCheck;

    @Override
    public void load() {
        store = new UpdateStore(getContext());
        runStartupSelfHeal();
    }

    // ------------------------------------------------------------ 启动自愈

    /**
     * 两件必须在这里做的事（插件 `load()` 在 WebView 加载页面之前执行）：
     *
     * 1. **原生升级检测**：装了新 APK（versionCode 变了）就丢掉 OTA 包，回内置资源，
     *    否则会出现"新原生 + 旧 Web"。
     * 2. **pending 包回滚**：上一个包启动后 JS 没调用 `notifyAppReady()`（崩了/白屏），
     *    本次启动累计到 2 次就自动回滚到上一版。
     */
    private void runStartupSelfHeal() {
        long currentCode = store.appVersionCode();
        long storedCode = store.getInstalledAppVersion();
        if (storedCode < 0) {
            store.setInstalledAppVersion(currentCode);
            store.setBootAttempts(0);
            return;
        }
        if (storedCode != currentCode) {
            clearAllBundles();
            store.clearState();
            store.clearCapacitorBasePath();
            store.setInstalledAppVersion(currentCode);
            // 插件 load() 时 WebView 可能已经按旧持久化路径加载了 OTA 包 → 切回内置资源
            if (store.getCurrentBundleId() != null) {
                getBridge().setServerAssetPath("public");
            }
            return;
        }

        if (!store.isPending()) {
            return;
        }

        int attempts = store.getBootAttempts() + 1;
        store.setBootAttempts(attempts);
        if (attempts < 2) {
            return;
        }

        String note = "新包连续 " + attempts + " 次启动未能确认（可能白屏/JS 崩溃），已自动回滚。";
        getBridge().getWebView().post(() -> {
            // 回滚到上一版；没有上一版就回内置资源
            String previous = store.getPreviousBundleId();
            File previousDir = previous != null ? store.bundleDir(previous) : null;
            store.setPending(false);
            store.setBootAttempts(0);
            if (previousDir != null && previousDir.exists()) {
                String previousVersion = store.prefs().getString("st_previous_version", previous);
                store.setCurrentBundleId(previous);
                store.prefs().edit()
                        .putString("st_current_version", previousVersion)
                        .remove("st_previous")
                        .remove("st_previous_version")
                        .apply();
                store.persistCapacitorBasePath(previousDir.getAbsolutePath());
                store.setLastNote(note);
                getBridge().setServerBasePath(previousDir.getAbsolutePath());
            } else {
                store.clearState();
                store.clearCapacitorBasePath();
                store.setLastNote(note);
                getBridge().setServerAssetPath("public");
            }
        });
    }

    // ------------------------------------------------------------ 配置

    @PluginMethod
    public void configure(PluginCall call) {
        String serverUrl = call.getString("serverUrl");
        if (serverUrl == null || serverUrl.isEmpty()) {
            call.reject("serverUrl 不能为空", "INVALID_CONFIG");
            return;
        }
        String publicKey = call.getString("publicKey", store.getPublicKey());
        boolean allowUnsigned = Boolean.TRUE.equals(call.getBoolean("allowUnsigned", store.isAllowUnsigned()));
        String channel = call.getString("channel", store.getChannel());
        String appId = call.getString("appId", store.getAppId());
        boolean allowDowngrade = Boolean.TRUE.equals(call.getBoolean("allowDowngrade", store.isAllowDowngrade()));
        Integer timeout = call.getInt("timeoutMs", store.getTimeoutMs());

        store.saveConfig(serverUrl, publicKey, allowUnsigned, channel, appId, allowDowngrade,
                timeout != null ? timeout : 30000);
        call.resolve(configObject());
    }

    @PluginMethod
    public void getConfig(PluginCall call) {
        call.resolve(configObject());
    }

    private JSObject configObject() {
        JSObject ret = new JSObject();
        ret.put("serverUrl", store.getServerUrl());
        String publicKey = store.getPublicKey();
        ret.put("publicKeyPreview", publicKey == null ? null
                : publicKey.replaceAll("\\s", "").substring(0, Math.min(16, publicKey.replaceAll("\\s", "").length())) + "...");
        ret.put("allowUnsigned", store.isAllowUnsigned());
        ret.put("channel", store.getChannel());
        ret.put("appId", store.getAppId());
        ret.put("allowDowngrade", store.isAllowDowngrade());
        ret.put("timeoutMs", store.getTimeoutMs());
        ret.put("platform", "android");
        return ret;
    }

    // ------------------------------------------------------------ 检查更新

    @PluginMethod
    public void checkForUpdate(PluginCall call) {
        String serverUrl = call.getString("serverUrl", store.getServerUrl());
        if (serverUrl == null || serverUrl.isEmpty()) {
            call.reject("NOT_CONFIGURED: 请先调用 configure({ serverUrl })", "NOT_CONFIGURED");
            return;
        }
        String channel = call.getString("channel", store.getChannel());
        boolean force = Boolean.TRUE.equals(call.getBoolean("force", false));
        String currentVersion = currentVersion();

        try {
            String url = serverUrl.replaceAll("/+$", "")
                    + "/latest.json?appId=" + android.net.Uri.encode(store.getAppId())
                    + "&platform=android"
                    + "&channel=" + android.net.Uri.encode(channel)
                    + "&version=" + android.net.Uri.encode(currentVersion);
            JSONObject body = UpdateInstaller.fetchJson(url, store.getTimeoutMs());
            lastCheck = body;
            store.setLastCheckAt(System.currentTimeMillis());

            String version = body.optString("version", "");
            String minNative = body.optString("minNativeVersion", "");

            JSObject ret = new JSObject();
            ret.put("currentVersion", currentVersion);
            ret.put("version", version);
            ret.put("url", body.optString("url", null));
            ret.put("sha256", body.optString("sha256", null));
            ret.put("signature", body.optString("signature", null));
            ret.put("notes", body.optString("notes", null));
            ret.put("size", body.has("size") ? body.optLong("size") : null);

            if (version.isEmpty()) {
                ret.put("available", false);
                ret.put("reason", "UP_TO_DATE");
                call.resolve(ret);
                return;
            }
            if (!minNative.isEmpty() && compareVersions(store.builtinVersion(), minNative) < 0) {
                ret.put("available", false);
                ret.put("reason", "NATIVE_TOO_OLD");
                call.resolve(ret);
                return;
            }
            int cmp = compareVersions(version, currentVersion);
            boolean newer = cmp > 0 || (cmp == 0 && !version.equals(currentVersion));
            if (!newer && !force) {
                ret.put("available", false);
                ret.put("reason", cmp < 0 ? "DOWNGRADE_BLOCKED" : "UP_TO_DATE");
                call.resolve(ret);
                return;
            }
            ret.put("available", true);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("CHECK_FAILED: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------ 安装

    @PluginMethod
    public void downloadAndInstall(PluginCall call) {
        String url = call.getString("url");
        String version = call.getString("version");
        String sha256 = call.getString("sha256");
        String signature = call.getString("signature");

        if (url == null && lastCheck != null) {
            url = lastCheck.optString("url", null);
            version = lastCheck.optString("version", null);
            sha256 = lastCheck.optString("sha256", null);
            signature = lastCheck.optString("signature", null);
        }
        if (url == null || url.isEmpty()) {
            call.reject("NO_UPDATE_INFO: 请先 checkForUpdate() 或显式传入 url", "NO_UPDATE_INFO");
            return;
        }
        if (version == null || version.isEmpty()) {
            call.reject("VERSION_REQUIRED: 需要 version 来生成本地 bundle 目录", "VERSION_REQUIRED");
            return;
        }
        if (sha256 == null || sha256.isEmpty()) {
            call.reject("SHA256_REQUIRED: 必须校验包摘要", "SHA256_REQUIRED");
            return;
        }
        if (!store.isAllowUnsigned()) {
            if (store.getPublicKey() == null || store.getPublicKey().isEmpty()) {
                call.reject("SIGNATURE_KEY_MISSING: 未配置 publicKey；本地调试可用 allowUnsigned: true", "SIGNATURE_KEY_MISSING");
                return;
            }
            if (signature == null || signature.isEmpty()) {
                call.reject("SIGNATURE_REQUIRED: 后端没有返回签名", "SIGNATURE_REQUIRED");
                return;
            }
        }

        File zipFile = new File(store.rootDir(), "download-" + System.currentTimeMillis() + ".zip");
        String bundleId = sanitizeVersion(version) + "-" + sha256.substring(0, Math.min(8, sha256.length()));
        File staging = new File(store.bundlesDir(), ".staging-" + bundleId);
        File target = store.bundleDir(bundleId);

        try {
            UpdateInstaller.download(url, zipFile, store.getTimeoutMs(), (bytes, total) -> {
                JSObject progress = new JSObject();
                progress.put("bytes", bytes);
                progress.put("total", total);
                progress.put("percent", total > 0 ? Math.round(bytes * 1000.0 / total) / 10.0 : -1);
                notifyListeners("downloadProgress", progress);
            });

            String actualSha = UpdateVerifier.sha256Hex(zipFile);
            if (!actualSha.equalsIgnoreCase(sha256)) {
                throw new SecurityException("SHA256_MISMATCH: 期望 " + sha256 + "，实际 " + actualSha);
            }
            if (!store.isAllowUnsigned()) {
                boolean ok = UpdateVerifier.verifyFileSignature(zipFile, signature, store.getPublicKey());
                if (!ok) {
                    throw new SecurityException("SIGNATURE_INVALID: 签名校验失败（包被篡改或公钥不匹配）");
                }
            }

            UpdateStore.deleteRecursively(staging);
            UpdateInstaller.ExtractResult result = UpdateInstaller.extract(zipFile, staging);
            UpdateStore.deleteRecursively(target);
            if (!staging.renameTo(target)) {
                throw new java.io.IOException("INSTALL_FAILED: 无法把临时目录重命名为正式目录");
            }

            String previousId = store.getCurrentBundleId();
            String previousVersion = store.prefs().getString("st_current_version", null);
            store.setPreviousBundleId(previousId);
            store.prefs().edit().putString("st_previous_version", previousVersion).apply();
            store.setCurrentBundleId(bundleId);
            store.prefs().edit().putString("st_current_version", version).apply();
            store.setPending(true);
            store.setBootAttempts(0);
            store.setLastNote(null);
            store.persistCapacitorBasePath(target.getAbsolutePath());
            store.pruneOldBundles(bundleId, previousId);

            JSObject ret = new JSObject();
            ret.put("installed", true);
            ret.put("version", version);
            ret.put("bundleId", bundleId);
            ret.put("requiresReload", true);
            ret.put("files", result.files);
            ret.put("bytes", result.bytes);
            call.resolve(ret);

            // 先 resolve 再 reload：reload 会销毁 JS 上下文，否则 promise 永远收不到
            reloadLater(target.getAbsolutePath());
        } catch (Exception e) {
            UpdateStore.deleteRecursively(staging);
            call.reject(e.getMessage() == null ? "INSTALL_FAILED" : e.getMessage(), e);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            zipFile.delete();
        }
    }

    private void reloadLater(String basePath) {
        getBridge().getWebView().postDelayed(() -> {
            if (basePath == null || basePath.isEmpty()) {
                getBridge().setServerAssetPath("public");
            } else {
                getBridge().setServerBasePath(basePath);
            }
        }, 500);
    }

    // ------------------------------------------------------------ 状态

    @PluginMethod
    public void getState(PluginCall call) {
        call.resolve(stateObject());
    }

    private JSObject stateObject() {
        String currentId = store.getCurrentBundleId();
        File currentDir = currentId != null ? store.bundleDir(currentId) : null;
        boolean usingOta = currentDir != null && currentDir.exists();
        String previousId = store.getPreviousBundleId();

        JSObject ret = new JSObject();
        ret.put("platform", "android");
        ret.put("currentVersion", usingOta
                ? store.prefs().getString("st_current_version", currentId)
                : store.builtinVersion());
        ret.put("currentBundleId", usingOta ? currentId : "builtin");
        ret.put("builtinVersion", store.builtinVersion());
        ret.put("usingOta", usingOta);
        ret.put("pending", store.isPending());
        ret.put("bootAttempts", store.getBootAttempts());
        ret.put("previousVersion", usingOta ? store.prefs().getString("st_previous_version", null) : null);
        long lastCheck = store.getLastCheckAt();
        ret.put("lastCheckAt", lastCheck > 0 ? lastCheck : null);

        List<File> dirs = store.listBundleDirs();
        java.util.List<JSObject> items = new java.util.ArrayList<>();
        for (File dir : dirs) {
            JSObject item = new JSObject();
            String id = dir.getName();
            item.put("bundleId", id);
            item.put("version", id.equals(currentId)
                    ? store.prefs().getString("st_current_version", id)
                    : id.equals(previousId)
                        ? store.prefs().getString("st_previous_version", id)
                        : id);
            item.put("path", dir.getAbsolutePath());
            item.put("bytes", store.dirSize(dir));
            item.put("active", usingOta && id.equals(currentId));
            item.put("previous", id.equals(previousId));
            items.add(item);
        }
        ret.put("updates", new JSArray(new java.util.ArrayList<>(items)));
        String note = store.getLastNote();
        if (note != null) {
            ret.put("note", note);
        }
        return ret;
    }

    @PluginMethod
    public void notifyAppReady(PluginCall call) {
        boolean wasPending = store.isPending();
        store.setPending(false);
        store.setBootAttempts(0);
        JSObject ret = new JSObject();
        ret.put("ok", true);
        ret.put("currentVersion", currentVersion());
        ret.put("pendingCleared", wasPending);
        call.resolve(ret);
    }

    @PluginMethod
    public void rollback(PluginCall call) {
        String previousId = store.getPreviousBundleId();
        File previousDir = previousId != null ? store.bundleDir(previousId) : null;
        JSObject ret = new JSObject();
        if (previousDir != null && previousDir.exists()) {
            String previousVersion = store.prefs().getString("st_previous_version", previousId);
            store.setCurrentBundleId(previousId);
            store.prefs().edit()
                    .putString("st_current_version", previousVersion)
                    .remove("st_previous")
                    .remove("st_previous_version")
                    .apply();
            store.setPending(false);
            store.setBootAttempts(0);
            store.persistCapacitorBasePath(previousDir.getAbsolutePath());
            ret.put("rolledBack", true);
            ret.put("toVersion", previousVersion);
            ret.put("requiresReload", true);
            call.resolve(ret);
            reloadLater(previousDir.getAbsolutePath());
            return;
        }
        store.clearState();
        store.clearCapacitorBasePath();
        ret.put("rolledBack", false);
        ret.put("toVersion", store.builtinVersion());
        ret.put("requiresReload", true);
        call.resolve(ret);
        reloadLater(null);
    }

    @PluginMethod
    public void reset(PluginCall call) {
        int cleared = clearAllBundles();
        store.clearState();
        store.clearCapacitorBasePath();
        store.setLastNote(null);
        JSObject ret = new JSObject();
        ret.put("cleared", cleared);
        ret.put("currentVersion", store.builtinVersion());
        call.resolve(ret);
        reloadLater(null);
    }

    @PluginMethod
    public void reload(PluginCall call) {
        call.resolve();
        getBridge().getWebView().postDelayed(() -> getBridge().reload(), 200);
    }

    // ------------------------------------------------------------ 工具

    @PluginMethod
    public void verifyBundle(PluginCall call) {
        String zipBase64 = call.getString("zipBase64");
        if (zipBase64 == null || zipBase64.isEmpty()) {
            call.reject("zipBase64 不能为空", "INVALID_ARGUMENT");
            return;
        }
        String expectedSha256 = call.getString("sha256");
        String signature = call.getString("signature");
        String publicKey = call.getString("publicKey", store.getPublicKey());
        long stamp = System.currentTimeMillis();
        File zipFile = new File(getContext().getCacheDir(), "lu-verify-" + stamp + ".zip");
        File extractDir = new File(getContext().getCacheDir(), "lu-verify-" + stamp);

        try {
            byte[] payload = android.util.Base64.decode(zipBase64, android.util.Base64.DEFAULT);
            try (java.io.FileOutputStream output = new java.io.FileOutputStream(zipFile)) {
                output.write(payload);
            }
            String actualSha256 = UpdateVerifier.sha256Hex(zipFile);
            boolean shaMatches = expectedSha256 == null || expectedSha256.equalsIgnoreCase(actualSha256);

            Boolean signatureValid = null;
            String note = null;
            if (signature != null && !signature.isEmpty() && publicKey != null && !publicKey.isEmpty()) {
                signatureValid = UpdateVerifier.verifyFileSignature(zipFile, signature, publicKey);
            } else if (!store.isAllowUnsigned()) {
                note = "没有提供 signature/publicKey，跳过验签";
            }

            int files = 0;
            long totalBytes = 0;
            List<String> entries = new java.util.ArrayList<>();
            if (shaMatches) {
                // 解压失败不应该让整个自检报错：把原因放进 note，仍然返回 sha/签名结论
                try {
                    UpdateInstaller.ExtractResult extracted = UpdateInstaller.extract(zipFile, extractDir);
                    files = extracted.files;
                    totalBytes = extracted.bytes;
                    collectEntries(extractDir, "", entries);
                } catch (Exception extractError) {
                    note = ((note == null) ? "" : note + "；") + extractError.getMessage();
                }
            }

            JSObject ret = new JSObject();
            ret.put("valid", shaMatches && !Boolean.FALSE.equals(signatureValid) && files > 0);
            ret.put("sha256", actualSha256);
            if (expectedSha256 == null) {
                ret.put("sha256Matches", null);
            } else {
                ret.put("sha256Matches", shaMatches);
            }
            if (signatureValid == null) {
                ret.put("signatureValid", null);
            } else {
                ret.put("signatureValid", signatureValid);
            }
            ret.put("files", files);
            ret.put("bytes", totalBytes);
            ret.put("entries", new JSArray(new java.util.ArrayList<>(entries)));
            if (note != null) {
                ret.put("note", note);
            }
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("VERIFY_FAILED: " + e.getMessage(), e);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            zipFile.delete();
            UpdateStore.deleteRecursively(extractDir);
        }
    }

    private void collectEntries(File dir, String prefix, List<String> out) {
        File[] children = dir.listFiles();
        if (children == null || out.size() >= 100) {
            return;
        }
        for (File child : children) {
            if (out.size() >= 100) {
                return;
            }
            String relative = prefix.isEmpty() ? child.getName() : prefix + "/" + child.getName();
            if (child.isDirectory()) {
                collectEntries(child, relative, out);
            } else {
                out.add(relative);
            }
        }
    }

    private int clearAllBundles() {
        int count = 0;
        for (File dir : store.listBundleDirs()) {
            UpdateStore.deleteRecursively(dir);
            count++;
        }
        return count;
    }

    private String currentVersion() {
        String currentId = store.getCurrentBundleId();
        File dir = currentId != null ? store.bundleDir(currentId) : null;
        if (dir != null && dir.exists()) {
            return store.prefs().getString("st_current_version", currentId);
        }
        return store.builtinVersion();
    }

    private String sanitizeVersion(String version) {
        return version.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /** 语义化版本比较：数字段按数值比，非数字段按字符串比；`1.2.0` > `1.2.0-beta`。 */
    static int compareVersions(String left, String right) {
        if (left == null) return -1;
        if (right == null) return 1;
        String[] a = left.split("[.\\-+]");
        String[] b = right.split("[.\\-+]");
        int length = Math.max(a.length, b.length);
        for (int i = 0; i < length; i++) {
            String partA = i < a.length ? a[i] : "";
            String partB = i < b.length ? b[i] : "";
            if (partA.equals(partB)) {
                continue;
            }
            boolean numericA = partA.matches("\\d+");
            boolean numericB = partB.matches("\\d+");
            if (numericA && numericB) {
                long valueA = Long.parseLong(partA);
                long valueB = Long.parseLong(partB);
                if (valueA != valueB) {
                    return Long.compare(valueA, valueB);
                }
            } else {
                // 数字段比字母段"新"（1.0.0 > 1.0.0-beta）
                if (numericA) return 1;
                if (numericB) return -1;
                int result = partA.compareTo(partB);
                if (result != 0) {
                    return result;
                }
            }
        }
        return 0;
    }
}
