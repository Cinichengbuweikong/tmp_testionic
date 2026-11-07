/**
 * NativeFiles 插件（capacitor-native-files）。
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
 * App 私有目录（`getAppDirectories().dirs.appExternalFilesDir` 等）任何版本都免权限。
 */
export interface NativeFilesPlugin {
  /**
   * 打开系统文件选择器（Android: `ACTION_OPEN_DOCUMENT`；iOS: `UIDocumentPicker`）。
   *
   * @since 0.2.0
   */
  pickFiles(options?: PickFilesOptions): Promise<PickFilesResult>;

  /**
   * 打开系统文件夹选择器，返回**绝对路径**。
   *
   * Android 系统限制只能单选；iOS 为跨平台一致也做单选。
   *
   * @since 0.2.0
   */
  pickFolders(options?: PickFoldersOptions): Promise<PickFoldersResult>;

  /**
   * 读取文件（base64 走桥；单次最多 16MB，可用 offset/length 分块）。
   *
   * @since 0.2.0
   */
  readFile(options: ReadFileOptions): Promise<ReadFileResult>;

  /**
   * 写入文件。`mode`：`create`（已存在则报错）/ `overwrite`（默认）/ `append`。
   * 父目录不存在会自动创建。
   *
   * @since 0.2.0
   */
  writeFile(options: WriteFileOptions): Promise<WriteFileResult>;

  /**
   * 删除文件（目录请用 `deleteFolder`）。
   *
   * @since 0.2.0
   */
  deleteFile(options: { path: string }): Promise<DeleteResult>;

  /**
   * 列出目录内容（默认"文件夹优先 + 名称"排序）。
   *
   * @since 0.2.0
   */
  listFolder(options: ListFolderOptions): Promise<ListFolderResult>;

  /**
   * 创建文件夹（`path` 是**完整目标路径**；父目录自动递归创建）。
   *
   * @since 0.2.0
   */
  createFolder(options: { path: string }): Promise<{ path: string; created: boolean }>;

  /**
   * 删除文件夹。默认只能删空目录；`recursive: true` 递归删除。
   *
   * @since 0.2.0
   */
  deleteFolder(options: { path: string; recursive?: boolean }): Promise<DeleteResult>;

  /**
   * 重命名文件夹（或文件）。返回**新路径**。
   *
   * @since 0.2.0
   */
  renameFolder(options: { path: string; newName: string }): Promise<{
    renamed: boolean;
    path: string;
    from: string;
  }>;

  /**
   * 读取单个文件/目录的详细信息（对应 data-channel 原来的 `GET /info`）。
   *
   * @since 0.2.0
   */
  getInfo(options: { path: string }): Promise<PathInfo>;

  /**
   * 检查是否已获得"所有文件访问权限"（Android）。
   * iOS 恒为 `{ granted: true, applicable: false }`。
   *
   * @since 0.2.0
   */
  checkAllFilesAccess(): Promise<AllFilesAccessStatus>;

  /**
   * 申请"所有文件访问权限"（Android）。
   *
   * - Android 11+：打开系统的"所有文件访问权限"设置页，返回后检查结果；
   * - Android ≤ 10：请求 `WRITE_EXTERNAL_STORAGE` 运行时权限；
   * - iOS：没有该权限概念，返回 `{ granted: true, applicable: false }`。
   *
   * @since 0.2.0
   */
  requestAllFilesAccess(): Promise<AllFilesAccessStatus>;

  /**
   * 返回 App 与系统常用目录的绝对路径（不主动创建）。
   *
   * @since 0.2.0
   */
  getAppDirectories(): Promise<AppDirectoriesResult>;

  /**
   * 把文件复制到 data-channel 的默认根目录（`Android/data/<pkg>/files/dataChannel`），
   * 返回相对路径，方便直接给 data-channel 上传。
   *
   * @since 0.2.0
   */
  copyToAppStorage(options: {
    path: string;
    relativePath?: string;
  }): Promise<{ absolutePath: string; relativePath: string; bytes: number }>;
}

export interface PickFilesOptions {
  /** 是否多选（默认 false）。 */
  multiple?: boolean;
  /** 只显示这些 MIME 类型（Android 用 EXTRA_MIME_TYPES；iOS 忽略）。 */
  mimeTypes?: string[];
}

export interface PickFoldersOptions {
  /** 保留字段（两端目前都是单选）。 */
  multiple?: boolean;
}

/** 文件条目。 */
export interface FileEntry {
  /** 绝对路径。 */
  path: string;
  name: string;
  isDirectory: false;
  size: number;
  lastModified: number;
  mimeType: string;
}

/** 目录条目。 */
export interface DirEntry {
  /** 绝对路径。 */
  path: string;
  name: string;
  isDirectory: true;
  size: 0;
  lastModified: number;
  mimeType: 'inode/directory';
}

export type FsEntry = FileEntry | DirEntry;

export interface PickFilesResult {
  files: FileEntry[];
  cancelled: boolean;
  /** 无法映射成真实路径的 URI（云盘等 provider）。 */
  unmapped?: string[];
  warning?: string;
}

export interface PickFoldersResult {
  folders: DirEntry[];
  cancelled: boolean;
}

export interface ReadFileOptions {
  path: string;
  /** 起始字节偏移（默认 0）。 */
  offset?: number;
  /** 读取字节数（默认/上限 16MB）。 */
  length?: number;
}

export interface ReadFileResult {
  path: string;
  /** base64 编码内容。 */
  base64: string;
  /** 本次读取的字节数。 */
  bytes: number;
  /** 文件总大小。 */
  size: number;
  offset: number;
}

export interface WriteFileOptions {
  path: string;
  /** base64 编码内容。 */
  base64: string;
  mode?: 'create' | 'overwrite' | 'append';
}

export interface WriteFileResult {
  path: string;
  /** 本次写入字节数。 */
  bytes: number;
  /** 写入后的文件大小。 */
  size: number;
}

export interface DeleteResult {
  deleted: boolean;
  reason?: string;
  hint?: string;
}

export interface ListFolderOptions {
  path: string;
  /** 是否递归列出（默认 false）。 */
  recursive?: boolean;
  /** 最多返回多少条（默认 5000）。 */
  maxEntries?: number;
}

export interface ListFolderResult {
  path: string;
  entries: FsEntry[];
  /** 是否因为 maxEntries 被截断。 */
  truncated: boolean;
}

/** 文件/目录信息。 */
export interface PathInfo {
  path: string;
  name: string;
  exists: boolean;
  isDirectory: boolean;
  isFile: boolean;
  size: number;
  lastModified: number;
  readable: boolean;
  writable: boolean;
  mimeType: string;
}

export interface AllFilesAccessStatus {
  granted: boolean;
  /** iOS 上为 false（没有该权限概念）。 */
  applicable?: boolean;
  /** iOS 时的说明。 */
  message?: string;
  androidVersion?: number;
  requiresSettings?: boolean;
  permission?: string;
}

export interface AppDirectoriesResult {
  platform: 'android' | 'ios' | 'web';
  /** key → 绝对路径（不存在/不适用为 null）。 */
  dirs: Record<string, string | null>;
  androidVersion?: number;
  /** Android：是否已获得所有文件访问权限。 */
  allFilesAccess?: boolean;
}
