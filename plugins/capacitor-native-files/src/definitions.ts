/**
 * NativeFiles 插件（capacitor-native-files）类型定义。
 *
 * ======================= 路径语义（0.2.0 起）=======================
 *
 * **所有 API 都用真实文件系统绝对路径**（如 `/storage/emulated/0/Notes/a.md`），不用 `content://`。
 * 前端可以直接拼路径、算父目录/子目录：
 *
 * ```ts
 * const { folders } = await NativeFiles.pickFolders();
 * const root = folders[0].path;                       // /storage/emulated/0/Notes
 * const { entries } = await NativeFiles.listFolder({ path: `${root}/chapters` });
 * const md = entries.find((e) => e.name === '01.md')!;
 * const { base64 } = await NativeFiles.readFile({ path: md.path });
 * ```
 *
 * Android 选择器返回 `content://`，插件会解析成真实路径（internal storage / SD 卡）；
 * 云盘等无法映射成路径的 provider 会被跳过，并在 `unmapped` 里返回。
 *
 * ======================= 权限（Android）=======================
 *
 * | 版本 | 需要 |
 * |------|------|
 * | ≤ 9 | `WRITE_EXTERNAL_STORAGE` |
 * | 10 | `WRITE_EXTERNAL_STORAGE` + 宿主 `requestLegacyExternalStorage="true"` |
 * | **11+** | **`MANAGE_EXTERNAL_STORAGE`** → `requestAllFilesAccess()` 打开系统设置 |
 *
 * App 私有目录（`getAppFilesDir()` / `getAppExternalFilesDir()` 等）任何版本都免权限。
 *
 * ======================= 性能提示 ========================
 *
 * `readFile` / `writeFile` 的内容走 **base64 + Capacitor 桥**，实测吞吐约 **10–11 MB/s**
 * （真机 Android 10，8MB 文件：写 783ms、读 728ms），且单次上限 16MB。
 * 大文件请：
 * - 用 `readFile` 的 `offset`/`length` 分块读（每块 256–512KB 较均衡）；
 * - 或改用 `capacitor-data-channel` 的 HTTP（实测 93MB/s 上传、322MB/s 下载）。
 *
 * 只读展示（图片/音视频/文本）可以完全不经过桥：
 * `Capacitor.convertFileSrc(absolutePath)` → `https://localhost/_capacitor_file_/...`（iOS 为 `capacitor://`）。
 * 注意它当前的 **Range 实现有 bug**（忽略 end），详见 README 第 7 节。
 */
export interface NativeFilesPlugin {
  /**
   * 打开系统文件选择器（Android: `ACTION_OPEN_DOCUMENT`；iOS: `UIDocumentPicker`）。
   *
   * 返回的 `files[].path` 是**真实绝对路径**，可直接用于本插件其它 API 或
   * `Capacitor.convertFileSrc(path)` 在 WebView 里显示。
   *
   * @since 0.2.0
   */
  pickFiles(options?: PickFilesOptions): Promise<PickFilesResult>;

  /**
   * 打开系统文件夹选择器，返回**绝对路径**。
   *
   * - Android 系统限制：文件夹选择器只能单选，且**不能选存储根目录 / `Download` /
   *   `Android/data` / `Android/obb`**（Android 11+ SAF 规则）；
   * - iOS 为跨平台一致也做单选；iOS 选择后插件会持久化 security-scoped bookmark。
   *
   * @since 0.2.0
   */
  pickFolders(options?: PickFoldersOptions): Promise<PickFoldersResult>;

  /**
   * 读取文件内容（base64）。
   *
   * - `offset`：起始字节偏移（默认 0）；
   * - `length`：读取字节数（默认 = 上限 = **16MB**；超过会被截断到 16MB）；
   * - 返回的 `bytes` 是**本次实际读取**的字节数，`size` 是**文件总大小**。
   *
   * 分块读示例（每块 512KB，避免一次传 16MB 的 base64）：
   *
   * ```ts
   * for (let offset = 0; offset < info.size; offset += 512 * 1024) {
   *   const { base64, bytes } = await NativeFiles.readFile({ path, offset, length: 512 * 1024 });
   *   // 处理这一块…
   * }
   * ```
   *
   * @since 0.2.0
   */
  readFile(options: ReadFileOptions): Promise<ReadFileResult>;

  /**
   * 写入文件（base64 解码后落盘）。
   *
   * - `mode: 'create'`：文件已存在则 reject（`FILE_ALREADY_EXISTS`）；
   * - `mode: 'overwrite'`（默认）：覆盖；
   * - `mode: 'append'`：追加到末尾；
   * - **父目录不存在会自动递归创建**（`mkdirs`）。
   *
   * @since 0.2.0
   */
  writeFile(options: WriteFileOptions): Promise<WriteFileResult>;

  /**
   * 删除文件（目录请用 `deleteFolder`）。
   *
   * 文件不存在时**不报错**，返回 `{ deleted: false, reason: 'NOT_FOUND' }`。
   *
   * @since 0.2.0
   */
  deleteFile(options: { path: string }): Promise<DeleteResult>;

  /**
   * 列出目录内容。
   *
   * - 排序：**文件夹在前**，然后按名称大小写不敏感升序；
   * - 隐藏文件：iOS 跳过（`.skipsHiddenFiles`），Android 不跳过（行为差异见 README）；
   * - `recursive: true` 会递归展开子目录（深度优先）；
   * - `maxEntries` 默认 5000，达到上限时 `truncated: true`。
   *
   * @since 0.2.0
   */
  listFolder(options: ListFolderOptions): Promise<ListFolderResult>;

  /**
   * 创建文件夹（`path` 是**完整目标路径**，父目录自动递归创建）。
   *
   * 已存在且是目录时返回 `{ created: false }`（不报错）；
   * 已存在但是文件时 reject（`PATH_EXISTS_AS_FILE`）。
   *
   * @since 0.2.0
   */
  createFolder(options: { path: string }): Promise<{ path: string; created: boolean }>;

  /**
   * 删除文件夹。
   *
   * - 默认（`recursive` 不传）：**只能删空目录**，非空返回
   *   `{ deleted: false, reason: 'NOT_EMPTY_OR_FAILED', hint: ... }`；
   * - `recursive: true`：连同内容一起删除（**不可恢复**）。
   *
   * @since 0.2.0
   */
  deleteFolder(options: { path: string; recursive?: boolean }): Promise<DeleteResult>;

  /**
   * 重命名文件夹**或文件**（同一个父目录下改名），返回**新路径**。
   *
   * - `newName` 只能是简单名称（不能含 `/`、不能是 `.` / `..`）；
   * - 目标已存在时 reject（`TARGET_EXISTS`）。
   *
   * @since 0.2.0
   */
  renameFolder(options: { path: string; newName: string }): Promise<{
    renamed: boolean;
    path: string;
    from: string;
  }>;

  /**
   * 读取单个文件/目录的详细信息（对应 data-channel 原来的 `GET /info` 路由）。
   *
   * 文件不存在时**不报错**：`exists: false`，其余字段为零值。
   *
   * @since 0.2.0
   */
  getInfo(options: { path: string }): Promise<PathInfo>;

  /**
   * 检查是否已获得"所有文件访问权限"（Android）。
   *
   * - Android 11+：`Environment.isExternalStorageManager()`；
   * - Android ≤ 10：`WRITE_EXTERNAL_STORAGE` 是否已授予；
   * - iOS：恒为 `{ granted: true, applicable: false }`（没有该权限概念）。
   *
   * @since 0.2.0
   */
  checkAllFilesAccess(): Promise<AllFilesAccessStatus>;

  /**
   * 申请"所有文件访问权限"（Android）。
   *
   * - **已授权时直接返回 `{ granted: true }`，不弹任何界面**（设计如此）；
   * - Android 11+ 未授权：打开系统设置页 `Settings → All files access → 你的 App`，
   *   用户返回后 Promise 才兑现（值为最新状态）；
   * - Android ≤ 10：请求 `WRITE_EXTERNAL_STORAGE` 运行时权限；
   * - iOS：没有该权限概念，返回 `{ granted: true, applicable: false }`。
   *
   * 注意：撤销权限（系统设置或 `adb shell appops set <pkg> MANAGE_EXTERNAL_STORAGE default`）
   * 会**杀掉 App 进程**，这是系统行为。
   *
   * @since 0.2.0
   */
  requestAllFilesAccess(): Promise<AllFilesAccessStatus>;

  /**
   * 返回 App 私有内部文件目录（绝对路径）。
   *
   * - Android：`/data/user/0/<pkg>/files`（任何版本免权限；卸载即删）
   * - iOS：`Library/Application Support`
   *
   * @since 0.4.0
   */
  getAppFilesDir(): Promise<DirectoryPathResult>;

  /**
   * 返回 App 私有内部缓存目录。
   *
   * - Android：`/data/user/0/<pkg>/cache`
   * - iOS：`Library/Caches`
   *
   * 系统在空间不足时可能清理这里的文件。
   *
   * @since 0.4.0
   */
  getAppCacheDir(): Promise<DirectoryPathResult>;

  /**
   * 返回临时目录。
   *
   * - Android：没有独立 tmp，返回 `cache` 目录（见 `getAppCacheDir`）
   * - iOS：`tmp`
   *
   * @since 0.4.0
   */
  getAppTempDir(): Promise<DirectoryPathResult>;

  /**
   * 返回 App 的**外部私有**文件目录。
   *
   * - Android：`/storage/emulated/0/Android/data/<pkg>/files`（免权限，卸载即删）
   * - iOS：**没有**（沙盒内所有目录都算内部）→ `{ path: null }`
   *
   * @since 0.4.0
   */
  getAppExternalFilesDir(): Promise<DirectoryPathResult>;

  /**
   * 返回 App 的外部私有缓存目录。
   *
   * - Android：`/storage/emulated/0/Android/data/<pkg>/cache`
   * - iOS：没有 → `{ path: null }`
   *
   * @since 0.4.0
   */
  getAppExternalCacheDir(): Promise<DirectoryPathResult>;

  /**
   * 返回 **App 专属**的文档目录。
   *
   * - Android：`/storage/emulated/0/Android/data/<pkg>/files/Documents`
   * - iOS：`Documents`（开启 `UIFileSharingEnabled` 后可在"文件"App 看到）
   *
   * 与公共的 `getSharedDocumentsDir()`（`/storage/emulated/0/Documents`）不同：
   * 这个是 App 独占、免权限、卸载即删。
   *
   * @since 0.4.0
   */
  getAppDocumentsDir(): Promise<DirectoryPathResult>;

  /**
   * 返回**共享存储根目录**。
   *
   * - Android：`/storage/emulated/0`
   * - iOS：没有公共文件系统 → `{ path: null }`
   *
   * Android 11+ 读写其中任意位置需要"所有文件访问权限"（`requestAllFilesAccess()`）。
   *
   * @since 0.4.0
   */
  getSharedStorageDir(): Promise<DirectoryPathResult>;

  /**
   * 返回系统下载目录。
   *
   * - Android：`/storage/emulated/0/Download`
   * - iOS：没有 → `{ path: null }`
   *
   * 注意：Android 11+ 的 SAF 选择器**不允许**把该目录本身作为授权目标（系统限制），
   * 但拿到路径后用本插件直接 `listFolder` / 读写是可以的。
   *
   * @since 0.4.0
   */
  getDownloadsDir(): Promise<DirectoryPathResult>;

  /**
   * 返回系统文档目录（公共）。
   *
   * - Android：`/storage/emulated/0/Documents`
   * - iOS：没有 → `{ path: null }`（App 自己的用 `getAppDocumentsDir()`）
   *
   * @since 0.4.0
   */
  getSharedDocumentsDir(): Promise<DirectoryPathResult>;

  /**
   * 返回系统图片目录。
   *
   * - Android：`/storage/emulated/0/Pictures`
   * - iOS：没有 → `{ path: null }`
   *
   * @since 0.4.0
   */
  getPicturesDir(): Promise<DirectoryPathResult>;

  /**
   * 返回系统相机目录。
   *
   * - Android：`/storage/emulated/0/DCIM`
   * - iOS：没有 → `{ path: null }`
   *
   * @since 0.4.0
   */
  getDcimDir(): Promise<DirectoryPathResult>;

  /**
   * 返回系统视频目录。
   *
   * - Android：`/storage/emulated/0/Movies`
   * - iOS：没有 → `{ path: null }`
   *
   * @since 0.4.0
   */
  getMoviesDir(): Promise<DirectoryPathResult>;

  /**
   * 返回系统音乐目录。
   *
   * - Android：`/storage/emulated/0/Music`
   * - iOS：没有 → `{ path: null }`
   *
   * @since 0.4.0
   */
  getMusicDir(): Promise<DirectoryPathResult>;

  /**
   * 返回系统闹钟铃声目录。
   *
   * - Android：`/storage/emulated/0/Alarms`
   * - iOS：没有 → `{ path: null }`
   *
   * @since 0.5.0
   */
  getAlarmsDir(): Promise<DirectoryPathResult>;

  /**
   * 返回系统通知铃声目录。
   *
   * - Android：`/storage/emulated/0/Notifications`
   * - iOS：没有 → `{ path: null }`
   *
   * @since 0.5.0
   */
  getNotificationsDir(): Promise<DirectoryPathResult>;

  /**
   * 返回系统播客目录。
   *
   * - Android：`/storage/emulated/0/Podcasts`
   * - iOS：没有 → `{ path: null }`
   *
   * @since 0.5.0
   */
  getPodcastsDir(): Promise<DirectoryPathResult>;

  /**
   * 返回系统铃声目录。
   *
   * - Android：`/storage/emulated/0/Ringtones`
   * - iOS：没有 → `{ path: null }`
   *
   * @since 0.5.0
   */
  getRingtonesDir(): Promise<DirectoryPathResult>;

  /**
   * 返回 App 私有**数据根目录**。
   *
   * - Android：`/data/user/0/<pkg>`（`files` / `cache` 的上级）
   * - iOS：沙盒根 `NSHomeDirectory()`
   *
   * @since 0.5.0
   */
  getAppDataDir(): Promise<DirectoryPathResult>;

  /**
   * 返回 App **外部私有根目录**。
   *
   * - Android：`/storage/emulated/0/Android/data/<pkg>`（`files` / `cache` 的上级）
   * - iOS：没有 → `{ path: null }`
   *
   * @since 0.5.0
   */
  getAppExternalRootDir(): Promise<DirectoryPathResult>;

  /**
   * 返回 native 库目录。
   *
   * - Android：APK 内的 `lib/<abi>` 目录（`applicationInfo.nativeLibraryDir`）
   * - iOS：没有该概念 → `{ path: null }`
   *
   * @since 0.5.0
   */
  getNativeLibDir(): Promise<DirectoryPathResult>;

  /**
   * 返回 `Library` 目录。
   *
   * - iOS：`Library`（`Application Support` / `Caches` 的上级）
   * - Android：没有该概念 → `{ path: null }`
   *
   * @since 0.5.0
   */
  getLibraryDir(): Promise<DirectoryPathResult>;

  /**
   * 返回 iCloud Documents 目录。
   *
   * - iOS：iCloud 容器里的 `Documents`（**需要宿主开启 iCloud 能力**，
   *   否则 `{ path: null, reason: 'ICLOUD_NOT_AVAILABLE' }`）
   * - Android：没有该概念 → `{ path: null }`
   *
   * @since 0.5.0
   */
  getIcloudDocumentsDir(): Promise<DirectoryPathResult>;

  /**
   * 返回 App 安装包路径。
   *
   * - Android：APK 文件路径（`applicationInfo.sourceDir`）
   * - iOS：`.app` 包目录
   *
   * @since 0.4.0
   */
  getAppInstallPath(): Promise<DirectoryPathResult>;
}

/** `pickFiles` 参数。 */
export interface PickFilesOptions {
  /** 是否多选（默认 `false`）。Android 用 `EXTRA_ALLOW_MULTIPLE`；iOS 用 `allowsMultipleSelection`。 */
  multiple?: boolean;
  /**
   * 只显示这些 MIME 类型（如 `['image/*', 'text/markdown']`）。
   *
   * - Android：作为 `EXTRA_MIME_TYPES` 传给系统选择器（真正的过滤）；
   * - iOS：目前**忽略**（`UTType.item`，由系统按文件类型展示）。
   */
  mimeTypes?: string[];
}

/** `pickFolders` 参数（两端目前都是单选，`multiple` 仅保留字段）。 */
export interface PickFoldersOptions {
  /** 保留字段；Android SAF 不支持文件夹多选，iOS 也统一为单选。 */
  multiple?: boolean;
}

/** 文件条目（`listFolder` / `pickFiles` 返回）。 */
export interface FileEntry {
  /** 真实绝对路径，可直接拼子路径或传给本插件其它 API。 */
  path: string;
  /** 文件名（含扩展名），不含目录部分。 */
  name: string;
  /** 恒为 `false`（用于和 `DirEntry` 做联合类型收窄）。 */
  isDirectory: false;
  /** 文件字节数（目录恒为 0）。 */
  size: number;
  /** 最后修改时间（Unix 毫秒；未知时为 0）。 */
  lastModified: number;
  /** MIME 类型（按扩展名推断；未知时为空字符串 `''`）。 */
  mimeType: string;
}

/** 目录条目。 */
export interface DirEntry {
  /** 真实绝对路径。 */
  path: string;
  /** 目录名。 */
  name: string;
  /** 恒为 `true`。 */
  isDirectory: true;
  /** 恒为 0。 */
  size: 0;
  /** 最后修改时间（Unix 毫秒；未知时为 0）。 */
  lastModified: number;
  /** 恒为 `'inode/directory'`。 */
  mimeType: 'inode/directory';
}

/** `listFolder` 的条目：用 `isDirectory` 收窄。 */
export type FsEntry = FileEntry | DirEntry;

/** `pickFiles` 返回值。 */
export interface PickFilesResult {
  /** 选中的文件（用户取消时为空数组）。 */
  files: FileEntry[];
  /** 用户是否取消了选择。 */
  cancelled: boolean;
  /**
   * 无法映射成真实路径的 URI 列表（Android 云盘 / 第三方 SAF provider）。
   *
   * 这些条目**不会出现在 `files` 里**（没有可用的绝对路径），只在有值时返回。
   */
  unmapped?: string[];
  /** 当存在 `unmapped` 时给出的提示文案（英文）。 */
  warning?: string;
}

/** `pickFolders` 返回值。 */
export interface PickFoldersResult {
  /** 选中的文件夹（当前实现最多 1 个；取消时为空数组）。 */
  folders: DirEntry[];
  /** 用户是否取消了选择。 */
  cancelled: boolean;
}

/** `readFile` 参数。 */
export interface ReadFileOptions {
  /** 文件绝对路径。 */
  path: string;
  /** 起始字节偏移（默认 0；大于文件大小时返回空内容）。 */
  offset?: number;
  /** 读取字节数（默认 = 上限 **16MB**；超出会被截断到 16MB）。 */
  length?: number;
}

/** `readFile` 返回值。 */
export interface ReadFileResult {
  /** 文件的绝对路径（原样返回）。 */
  path: string;
  /** 本次读取内容的 base64（标准 base64，无换行）。 */
  base64: string;
  /** 本次实际读取的字节数（= `base64` 解码后的长度）。 */
  bytes: number;
  /** **文件总大小**（不是本次读取的长度）。 */
  size: number;
  /** 本次读取的起始偏移（原样返回，便于分块拼接）。 */
  offset: number;
}

/** `writeFile` 参数。 */
export interface WriteFileOptions {
  /** 目标文件绝对路径（父目录不存在会自动创建）。 */
  path: string;
  /** 要写入的内容（**base64 编码**；用 `btoa` / `FileReader.readAsDataURL` 的后半段）。 */
  base64: string;
  /**
   * 写入模式：
   * - `create`：文件已存在则 reject（`FILE_ALREADY_EXISTS`）
   * - `overwrite`（默认）：清空后写入
   * - `append`：追加到末尾
   */
  mode?: 'create' | 'overwrite' | 'append';
}

/** `writeFile` 返回值。 */
export interface WriteFileResult {
  /** 写入的文件绝对路径。 */
  path: string;
  /** 本次写入的字节数（= `base64` 解码后的长度）。 */
  bytes: number;
  /** 写入后的**文件总大小**（append 时会大于 `bytes`）。 */
  size: number;
}

/** `deleteFile` / `deleteFolder` 返回值。 */
export interface DeleteResult {
  /** 是否真的删除了（文件/目录本来就不存在时为 `false`）。 */
  deleted: boolean;
  /** 失败原因：`NOT_FOUND` / `NOT_EMPTY_OR_FAILED` 等。 */
  reason?: string;
  /** 补充提示（例如非空目录建议传 `recursive: true`）。 */
  hint?: string;
}

/** `listFolder` 参数。 */
export interface ListFolderOptions {
  /** 目录绝对路径。 */
  path: string;
  /** 是否递归列出子目录（默认 `false`；深度优先）。 */
  recursive?: boolean;
  /** 最多返回多少条（默认 5000，最大 20000）。 */
  maxEntries?: number;
}

/** `listFolder` 返回值。 */
export interface ListFolderResult {
  /** 被列出的目录绝对路径。 */
  path: string;
  /** 条目列表：文件夹在前，再按名称（大小写不敏感）升序。 */
  entries: FsEntry[];
  /** 是否因为 `maxEntries` 被截断。 */
  truncated: boolean;
}

/** `getInfo` 返回值（文件或目录）。 */
export interface PathInfo {
  /** 绝对路径。 */
  path: string;
  /** 文件名/目录名（不含父路径）。 */
  name: string;
  /** 是否存在（不存在时其余字段为零值，且**不会 reject**）。 */
  exists: boolean;
  /** 是否是目录。 */
  isDirectory: boolean;
  /** 是否是普通文件（存在且不是目录）。 */
  isFile: boolean;
  /** 字节数（目录恒为 0）。 */
  size: number;
  /** 最后修改时间（Unix 毫秒；未知为 0）。 */
  lastModified: number;
  /** 当前进程是否可读。 */
  readable: boolean;
  /** 当前进程是否可写。 */
  writable: boolean;
  /** MIME 类型（文件按扩展名推断；目录为 `'inode/directory'`；未知为空串）。 */
  mimeType: string;
}

/** 权限状态（Android 有实际含义；iOS 恒为 `applicable: false`）。 */
export interface AllFilesAccessStatus {
  /** 是否已获得"所有文件访问权限"（Android）／是否可用（iOS 恒 true）。 */
  granted: boolean;
  /** iOS 上为 `false`（没有该权限概念）。 */
  applicable?: boolean;
  /** iOS 上的说明文案（提示用 document picker 拿 scoped 访问）。 */
  message?: string;
  /** Android API level（如 `36`）。 */
  androidVersion?: number;
  /** Android 11+ 为 `true`（需要跳系统设置页）；≤10 为 `false`（走运行时权限）。 */
  requiresSettings?: boolean;
  /** 需要/使用的权限名：`MANAGE_EXTERNAL_STORAGE` 或 `WRITE_EXTERNAL_STORAGE`。 */
  permission?: string;
}

/**
 * 单条路径查询的返回值。
 *
 * - 路径适用：`{ path: '/storage/emulated/0/Documents' }`
 * - 该平台没有这个目录：`{ path: null, reason: 'NOT_APPLICABLE_ON_IOS' }`
 * - 外部存储未挂载：`{ path: null, reason: 'EXTERNAL_STORAGE_NOT_MOUNTED' }`
 * - iCloud 未开启：`{ path: null, reason: 'ICLOUD_NOT_AVAILABLE' }`
 *
 * 注意：`path` 不为 `null` **不代表目录已存在**，只表示"该目录应当在这里"；
 * 判断存在性用 `getInfo({ path })` 或直接 `createFolder`。
 */
export interface DirectoryPathResult {
  /** 绝对路径；`null` 表示该平台没有这个目录。 */
  path: string | null;
  /** `path` 为 `null` 时说明原因（`NOT_APPLICABLE_ON_IOS` 等）。 */
  reason?: string;
}
