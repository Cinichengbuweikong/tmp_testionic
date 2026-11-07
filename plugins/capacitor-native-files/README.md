# capacitor-native-files

本地**文件系统**插件：系统原生文件/文件夹选择器 + **真实绝对路径**读写 + 文件夹管理。

> **0.2.0 起所有 API 都用真实文件系统绝对路径**（如 `/storage/emulated/0/Notes/a.md`），
> 不再使用 `content://` URI。可以直接拼路径、算父/子目录——Markdown 阅读器、笔记树这类
> 需要"按目录结构遍历"的应用可以正常实现。
>
> 与 [`capacitor-data-channel`](../capacitor-data-channel/) 配合：大数据读写走 data-channel，
> 选择/遍历/管理文件走本插件。

## 能力一览

| 功能 | API | 说明 |
| --- | --- | --- |
| 选择文件 | `pickFiles()` | ✅ 多选（`multiple: true`） |
| 选择文件夹 | `pickFolders()` | ❌ 单选（Android SAF 系统限制，iOS 保持一致） |
| 读文件 | `readFile()` | base64；可 `offset`/`length` 分块，单次上限 16MB |
| 写文件 | `writeFile()` | `create` / `overwrite` / `append`；父目录自动创建 |
| 删除文件 | `deleteFile()` | — |
| 列目录 | `listFolder()` | 文件夹优先排序，可 `recursive` |
| 新建文件夹 | `createFolder()` | `path` 是完整目标路径（递归创建） |
| 删除文件夹 | `deleteFolder()` | 空目录直接删；非空需 `recursive: true` |
| 重命名 | `renameFolder()` | 返回新路径（文件/文件夹都可用） |
| 信息 | `getInfo()` | `{ exists, isDirectory, size, mimeType, lastModified, readable, writable }` |
| 所有文件访问权限 | `checkAllFilesAccess()` / `requestAllFilesAccess()` | Android 11+ 打开系统设置页 |
| 常用目录 | `getAppDirectories()` | App 私有目录 / 公共目录 / 安装目录的绝对路径 |
| 复制到 data-channel 根目录 | `copyToAppStorage()` | 返回相对路径 |

## 安装

```bash
npm install ./plugins/capacitor-native-files
npx cap sync
```

> **Android 公共目录权限**（读写任意路径必需）：
>
> | 版本 | 需要 |
> | --- | --- |
> | ≤ 9 | `WRITE_EXTERNAL_STORAGE` |
> | 10 | `WRITE_EXTERNAL_STORAGE` + 宿主 `requestLegacyExternalStorage="true"` |
> | **11+** | **`MANAGE_EXTERNAL_STORAGE`** → 调 `requestAllFilesAccess()` 让用户去系统设置里开启 |
>
> **App 私有目录**（`getAppDirectories().dirs.appExternalFilesDir` 等）**任何版本都免权限**。
> iOS 没有"公共目录"概念，用系统选择器选择文件夹后由插件持久化 security-scoped bookmark。

## 路径约定（重要）

所有 API 的 `path` 参数都是**绝对路径**：

| 平台 | 示例 |
| --- | --- |
| Android | `/storage/emulated/0/Notes/a.md`（内部存储）、`/storage/1A2B-3C4D/Books/x.md`（SD 卡） |
| iOS | `/var/mobile/Containers/Data/Application/<uuid>/Documents/Notes/a.md`（沙盒）<br>用户选择的 iCloud/文件 App 目录也是绝对路径（插件持有 security-scoped bookmark） |

系统选择器返回的 `content://`（Android）会在插件内解析成真实路径：

- `primary:Download/x` → `/storage/emulated/0/Download/x`
- `home:` → `/storage/emulated/0/Documents/...`
- `<卷 UUID>:Music` → `/storage/<UUID>/Music`
- MediaStore → 查 `_data` 列
- **云盘 / 第三方 SAF provider 没有 1:1 文件路径** → 会被跳过并在 `unmapped` 里返回（不会静默失败）

## 用法

### 0) 先拿常用目录（推荐）

```ts
import { NativeFiles } from 'capacitor-native-files';

const { platform, dirs } = await NativeFiles.getAppDirectories();
// Android:
// { appFilesDir, appCacheDir, appExternalFilesDir, appExternalCacheDir, appExternalRoot,
//   apkPath, nativeLibDir, appDataDir,
//   externalStorage, downloads, documents, pictures, dcim, movies, music, alarms, ... }
// iOS: { home, documents, library, applicationSupport, caches, temporary, bundle, icloudDocuments }
console.log(dirs.documents); // /storage/emulated/0/Documents
```

### 1) 选文件夹 → 得到绝对路径 → 拼路径遍历（Markdown 阅读器场景）

```ts
const { folders, cancelled } = await NativeFiles.pickFolders();
if (cancelled) return;

const root = folders[0].path;                  // 例如 /storage/emulated/0/Notes
const { entries } = await NativeFiles.listFolder({ path: root });

for (const entry of entries) {
  if (entry.isDirectory) {
    // 直接拼子目录 —— 这就是"真实路径"的意义
    const sub = await NativeFiles.listFolder({ path: entry.path });
    console.log(`${entry.name}/`, sub.entries.map((e) => e.name));
  } else if (entry.name.endsWith('.md')) {
    const { base64 } = await NativeFiles.readFile({ path: entry.path });
    const text = atob(base64);                 // 纯 ASCII 内容；中文见下方说明
    console.log(entry.name, text.length);
  }
}

// 新建/重命名/删除
await NativeFiles.createFolder({ path: `${root}/drafts/2026` });
await NativeFiles.renameFolder({ path: `${root}/drafts`, newName: 'drafts-old' });
await NativeFiles.deleteFolder({ path: `${root}/drafts-old`, recursive: true });
```

> **非 ASCII 文本**：`readFile` 返回 base64，`atob` 得到的是二进制字符串。
> 读 UTF-8 文本请用：
>
> ```ts
> const bytes = Uint8Array.from(atob(base64), (c) => c.charCodeAt(0));
> const text = new TextDecoder('utf-8').decode(bytes);
> ```
>
> 写文本反过来：`btoa(String.fromCharCode(...new TextEncoder().encode(text)))`（大文本请分块）。

### 2) 选文件（多选）

```ts
const { files, cancelled, unmapped } = await NativeFiles.pickFiles({
  multiple: true,
  mimeTypes: ['text/markdown', 'text/plain'],
});
// files: [{ path, name, size, lastModified, mimeType }]
if (unmapped?.length) {
  console.warn('这些来源没有真实路径（云盘等），已跳过：', unmapped);
}
```

### 3) 读写文件

```ts
// 写（父目录会自动创建）
await NativeFiles.writeFile({
  path: '/storage/emulated/0/Documents/hello.md',
  base64: btoa('# hello'),
  mode: 'overwrite',      // create | overwrite | append
});

// 读（base64；单次最多 16MB）
const { base64, bytes, size } = await NativeFiles.readFile({ path: '.../hello.md' });

// 分块读大文件
const chunk = await NativeFiles.readFile({ path, offset: 0, length: 1024 * 1024 });

// 删除（不可恢复）
await NativeFiles.deleteFile({ path: '.../hello.md' });
```

### 4) 文件/目录信息（对应 data-channel 原来的 `GET /info`）

```ts
const info = await NativeFiles.getInfo({ path: '/storage/emulated/0/Notes/a.md' });
// { path, name, exists, isDirectory, isFile, size, lastModified, readable, writable, mimeType }
```

### 5) 申请"所有文件访问权限"（Android 11+）

```ts
// 先查
const status = await NativeFiles.checkAllFilesAccess();
// { granted, androidVersion, requiresSettings, permission }

// 再申请（Android 11+ 打开系统设置页；≤10 弹运行时权限；iOS 返回 applicable:false）
const result = await NativeFiles.requestAllFilesAccess();
console.log('granted:', result.granted);
```

> 没有该权限时，读写公共目录会 reject，错误信息里带
> `Grant "All files access" via NativeFiles.requestAllFilesAccess() first.` 提示。

### 6) 与 data-channel 配合

```ts
// 1) 选文件（拿到绝对路径）
const { files } = await NativeFiles.pickFiles({ multiple: false });
// 2) 复制进 data-channel 根目录（免权限），拿到相对路径
const copied = await NativeFiles.copyToAppStorage({ path: files[0].path });
// 3) data-channel 高速上传（data-channel 的 path 是相对 rootDir 的路径）
const http = await DataChannel.startHttp();
const otp = await DataChannel.createHttpOtp({ scope: 'write', path: copied.relativePath });
await fetch(`${http.baseUrl}/upload?path=${copied.relativePath}`, {
  method: 'PUT',
  headers: { Authorization: `OTP ${otp.otp}` },
  body: fileBlob,
});
```

> 如果文件本来就在 data-channel 的 rootDir 下，可以跳过复制，直接用相对 rootDir 的路径。

## API 说明

| 方法 | 参数 | 返回 |
| --- | --- | --- |
| `pickFiles` | `{ multiple?, mimeTypes? }` | `{ files: FileEntry[], cancelled, unmapped?, warning? }` |
| `pickFolders` | — | `{ folders: DirEntry[], cancelled }` |
| `readFile` | `{ path, offset?, length? }` | `{ path, base64, bytes, size, offset }` |
| `writeFile` | `{ path, base64, mode? }` | `{ path, bytes, size }` |
| `deleteFile` | `{ path }` | `{ deleted, reason? }` |
| `listFolder` | `{ path, recursive?, maxEntries? }` | `{ path, entries: FsEntry[], truncated }` |
| `createFolder` | `{ path }` | `{ path, created }` |
| `deleteFolder` | `{ path, recursive? }` | `{ deleted, reason?, hint? }` |
| `renameFolder` | `{ path, newName }` | `{ renamed, path, from }` |
| `getInfo` | `{ path }` | `PathInfo` |
| `checkAllFilesAccess` | — | `{ granted, androidVersion, requiresSettings, permission }` |
| `requestAllFilesAccess` | — | `{ granted, androidVersion }` / `{ granted, applicable: false, message }` |
| `getAppDirectories` | — | `{ platform, dirs, androidVersion?, allFilesAccess? }` |
| `copyToAppStorage` | `{ path, relativePath? }` | `{ absolutePath, relativePath, bytes }` |

`FileEntry`：`{ path, name, isDirectory: false, size, lastModified, mimeType }`
`DirEntry`：`{ path, name, isDirectory: true, size: 0, lastModified, mimeType: 'inode/directory' }`

**错误约定**：错误信息带明确前缀，便于程序判断：
`NOT_FOUND` / `IS_A_DIRECTORY` / `NOT_A_DIRECTORY` / `PATH_EXISTS_AS_FILE` / `TARGET_EXISTS` /
`FILE_ALREADY_EXISTS` / `PATH_MUST_BE_ABSOLUTE` / `INVALID_NAME` / `INVALID_RELATIVE_PATH` /
`PERMISSION_DENIED`（附申请权限提示）。

## 平台差异与注意事项

| 事项 | Android | iOS |
| --- | --- | --- |
| 公共目录 | ✅（需 `MANAGE_EXTERNAL_STORAGE`，API 30+） | 🚫 不存在，`downloads/pictures/...` 为 null |
| 选择器 | SAF（`ACTION_OPEN_DOCUMENT` / `ACTION_OPEN_DOCUMENT_TREE`） | `UIDocumentPickerViewController` |
| 文件夹多选 | ❌ 系统不支持 | 系统支持，但本插件统一单选（跨平台一致） |
| 路径授权 | 权限（所有文件访问） | security-scoped bookmark（**已持久化到 UserDefaults，重启后仍可访问**） |
| 云盘来源 | 无法映射成路径 → `unmapped` | 选择后即可用（bookmark 保存在本机） |

**注意**：

1. **删除不可恢复**（直接作用到真实文件），请二次确认；
2. iOS 对外部目录的访问依赖 bookmark：**同一条路径前缀下**（含子目录）都能访问；
   如果用户移动了该文件夹，bookmark 可能失效，需要重新选择；
3. Android 11+ 的 `MANAGE_EXTERNAL_STORAGE` 在 Google Play 有政策限制（上架需说明用途），
   如果你的应用只读写自己的目录，用 `appExternalFilesDir` 即可，**不需要**该权限；
4. `listFolder` 默认跳过隐藏文件（iOS `.skipsHiddenFiles`）；`maxEntries` 默认 5000。

## 实测记录（2026-11-07）

| 环境 | 结果 |
| --- | --- |
| 模拟器 Pixel 6 / Android 16（已授予所有文件访问） | `getAppDirectories` 全部路径正确；**公共目录 `/storage/emulated/0/Documents` 建目录/写/读/列/改名/递归删全部通过**；`copyToAppStorage` 返回 `dataChannel/nf-copy/a.md` ✓ |
| 真机 Redmi K30S / Android 10（WRITE_EXTERNAL_STORAGE 已授权） | 同上全部通过（`checkAllFilesAccess: { granted: true, permission: 'WRITE_EXTERNAL_STORAGE' }`）✓ |
| autotest（App 私有目录全链路） | 写 37B → 读回一致 → info 37B → 列目录 → 改名 → 删除后 `exists:false` ✓ |
| 选择器 | 系统 UI，需手动操作（未自动化） |

## 开发

```bash
cd plugins/capacitor-native-files
npm install
npm run build   # tsc → dist/esm
```

- Android：`android/src/main/java/com/testionic/nativefiles/NativeFilesPlugin.java`
  （`java.io.File` 路径操作 + SAF 选择器 + URI→路径解析 + 所有文件访问权限）
- iOS：`ios/Sources/NativeFilesPlugin/NativeFilesPlugin.swift`
  （`FileManager` + `UIDocumentPicker` + security-scoped bookmark 持久化 + 最长祖先路径授权）
