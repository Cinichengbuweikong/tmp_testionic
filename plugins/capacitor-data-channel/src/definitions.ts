import type { PluginListenerHandle } from '@capacitor/core';

/**
 * DataChannel 插件（capacitor-data-channel）。
 *
 * ======================= 这个插件解决什么 =======================
 *
 * Capacitor 的 JS↔Native 桥是 **JSON 序列化**（复杂类型必须 base64），传 10MB 视频要经历
 * "base64 膨胀 33% + JSON 编解码"，代价极高。本插件改用 **本地 HTTP + WebSocket Server**
 * （只绑 127.0.0.1、随机端口、Bearer token 鉴权），让 WebView 用标准 `fetch` /
 * `<video src>` / `new WebSocket` 直接收发**原始二进制**，绕开桥，从而高效传输大体积数据。
 *
 * - HTTP：大文件上传/下载（流式、Range、断点续传）
 * - WebSocket：实时双向二进制帧（音视频流、连续 buffer、消息通道）
 *
 * ======================= 典型用法 =======================
 *
 * ```ts
 * const ch = await DataChannel.start({ rootDir: '/sdcard/Download/myData' });
 *
 * // 上传（原始二进制，不经过 base64）
 * await fetch(`${ch.baseUrl}/upload?path=clip.mp4`, {
 *   method: 'PUT',
 *   headers: { Authorization: `Bearer ${ch.token}` },
 *   body: fileBlob,
 * });
 *
 * // 下载（流式；<video>/<img> 可带 query token 直接引用）
 * const res = await fetch(`${ch.baseUrl}/download?path=clip.mp4&token=${ch.token}`);
 *
 * // WebSocket 实时双向二进制（默认 echo：收到什么回什么）
 * const ws = new WebSocket(ch.wsUrl);
 * ws.binaryType = 'arraybuffer';
 * ws.onmessage = (e) => console.log('got', e.data.byteLength, 'bytes');
 * ws.send(new Uint8Array([1, 2, 3]));
 * ```
 */
export interface DataChannelPlugin {
  /**
   * 启动本地 HTTP + WebSocket Server（若已启动则返回现有信息）。
   *
   * @since 0.1.0
   */
  start(options?: StartChannelOptions): Promise<ChannelInfo>;

  /**
   * 停止 server（会中断进行中的 HTTP 连接与 WebSocket 连接）。
   *
   * @since 0.1.0
   */
  stop(): Promise<ChannelInfo>;

  /**
   * 读取当前通道信息（不改变状态）。
   *
   * @since 0.1.0
   */
  getInfo(): Promise<ChannelInfo>;

  /**
   * 是否已获得"访问任意存储路径"的权限。
   *
   * - Android 11+：`MANAGE_EXTERNAL_STORAGE`（所有文件访问权限）
   * - Android 10 及以下：`WRITE_EXTERNAL_STORAGE`（运行时权限）
   * - iOS：沙盒模型，始终返回 `granted: true`（只能访问沙盒内路径）
   *
   * @since 0.2.0
   */
  checkAllFilesAccess(): Promise<StorageAccessInfo>;

  /**
   * 申请"访问任意存储路径"的权限。
   *
   * - Android 11+：跳转系统设置页，用户需手动开启"所有文件访问权限"
   * - Android 10 及以下：弹出运行时权限对话框
   * - iOS：无操作，直接返回 `granted: true`
   *
   * 调用后请重新 `checkAllFilesAccess()` 或直接重试 `start({ rootDir: '/绝对路径' })`。
   *
   * @since 0.2.0
   */
  requestAllFilesAccess(): Promise<StorageAccessInfo>;

  /**
   * 监听通道状态变化（start/stop）。
   *
   * @since 0.1.0
   */
  addListener(
    eventName: 'channelStateChanged',
    listenerFunc: (info: ChannelInfo) => void,
  ): Promise<PluginListenerHandle>;

  /**
   * 移除本插件的所有监听。
   *
   * @since 0.1.0
   */
  removeAllListeners(): Promise<void>;
}

/** 启动参数。 */
export interface StartChannelOptions {
  /**
   * 端口号；0 或省略 = 由系统分配随机端口（推荐，避免冲突与被猜）。
   */
  port?: number;

  /**
   * 数据根目录。三种写法：
   *
   * 1. **不传**（默认）：
   *    - Android：`/sdcard/Android/data/<包名>/files/dataChannel`（App 专属外部目录，**无需权限**）
   *    - iOS：`Documents/dataChannel`（可在"文件"App 中访问，需 Info.plist 开启 UIFileSharingEnabled）
   * 2. **相对路径**（如 `myData`）：相对 App 私有目录
   * 3. **绝对路径**（如 `/sdcard/Download/myData`）：任意位置
   *    - Android 11+ 需要"所有文件访问权限"（先调 `requestAllFilesAccess()`）
   *    - Android 10 及以下需要存储权限（同样调 `requestAllFilesAccess()`）
   *    - iOS 只允许 App 沙盒内的路径（Documents / Application Support / Caches / tmp）
   *
   * 所有 HTTP 路由的 `path` 参数都相对这个目录解析。
   */
  rootDir?: string;

  /**
   * 单个 HTTP 请求允许的最大字节数（默认 512MB）。超过返回 413。
   */
  maxBodyBytes?: number;

  /**
   * WebSocket 是否开启 echo 模式（默认 `true`）：收到什么就原样回发什么。
   * 关闭后收到的消息会被丢弃——这是留给后续开发者接入自有业务逻辑的模式。
   *
   * @since 0.2.0
   */
  wsEcho?: boolean;
}

/** 通道信息。 */
export interface ChannelInfo {
  /** server 是否在运行。 */
  running: boolean;

  /** 实际监听的端口（随机端口时由系统分配）。 */
  port: number;

  /** HTTP 基础 URL，如 `http://127.0.0.1:43123`（只监听 127.0.0.1）。 */
  baseUrl: string;

  /** WebSocket URL，如 `ws://127.0.0.1:43123/ws?token=xxx`（未运行时为空）。 */
  wsUrl: string;

  /** 访问令牌：请求需带 `Authorization: Bearer <token>` 或 `?token=<token>`。 */
  token: string;

  /** 数据根目录的绝对路径（便于与文件系统插件配合）。 */
  rootPath: string;

  /** 单请求最大字节数。 */
  maxBodyBytes: number;

  /** 启动时间戳（毫秒），未运行为 0。 */
  startedAt: number;

  /** 当前活动 HTTP 连接数。 */
  activeConnections: number;

  /** 当前已连接的 WebSocket 客户端数。 */
  wsClients: number;

  /** WebSocket 端点是否可用。 */
  webSocketEnabled: boolean;
}

/** 存储访问权限信息。 */
export interface StorageAccessInfo {
  /** 是否已获得访问任意路径的权限。 */
  granted: boolean;

  /** 平台：`android` / `ios`。 */
  platform?: string;

  /** Android 权限模式：`MANAGE_EXTERNAL_STORAGE` / `WRITE_EXTERNAL_STORAGE`；iOS 为 `sandbox`。 */
  mode?: string;

  /** Android SDK 版本。 */
  sdkInt?: number;

  /** 是否已打开系统设置/权限对话框（需要用户操作）。 */
  opened?: boolean;
}
