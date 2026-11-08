import type { PluginListenerHandle } from '@capacitor/core';

/**
 * KeyboardAvoiding 插件（capacitor-keyboard-avoiding）。
 *
 * 两大能力：
 * 1. **事件通知**：键盘弹出/隐藏时派发 `keyboardChanged`，载荷里有键盘高度、当前视口高度——
 *    页面据此自己实现 KeyboardAvoidingView 式布局；
 * 2. **自动避让（可选）**：`enable()` 后由插件负责把 WebView 的可用区域限制在键盘上方
 *    （Android 用"补差"方式，不重复避让；iOS 用 contentInset，因为 WKWebView 默认不避让）。
 */
export interface KeyboardAvoidingPlugin {
  /**
   * 开启自动避让：键盘弹出时保证 WebView 内容不被键盘遮挡。
   *
   * 注意：Android 上系统/其它插件可能已经做了避让（如 Capacitor 默认 adjustResize），
   * 本插件会检测并只补足缺口，不会双重缩小。
   *
   * @since 0.1.0
   */
  enable(): Promise<KeyboardState>;

  /**
   * 关闭自动避让（恢复未避让时的布局；键盘事件仍会照常派发）。
   *
   * @since 0.1.0
   */
  disable(): Promise<KeyboardState>;

  /**
   * 按指定状态设置自动避让开关。
   *
   * @since 0.1.0
   */
  setEnabled(options: { enabled: boolean }): Promise<KeyboardState>;

  /**
   * 读取当前状态（键盘是否可见、自动避让是否开启等），不改变任何状态。
   *
   * @since 0.1.0
   */
  isEnabled(): Promise<KeyboardState>;

  /**
   * 同 `isEnabled()`，语义更清晰的别名。
   *
   * @since 0.1.0
   */
  getState(): Promise<KeyboardState>;

  /**
   * 监听键盘弹出/隐藏。载荷为 KeyboardState。
   *
   * 解除监听：`const handle = await KeyboardAvoiding.addListener(...); await handle.remove();`
   *
   * @since 0.1.0
   */
  addListener(
    eventName: 'keyboardChanged',
    listenerFunc: (state: KeyboardState) => void,
  ): Promise<PluginListenerHandle>;

  /**
   * 移除本插件的所有监听。
   *
   * @since 0.1.0
   */
  removeAllListeners(): Promise<void>;
}

/** 键盘状态。 */
export interface KeyboardState {
  /** 键盘是否可见。 */
  visible: boolean;

  /** 键盘高度（CSS px）；不可见时为 0。 */
  height: number;

  /** 键盘高度（物理像素）；不可见时为 0。 */
  heightPx: number;

  /**
   * 当前 WebView 内容区高度（CSS px）——键盘弹出时即为"键盘上方剩余空间"。
   * 页面可以直接用这个值做布局（如 `height: ${viewportHeight}px`）。
   */
  viewportHeight: number;

  /** 当前 WebView 内容区高度（物理像素）。 */
  viewportHeightPx: number;

  /** 自动避让是否开启。 */
  autoAvoid: boolean;

  /** 键盘动画时长（毫秒）；Android 无统一值时为 0，iOS 取系统动画时长。 */
  animationDuration: number;
}
