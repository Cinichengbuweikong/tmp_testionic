/**
 * 后台保活插件（Android 前台服务 / iOS 尽力而为 / Web 不支持）。
 *
 * ## 能力边界（先读这里）
 *
 * - **Android**：`start()` 会拉起一个**前台服务**并显示常驻通知。前台服务是唯一被系统
 *   承认的"App 在后台继续运行"的手段 —— 只发一条普通通知**不会**让进程免于被杀。
 *   默认使用 `specialUse` 类型（不受 Android 14+ 的 `dataSync` 6 小时限制），
 *   可选 `mediaPlayback`（真的在放音/放视频的 App 用）。
 * - **iOS**：**不存在**等价的"常驻通知 + 永久保活"。本插件提供三种尽力方案：
 *   - `backgroundTask`：`beginBackgroundTask` 续期，只能多争取几十秒到几分钟；
 *   - `audio`：后台播放静音音频（需要宿主 Info.plist 里 `UIBackgroundModes: audio`），
 *     这是唯一能让进程**持续**运行的方式，但**非音频类 App 有被 App Store 拒审的风险**；
 *   - `location`：持续定位（需要 `UIBackgroundModes: location` + 定位权限），
 *     同样只适合导航/运动类 App。
 * - **Web**：浏览器没有后台执行保证（页面隐藏后 setTimeout 会被节流/冻结），返回
 *   `running: false` 并说明原因。
 *
 * README 里有完整的能力矩阵、Android 14+ 前台服务类型限制、Google Play / App Store 政策。
 */
export interface BackgroundKeepalivePlugin {
  /**
   * 开始保活。
   *
   * @param options.mode
   *   - Android：`specialUse`（默认）/ `mediaPlayback` / `dataSync`
   *   - iOS：`auto`（默认，有 audio 后台模式就用 audio，否则 backgroundTask）/
   *     `audio` / `location` / `backgroundTask`
   * @param options.notification Android 常驻通知的外观
   * @param options.keepScreenOn Android：前台服务期间保持屏幕常亮
   */
  start(options?: KeepaliveStartOptions): Promise<KeepaliveStatus>;

  /** 停止保活并移除通知。 */
  stop(): Promise<KeepaliveStatus>;

  /** 当前是否在保活中（不会产生副作用）。 */
  isRunning(): Promise<KeepaliveStatus>;

  /** 更新 Android 常驻通知的标题/正文（iOS/Web 为空操作）。 */
  updateNotification(options: {
    title?: string;
    text?: string;
    subText?: string;
  }): Promise<KeepaliveStatus>;

  /**
   * 平台能力矩阵：每个模式是否受支持、需要什么宿主配置。
   * 用来在 UI 上告诉用户"iOS 上做不到永久保活"。
   */
  getCapabilities(): Promise<KeepaliveCapabilities>;

  /** Android：是否已豁免电池优化（iOS/Web 返回 `applicable:false`）。 */
  isBatteryOptimizationIgnored(): Promise<{
    applicable: boolean;
    ignored: boolean;
  }>;

  /** Android：弹出系统的"忽略电池优化"授权对话框。 */
  requestBatteryOptimizationExemption(): Promise<{
    applicable: boolean;
    ignored: boolean;
    requested: boolean;
  }>;

  /** Android：打开本 App 的电池优化设置页（用户拒绝对话框时的兜底）。 */
  openBatteryOptimizationSettings(): Promise<{ opened: boolean }>;

  /** 监听保活状态变化（start/stop、服务被系统重建/杀死）。 */
  addListener(
    eventName: 'keepaliveChanged',
    listenerFunc: (status: KeepaliveStatus) => void,
  ): Promise<import('@capacitor/core').PluginListenerHandle>;
}

export interface KeepaliveStartOptions {
  mode?: KeepaliveMode;
  notification?: {
    title?: string;
    text?: string;
    subText?: string;
    /** 通知小图标资源名（Android drawable），默认用 App 图标 */
    smallIcon?: string;
    channelId?: string;
    channelName?: string;
    /** 是不是可以划掉（false = ongoing 常驻，默认 false） */
    dismissible?: boolean;
  };
  keepScreenOn?: boolean;
  /** iOS：audio 模式下的音量（0-1，默认 1.0；音频本身是静音） */
  audioVolume?: number;
  /** iOS：location 模式下的最小位移（米，默认 10） */
  distanceFilter?: number;
}

export type KeepaliveMode =
  | 'specialUse'
  | 'mediaPlayback'
  | 'dataSync'
  | 'auto'
  | 'audio'
  | 'location'
  | 'backgroundTask';

export interface KeepaliveStatus {
  running: boolean;
  platform: 'android' | 'ios' | 'web';
  /** 实际生效的模式（未运行为 `none`） */
  mode:
    | 'foreground-service'
    | 'audio'
    | 'location'
    | 'background-task'
    | 'none';
  /** Android：前台服务是否在运行 */
  foregroundService?: boolean;
  /** Android：通知是否真的显示出来了（Android 13+ 需要 POST_NOTIFICATIONS） */
  notificationVisible?: boolean;
  /** Android：是否持有 POST_NOTIFICATIONS */
  notificationPermissionGranted?: boolean;
  /** Android：电池优化是否已豁免 */
  batteryOptimizationIgnored?: boolean;
  /** iOS：后台剩余时间（秒，`backgroundTask` 模式） */
  backgroundTimeRemaining?: number | null;
  /** 一句话说明（尤其是"不支持"或"被系统打断"的时候） */
  note?: string;
}

export interface KeepaliveCapabilities {
  platform: 'android' | 'ios' | 'web';
  /** 是否能做到「进程长期存活」 */
  continuous: boolean;
  modes: KeepaliveModeCapability[];
  note: string;
}

export interface KeepaliveModeCapability {
  mode: string;
  supported: boolean;
  /** 需要的宿主配置（Info.plist / AndroidManifest / 权限） */
  requires: string[];
  /** 这个模式的真实效果 */
  effect: string;
  recommended: boolean;
}
