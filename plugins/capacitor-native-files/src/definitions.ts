import type { PluginListenerHandle } from '@capacitor/core';

/**
 * NativeFiles 插件（capacitor-native-files）。
 *
 * 提供**系统原生文件/文件夹选择器**与**文件系统读写**能力，路径统一用 URI 表示：
 * - Android：`content://` 或 `file://`（SAF 授权，无需存储权限）
 * - iOS：`file://`（系统选择器授权 + security-scoped bookmark）
 *
 * 设计目标：与 `capacitor-data-channel` 配合——选中大文件后可以先 `copyToAppStorage()`
 * 复制到 App 自有目录，再用 data-channel 的高速 HTTP/WebSocket 传输。
 *
 * ```ts
 * // 1) 选择文件
 * const picked = await NativeFiles.pickFiles({ multiple: true });
 * // 2) 复制到 App 存储（返回 data-channel 可用的相对路径）
 * const copied = await NativeFiles.copyToAppStorage({ uri: picked.files[0].uri });
 * // 3) 交给 data-channel 上传
 * await fetch(`${ch.baseUrl}/upload?path=${copied.relativePath}`, { ... });
 * ```
 */
export interface NativeFilesPlugin {
  /**
   * 打开系统文件选择器（支持多选）。
   *
   * @since 0.1.0
   */
  pickFiles(options?: PickFilesOptions): Promise<PickFilesResult>;

  /**
   * 打开系统文件夹选择器（**单选**：Android 的 SAF 不支持多选文件夹）。
   *
   * **重要**：iOS 上选择文件夹后，App 获得的是 security-scoped 授权；Android 上是 SAF tree URI。
   * 两个平台都不保证长期有效（App 重启后 Android 需要重新确认，iOS 需要持久化 bookmark）。
   *
   * @since 0.1.0
   */
  pickFolders(options?: PickFoldersOptions): Promise<PickFoldersResult>;

  /**
   * 读取文件内容（base64）。大文件建议配合 `offset`/`length` 分块读取，或先复制到 App 存储。
   *
   * @since 0.1.0
   */
  readFile(options: ReadFileOptions): Promise<ReadFileResult>;

  /**
   * 写文件：`create`（新建，已存在则失败）/ `append`（追加）/ `overwrite`（覆盖）。
   *
   * @since 0.1.0
   */
  writeFile(options: WriteFileOptions): Promise<WriteFileResult>;

  /**
   * 删除文件。
   *
   * @since 0.1.0
   */
  deleteFile(options: { uri: string }): Promise<void>;

  /**
   * 列出文件夹内容（文件 + 子文件夹）。
   *
   * @since 0.1.0
   */
  listFolder(options: { uri: string }): Promise<ListFolderResult>;

  /**
   * 在指定文件夹内新建子文件夹。
   *
   * @since 0.1.0
   */
  createFolder(options: { parentUri: string; name: string }): Promise<FolderEntry>;

  /**
   * 删除文件夹（递归删除）。
   *
   * @since 0.1.0
   */
  deleteFolder(options: { uri: string }): Promise<void>;

  /**
   * 重命名文件夹。
   *
   * @since 0.1.0
   */
  renameFolder(options: { uri: string; newName: string }): Promise<FolderEntry>;

  /**
   * 读取文件/文件夹元信息。
   *
   * @since 0.1.0
   */
  getInfo(options: { uri: string }): Promise<FileInfo>;

  /**
   * 把文件复制到 App 自有目录（data-channel 的默认根目录），返回 data-channel 可用的相对路径。
   * 用于"系统选择器选中的文件"交给 data-channel 做高速传输。
   *
   * @since 0.1.0
   */
  copyToAppStorage(options: CopyToAppStorageOptions): Promise<CopyToAppStorageResult>;

  /**
   * 监听文件选择器被取消等事件（预留）。
   *
   * @since 0.1.0
   */
  addListener(
    eventName: 'pickerCancelled',
    listenerFunc: () => void,
  ): Promise<PluginListenerHandle>;

  /**
   * 移除本插件的所有监听。
   *
   * @since 0.1.0
   */
  removeAllListeners(): Promise<void>;
}

/** 选择文件参数。 */
export interface PickFilesOptions {
  /** 是否多选，默认 false。 */
  multiple?: boolean;
  /** MIME 类型过滤，如 `['image/*', 'application/pdf']`；不传则不过滤。 */
  mimeTypes?: string[];
}

/** 选中的文件。 */
export interface PickedFile {
  /** 文件 URI（Android content://file://，iOS file://）。 */
  uri: string;
  /** 显示名（含扩展名）。 */
  name: string;
  /** 字节数（未知时为 -1）。 */
  size: number;
  /** MIME 类型（未知时为空串）。 */
  mimeType: string;
  /** iOS 的 security-scoped bookmark（base64），可用于后续重新访问。 */
  bookmark?: string;
}

export interface PickFilesResult {
  /** 选中的文件；用户取消时为空数组。 */
  files: PickedFile[];
  /** 是否被用户取消。 */
  cancelled: boolean;
}

export interface PickFoldersOptions {
  /** 是否多选；Android 不支持多选文件夹，会忽略此参数。 */
  multiple?: boolean;
}

export interface PickFoldersResult {
  /** 选中的文件夹（当前实现最多 1 个）。 */
  folders: FolderEntry[];
  cancelled: boolean;
}

/** 文件夹/文件条目。 */
export interface FolderEntry {
  uri: string;
  name: string;
  /** 是否为文件夹。 */
  isDirectory: boolean;
  size: number;
  mimeType?: string;
  /** 最后修改时间（毫秒，未知时 0）。 */
  lastModified: number;
}

export interface ReadFileOptions {
  uri: string;
  /** 起始字节偏移，默认 0。 */
  offset?: number;
  /** 读取字节数，默认读到结尾。 */
  length?: number;
}

export interface ReadFileResult {
  /** base64 编码的内容。 */
  base64: string;
  /** 实际读取的字节数。 */
  bytes: number;
  /** 文件总大小。 */
  size: number;
}

export interface WriteFileOptions {
  uri: string;
  /** base64 编码的内容。 */
  base64: string;
  /** 写入模式，默认 `overwrite`。 */
  mode?: 'create' | 'append' | 'overwrite';
}

export interface WriteFileResult {
  /** 实际写入的字节数。 */
  bytes: number;
  /** 写入后的文件总大小。 */
  size: number;
}

export interface ListFolderResult {
  /** 子条目（按名称排序）。 */
  entries: FolderEntry[];
}

export interface FileInfo {
  uri: string;
  name: string;
  exists: boolean;
  isDirectory: boolean;
  size: number;
  mimeType: string;
  lastModified: number;
}

export interface CopyToAppStorageOptions {
  /** 源文件 URI。 */
  uri: string;
  /** 目标相对路径（相对 data-channel 根目录），默认用源文件名。 */
  relativePath?: string;
}

export interface CopyToAppStorageResult {
  /** App 自有目录内的绝对路径。 */
  absolutePath: string;
  /** data-channel 可用的相对路径。 */
  relativePath: string;
  /** 复制的字节数。 */
  bytes: number;
}
