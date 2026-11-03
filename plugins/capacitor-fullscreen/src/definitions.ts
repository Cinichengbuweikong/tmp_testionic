import type { PluginListenerHandle } from '@capacitor/core';

/**
 * Fullscreen 插件（capacitor-fullscreen）。
 *
 * 用途：**沉浸式全屏**——隐藏状态栏与导航栏，让 WebView 内容完整铺满屏幕
 * （与 capacitor-webview-to-edge 不同：那边系统栏仍可见，只是内容延伸到下面）。
 *
 * 进入全屏后系统栏隐藏、视口自动铺满整屏（由系统/SystemBars 的 insets 处理），
 * 退出后系统栏恢复、布局还原。
 */
export interface FullscreenPlugin {
  /**
   * 进入全屏（隐藏状态栏 + 导航栏）。
   *
   * @since 0.1.0
   */
  enable(): Promise<FullscreenState>;

  /**
   * 退出全屏（恢复系统栏）。
   *
   * @since 0.1.0
   */
  disable(): Promise<FullscreenState>;

  /**
   * 设置全屏状态。
   *
   * @since 0.1.0
   */
  setFullscreen(options: { enabled: boolean }): Promise<FullscreenState>;

  /**
   * 切换全屏状态。
   *
   * @since 0.1.0
   */
  toggle(): Promise<FullscreenState>;

  /**
   * 读取当前全屏状态（不改变状态、不触发事件）。
   *
   * @since 0.1.0
   */
  isFullscreen(): Promise<FullscreenState>;

  /**
   * 监听全屏状态变化（由本插件的 enable/disable/setFullscreen/toggle 触发）。
   *
   * 解除监听：`const handle = await Fullscreen.addListener(...); await handle.remove();`
   *
   * @since 0.1.0
   */
  addListener(
    eventName: 'fullscreenChanged',
    listenerFunc: (state: FullscreenState) => void,
  ): Promise<PluginListenerHandle>;

  /**
   * 移除本插件的所有监听。
   *
   * @since 0.1.0
   */
  removeAllListeners(): Promise<void>;
}

/** 全屏状态。 */
export interface FullscreenState {
  /** 是否处于全屏（系统栏已隐藏）。 */
  fullscreen: boolean;

  /** 状态栏是否隐藏。 */
  statusBarHidden: boolean;

  /**
   * 导航栏是否隐藏。
   * 注意：iOS 没有系统导航栏（对应 Home Indicator，只能由系统自动隐藏/显示），
   * 该字段在 iOS 上恒与 fullscreen 同步，但实际视觉由系统控制。
   */
  navigationBarHidden: boolean;
}
