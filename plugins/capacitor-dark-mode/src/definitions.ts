export interface DarkModePlugin {
  /**
   * 设置主题模式。
   *
   * - `system`：跟随系统（默认）
   * - `light` / `dark`：强制亮色 / 暗色，忽略系统设置
   *
   * 生效方式是给页面根元素设置 `data-theme="light|dark"` 和 `color-scheme`，
   * 并自动把 `@media (prefers-color-scheme: dark)` 里的规则映射到 `html[data-theme="dark"]`
   * （因为 MIUI 等 ROM 的 WebView 不会正确报告 prefers-color-scheme）。
   *
   * @since 0.1.0
   */
  setTheme(options: SetThemeOptions): Promise<ThemeInfo>;

  /**
   * 读取当前主题（含系统深浅色与是否被强制）。
   *
   * @since 0.1.0
   */
  getTheme(): Promise<ThemeInfo>;

  /**
   * 是否允许 WebView 的「算法暗色 / forceDark」。
   *
   * 默认是 **false**（插件加载时会主动关掉），因为 MIUI 等 ROM 的 forceDark
   * 会把已经适配深色的页面二次反色，颜色全部错乱。
   * 如果确实需要系统帮你反色，可以打开。
   *
   * @since 0.1.0
   */
  setForceDark(options: SetForceDarkOptions): Promise<void>;

  /**
   * 读取插件的运行状态，主要用于调试与自动化测试。
   *
   * @since 0.1.0
   */
  getInfo(): Promise<DarkModeInfo>;

  /**
   * 监听主题变化（系统深浅色切换、或调用 setTheme 之后）。
   *
   * @since 0.1.0
   */
  addListener(eventName: 'themeChanged', listener: (info: ThemeInfo) => void): Promise<PluginListenerHandle>;
}

export type ThemeMode = 'light' | 'dark' | 'system';

export interface SetThemeOptions {
  theme: ThemeMode;
}

export interface SetForceDarkOptions {
  /**
   * true = 允许系统/ROM 对 WebView 做算法反色（不推荐）。
   */
  enabled: boolean;
}

export interface ThemeInfo {
  /**
   * 当前设置的模式：system / light / dark。
   */
  mode: ThemeMode;

  /**
   * 当前实际是否深色。
   */
  isDark: boolean;

  /**
   * 当前是否处于「强制」状态（mode 不是 system）。
   */
  overridden: boolean;
}

export interface DarkModeInfo extends ThemeInfo {
  /**
   * WebView 的 forceDark 是否处于开启状态（插件默认关闭）。
   */
  forceDarkEnabled: boolean;

  /**
   * 页面是否已经收到过注入（data-theme 是否已写入）。
   */
  injected: boolean;

  /**
   * WebView 的 user-agent，方便排查 ROM/WebView 版本问题。
   */
  userAgent: string;
}

export interface PluginListenerHandle {
  remove(): Promise<void>;
}
