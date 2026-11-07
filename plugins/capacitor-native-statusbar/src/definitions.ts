export interface NativeStatusBarPlugin {
  /**
   * 设置状态栏背景色。
   *
   * 支持 `#RGB`、`#RRGGBB`、`#AARRGGBB`（Android 习惯的 ARGB 顺序）以及 `transparent`。
   * 传 `transparent` 会移除背景层，恢复成系统默认（透明）。
   *
   * @since 0.1.0
   */
  setBackgroundColor(options: SetBackgroundColorOptions): Promise<StatusBarBackgroundResult>;

  /**
   * 设置状态栏文字/图标样式。
   *
   * - `light`：亮色内容（白字白图标），适合深色背景
   * - `dark`：暗色内容（黑字黑图标），适合浅色背景
   * - `default`：跟随系统深浅色模式
   *
   * @since 0.1.0
   */
  setStyle(options: SetStyleOptions): Promise<void>;

  /**
   * 读取当前状态栏状态，主要用于调试与自动化测试。
   *
   * @since 0.1.0
   */
  getInfo(): Promise<StatusBarInfo>;
}

export type StatusBarStyle = 'light' | 'dark' | 'default';

export interface SetBackgroundColorOptions {
  /**
   * 颜色字符串，例如 `#FF5722`、`#f00`、`#80FF0000`、`transparent`。
   */
  color: string;

  /**
   * 当内容已经铺到状态栏下面（`capacitor-webview-to-edge` 开启、或全屏隐藏了状态栏）时，
   * 是否仍然绘制色块。**默认 `false`**：让位给页面内容，避免不透明色块挡住内容。
   *
   * 需要「半透明状态栏盖在内容上」这类设计时传 `true`（例如 `#80000000`）。
   *
   * @since 0.4.0
   */
  overlayInEdgeToEdge?: boolean;
}

export interface SetStyleOptions {
  /**
   * 状态栏内容样式。
   */
  style: StatusBarStyle;
}

export interface StatusBarBackgroundResult {
  /**
   * 实际生效的颜色（`#AARRGGBB` 形式）。
   */
  color: string;

  /**
   * 状态栏高度，单位是 CSS px（Android 为 dp，iOS 为 pt）。
   */
  height: number;

  /**
   * 状态栏那一条是否已由内容自己画（edge-to-edge / 全屏）。
   */
  edgeToEdge?: boolean;

  /**
   * 是否真的画了色块（edge-to-edge 且未要求 overlayInEdgeToEdge 时为 false）。
   */
  usingOverlay?: boolean;
}

export interface StatusBarInfo {
  /**
   * 当前背景色，`null` 表示没有设置过。
   */
  color: string | null;

  /**
   * 当前内容样式。
   */
  style: StatusBarStyle;

  /**
   * 状态栏高度，单位 CSS px。
   */
  height: number;

  /**
   * 是否正在用「自绘色块」呈现背景色。
   * Android 15+ 会忽略系统状态栏颜色，插件只能自己画一层，此时为 true。
   *
   * 当内容铺到状态栏下面（edge-to-edge）时，默认不再画色块 → 这里为 false。
   */
  usingOverlay: boolean;

  /**
   * 状态栏那一条是否已由内容自己画（`capacitor-webview-to-edge` 开启 / 全屏）。
   */
  edgeToEdge: boolean;

  /**
   * 是否要求了「edge-to-edge 时仍绘制色块」。
   */
  overlayInEdgeToEdge: boolean;
}
