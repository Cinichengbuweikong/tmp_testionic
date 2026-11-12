import type { PluginListenerHandle } from '@capacitor/core';

/**
 * 开机自启插件（`capacitor-boot-autostart`）。
 *
 * **它做什么**：在 Android 上注册 `BOOT_COMPLETED` 广播，开机后启动一个
 * **前台服务**（带常驻通知），让 App 进程保持存活。这样用户点图标时是
 * **热启动/恢复**（不会重新走冷启动、也不会重新显示启动页），效果接近微信：
 * 点开就能直接用。
 *
 * **它不做什么**（受系统限制）：
 * - **不能**在开机时自动把界面弹到前台（Android 10+ 禁止后台启动 Activity）；
 * - **不能**保证在所有厂商 ROM 上生效（MIUI/HyperOS 等需要用户手动开启"自启动"权限）；
 * - **iOS 完全不支持**（系统没有给第三方 App 提供开机自启能力）。
 *
 * 平台差异：
 * | 能力 | Android | iOS |
 * | --- | --- | --- |
 * | `enable/disable` | ✅ 真实生效 | ❌ 返回 `supported=false` |
 * | 开机保持进程 | ✅ 前台服务 | ❌ |
 * | 开机自动显示界面 | ❌（系统禁止） | ❌ |
 */
export interface BootAutostartPlugin {
  /**
   * 读取当前状态（不会修改任何设置）。
   *
   * @since 0.1.0
   */
  getStatus(): Promise<BootAutostartStatus>;

  /**
   * 开启开机自启：
   * 1. 记录开关；
   * 2. 启用 `BOOT_COMPLETED` 广播接收器；
   * 3. **立即启动一次前台服务**（方便你马上验证；不想到此为止可忽略）。
   *
   * iOS 上返回 `supported=false` 且不改变状态。
   *
   * @since 0.1.0
   */
  enable(): Promise<BootAutostartStatus>;

  /**
   * 关闭开机自启：停止前台服务 + 禁用广播接收器。
   *
   * @since 0.1.0
   */
  disable(): Promise<BootAutostartStatus>;

  /**
   * 申请通知权限（Android 13+ 的 `POST_NOTIFICATIONS`）。
   *
   * 前台服务必须有通知；没有通知权限时服务仍可运行，但用户看不到常驻通知
   * （部分 ROM 会因此杀掉服务）。建议在 `enable()` 前调用。
   *
   * @since 0.1.0
   */
  requestNotificationPermission(): Promise<BootAutostartStatus>;

  /**
   * 申请"忽略电池优化"（Android 6+）。
   *
   * 部分 ROM 在电池优化开启时会杀掉前台服务；对"开机后一直活着"的场景建议申请。
   * 注意：Google Play 对该权限有政策限制（仅特定用途可申请）。
   *
   * @since 0.1.0
   */
  requestIgnoreBatteryOptimizations(): Promise<BootAutostartStatus>;

  /**
   * 打开系统的应用详情设置页（用户可在这里手动允许自启动/后台运行）。
   *
   * @since 0.1.0
   */
  openAppSettings(): Promise<void>;

  /**
   * 尝试打开厂商的"自启动管理"页面（MIUI/HyperOS 等）。
   *
   * 不同 ROM 的入口不同，插件会依次尝试常见 Intent，失败时回退到应用详情页。
   *
   * @since 0.1.0
   */
  openAutostartSettings(): Promise<void>;

  /**
   * 监听后台服务状态变化（启动/停止）。
   *
   * @since 0.1.0
   */
  addListener(
    eventName: 'serviceStateChanged',
    listenerFunc: (event: BootAutostartServiceEvent) => void,
  ): Promise<PluginListenerHandle>;

  /**
   * 移除本插件的所有监听。
   *
   * @since 0.1.0
   */
  removeAllListeners(): Promise<void>;
}

/** 插件状态。 */
export interface BootAutostartStatus {
  /** 当前平台是否支持开机自启（iOS 恒为 false）。 */
  supported: boolean;

  /** 用户是否开启了开机自启（插件记录的开关）。 */
  enabled: boolean;

  /** 广播接收器当前是否处于启用状态（Android）。 */
  receiverEnabled?: boolean;

  /** 前台服务是否正在运行（Android）。 */
  serviceRunning?: boolean;

  /** 是否拥有通知权限（Android 13+）。 */
  notificationsEnabled?: boolean;

  /** 是否已忽略电池优化（Android）。 */
  batteryOptimizationIgnored?: boolean;

  /** Android API level；iOS/Web 为 0。 */
  sdk?: number;

  /** 不支持时的原因（如 iOS）。 */
  reason?: string;
}

/** 前台服务状态变化事件。 */
export interface BootAutostartServiceEvent {
  /** 服务是否在运行。 */
  running: boolean;
}
