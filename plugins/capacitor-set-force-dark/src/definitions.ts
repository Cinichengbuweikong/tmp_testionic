/**
 * SetForceDark 插件（JS 契约）。
 *
 * 插件职责：设置 / 关闭 **WebView 的 forceDark（算法暗色）**，并把 App 期望的主题
 * 注入页面（`data-theme` / `color-scheme`），避免 ROM（尤其 MIUI）把页面颜色反坏。
 *
 * ⚠️ 它 **不控制** 系统的深色模式开关，也不会影响系统 UI 或其它 App。
 */
export interface SetForceDarkPlugin {
  /**
   * 设置 WebView 使用的主题。
   *
   * - `'system'`：跟随系统（默认）
   * - `'light'` / `'dark'`：强制亮色 / 暗色，忽略系统设置
   *
   * 效果：
   * 1. 关闭/保持 WebView 的算法暗色（避免 ROM 把颜色反掉）；
   * 2. 给页面根元素写 `data-theme="light|dark"` 和 `color-scheme`；
   * 3. 首次注入时把 `@media (prefers-color-scheme: dark)` 的规则映射到
   *    `html[data-theme="dark"]`，兼容没有改过 CSS 的老项目。
   *
   * @example
   * await SetForceDark.setTheme({ theme: 'system' });
   * await SetForceDark.setTheme({ theme: 'dark' });
   *
   * @since 0.1.0
   */
  setTheme(options: SetThemeOptions): Promise<ThemeState>;

  /**
   * 读取当前 WebView 主题状态（不是系统设置）。
   *
   * @example
   * const { requestedTheme, isDark } = await SetForceDark.getTheme();
   *
   * @since 0.1.0
   */
  getTheme(): Promise<ThemeState>;

  /**
   * 显式开关 WebView 的 forceDark / 算法暗色。
   *
   * 插件加载时**默认关闭**（false），因为 MIUI 等 ROM 的 forceDark 会把已经适配
   * 深色的页面二次反色，导致红/黄/绿等主题色严重偏色。
   * 只有在你确实想让系统帮忙反色时才需要打开。
   *
   * @example
   * await SetForceDark.setForceDark({ enabled: false });
   *
   * @since 0.1.0
   */
  setForceDark(options: SetForceDarkOptions): Promise<void>;

  /**
   * 读取插件运行状态，主要用于调试与自动化测试。
   *
   * @since 0.1.0
   */
  getInfo(): Promise<ForceDarkInfo>;

  /**
   * 监听 WebView 主题变化。
   *
   * 触发时机：
   * - 系统深浅色切换（且 requestedTheme 为 `'system'`）；
   * - 主动调用 `setTheme()` 之后。
   *
   * @example
   * const handle = await SetForceDark.addListener('themeChanged', (theme) => {
   *   console.log(theme.isDark);
   * });
   * // 不再需要时：await handle.remove();
   *
   * @since 0.1.0
   */
  addListener(eventName: 'themeChanged', listener: (theme: ThemeState) => void): Promise<PluginListenerHandle>;
}

/**
 * App 请求 WebView 使用的主题。
 * - `system`：跟随系统
 * - `light` / `dark`：强制
 */
export type RequestedTheme = 'light' | 'dark' | 'system';

export interface SetThemeOptions {
  /** 期望的主题。 */
  theme: RequestedTheme;
}

export interface SetForceDarkOptions {
  /**
   * `true` = 允许系统/ROM 对 WebView 做算法反色（不推荐）。
   * `false` = 交给页面自己的 CSS 决定颜色（插件默认值）。
   */
  enabled: boolean;
}

/** 当前 WebView 的主题状态。 */
export interface ThemeState {
  /**
   * App 请求的主题（`system` / `light` / `dark`），不是系统设置本身。
   */
  requestedTheme: RequestedTheme;

  /**
   * 解析后实际是否深色。
   * `requestedTheme` 为 `system` 时，等于系统当前是否深色。
   */
  isDark: boolean;

  /**
   * 是否处于"强制"状态（`requestedTheme !== 'system'`）。
   */
  isForced: boolean;
}

/** 插件调试信息。 */
export interface ForceDarkInfo extends ThemeState {
  /**
   * WebView 的 forceDark / 算法暗色是否开启（插件默认关闭）。
   */
  forceDarkEnabled: boolean;

  /**
   * 主题是否已经注入到页面（`data-theme` 是否已写入）。
   */
  themeInjected: boolean;

  /**
   * WebView 的 user-agent，方便排查 ROM / WebView 版本问题。
   */
  webViewUserAgent: string;
}

export interface PluginListenerHandle {
  remove(): Promise<void>;
}
