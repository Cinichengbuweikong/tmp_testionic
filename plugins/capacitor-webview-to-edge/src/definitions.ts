/**
 * `capacitor-webview-to-edge` 的 JS 契约。
 *
 * 作用：**运行时**开关「WebView 内容是否延伸到状态栏 / 导航栏下面」，
 * 并把安全区数值交给页面（CSS 变量 + API）。
 *
 * 注意：它不是"隐藏状态栏的全屏模式"——系统栏仍然显示，只是页面内容可以铺到它们下面。
 *
 * 默认关闭：不调用 `enable()` 时，行为与 Capacitor 默认一致（内容让出状态栏）。
 */
export interface WebViewToEdgePlugin {
  /**
   * 开启：WebView 内容延伸到屏幕边缘（状态栏/导航栏下面）。
   *
   * 页面需要自己用 `--safe-area-inset-*` 或 `getInsets()` 给顶部内容留出空间，
   * 否则内容会被状态栏/刘海压住。
   *
   * @example
   * await WebViewToEdge.enable();
   *
   * @since 0.1.0
   */
  enable(): Promise<ToEdgeState>;

  /**
   * 关闭：恢复 Capacitor 默认行为（内容让出状态栏/底部系统栏）。
   *
   * @example
   * await WebViewToEdge.disable();
   *
   * @since 0.1.0
   */
  disable(): Promise<ToEdgeState>;

  /**
   * 用布尔值开关，适合和设置项绑定。
   *
   * @example
   * await WebViewToEdge.setEnabled({ enabled: true });
   *
   * @since 0.1.0
   */
  setEnabled(options: SetEnabledOptions): Promise<ToEdgeState>;

  /**
   * 当前是否处于"内容延伸到屏幕边缘"状态。
   *
   * @since 0.1.0
   */
  isEnabled(): Promise<EnabledState>;

  /**
   * 读取当前安全区数值（单位 CSS px）。
   *
   * 老 WebView（如 Android 7.x 时代的 WebView 87）里 `env(safe-area-inset-*)` 恒为 0，
   * 这时应该用这里返回的值或者插件注入的 CSS 变量。
   *
   * @example
   * const { top } = await WebViewToEdge.getInsets();
   *
   * @since 0.1.0
   */
  getInsets(): Promise<SafeAreaInsets>;

  /**
   * 监听安全区变化（旋转、键盘、分屏等导致 insets 改变时触发）。
   *
   * @example
   * const handle = await WebViewToEdge.addListener('insetsChanged', (insets) => {
   *   console.log(insets.top);
   * });
   *
   * @since 0.1.0
   */
  addListener(eventName: 'insetsChanged', listener: (insets: SafeAreaInsets) => void): Promise<PluginListenerHandle>;
}

export interface SetEnabledOptions {
  enabled: boolean;
}

/** 安全区数值，单位 CSS px（Android dp / iOS pt）。 */
export interface SafeAreaInsets {
  /** 状态栏 / 刘海 / 挖孔区域高度。 */
  top: number;
  /** 右侧安全区（横屏挖孔等场景）。 */
  right: number;
  /** 底部系统栏（导航条）高度。 */
  bottom: number;
  /** 左侧安全区。 */
  left: number;
}

export interface ToEdgeState {
  /** 是否已开启。 */
  enabled: boolean;
  /** 当前安全区数值。 */
  insets: SafeAreaInsets;
}

export interface EnabledState {
  enabled: boolean;
}

export interface PluginListenerHandle {
  remove(): Promise<void>;
}
