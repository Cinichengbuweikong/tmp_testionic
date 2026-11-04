import type { PluginListenerHandle } from '@capacitor/core';

/**
 * DataChannel 插件（capacitor-data-channel）。
 *
 * ======================= 这个插件解决什么 =======================
 *
 * Capacitor 的 JS↔Native 桥是 **JSON 序列化**（复杂类型必须 base64），传 10MB 视频要经历
 * "base64 膨胀 33% + JSON 编解码"，代价极高。本插件改用 **本地 HTTP Server**
 * （只绑 127.0.0.1、随机端口、Bearer token 鉴权），让 WebView 用标准 `fetch` /
 * `<video src>` 直接收发**原始二进制**，绕开桥，从而高效传输大体积数据。
 *
 * ======================= 典型用法 =======================
 *
 * ```ts
 * const ch = await DataChannel.start();
 *
 * // 上传（原始二进制，不经过 base64）
 * await fetch(`${ch.baseUrl}/upload?path=videos/clip.mp4`, {
 *   method: 'PUT',
 *   headers: { Authorization: `Bearer ${ch.token}` },
 *   body: fileBlob,
 * });
 *
 * // 下载（流式；<video>/<img> 可带 query token 直接引用）
 * const res = await fetch(`${ch.baseUrl}/download?path=videos/clip.mp4&token=${ch.token}`);
 * const blob = await res.blob();
 *
 * // 也可以在页面里直接用：video.src = `${ch.baseUrl}/download?path=...&token=${ch.token}`
 * ```
 */
export interface DataChannelPlugin {
  /**
   * 启动本地 HTTP server（若已启动则返回现有信息）。
   *
   * @since 0.1.0
   */
  start(options?: StartChannelOptions): Promise<ChannelInfo>;

  /**
   * 停止本地 HTTP server（会中断进行中的连接）。
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
   * 数据根目录（App 沙盒内的目录名），默认 `data-channel`。
   * 所有 upload/download 的 `path` 都相对这个目录解析。
   */
  rootDir?: string;

  /**
   * 单个请求允许的最大字节数（默认 512MB）。超过返回 413。
   */
  maxBodyBytes?: number;
}

/** 通道信息。 */
export interface ChannelInfo {
  /** server 是否在运行。 */
  running: boolean;

  /** 实际监听的端口（随机端口时由系统分配）。 */
  port: number;

  /** 基础 URL，如 `http://127.0.0.1:43123`（只监听 127.0.0.1）。 */
  baseUrl: string;

  /** 访问令牌：请求需带 `Authorization: Bearer <token>` 或 `?token=<token>`。 */
  token: string;

  /** 数据根目录的绝对路径（App 沙盒内；便于后续文件系统插件直接使用）。 */
  rootPath: string;

  /** 单请求最大字节数。 */
  maxBodyBytes: number;

  /** 启动时间戳（毫秒），未运行为 0。 */
  startedAt: number;
}
