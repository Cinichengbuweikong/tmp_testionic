package com.testionic.nativefiles;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.util.Log;

import androidx.activity.result.ActivityResult;
import androidx.documentfile.provider.DocumentFile;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * NativeFiles 插件（Android 侧，jsName = "NativeFiles"）。
 *
 * 基于 **SAF（Storage Access Framework）**：
 *  - 文件选择：`ACTION_OPEN_DOCUMENT`（支持多选）；
 *  - 文件夹选择：`ACTION_OPEN_DOCUMENT_TREE`（**系统只支持单选**）；
 *  - 读写/删除/列目录/新建/重命名都通过 `DocumentFile` + `ContentResolver` 完成，
 *    **不需要任何存储权限**（权限来自用户在选择器里的授权）。
 *
 * URI 约定：Android 返回 `content://...`，后续所有 API 的 `uri` 参数都用它。
 * iOS 返回 `file://...`（下同）。
 */
@CapacitorPlugin(name = "NativeFiles")
public class NativeFilesPlugin extends Plugin {

    private static final String TAG = "NativeFiles";
    private static final long DEFAULT_READ_LIMIT = 16L * 1024 * 1024; // 单次读上限 16MB（base64 走桥）

    // ---------------------------------------------------------------- 选择器

    @PluginMethod
    public void pickFiles(PluginCall call) {
        Boolean multiple = call.getBoolean("multiple", false);
        JSArray mimeTypes = call.getArray("mimeTypes");

        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        if (multiple != null && multiple) {
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
            call.resolve(cancelledFiles());
            return;
        }
        Intent data = result.getData();
        List<Uri> uris = new ArrayList<>();
        if (data != null) {
            ClipData clip = data.getClipData();
            if (clip != null) {
                for (int i = 0; i < clip.getItemCount(); i++) {
                    ClipData.Item item = clip.getItemAt(i);
                    if (item.getUri() != null) {
                        uris.add(item.getUri());
                    }
                }
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
            persistPermissions(data, uris);
        }

        JSArray files = new JSArray();
        for (Uri uri : uris) {
            JSObject file = new JSObject();
            file.put("uri", uri.toString());
            String[] meta = queryNameAndSize(uri);
            file.put("name", meta[0]);
            file.put("size", Long.parseLong(meta[1]));
            file.put("mimeType", meta[2]);
            files.put(file);
        }
        JSObject ret = new JSObject();
        ret.put("files", files);
        ret.put("cancelled", false);
        call.resolve(ret);
    }

    @PluginMethod
    public void pickFolders(PluginCall call) {
        // Android 的 SAF 不支持一次选择多个文件夹（系统限制）
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
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
            JSObject ret = new JSObject();
            ret.put("folders", new JSArray());
            ret.put("cancelled", true);
            call.resolve(ret);
            return;
        }
        Uri uri = result.getData().getData();
        if (uri == null) {
            JSObject ret = new JSObject();
            ret.put("folders", new JSArray());
            ret.put("cancelled", true);
            call.resolve(ret);
            return;
        }
        int flags = result.getData().getFlags() &
            (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            getContext().getContentResolver().takePersistableUriPermission(uri, flags);
        } catch (Exception ignored) {
            // 某些 provider 不支持持久化，忽略
        }
        DocumentFile dir = DocumentFile.fromTreeUri(getContext(), uri);
        JSArray folders = new JSArray();
        if (dir != null) {
            folders.put(toEntry(dir, uri.toString()));
        }
        JSObject ret = new JSObject();
        ret.put("folders", folders);
        ret.put("cancelled", false);
        call.resolve(ret);
    }

    // ---------------------------------------------------------------- 文件读写

    @PluginMethod
    public void readFile(PluginCall call) {
        String uriString = call.getString("uri");
        if (uriString == null || uriString.isEmpty()) {
            call.reject("missing uri");
            return;
        }
        long offset = call.getLong("offset", 0L) == null ? 0L : call.getLong("offset", 0L);
        Long lengthOption = call.getLong("length", null);
        long length = lengthOption == null ? DEFAULT_READ_LIMIT : lengthOption;
        if (length <= 0 || length > DEFAULT_READ_LIMIT) {
            length = DEFAULT_READ_LIMIT;
        }

        Uri uri = Uri.parse(uriString);
        try (InputStream in = getContext().getContentResolver().openInputStream(uri)) {
            if (in == null) {
                call.reject("cannot open input stream");
                return;
            }
            long skipped = 0;
            while (skipped < offset) {
                long n = in.skip(offset - skipped);
                if (n <= 0) {
                    break;
                }
                skipped += n;
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[64 * 1024];
            long remaining = length;
            while (remaining > 0) {
                int toRead = (int) Math.min(chunk.length, remaining);
                int read = in.read(chunk, 0, toRead);
                if (read < 0) {
                    break;
                }
                buffer.write(chunk, 0, read);
                remaining -= read;
            }
            byte[] bytes = buffer.toByteArray();
            String[] meta = queryNameAndSize(uri);
            JSObject ret = new JSObject();
            ret.put("base64", Base64.encodeToString(bytes, Base64.NO_WRAP));
            ret.put("bytes", bytes.length);
            ret.put("size", Long.parseLong(meta[1]));
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("read failed: " + e.getMessage());
        }
    }

    @PluginMethod
    public void writeFile(PluginCall call) {
        String uriString = call.getString("uri");
        String base64 = call.getString("base64");
        String mode = call.getString("mode", "overwrite");
        if (uriString == null || base64 == null) {
            call.reject("missing uri or base64");
            return;
        }
        Uri uri = Uri.parse(uriString);
        byte[] bytes = Base64.decode(base64, Base64.DEFAULT);

        try {
            DocumentFile file = DocumentFile.fromSingleUri(getContext(), uri);
            boolean exists = file != null && file.exists();
            if ("create".equals(mode) && exists) {
                call.reject("FILE_ALREADY_EXISTS");
                return;
            }
            String openMode = "append".equals(mode) ? "wa" : "wt";
            try (OutputStream out = getContext().getContentResolver().openOutputStream(uri, openMode)) {
                if (out == null) {
                    call.reject("cannot open output stream");
                    return;
                }
                out.write(bytes);
                out.flush();
            }
            String[] meta = queryNameAndSize(uri);
            JSObject ret = new JSObject();
            ret.put("bytes", bytes.length);
            ret.put("size", Long.parseLong(meta[1]));
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("write failed: " + e.getMessage());
        }
    }

    @PluginMethod
    public void deleteFile(PluginCall call) {
        String uriString = call.getString("uri");
        if (uriString == null || uriString.isEmpty()) {
            call.reject("missing uri");
            return;
        }
        try {
            Uri uri = Uri.parse(uriString);
            DocumentFile file = DocumentFile.fromSingleUri(getContext(), uri);
            boolean deleted = file != null && file.delete();
            if (!deleted) {
                try {
                    deleted = DocumentsContract.deleteDocument(getContext().getContentResolver(), uri);
                } catch (Exception ignored) {
                    // 忽略
                }
            }
            if (deleted) {
                call.resolve();
            } else {
                call.reject("delete failed");
            }
        } catch (Exception e) {
            call.reject("delete failed: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- 文件夹

    @PluginMethod
    public void listFolder(PluginCall call) {
        String uriString = call.getString("uri");
        if (uriString == null || uriString.isEmpty()) {
            call.reject("missing uri");
            return;
        }
        try {
            DocumentFile dir = toDocumentFile(Uri.parse(uriString), true);
            if (dir == null || !dir.isDirectory()) {
                call.reject("not a directory");
                return;
            }
            DocumentFile[] children = dir.listFiles();
            // 按名称排序（文件夹优先）
            List<DocumentFile> sorted = new ArrayList<>();
            for (DocumentFile child : children) {
                sorted.add(child);
            }
            sorted.sort((a, b) -> {
                boolean da = a.isDirectory();
                boolean db = b.isDirectory();
                if (da != db) {
                    return da ? -1 : 1;
                }
                return a.getName() == null ? 0 : a.getName().compareToIgnoreCase(b.getName() == null ? "" : b.getName());
            });
            JSArray entries = new JSArray();
            for (DocumentFile child : sorted) {
                entries.put(toEntry(child, null));
            }
            JSObject ret = new JSObject();
            ret.put("entries", entries);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("list failed: " + e.getMessage());
        }
    }

    @PluginMethod
    public void createFolder(PluginCall call) {
        String parentUri = call.getString("parentUri");
        String name = call.getString("name");
        if (parentUri == null || name == null || name.isEmpty()) {
            call.reject("missing parentUri or name");
            return;
        }
        try {
            DocumentFile parent = toDocumentFile(Uri.parse(parentUri), true);
            if (parent == null || !parent.isDirectory()) {
                call.reject("parent is not a directory");
                return;
            }
            DocumentFile created = parent.createDirectory(name);
            if (created == null) {
                call.reject("create folder failed");
                return;
            }
            call.resolve(toEntry(created, null));
        } catch (Exception e) {
            call.reject("create folder failed: " + e.getMessage());
        }
    }

    @PluginMethod
    public void deleteFolder(PluginCall call) {
        String uriString = call.getString("uri");
        if (uriString == null || uriString.isEmpty()) {
            call.reject("missing uri");
            return;
        }
        try {
            DocumentFile dir = toDocumentFile(Uri.parse(uriString), true);
            if (dir == null || !dir.isDirectory()) {
                call.reject("not a directory");
                return;
            }
            if (dir.delete()) {
                call.resolve();
            } else {
                call.reject("delete folder failed");
            }
        } catch (Exception e) {
            call.reject("delete folder failed: " + e.getMessage());
        }
    }

    @PluginMethod
    public void renameFolder(PluginCall call) {
        String uriString = call.getString("uri");
        String newName = call.getString("newName");
        if (uriString == null || newName == null || newName.isEmpty()) {
            call.reject("missing uri or newName");
            return;
        }
        try {
            DocumentFile dir = toDocumentFile(Uri.parse(uriString), true);
            if (dir == null || !dir.isDirectory()) {
                call.reject("not a directory");
                return;
            }
            if (!dir.renameTo(newName)) {
                call.reject("rename failed");
                return;
            }
            DocumentFile renamed = toDocumentFile(Uri.parse(uriString), true);
            call.resolve(toEntry(renamed, null));
        } catch (Exception e) {
            call.reject("rename failed: " + e.getMessage());
        }
    }

    @PluginMethod
    public void getInfo(PluginCall call) {
        String uriString = call.getString("uri");
        if (uriString == null || uriString.isEmpty()) {
            call.reject("missing uri");
            return;
        }
        try {
            Uri uri = Uri.parse(uriString);
            DocumentFile file = toDocumentFile(uri, false);
            String[] meta = queryNameAndSize(uri);
            JSObject ret = new JSObject();
            ret.put("uri", uriString);
            ret.put("name", meta[0]);
            ret.put("exists", file != null && file.exists());
            ret.put("isDirectory", file != null && file.isDirectory());
            ret.put("size", Long.parseLong(meta[1]));
            ret.put("mimeType", meta[2]);
            ret.put("lastModified", file != null ? file.lastModified() : 0L);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("get info failed: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- 复制到 App 存储（配合 data-channel）

    @PluginMethod
    public void copyToAppStorage(PluginCall call) {
        String uriString = call.getString("uri");
        String relativePath = call.getString("relativePath", null);
        if (uriString == null || uriString.isEmpty()) {
            call.reject("missing uri");
            return;
        }
        try {
            Uri uri = Uri.parse(uriString);
            String[] meta = queryNameAndSize(uri);
            String fileName = meta[0];
            String rel = relativePath != null && !relativePath.isEmpty() ? relativePath : fileName;

            // 与 data-channel 的默认根目录一致
            File root = getContext().getExternalFilesDir("dataChannel");
            if (root == null) {
                root = new File(getContext().getFilesDir(), "dataChannel");
            }
            File target = new File(root, rel);
            if (!target.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator)) {
                call.reject("invalid relativePath");
                return;
            }
            File parent = target.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                call.reject("cannot create destination directory");
                return;
            }

            long copied = 0;
            try (
                InputStream in = getContext().getContentResolver().openInputStream(uri);
                FileOutputStream out = new FileOutputStream(target)
            ) {
                if (in == null) {
                    call.reject("cannot open source stream");
                    return;
                }
                byte[] buffer = new byte[256 * 1024];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                    copied += read;
                }
                out.flush();
            }

            JSObject ret = new JSObject();
            ret.put("absolutePath", target.getAbsolutePath());
            ret.put("relativePath", rel.replace(File.separatorChar, '/'));
            ret.put("bytes", copied);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("copy failed: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- 内部工具

    /** 解析 URI：tree URI 用 fromTreeUri（目录），其它用 fromSingleUri。 */
    private DocumentFile toDocumentFile(Uri uri, boolean preferTree) {
        if (preferTree && DocumentsContract.isTreeUri(uri)) {
            return DocumentFile.fromTreeUri(getContext(), uri);
        }
        DocumentFile single = DocumentFile.fromSingleUri(getContext(), uri);
        if (single != null) {
            return single;
        }
        return DocumentFile.fromTreeUri(getContext(), uri);
    }

    private JSObject toEntry(DocumentFile file, String explicitUri) {
        JSObject entry = new JSObject();
        entry.put("uri", explicitUri != null ? explicitUri : (file.getUri() != null ? file.getUri().toString() : ""));
        entry.put("name", file.getName() != null ? file.getName() : "");
        entry.put("isDirectory", file.isDirectory());
        entry.put("size", file.length());
        entry.put("mimeType", file.getType() != null ? file.getType() : "");
        entry.put("lastModified", file.lastModified());
        return entry;
    }

    /** 查询显示名 / 大小 / MIME，返回 [name, size, mimeType]。 */
    private String[] queryNameAndSize(Uri uri) {
        String name = "";
        long size = -1;
        String mimeType = "";
        ContentResolver resolver = getContext().getContentResolver();
        try (Cursor cursor = resolver.query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (nameIndex >= 0) {
                    name = cursor.getString(nameIndex);
                }
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    size = cursor.getLong(sizeIndex);
                }
            }
        } catch (Exception ignored) {
            // 某些 provider 不支持查询
        }
        if (name == null || name.isEmpty()) {
            name = uri.getLastPathSegment() != null ? uri.getLastPathSegment() : "unknown";
        }
        try {
            mimeType = resolver.getType(uri);
        } catch (Exception ignored) {
            // 忽略
        }
        if (mimeType == null) {
            mimeType = "";
        }
        return new String[] { name, Long.toString(size), mimeType };
    }

    private void persistPermissions(Intent data, List<Uri> uris) {
        int flags = data.getFlags() &
            (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        for (Uri uri : uris) {
            try {
                getContext().getContentResolver().takePersistableUriPermission(uri, flags);
            } catch (Exception ignored) {
                // 忽略不支持持久化的 provider
            }
        }
    }

    private JSObject cancelledFiles() {
        JSObject ret = new JSObject();
        ret.put("files", new JSArray());
        ret.put("cancelled", true);
        return ret;
    }
}
