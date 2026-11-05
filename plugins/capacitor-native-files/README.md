# capacitor-native-files

本地**文件系统**插件：系统原生文件/文件夹选择器 + 文件读写 + 文件夹管理，可与
[`capacitor-data-channel`](../capacitor-data-channel/) 配合实现"选择文件 → 高速传输"。

## 能力一览

| 功能 | API | 多选 |
| --- | --- | --- |
| 选择文件（系统选择器） | `pickFiles()` | ✅ 支持（`multiple: true`） |
| 选择文件夹（系统选择器） | `pickFolders()` | ❌ **单选**（Android SAF 系统限制，iOS 保持一致） |
| 读文件 | `readFile()`（base64，可 offset/length 分块） | — |
| 写文件 | `writeFile()`（create / append / overwrite） | — |
| 删除文件 | `deleteFile()` | — |
| 列目录 | `listFolder()` | — |
| 新建文件夹 | `createFolder()` | — |
| 删除文件夹 | `deleteFolder()` | — |
| 重命名文件夹 | `renameFolder()` | — |
| 文件/文件夹信息 | `getInfo()` | — |
| 复制到 App 存储（配合 data-channel） | `copyToAppStorage()` | — |

## 安装

```bash
npm install ./plugins/capacitor-native-files
npx cap sync
```

> **无需存储权限**：Android 用 SAF（Storage Access Framework），权限来自用户在选择器里的授权；
> iOS 用 UIDocumentPicker + security-scoped URL。

## URI 约定（重要）

所有 API 的 `uri` 参数是**平台 URI**，不是普通文件路径：

| 平台 | URI 形式 | 说明 |
| --- | --- | --- |
| Android | `content://...` | SAF 文档 URI（用户在系统选择器里授权的位置） |
| iOS | `file://...` | 文件 URL（系统选择器授予 security-scoped 访问） |

> 为什么不是 `/sdcard/...` 这样的路径？Android 10+ 的 scoped storage 不允许直接按路径访问
> 公共目录，SAF 是官方推荐方式；iOS 则根本没有"共享文件系统"。
>
> 需要"文件路径"（给 data-channel 用）时，用 `copyToAppStorage()` 复制到 App 自有目录。

## 用法

### 选择文件（支持多选）

```ts
import { NativeFiles } from 'capacitor-native-files';

const result = await NativeFiles.pickFiles({
  multiple: true,
  mimeTypes: ['image/*', 'video/*', 'application/pdf'], // 可选，MIME 过滤
});

if (result.cancelled) {
  console.log('用户取消了选择');
} else {
  for (const file of result.files) {
    console.log(file.uri, file.name, file.size, file.mimeType);
  }
  // iOS 额外返回 bookmark（可用于后续恢复访问）
}
```

### 选择文件夹（单选）

```ts
const result = await NativeFiles.pickFolders();
if (!result.cancelled) {
  const folder = result.folders[0];
  console.log(folder.uri, folder.name);
}
```

### 读 / 写 / 删除文件

```ts
// 读：base64（大文件用 offset/length 分块读，单次上限 16MB）
const content = await NativeFiles.readFile({ uri: file.uri });
console.log(content.size, content.bytes, content.base64);

// 写：create（已存在则失败）/ append（追加）/ overwrite（覆盖）
await NativeFiles.writeFile({
  uri: file.uri,
  base64: btoa('hello'),
  mode: 'append',
});

// 删
await NativeFiles.deleteFile({ uri: file.uri });
```

### 文件夹操作

```ts
// 列目录
const { entries } = await NativeFiles.listFolder({ uri: folder.uri });
for (const entry of entries) {
  console.log(entry.name, entry.isDirectory, entry.size);
}

// 新建子文件夹
const created = await NativeFiles.createFolder({ parentUri: folder.uri, name: 'exports' });

// 重命名
const renamed = await NativeFiles.renameFolder({ uri: created.uri, newName: 'out' });

// 删除（递归）
await NativeFiles.deleteFolder({ uri: renamed.uri });
```

### 与 data-channel 配合（推荐流程）

```ts
import { DataChannel } from 'capacitor-data-channel';
import { NativeFiles } from 'capacitor-native-files';

// 1) 系统选择器选文件
const { files } = await NativeFiles.pickFiles({ multiple: false });
// 2) 流式复制到 App 自有目录（data-channel 默认根目录）
const copied = await NativeFiles.copyToAppStorage({ uri: files[0].uri });
// 3) data-channel 高速上传/读取
const ch = await DataChannel.start();
const blob = await (await fetch(`${ch.baseUrl}/download?path=${copied.relativePath}&token=${ch.token}`)).blob();
```

## API 说明

### pickFiles(options?) → PickFilesResult

| 参数 | 类型 | 说明 |
| --- | --- | --- |
| `multiple` | `boolean` | 是否多选（默认 false） |
| `mimeTypes` | `string[]` | MIME 过滤（如 `image/*`）；不传则不过滤 |

返回 `{ files: PickedFile[], cancelled: boolean }`；`PickedFile`：

| 字段 | 说明 |
| --- | --- |
| `uri` | 平台 URI |
| `name` | 显示名（含扩展名） |
| `size` | 字节数（未知为 -1） |
| `mimeType` | MIME 类型（未知为空串） |
| `bookmark?` | iOS 的 security-scoped bookmark（base64） |

### pickFolders(options?) → PickFoldersResult

返回 `{ folders: FolderEntry[], cancelled: boolean }`（**最多 1 个**，单选）。

### readFile(options) → ReadFileResult

`{ uri, offset?, length? }` → `{ base64, bytes, size }`。
**单次最多 16MB**（base64 走桥，大文件请分块或复制到 App 存储后用 data-channel）。

### writeFile(options) → WriteFileResult

`{ uri, base64, mode?: 'create' | 'append' | 'overwrite' }` → `{ bytes, size }`。
`create` 时若文件已存在会 reject（`FILE_ALREADY_EXISTS`）。

### listFolder / createFolder / deleteFolder / renameFolder / getInfo

`FolderEntry`：`{ uri, name, isDirectory, size, mimeType, lastModified }`；
`getInfo` 返回 `FileInfo`：`{ uri, name, exists, isDirectory, size, mimeType, lastModified }`。

### copyToAppStorage(options) → CopyToAppStorageResult

`{ uri, relativePath? }` → `{ absolutePath, relativePath, bytes }`。
复制目标为 data-channel 的默认根目录（Android `/sdcard/Android/data/<pkg>/files/dataChannel`，
iOS `Documents/dataChannel`），返回的 `relativePath` 可直接用于 data-channel 的 `path` 参数。

## 平台差异与注意事项

| 事项 | Android | iOS |
| --- | --- | --- |
| 选择器 | SAF（`ACTION_OPEN_DOCUMENT` / `ACTION_OPEN_DOCUMENT_TREE`） | `UIDocumentPickerViewController` |
| 文件多选 | ✅ | ✅ |
| 文件夹多选 | ❌ 系统不支持 | ✅ 支持，但**本插件统一为单选**（跨平台一致） |
| URI | `content://` | `file://` |
| 权限 | 无（SAF 授权） | 无（security-scoped） |
| 授权持久性 | `takePersistableUriPermission` 后重启仍有效（部分 provider 不支持） | App 重启后失效；返回的 `bookmark` 可用于恢复（当前版本尚未提供按 bookmark 恢复的 API） |

**注意**：
- 文件夹选择器返回的 URI 是"文件夹"，`listFolder` / `createFolder` 等都基于它；
- 对 iOS，选中文件后 App 只有本次运行的访问权；要长期访问需持久化 bookmark；
- 删除操作直接作用于真实文件，**不可恢复**，请二次确认。

## 实测记录（真机 Redmi K30S / Android 10）

| 项目 | 结果 |
| --- | --- |
| 方法注册 | 11 个方法全部可用 ✓ |
| `getInfo(file:///does-not-exist-xyz)` | `{ exists: false, name: 'does-not-exist-xyz', size: -1 }` ✓ |
| `getInfo(content://...)` | `{ exists: false }` ✓ |
| 文件/文件夹选择器 | 系统 UI，需手动操作验证（未自动化） |

## 开发

```bash
cd plugins/capacitor-native-files
npm install
npm run build   # tsc → dist/esm
```

- Android：`android/src/main/java/com/testionic/nativefiles/NativeFilesPlugin.java`（SAF + DocumentFile）
- iOS：`ios/Sources/NativeFilesPlugin/NativeFilesPlugin.swift`（UIDocumentPicker + security-scoped）
