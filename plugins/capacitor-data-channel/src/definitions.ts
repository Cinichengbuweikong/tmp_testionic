import type { PluginListenerHandle } from '@capacitor/core';

/**
 * DataChannel 插件（capacitor-data-channel）。
 *
 * ======================= 能力 =======================
 *
 * 两个**完全独立**的本地 server，可分别启停（节省资源、减少攻击面）：
 * - **HTTP**（`startHttp/stopHttp`）：大文件上传/下载（流式、Range）；
 * - **WebSocket**（`startWebSocket/stopWebSocket`）：实时双向二进制帧。
 *
 * ======================= 鉴权：OTP（不是长期 token）=======================
 *
 * 长期 token 一旦被抓包拿到就等于全权限。本插件改用 **OTP**：
 * - OTP 通过 **Capacitor 桥**下发（`createHttpOtp` / `createWsOtp`）——
 *   桥是进程内通信（postMessage），**抓包软件看不到生成过程**；
 * - 抓包者只能看到"已经用掉"的 OTP → 无法重放；
 * - **一次性**（默认）：用一次作废，无时间限制；
 * - **可复用（reusable）**：仅允许 `scope: 'read'`（媒体持续读取用）。
 *   ⚠️ **必须在使用完后调用 `revokeOtp`**，否则在 server 运行期间永久有效（严重安全问题）；
 * - 写操作**不允许 reusable**（每次写必须新 OTP）；
 * - 无 TTL：等价于"server 生命周期内有效"，server 停止即全部失效。
 *
 * ======================= 连接限流 =======================
 *
 * 两级：未认证连接 4（防扫描/DoS）/ 已认证连接 `maxConnections`（默认 16，保证视频多 Range 并发）。
 * 身份识别靠 OTP（连接来源无法识别），限流只用于防滥用。
 *
 * ======================= 典型用法 =======================
 *
 * ```ts
 * const http = await DataChannel.startHttp();
 *
 * // 上传：先通过桥拿一次性 OTP（写）
 * const w = await DataChannel.createHttpOtp({ scope: 'write', path: 'videos/clip.mp4' });
 * await fetch(`${http.baseUrl}/upload?path=videos/clip.mp4`, {
 *   method: 'PUT',
 *   headers: { Authorization: `OTP ${w.otp}` },
 *   body: file,
 * });
 *
 * // 播放：媒体用 reusable + read OTP（放 URL）
 * const r = await DataChannel.createHttpOtp({ scope: 'read', path: 'videos/clip.mp4', reusable: true });
 * video.src = `${http.baseUrl}/download?path=videos/clip.mp4&otp=${r.otp}`;
 * // ⚠️ 播放结束后必须 revoke
 * video.onended = () => DataChannel.revokeOtp({ otp: r.otp });
 *
 * // WebSocket：一次性 OTP 走子协议（不进 URL）
 * const wsInfo = await DataChannel.startWebSocket();
 * const wsOtp = await DataChannel.createWsOtp();
 * const ws = new WebSocket(`${wsInfo.wsUrl}`, [`otp.${wsOtp.otp}`]);
 * ```
 */
export interface DataChannelPlugin {
  /**
   * 启动本地 HTTP Server（若已启动则直接返回现有信息）。
   *
   * @since 0.3.0
   */
  startHttp(options?: StartHttpOptions): Promise<HttpInfo>;

  /**
   * 停止 HTTP Server（会中断进行中的连接；并撤销该 server 的所有 OTP）。
   *
   * @since 0.3.0
   */
  stopHttp(): Promise<HttpInfo>;

  /**
   * 启动本地 WebSocket Server（若已启动则直接返回现有信息）。
   *
   * @since 0.3.0
   */
  startWebSocket(options?: StartWsOptions): Promise<WsInfo>;

  /**
   * 停止 WebSocket Server（会断开所有客户端；并撤销该 server 的所有 OTP）。
   *
   * @since 0.3.0
   */
  stopWebSocket(): Promise<WsInfo>;

  /**
   * 读取 HTTP server 的当前状态。
   *
   * @since 0.3.0
   */
  getHttpInfo(): Promise<HttpInfo>;

  /**
   * 读取 WebSocket server 的当前状态。
   *
   * @since 0.3.0
   */
  getWsInfo(): Promise<WsInfo>;

  /**
   * 创建 HTTP OTP（通过桥下发，抓包看不到）。
   *
   * - `scope` 默认 `'read'`；
   * - `path` **必填**（OTP 绑定到具体文件/文件夹，只能访问该路径）；
   * - `reusable: true` **仅允许** `scope: 'read'`；写操作必须每次新 OTP；
   * - 无 TTL（server 停止即失效）。
   *
   * ⚠️ `reusable: true` 的 OTP **必须在使用完后 `revokeOtp`**，否则在 server 运行期间永久有效。
   *
   * @since 0.3.0
   */
  createHttpOtp(options: CreateHttpOtpOptions): Promise<HttpOtpInfo>;

  /**
   * 创建 WebSocket OTP（一次性）。
   *
   * 用于两处：
   * 1. **建立连接**：`new WebSocket(wsUrl, ['otp.' + otp])`（握手校验）；
   * 2. **每条 Client → Server 消息**：消息体前缀 `[1 字节 otpLen][otp][payload]`。
   *
   * @since 0.3.0
   */
  createWsOtp(): Promise<WsOtpInfo>;

  /**
   * **批量**创建 WebSocket OTP（一次桥调用生成 N 个）。
   *
   * 为什么需要：Capacitor 的插件调用是**串行**的（Android 单 `HandlerThread` / iOS 串行
   * `DispatchQueue`），每条消息一次 `createWsOtp` 会累加往返延迟；批量生成把 N 次调用压成 1 次，
   * 高频小包场景能省掉绝大部分桥开销。配合 `encodeWsMessageWithOtp`（同步）使用。
   *
   * @param options.count 1..256（与当前活跃 OTP 数之和不得超过 256）
   * @since 0.4.0
   */
  createWsOtps(options: { count: number }): Promise<{ otps: string[]; count: number }>;

  /**
   * **批量**创建 HTTP OTP（一次桥调用生成 N 个）。
   *
   * @param options.count 1..256；其余参数同 `createHttpOtp`
   * @since 0.4.0
   */
  createHttpOtps(options: CreateHttpOtpOptions & { count: number }): Promise<{
    otps: string[];
    count: number;
    scope: string;
    path: string;
    reusable: boolean;
  }>;

  /**
   * **批量**验证并消费 OTP（用于 WebView 校验 Server → Client 的 WS 消息）。
   *
   * ⚠️ 只有在你能够接受"先攒 K 条、验证后再交付"的延迟时才用；返回时结果已全部验证，
   * 未通过项必须丢弃。
   *
   * @since 0.4.0
   */
  validateAndConsumeOtps(options: { otps: string[] }): Promise<{ results: boolean[] }>;

  /**
   * 验证并消费一个 OTP（用于 WebView 校验 **Server → Client** 的 WS 消息）。
   *
   * Server 每发一条消息，会生成一个一次性 nonce 并放进消息前缀
   * （`[1 字节 otpLen][nonce][payload]`），同时记录在服务端 OTP 表里。
   * JS 收到消息后调用本 API：
   * - 返回 `valid: true` → 该消息来自本 App 的 native（合法），并且该 nonce 已被消费（不可重放）；
   * - 返回 `valid: false` → 消息被篡改/伪造/重放，**必须丢弃**。
   *
   * 注意：本 API 会同时检查 HTTP 与 WS 的 OTP 表（消费成功即返回 true）。
   *
   * @since 0.3.0
   */
  validateAndConsumeOTP(options: { otp: string }): Promise<{ valid: boolean }>;

  /**
   * 撤销单个 OTP（媒体播放结束/页面卸载时调用）。
   *
   * @since 0.3.0
   */
  revokeOtp(options: { otp: string }): Promise<{ revoked: boolean }>;

  /**
   * 批量撤销 OTP（不传 server 则两者都撤销）。
   *
   * 建议在切后台、页面卸载、退出播放时调用，避免 reusable OTP 长期有效。
   *
   * @since 0.3.0
   */
  revokeAllOtps(options?: { server?: 'http' | 'ws' }): Promise<{ revoked: number }>;

  /**
   * 监听通道状态变化（start/stop 时派发，载荷同 `getInfo`）。
   *
   * @since 0.3.0
   */
  addListener(
    eventName: 'stateChanged',
    listenerFunc: (info: ChannelInfo) => void,
  ): Promise<PluginListenerHandle>;

  /**
   * 移除本插件的所有监听。
   *
   * @since 0.3.0
   */
  removeAllListeners(): Promise<void>;
}

/** 启动 HTTP Server 的参数。 */
export interface StartHttpOptions {
  /** 端口号；0 或省略 = 系统随机分配（推荐）。 */
  port?: number;

  /**
   * 数据根目录。三种写法：
   * 1. **不传**：Android `/sdcard/Android/data/<包名>/files/dataChannel`（无需权限）；
   *    iOS `Documents/dataChannel`；
   * 2. **相对路径**：相对 App 私有目录；
   * 3. **绝对路径**：任意位置（权限由 `capacitor-native-files` 或系统设置提供）。
   */
  rootDir?: string;

  /** 单个 HTTP 请求最大字节数（默认 512MB）。 */
  maxBodyBytes?: number;

  /** 已认证连接并发上限（默认 16；未认证连接上限固定为 4）。 */
  maxConnections?: number;
}

/** 启动 WebSocket Server 的参数。 */
export interface StartWsOptions {
  /** 端口号；0 或省略 = 系统随机分配。 */
  port?: number;

  /** 是否 echo（默认 true）：收到什么回发什么；false 时丢弃消息，留给业务接入。 */
  wsEcho?: boolean;

  /** 已认证连接并发上限（默认 16；未认证连接上限固定为 4）。 */
  maxConnections?: number;
}

/** 创建 HTTP OTP 的参数。 */
export interface CreateHttpOtpOptions {
  /** 权限：`read`（默认）/ `write` / `readwrite`。 */
  scope?: 'read' | 'write' | 'readwrite';

  /** **必填**：OTP 绑定的路径（相对 rootDir，如 `videos/clip.mp4`）。 */
  path: string;

  /** 是否可复用（默认 false）。**仅允许 `scope: 'read'`**；用完后必须 revoke。 */
  reusable?: boolean;
}

/** HTTP server 信息。 */
export interface HttpInfo {
  running: boolean;
  port: number;
  baseUrl: string;
  rootPath: string;
  maxBodyBytes: number;
  maxConnections: number;
  activeConnections: number;
  pendingConnections: number;
  otpCount: number;
  startedAt: number;
}

/** WebSocket server 信息。 */
export interface WsInfo {
  running: boolean;
  port: number;
  wsUrl: string;
  wsEcho: boolean;
  maxConnections: number;
  activeConnections: number;
  pendingConnections: number;
  clients: number;
  otpCount: number;
  startedAt: number;
}

/** 两个 server 的状态。 */
export interface ChannelInfo {
  http: HttpInfo | null;
  webSocket: WsInfo | null;
}

/** HTTP OTP 信息。 */
export interface HttpOtpInfo {
  /** OTP 本身（通过桥下发，抓包看不到）。 */
  otp: string;
  scope: string;
  path: string;
  reusable: boolean;
}

/** WebSocket OTP 信息。 */
export interface WsOtpInfo {
  otp: string;
  reusable: false;
}
