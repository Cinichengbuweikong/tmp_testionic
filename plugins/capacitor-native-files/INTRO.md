# capacitor-native-files 插件介绍

> 一句话：**把"真实文件系统绝对路径"带进 Capacitor——选目录、拼路径、遍历读写，
> 而不是拿着一串 `content://` URI 干着急。**

## 它解决什么问题

Capacitor 官方的 `@capacitor/filesystem` 能用，但它的模型是"**`Directory` 枚举 + 相对路径**"：
你只能读写 `Documents` / `Data` / `Cache` 这些预定义目录里的文件，**拿不到一个可以自由拼接的绝对路径**。

对下面这类应用，这就是硬伤：

- **Markdown 阅读器 / 笔记树**：需要"选一个书库根目录 → 列出里面的章节文件夹 →
  拼 `${dir}/chapter-01/a.md` 读文件"；
- **相册 / 文件管理器**：需要按目录结构递归遍历、按父目录/子目录自由跳转；
- **批量处理**：需要把"某个目录下所有 `.md`"读出来做全文索引。

这些操作的前提都是：**手里有一个真实路径字符串**，而不是一个需要系统代理才能访问的 URI。

本插件就是为此设计的：

1. **所有 API 都用真实绝对路径**（`/storage/emulated/0/Notes/a.md`），可以直接字符串拼接；
2. 系统选择器返回的 `content://` 会在插件内**解析成真实路径**（内部存储 / SD 卡 / MediaStore）；
3. 权限模型**按目录分层**：App 私有目录免权限，公共目录按 Android 版本申请对应权限；
4. 选择器只是"入口"之一——拿到根路径后，**不再依赖选择器**就能遍历整个目录树。

## 核心设计

**① 路径即契约。** 每个 API 的输入输出都是绝对路径：`listFolder` 返回的每个条目自带 `path`，
`getInfo` 回显 `path`，`createFolder` 接收完整目标路径。没有"相对某个 root"的隐式状态。

**② 一个方法查一条路径。** 23 个目录 API（`getAppFilesDir()`、`getSharedDocumentsDir()`、
`getDownloadsDir()`……）每个只查一条，返回 `{ path, reason? }`：
只想要一个路径就不必等其它路径，某个取不到也不影响别的。
`path: null` + `reason`（如 `NOT_APPLICABLE_ON_IOS`）明确表达"这个平台没有这个目录"，
不会因为目录不存在就抛错。

**③ 权限与能力分离。** 读写公共目录的权限是 Android 系统层面的
（≤9 `WRITE_EXTERNAL_STORAGE` / 10 加 `requestLegacyExternalStorage` / 11+ `MANAGE_EXTERNAL_STORAGE`），
插件负责**检查 + 引导申请**（`checkAllFilesAccess` / `requestAllFilesAccess`），
而 App 私有目录任何版本都免权限。所以"要不要用户授权"由你选的目录决定。

**④ 和传输分开。** 文件内容走桥（base64）适合 ≤16MB 的一次性读写的场景；
更大的文件、上传、断点续传、实时推送，交给
[`capacitor-data-channel`](../capacitor-data-channel/) 的本地 HTTP/WS——
它的 `rootDir` 也支持绝对路径，正好接上本插件拿到的路径。

## 提供什么

| 分类 | API |
| --- | --- |
| 选择器 | `pickFiles`（多选）/ `pickFolders`（单选，Android SAF 限制） |
| 文件读写 | `readFile`（base64，`offset`/`length` 分块，单次 ≤16MB）/ `writeFile`（create / overwrite / append） |
| 文件管理 | `deleteFile` / `getInfo` |
| 目录管理 | `listFolder`（可递归、可限条数）/ `createFolder`（完整路径，递归创建）/ `deleteFolder`（默认只删空目录，`recursive` 递归）/ `renameFolder`（文件目录通用，返回新路径） |
| 权限 | `checkAllFilesAccess` / `requestAllFilesAccess` |
| 常用目录 | 23 个单条路径 API：App 私有（files/cache/temp/externalFiles/externalCache/externalRoot/data/installPath/nativeLib）、文档（appDocuments/sharedDocuments）、公共（externalStorage/downloads/pictures/dcim/movies/music/alarms/notifications/podcasts/ringtones）、iOS（library/icloudDocuments） |

## 30 秒上手

```ts
import { NativeFiles } from 'capacitor-native-files';

// 1) 选一个"书库"根目录（只选一次）
const { folders, cancelled } = await NativeFiles.pickFolders();
if (cancelled) return;
const root = folders[0].path;                    // /storage/emulated/0/Notes

// 2) 之后完全用路径遍历，不再需要选择器
const { entries } = await NativeFiles.listFolder({ path: root });
for (const chapter of entries.filter((e) => e.isDirectory)) {
  const sub = await NativeFiles.listFolder({ path: chapter.path });
  for (const md of sub.entries.filter((e) => !e.isDirectory && e.name.endsWith('.md'))) {
    const { base64 } = await NativeFiles.readFile({ path: md.path });
    const bytes = Uint8Array.from(atob(base64), (c) => c.charCodeAt(0));
    const text = new TextDecoder('utf-8').decode(bytes);
    console.log(md.name, text.length);
  }
}

// 3) 写回去（父目录不存在会自动创建）
await NativeFiles.writeFile({
  path: `${root}/.index/cache.txt`,
  base64: btoa('index'),
  mode: 'overwrite',
});
```

## 平台差异（必须知道的）

| 事项 | Android | iOS |
| --- | --- | --- |
| 公共目录 | ✅（11+ 需"所有文件访问权限"） | 🚫 不存在，相关 API 返回 `null` |
| 选择器 | SAF（`ACTION_OPEN_DOCUMENT` / `_TREE`） | `UIDocumentPickerViewController` |
| 文件夹多选 | ❌ 系统不支持 | 系统支持，但本插件统一单选 |
| 路径授权 | 系统权限 | security-scoped bookmark（**已持久化，重启后仍可用**） |
| 选不到的位置 | 不能选存储根目录 / `Download` 本身 / `Android/data` / `obb`（Android 11+ SAF 规则） | — |
| 不可访问的位置 | `Android/data`、`Android/obb`（**有全文件权限也不行**） | 沙盒外需用户授权 |

## 性能（真机 Android 10 实测）

| 通道 | 吞吐 | 适用 |
| --- | --- | --- |
| 桥（本插件 base64） | ~10–11 MB/s（8MB 写 0.78s / 读 0.73s） | ≤16MB 的一次性读写、配置/小文件 |
| data-channel HTTP | 上传 ~93 MB/s、下载 ~322 MB/s | 大文件、批量、上传/下载、断点续传 |
| `Capacitor.convertFileSrc()` | 不过桥（WebView 流式解码） | 图片/音频/视频/PDF 的**只读展示** |

> 10 几 MB 的文件用桥完全够用；但 JS 侧的 `btoa`/`atob` 也要计入成本（8MB 编码约 471ms）。
> 细节见 README 的「性能实测」与「在 WebView 里直接读文件」两节。

## 已知边界

1. **删除不可恢复**，`deleteFolder(recursive: true)` 尤其要二次确认；
2. iOS 对外部目录的访问依赖 bookmark，用户移动/删除该目录后需要重新选择；
3. `Android/data`、`Android/obb` 在 Android 11+ 对第三方 App **完全不可访问**，别把数据放那里；
4. Capacitor 内置的 `_capacitor_file_` URL **Range 实现有 bug**（忽略 end），
   视频拖动/PDF 翻页请用 data-channel；
5. `MANAGE_EXTERNAL_STORAGE` 在 Google Play 有政策限制，只读写自己目录时**不要**申请它。

## 验证情况

- Android 真机（Redmi K30S / Android 10）+ 模拟器（Pixel 6 / Android 16）：
  `pickFiles`/`pickFolders`/读写/列目录/改名/递归删除 全部实测通过；
- autotest（App 私有目录全链路）与 smoke 均通过；iOS 侧编译 + 模拟器 autotest 由 CI 覆盖；
- 23 个目录 API 在两端逐个验证，单条耗时 2–35ms。
