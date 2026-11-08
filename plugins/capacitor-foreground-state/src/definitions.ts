import type { PluginListenerHandle } from '@capacitor/core';

/**
 * ForegroundState 插件（capacitor-foreground-state）。
 *
 * 让 WebView 里的 JS 知道**应用当前是否在前台**，并在前后台切换时收到事件。
 *
 * 典型用途：
 * - 切后台时暂停视频/动画/音频、停止轮询，回前台时恢复（省电）；
 * - 切后台前自动保存草稿；
 * - 记录用户使用时长（结合 `backgroundDuration`）；
 * - 长任务（如 data-channel 传输）在切后台时提示用户（iOS 后台会被挂起）。
 *
 * ```ts
 * import { ForegroundState } from 'capacitor-foreground-state';
 *
 * const state = await ForegroundState.getState();
 * const handle = await ForegroundState.addListener('stateChanged', (s) => {
 *   console.log('state:', s.state, 'isActive:', s.isActive, 'backgroundDuration:', s.backgroundDuration);
 * });
 * // 取消单个监听
 * await handle.remove();
 * // 取消所有监听
 * await ForegroundState.removeAllListeners();
 * ```
 */
export interface ForegroundStatePlugin {
  /**
   * 读取当前前台/后台状态。
   *
   * @since 0.1.0
   */
  getState(): Promise<ForegroundStateInfo>;

  /**
   * 监听应用前后台状态变化。
   *
   * 注意：`inactive` 在两端含义略有不同——
   * - iOS：来电/通知中心/控制中心等"半遮挡"时进入 inactive，随后可能回 active 或进 background；
   * - Android：本插件将 pause 视为 background（无单独的 inactive 通知）。
   *
   * @since 0.1.0
   */
  addListener(
    eventName: 'stateChanged',
    listenerFunc: (state: ForegroundStateInfo) => void,
  ): Promise<PluginListenerHandle>;

  /**
   * 移除本插件的所有监听。
   *
   * @since 0.1.0
   */
  removeAllListeners(): Promise<void>;
}

/** 应用前后台状态。 */
export interface ForegroundStateInfo {
  /** 是否处于前台活跃状态（等价于 `state === 'active'`）。 */
  isActive: boolean;

  /**
   * 状态：`active`（前台活跃）/ `inactive`（过渡态，iOS 常见）/ `background`（后台）。
   */
  state: 'active' | 'inactive' | 'background';

  /** 本次状态变化的时间戳（毫秒）。 */
  changedAt: number;

  /** 最近一次进入后台的时间戳（毫秒）；从未进入过后台时为 0。 */
  lastBackgroundAt: number;

  /**
   * 若当前是 active 且刚从后台返回：在后台停留的毫秒数；否则为 0。
   * 可用于统计"用户离开多久"。
   */
  backgroundDuration: number;
}
