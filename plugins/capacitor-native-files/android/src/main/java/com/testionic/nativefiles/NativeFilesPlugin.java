package com.testionic.nativefiles;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;
import android.webkit.MimeTypeMap;

import androidx.activity.result.ActivityResult;
import androidx.core.content.ContextCompat;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * NativeFiles 插件（Android 侧，jsName = "NativeFiles"）。
 *
 * ============================ 一、路径语义：真实绝对路径 ============================
 *
 * 本插件**所有 API 都用真实文件系统绝对路径**（如 `/storage/emulated/0/Notes/a.md`），
 * 不用 `content://`。这样前端可以直接拼路径、算父目录/子目录（Markdown 阅读器、笔记树等场景的刚需）。
 *
 * 系统选择器返回的是 `content://` URI，插件会把它解析成真实路径：
 *   1) `file://` → 直接取 path；
 *   2) DocumentsProvider（`com.android.externalstorage.documents`）：`primary:Download/x`
 *      → `/storage/emulated/0/Download/x`；`home:` → `Documents/`；`xxxx-xxxx:` → `/storage/<uuid>/...`；
 *   3) MediaStore：查询 `_data` 列；
 *   4) 其它 provider（云盘 / U 盘 / 第三方 SAF）**无法映射成路径** → 明确 reject（附原因）。
 *
 * ============================ 二、权限：Android 版本差异 ============================
 *
 * 用真实路径读写任意目录，权限要求随系统版本变化（实测与官方文档一致）：
 *
 * | 版本 | 需要 |
 * |------|------|
 * | Android ≤ 9（API ≤ 28） | `WRITE_EXTERNAL_STORAGE`（运行时权限） |
 * | Android 10（API 29） | `WRITE_EXTERNAL_STORAGE` + 宿主 manifest 的 `requestLegacyExternalStorage="true"` |
 * | **Android 11+（API 30+）** | **`MANAGE_EXTERNAL_STORAGE`**（所有文件访问权限，需要在系统设置里手动开） |
 *
 * 所以：`requestAllFilesAccess()` 在 API 30+ 会打开"所有文件访问权限"设置页；API ≤ 29 则走运行时权限。
 * 没有权限时读写公共目录会抛 `EACCES`，插件会把错误包装成带 `requestAllFilesAccess()` 提示的信息。
 *
 * ============================ 三、App 私有目录免权限 ============================
 *
 * `getAppExternalFilesDir()`（`/storage/emulated/0/Android/data/<pkg>/files`）、
 * `appFilesDir`、`appCacheDir` 等属于 App 自己的目录，**任何版本都免权限**，适合不需要用户授权的数据。
 */
@CapacitorPlugin(
    name = "NativeFiles",
    permissions = {
        @Permission(alias = "storage", strings = { Manifest.permission.WRITE_EXTERNAL_STORAGE })
    }
)
public class NativeFilesPlugin extends Plugin {

    private static final String TAG = "NativeFiles";
    private static final long DEFAULT_READ_LIMIT = 16L * 1024 * 1024; // 单次读上限 16MB（base64 走桥）

    // ================================================================ 选择器

    @PluginMethod
    public void pickFiles(PluginCall call) {
        Boolean multiple = call.getBoolean("multiple", false);
        JSArray mimeTypes = call.getArray("mimeTypes");

        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        if (Boolean.TRUE.equals(multiple)) {
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        }
        if (mimeTypes != null && mimeTypes.length() > 0) {
            List<String> types = new ArrayList<>();
            for (int i = 0; i < mimeTypes.length(); i++) {
                try {
                    types.add(mimeTypes.getString(i));
                } catch (Exception ignored) {
                    // 忽略非法项
                }
            }
            if (!types.isEmpty()) {
                intent.putExtra(Intent.EXTRA_MIME_TYPES, types.toArray(new String[0]));
            }
        }
        startActivityForResult(call, intent, "pickFilesResult");
    }

    @ActivityCallback
    private void pickFilesResult(PluginCall call, ActivityResult result) {
        if (call == null) {
            return;
        }
        if (result.getResultCode() != Activity.RESULT_OK) {
            JSObject ret = new JSObject();
            ret.put("files", new JSArray());
            ret.put("cancelled", true);
            call.resolve(ret);
            return;
        }
        Intent data = result.getData();
        List<Uri> uris = new ArrayList<>();
        if (data != null) {
            ClipData clip = data.getClipData();
            if (clip != null) {
                for (int i = 0; i < clip.getItemCount(); i++) {
                    if (clip.getItemAt(i).getUri() != null) {
                        uris.add(clip.getItemAt(i).getUri());
                    }
                }
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
        }

        JSArray files = new JSArray();
        List<String> unmapped = new ArrayList<>();
        for (Uri uri : uris) {
            String path = resolvePathFromUri(uri);
            if (path == null) {
                unmapped.add(uri.toString());
                continue;
            }
            JSObject file = fileEntry(new File(path));
            files.put(file);
        }
        JSObject ret = new JSObject();
        ret.put("files", files);
        ret.put("cancelled", false);
        if (!unmapped.isEmpty()) {
            // 部分 provider（云盘等）无法映射成真实路径：明确告知，而不是静默丢弃
            JSArray unmappedJson = new JSArray();
            for (String item : unmapped) {
                unmappedJson.put(item);
            }
            ret.put("unmapped", unmappedJson);
            ret.put(
                "warning",
                "some picked items are not backed by a real filesystem path (cloud/SAF provider); " +
                "they were skipped. Pick from internal storage or SD card to get absolute paths."
            );
        }
        call.resolve(ret);
    }

    @PluginMethod
    public void pickFolders(PluginCall call) {
        // Android SAF 的系统限制：文件夹选择器只能单选
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION |
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        );
        startActivityForResult(call, intent, "pickFoldersResult");
    }

    @ActivityCallback
    private void pickFoldersResult(PluginCall call, ActivityResult result) {
        if (call == null) {
            return;
        }
        JSObject ret = new JSObject();
        JSArray folders = new JSArray();
        ret.put("folders", folders);
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
            ret.put("cancelled", true);
            call.resolve(ret);
            return;
        }
        Uri uri = result.getData().getData();
        if (uri == null) {
            ret.put("cancelled", true);
            call.resolve(ret);
            return;
        }
        String path = resolvePathFromUri(uri);
        if (path == null) {
            call.reject(
                "PICKED_FOLDER_HAS_NO_FILESYSTEM_PATH: this folder is provided by a cloud/SAF provider " +
                    "(not internal storage). Pick a folder under internal storage or SD card."
            );
            return;
        }
        folders.put(dirEntry(new File(path)));
        ret.put("folders", folders);
        ret.put("cancelled", false);
        call.resolve(ret);
    }

    // ================================================================ 文件读写

    @PluginMethod
    public void readFile(PluginCall call) {
        File file = requiredFile(call);
        if (file == null) {
            return;
        }
        // 注意：Capacitor 的 PluginCall.getLong() 只接受 Long 类型的 JSON 值，
        // 而 JS 传小整数时 org.json 会解析成 Integer → getLong 会**静默返回默认值**。
        // 所以这里统一用 readLong() 从 Number/String 转换。
        long offset = readLong(call, "offset", 0L);
        long length = readLong(call, "length", DEFAULT_READ_LIMIT);
        if (length <= 0 || length > DEFAULT_READ_LIMIT) {
            length = DEFAULT_READ_LIMIT;
        }
        if (!file.isFile()) {
            call.reject("NOT_A_FILE: " + file.getAbsolutePath());
            return;
        }
        // 用 RandomAccessFile.seek() 而不是 FileInputStream.skip()：
        // skip 在 offset 很大时是 O(offset)（实测分块读 4MB/512KB 块只有 6.4MB/s，整文件却有 10.9MB/s）。
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            raf.seek(Math.min(offset, raf.length()));
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[64 * 1024];
            long remaining = length;
            while (remaining > 0) {
                int toRead = (int) Math.min(chunk.length, remaining);
                int read = raf.read(chunk, 0, toRead);
                if (read < 0) {
                    break;
                }
                buffer.write(chunk, 0, read);
                remaining -= read;
            }
            byte[] bytes = buffer.toByteArray();
            JSObject ret = new JSObject();
            ret.put("path", file.getAbsolutePath());
            ret.put("base64", Base64.encodeToString(bytes, Base64.NO_WRAP));
            ret.put("bytes", bytes.length);
            ret.put("size", file.length());
            ret.put("offset", offset);
            call.resolve(ret);
        } catch (SecurityException e) {
            call.reject(permissionHint("read " + file.getAbsolutePath(), e));
        } catch (Exception e) {
            call.reject("READ_FAILED: " + e.getMessage());
        }
    }

    @PluginMethod
    public void writeFile(PluginCall call) {
        File file = requiredFile(call);
        if (file == null) {
            return;
        }
        String base64 = call.getString("base64");
        String mode = call.getString("mode", "overwrite");
        if (base64 == null) {
            call.reject("missing base64");
            return;
        }
        byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
        boolean append = "append".equals(mode);
        boolean createOnly = "create".equals(mode);

        try {
            if (createOnly && file.exists()) {
                call.reject("FILE_ALREADY_EXISTS: " + file.getAbsolutePath());
                return;
            }
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                call.reject("CANNOT_CREATE_PARENT: " + parent.getAbsolutePath());
                return;
            }
            try (OutputStream out = new FileOutputStream(file, append)) {
                out.write(bytes);
                out.flush();
            }
            JSObject ret = new JSObject();
            ret.put("path", file.getAbsolutePath());
            ret.put("bytes", bytes.length);
            ret.put("size", file.length());
            call.resolve(ret);
        } catch (SecurityException e) {
            call.reject(permissionHint("write " + file.getAbsolutePath(), e));
        } catch (Exception e) {
            call.reject("WRITE_FAILED: " + e.getMessage());
        }
    }

    @PluginMethod
    public void deleteFile(PluginCall call) {
        File file = requiredFile(call);
        if (file == null) {
            return;
        }
        try {
            if (!file.exists()) {
                JSObject ret = new JSObject();
                ret.put("deleted", false);
                ret.put("reason", "NOT_FOUND");
                call.resolve(ret);
                return;
            }
            if (file.isDirectory()) {
                call.reject("IS_A_DIRECTORY: use deleteFolder() for directories");
                return;
            }
            boolean deleted = file.delete();
            JSObject ret = new JSObject();
            ret.put("deleted", deleted);
            call.resolve(ret);
        } catch (SecurityException e) {
            call.reject(permissionHint("delete " + file.getAbsolutePath(), e));
        }
    }

    // ================================================================ 文件夹

    @PluginMethod
    public void listFolder(PluginCall call) {
        File dir = requiredFile(call);
        if (dir == null) {
            return;
        }
        if (!dir.isDirectory()) {
            call.reject("NOT_A_DIRECTORY: " + dir.getAbsolutePath());
            return;
        }
        Boolean recursiveOption = call.getBoolean("recursive", false);
        boolean recursive = Boolean.TRUE.equals(recursiveOption);
        int maxEntries = Math.max(1, Math.min(20000, call.getInt("maxEntries", 5000)));

        JSArray entries = new JSArray();
        try {
            collectEntries(dir, recursive, entries, maxEntries);
        } catch (SecurityException e) {
            call.reject(permissionHint("list " + dir.getAbsolutePath(), e));
            return;
        }
        JSObject ret = new JSObject();
        ret.put("path", dir.getAbsolutePath());
        ret.put("entries", entries);
        ret.put("truncated", entries.length() >= maxEntries);
        call.resolve(ret);
    }

    private void collectEntries(File dir, boolean recursive, JSArray out, int maxEntries) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        List<File> sorted = new ArrayList<>();
        for (File child : children) {
            sorted.add(child);
        }
        sorted.sort((a, b) -> {
            boolean da = a.isDirectory();
            boolean db = b.isDirectory();
            if (da != db) {
                return da ? -1 : 1;
            }
            return a.getName().compareToIgnoreCase(b.getName());
        });
        for (File child : sorted) {
            if (out.length() >= maxEntries) {
                return;
            }
            out.put(child.isDirectory() ? dirEntry(child) : fileEntry(child));
            if (recursive && child.isDirectory()) {
                collectEntries(child, true, out, maxEntries);
            }
        }
    }

    @PluginMethod
    public void createFolder(PluginCall call) {
        File dir = requiredFile(call);
        if (dir == null) {
            return;
        }
        try {
            if (dir.exists()) {
                if (dir.isDirectory()) {
                    JSObject ret = new JSObject();
                    ret.put("path", dir.getAbsolutePath());
                    ret.put("created", false);
                    call.resolve(ret);
                    return;
                }
                call.reject("PATH_EXISTS_AS_FILE: " + dir.getAbsolutePath());
                return;
            }
            boolean created = dir.mkdirs();
            JSObject ret = new JSObject();
            ret.put("path", dir.getAbsolutePath());
            ret.put("created", created);
            if (!created) {
                call.reject("CREATE_FOLDER_FAILED: " + dir.getAbsolutePath());
                return;
            }
            call.resolve(ret);
        } catch (SecurityException e) {
            call.reject(permissionHint("create " + dir.getAbsolutePath(), e));
        }
    }

    @PluginMethod
    public void deleteFolder(PluginCall call) {
        File dir = requiredFile(call);
        if (dir == null) {
            return;
        }
        if (!dir.isDirectory()) {
            call.reject("NOT_A_DIRECTORY: " + dir.getAbsolutePath());
            return;
        }
        Boolean recursiveOption = call.getBoolean("recursive", false);
        boolean recursive = Boolean.TRUE.equals(recursiveOption);
        try {
            boolean deleted = recursive ? deleteRecursively(dir) : dir.delete();
            JSObject ret = new JSObject();
            ret.put("deleted", deleted);
            if (!deleted && !recursive) {
                ret.put("reason", "NOT_EMPTY_OR_FAILED");
                ret.put("hint", "pass recursive: true to delete non-empty directories");
            }
            call.resolve(ret);
        } catch (SecurityException e) {
            call.reject(permissionHint("delete " + dir.getAbsolutePath(), e));
        }
    }

    private boolean deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        return file.delete();
    }

    @PluginMethod
    public void renameFolder(PluginCall call) {
        File source = requiredFile(call);
        if (source == null) {
            return;
        }
        String newName = call.getString("newName");
        if (newName == null || newName.isEmpty()) {
            call.reject("missing newName");
            return;
        }
        if (newName.contains("/") || newName.contains("\\") || newName.equals("..") || newName.equals(".")) {
            call.reject("INVALID_NAME: newName must be a simple file/folder name");
            return;
        }
        File parent = source.getParentFile();
        if (parent == null) {
            call.reject("CANNOT_RESOLVE_PARENT: " + source.getAbsolutePath());
            return;
        }
        File target = new File(parent, newName);
        if (target.exists()) {
            call.reject("TARGET_EXISTS: " + target.getAbsolutePath());
            return;
        }
        try {
            boolean renamed = source.renameTo(target);
            JSObject ret = new JSObject();
            ret.put("renamed", renamed);
            ret.put("path", target.getAbsolutePath());
            ret.put("from", source.getAbsolutePath());
            if (!renamed) {
                call.reject("RENAME_FAILED: " + source.getAbsolutePath() + " -> " + target.getAbsolutePath());
                return;
            }
            call.resolve(ret);
        } catch (SecurityException e) {
            call.reject(permissionHint("rename " + source.getAbsolutePath(), e));
        }
    }

    // ================================================================ 信息

    /** `getInfo({ path })` → 单个文件/目录的详细信息（data-channel 原来的 `/info` 路由）。 */
    @PluginMethod
    public void getInfo(PluginCall call) {
        File file = requiredFile(call);
        if (file == null) {
            return;
        }
        JSObject ret = new JSObject();
        ret.put("path", file.getAbsolutePath());
        ret.put("name", file.getName());
        ret.put("exists", file.exists());
        ret.put("isDirectory", file.isDirectory());
        ret.put("isFile", file.isFile());
        ret.put("size", file.isFile() ? file.length() : 0);
        ret.put("lastModified", file.lastModified());
        ret.put("readable", file.canRead());
        ret.put("writable", file.canWrite());
        ret.put("mimeType", file.isFile() ? guessMimeType(file.getName()) : "inode/directory");
        call.resolve(ret);
    }

    // ================================================================ 权限：所有文件访问

    @PluginMethod
    public void checkAllFilesAccess(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("granted", hasAllFilesAccess());
        ret.put("androidVersion", Build.VERSION.SDK_INT);
        ret.put("requiresSettings", Build.VERSION.SDK_INT >= Build.VERSION_CODES.R);
        ret.put("permission", Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
            ? "MANAGE_EXTERNAL_STORAGE"
            : "WRITE_EXTERNAL_STORAGE");
        call.resolve(ret);
    }

    /**
     * 申请"所有文件访问权限"。
     *
     * - Android 11+：`Environment.isExternalStorageManager()`；未授权则打开系统设置页（用户手动开），
     *   返回后通过 @ActivityCallback 再检查并 resolve `{ granted }`；
     * - Android ≤ 10：请求 `WRITE_EXTERNAL_STORAGE` 运行时权限。
     */
    @PluginMethod
    public void requestAllFilesAccess(PluginCall call) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                JSObject ret = new JSObject();
                ret.put("granted", true);
                ret.put("androidVersion", Build.VERSION.SDK_INT);
                call.resolve(ret);
                return;
            }
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + getContext().getPackageName()));
                startActivityForResult(call, intent, "allFilesAccessResult");
            } catch (Exception e) {
                // 某些 ROM 没有按包名的入口，退回到总列表
                try {
                    Intent fallback = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    startActivityForResult(call, fallback, "allFilesAccessResult");
                } catch (Exception e2) {
                    call.reject("CANNOT_OPEN_SETTINGS: " + e2.getMessage());
                }
            }
            return;
        }
        // API ≤ 29：运行时权限
        if (hasAllFilesAccess()) {
            JSObject ret = new JSObject();
            ret.put("granted", true);
            ret.put("androidVersion", Build.VERSION.SDK_INT);
            call.resolve(ret);
            return;
        }
        requestPermissionForAlias("storage", call, "storagePermissionCallback");
    }

    @PermissionCallback
    private void storagePermissionCallback(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("granted", getPermissionState("storage") == PermissionState.GRANTED);
        ret.put("androidVersion", Build.VERSION.SDK_INT);
        call.resolve(ret);
    }

    @ActivityCallback
    private void allFilesAccessResult(PluginCall call, ActivityResult result) {
        if (call == null) {
            return;
        }
        JSObject ret = new JSObject();
        ret.put("granted", Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager());
        ret.put("androidVersion", Build.VERSION.SDK_INT);
        call.resolve(ret);
    }

    private boolean hasAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return ContextCompat.checkSelfPermission(getContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE)
            == PackageManager.PERMISSION_GRANTED;
    }

    // ================================================================ 单条路径查询
    //
    // 设计：**一个方法只查一条路径**。好处：
    //  1) 只想要一个路径时，不必等其它路径都查完；
    //  2) 某条路径取不到（不存在 / 平台不支持）不影响其它调用；
    //  3) 每个方法都在 README 里单独说明含义。
    //
    // 统一返回 `{ path: string | null, reason?: string }`：path 为 null 表示"该平台没有这个目录"，
    // reason 说明原因。**不会因为目录不存在而 reject**（路径本来就是用来拼子路径的，不一定已存在）。

    private void resolvePath(PluginCall call, String path, String reasonIfNull) {
        JSObject ret = new JSObject();
        if (path == null) {
            ret.put("path", JSONObject.NULL);
            ret.put("reason", reasonIfNull == null ? "NOT_AVAILABLE" : reasonIfNull);
        } else {
            ret.put("path", path);
        }
        call.resolve(ret);
    }

    /** App 私有内部文件目录：`/data/user/0/<pkg>/files`（任何版本免权限，不随卸载保留）。 */
    @PluginMethod
    public void getAppFilesDir(PluginCall call) {
        resolvePath(call, pathOf(getContext().getFilesDir()), null);
    }

    /** App 私有内部缓存目录：`/data/user/0/<pkg>/cache`（系统可能随时清理）。 */
    @PluginMethod
    public void getAppCacheDir(PluginCall call) {
        resolvePath(call, pathOf(getContext().getCacheDir()), null);
    }

    /** 临时目录：Android 没有独立 tmp，返回 `cache` 目录。 */
    @PluginMethod
    public void getAppTempDir(PluginCall call) {
        resolvePath(call, pathOf(getContext().getCacheDir()), null);
    }

    /**
     * App 外部私有文件目录：`/storage/emulated/0/Android/data/<pkg>/files`。
     *
     * 免权限、卸载时删除；适合放"用户可见但属于 App 的大文件"。
     */
    @PluginMethod
    public void getAppExternalFilesDir(PluginCall call) {
        resolvePath(call, pathOf(getContext().getExternalFilesDir(null)), "EXTERNAL_STORAGE_NOT_MOUNTED");
    }

    /** App 外部私有缓存目录：`/storage/emulated/0/Android/data/<pkg>/cache`。 */
    @PluginMethod
    public void getAppExternalCacheDir(PluginCall call) {
        resolvePath(call, pathOf(getContext().getExternalCacheDir()), "EXTERNAL_STORAGE_NOT_MOUNTED");
    }

    /**
     * App 专属的"文档"目录：`/storage/emulated/0/Android/data/<pkg>/files/Documents`。
     *
     * 注意与公共的 `getSharedDocumentsDir()`（`/storage/emulated/0/Documents`）区分：
     * 这个是 App 独占、免权限、卸载即删；那个是用户可见的公共目录。
     */
    @PluginMethod
    public void getAppDocumentsDir(PluginCall call) {
        resolvePath(
            call,
            pathOf(getContext().getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)),
            "EXTERNAL_STORAGE_NOT_MOUNTED"
        );
    }

    /**
     * 共享存储根目录：`/storage/emulated/0`。
     *
     * Android 11+ 读写其中任意位置需要"所有文件访问权限"（`requestAllFilesAccess()`）。
     */
    @PluginMethod
    public void getSharedStorageDir(PluginCall call) {
        resolvePath(call, pathOf(Environment.getExternalStorageDirectory()), "EXTERNAL_STORAGE_NOT_MOUNTED");
    }

    /** 系统下载目录：`/storage/emulated/0/Download`（SAF 选择器不允许把该目录本身作为授权目标）。 */
    @PluginMethod
    public void getDownloadsDir(PluginCall call) {
        resolvePath(call, publicDir(Environment.DIRECTORY_DOWNLOADS), null);
    }

    /** 系统文档目录：`/storage/emulated/0/Documents`（公共目录，建议作为笔记库根）。 */
    @PluginMethod
    public void getSharedDocumentsDir(PluginCall call) {
        resolvePath(call, publicDir(Environment.DIRECTORY_DOCUMENTS), null);
    }

    /** 系统图片目录：`/storage/emulated/0/Pictures`。 */
    @PluginMethod
    public void getPicturesDir(PluginCall call) {
        resolvePath(call, publicDir(Environment.DIRECTORY_PICTURES), null);
    }

    /** 系统相机目录：`/storage/emulated/0/DCIM`。 */
    @PluginMethod
    public void getDcimDir(PluginCall call) {
        resolvePath(call, publicDir(Environment.DIRECTORY_DCIM), null);
    }

    /** 系统视频目录：`/storage/emulated/0/Movies`。 */
    @PluginMethod
    public void getMoviesDir(PluginCall call) {
        resolvePath(call, publicDir(Environment.DIRECTORY_MOVIES), null);
    }

    /** 系统音乐目录：`/storage/emulated/0/Music`。 */
    @PluginMethod
    public void getMusicDir(PluginCall call) {
        resolvePath(call, publicDir(Environment.DIRECTORY_MUSIC), null);
    }

    /** 系统闹钟铃声目录：`/storage/emulated/0/Alarms`。 */
    @PluginMethod
    public void getAlarmsDir(PluginCall call) {
        resolvePath(call, publicDir(Environment.DIRECTORY_ALARMS), null);
    }

    /** 系统通知铃声目录：`/storage/emulated/0/Notifications`。 */
    @PluginMethod
    public void getNotificationsDir(PluginCall call) {
        resolvePath(call, publicDir(Environment.DIRECTORY_NOTIFICATIONS), null);
    }

    /** 系统播客目录：`/storage/emulated/0/Podcasts`。 */
    @PluginMethod
    public void getPodcastsDir(PluginCall call) {
        resolvePath(call, publicDir(Environment.DIRECTORY_PODCASTS), null);
    }

    /** 系统铃声目录：`/storage/emulated/0/Ringtones`。 */
    @PluginMethod
    public void getRingtonesDir(PluginCall call) {
        resolvePath(call, publicDir(Environment.DIRECTORY_RINGTONES), null);
    }

    /**
     * App 私有数据根目录。
     *
     * - Android：`/data/user/0/<pkg>`（即 `applicationInfo.dataDir`）
     * - iOS：沙盒根 `NSHomeDirectory()`
     */
    @PluginMethod
    public void getAppDataDir(PluginCall call) {
        resolvePath(call, getContext().getApplicationInfo().dataDir, null);
    }

    /**
     * App 外部私有根目录：`/storage/emulated/0/Android/data/<pkg>`。
     *
     * 比 `getAppExternalFilesDir()` 高一层（`files` / `cache` 都在它下面）。
     */
    @PluginMethod
    public void getAppExternalRootDir(PluginCall call) {
        File files = getContext().getExternalFilesDir(null);
        resolvePath(call, files == null ? null : pathOf(files.getParentFile()), "EXTERNAL_STORAGE_NOT_MOUNTED");
    }

    /** native 库目录（Android：APK 里的 `lib/<abi>`；iOS 没有该概念，返回 null）。 */
    @PluginMethod
    public void getNativeLibDir(PluginCall call) {
        resolvePath(call, getContext().getApplicationInfo().nativeLibraryDir, null);
    }

    /** `Library` 目录：iOS 专有概念，Android 返回 null。 */
    @PluginMethod
    public void getLibraryDir(PluginCall call) {
        resolvePath(call, null, "NOT_APPLICABLE_ON_ANDROID");
    }

    /** iCloud Documents 目录：iOS 专有概念，Android 返回 null。 */
    @PluginMethod
    public void getIcloudDocumentsDir(PluginCall call) {
        resolvePath(call, null, "NOT_APPLICABLE_ON_ANDROID");
    }

    /** App 安装包路径（Android：APK 文件；iOS：`.app` 包目录）。 */
    @PluginMethod
    public void getAppInstallPath(PluginCall call) {
        resolvePath(call, getContext().getApplicationInfo().sourceDir, null);
    }

    private String publicDir(String type) {
        File dir = Environment.getExternalStoragePublicDirectory(type);
        return dir == null ? null : dir.getAbsolutePath();
    }

    private String pathOf(File file) {
        return file == null ? null : file.getAbsolutePath();
    }

    // ================================================================ 工具

    /**
     * 读取数字参数。
     *
     * Capacitor 的 `PluginCall.getLong()` 只接受 `Long` 类型的 JSON 值；JS 传小整数时
     * org.json 会解析成 `Integer` → 会**静默返回默认值**（我们就是被这个坑过：
     * `readFile` 的 offset/length 一直没生效）。这里统一从 `getData()` 取 Number/String 转换。
     */
    private long readLong(PluginCall call, String key, long defaultValue) {
        Object raw = call.getData().opt(key);
        if (raw instanceof Number) {
            return ((Number) raw).longValue();
        }
        if (raw instanceof String) {
            try {
                return Long.parseLong(((String) raw).trim());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    /** 取 `path` 参数并做基本校验（必须是绝对路径、不能含 NUL）。 */
    private File requiredFile(PluginCall call) {        String path = call.getString("path");
        if (path == null || path.trim().isEmpty()) {
            call.reject("missing path");
            return null;
        }
        String trimmed = path.trim();
        if (trimmed.indexOf('\0') >= 0) {
            call.reject("invalid path");
            return null;
        }
        // 兼容 file:// 前缀（方便和 data-channel 的下载结果拼接）
        if (trimmed.startsWith("file://")) {
            trimmed = trimmed.substring("file://".length());
        }
        if (!trimmed.startsWith("/")) {
            call.reject("PATH_MUST_BE_ABSOLUTE: " + path);
            return null;
        }
        return new File(trimmed);
    }

    private JSObject fileEntry(File file) {
        JSObject entry = new JSObject();
        entry.put("path", file.getAbsolutePath());
        entry.put("name", file.getName());
        entry.put("isDirectory", false);
        entry.put("size", file.length());
        entry.put("lastModified", file.lastModified());
        entry.put("mimeType", guessMimeType(file.getName()));
        return entry;
    }

    private JSObject dirEntry(File dir) {
        JSObject entry = new JSObject();
        entry.put("path", dir.getAbsolutePath());
        entry.put("name", dir.getName());
        entry.put("isDirectory", true);
        entry.put("size", 0);
        entry.put("lastModified", dir.lastModified());
        entry.put("mimeType", "inode/directory");
        return entry;
    }

    private String guessMimeType(String name) {
        if (name == null) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return mime == null ? "" : mime;
    }

    private String permissionHint(String action, Exception e) {
        String base = "PERMISSION_DENIED: cannot " + action + " (" + e.getMessage() + ")";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return base + ". Grant \"All files access\" via NativeFiles.requestAllFilesAccess() first.";
        }
        return base + ". Grant storage permission via NativeFiles.requestAllFilesAccess() first.";
    }

    /**
     * 把系统选择器给的 URI 解析成真实文件系统绝对路径。
     *
     * 支持：`file://`、externalstorage DocumentsProvider（primary:/home:/卷UUID:）、MediaStore（_data）。
     * 其它 provider（云盘 / 第三方 SAF）没有 1:1 文件路径，返回 null。
     */
    private String resolvePathFromUri(Uri uri) {
        if (uri == null) {
            return null;
        }
        String scheme = uri.getScheme();
        if ("file".equalsIgnoreCase(scheme)) {
            return uri.getPath();
        }
        if (!ContentResolver.SCHEME_CONTENT.equalsIgnoreCase(scheme)) {
            return null;
        }

        String documentId = null;
        try {
            if (DocumentsContract.isTreeUri(uri)) {
                documentId = DocumentsContract.getTreeDocumentId(uri);
            } else if (DocumentsContract.isDocumentUri(getContext(), uri)) {
                documentId = DocumentsContract.getDocumentId(uri);
            }
        } catch (Exception ignored) {
            // 不是 DocumentsProvider
        }
        if (documentId != null) {
            String path = documentIdToPath(documentId);
            if (path != null) {
                return path;
            }
        }

        // MediaStore 等 provider：查 _data
        try (Cursor cursor = getContext().getContentResolver().query(
            uri,
            new String[] { MediaStore.MediaColumns.DATA },
            null,
            null,
            null
        )) {
            if (cursor != null && cursor.moveToFirst()) {
                String data = cursor.getString(0);
                if (data != null && !data.isEmpty()) {
                    return data;
                }
            }
        } catch (Exception ignored) {
            // 忽略
        }
        return null;
    }

    /** `primary:Download/x` / `home:` / `1A2B-3C4D:Music` → 绝对路径。 */
    private String documentIdToPath(String documentId) {
        if (documentId == null || documentId.isEmpty()) {
            return null;
        }
        if (documentId.startsWith("raw:")) {
            return documentId.substring("raw:".length());
        }
        int colon = documentId.indexOf(':');
        String volume = colon >= 0 ? documentId.substring(0, colon) : "primary";
        String relative = colon >= 0 ? documentId.substring(colon + 1) : documentId;

        if ("home".equalsIgnoreCase(volume)) {
            File documents = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
            return relative.isEmpty() ? documents.getAbsolutePath() : new File(documents, relative).getAbsolutePath();
        }
        if ("primary".equalsIgnoreCase(volume)) {
            File root = Environment.getExternalStorageDirectory();
            return relative.isEmpty() ? root.getAbsolutePath() : new File(root, relative).getAbsolutePath();
        }
        // 可移动存储：/storage/<UUID>
        File volumeRoot = new File("/storage/" + volume);
        if (volumeRoot.isDirectory()) {
            return relative.isEmpty() ? volumeRoot.getAbsolutePath() : new File(volumeRoot, relative).getAbsolutePath();
        }
        return null;
    }
}
