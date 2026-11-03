/**
 * SystemInfo 插件（capacitor-system-info）的类型定义。
 *
 * 设计原则：每种信息一个独立 API，按需调用，避免一次返回全部。
 *
 * 单位约定（重要）：
 * - 字段名带 `px` 的是**物理像素**：Android 原生 px；iOS 为 pt × scale。
 * - 字段名带 `css` 的是 **CSS px**：Android 的 dp / iOS 的 pt，即 WebView 里 CSS 使用的单位。
 * - 换算：物理像素 = CSS px × density（density 在 getScreenInfo() 里）。
 */

export interface SystemInfoPlugin {
  /**
   * 屏幕信息：物理分辨率 / CSS 尺寸 / 密度 / 刷新率 / 朝向（跟随当前朝向）。
   *
   * @since 0.1.0
   */
  getScreenInfo(): Promise<ScreenInfo>;

  /**
   * WebView 视口尺寸——CSS 里 `100%` 对应的就是 viewport.css。
   * Android 默认（未用 webview-to-edge 铺满整屏）时视口会让出状态栏/导航栏；iOS 默认满屏。
   *
   * @since 0.1.0
   */
  getViewport(): Promise<ViewportInfo>;

  /**
   * 安全区：四边需要避让的区域（iOS safe-area 语义），单位 CSS px。
   *
   * @since 0.1.0
   */
  getSafeAreaInsets(): Promise<SafeAreaInsets>;

  /**
   * 状态栏信息（宽 = 屏幕宽）。
   *
   * @since 0.1.0
   */
  getStatusBarInfo(): Promise<StatusBarInfo>;

  /**
   * 底部导航栏信息（iOS 上对应 Home Indicator 区域）。
   *
   * @since 0.1.0
   */
  getNavigationBarInfo(): Promise<NavigationBarInfo>;

  /**
   * 刘海 / 挖孔信息。
   *
   * @since 0.1.0
   */
  getCutoutInfo(): Promise<CutoutInfo>;

  /**
   * 系统与设备信息：平台、版本、型号、CPU ABI、语言/时区、字体缩放、深色模式等。
   *
   * @since 0.1.0
   */
  getSystemInfo(): Promise<SystemInfoData>;

  /**
   * WebView 引擎信息：包名 / 版本号 / UserAgent。
   *
   * @since 0.1.0
   */
  getWebViewInfo(): Promise<WebViewInfo>;

  /**
   * 内存信息（字节）。
   *
   * @since 0.1.0
   */
  getMemoryInfo(): Promise<MemoryInfo>;

  /**
   * 存储信息（数据分区，字节）。
   *
   * @since 0.1.0
   */
  getStorageInfo(): Promise<StorageInfo>;

  /**
   * 电量信息。
   *
   * @since 0.1.0
   */
  getBatteryInfo(): Promise<BatteryInfo>;

  /**
   * 网络信息。
   *
   * @since 0.1.0
   */
  getNetworkInfo(): Promise<NetworkInfo>;

  /**
   * 应用自身信息。
   *
   * @since 0.1.0
   */
  getAppInfo(): Promise<AppInfo>;
}

/** 平台。 */
export type SystemInfoPlatform = 'android' | 'ios' | 'web';

/** 屏幕朝向。 */
export type SystemInfoOrientation = 'portrait' | 'landscape' | 'unknown';

/** 底部导航栏的交互形态。 */
export type SystemInfoNavMode = 'three_button' | 'two_button' | 'gesture' | 'none' | 'unknown';

export type SystemInfoNetworkType = 'wifi' | 'cellular' | 'ethernet' | 'vpn' | 'none' | 'unknown';

export interface SystemInfoSize {
  /** 宽。 */
  width: number;
  /** 高。 */
  height: number;
}

/** 四边安全区（CSS px）。 */
export interface SystemInfoEdges {
  top: number;
  right: number;
  bottom: number;
  left: number;
}

/** 屏幕坐标系的矩形（物理像素）。 */
export interface SystemInfoRect {
  left: number;
  top: number;
  right: number;
  bottom: number;
}

/** getScreenInfo() 的返回值。 */
export interface ScreenInfo {
  /** 物理分辨率（跟随当前朝向）。 */
  px: SystemInfoSize;
  /** CSS px 尺寸。 */
  css: SystemInfoSize;
  orientation: SystemInfoOrientation;
  /** 0 / 90 / 180 / 270。 */
  rotationDegrees: number;
  /** 像素密度：1 CSS px 对应多少物理像素（如 2.75）。 */
  density: number;
  /** DPI，如 440。 */
  densityDpi: number;
  /** 字体缩放后的密度。 */
  scaledDensity: number;
  /** 屏幕刷新率（Hz，当前模式）。 */
  refreshRate: number;
}

/** getViewport() 的返回值：WebView 视口（CSS 100% 的大小）。 */
export interface ViewportInfo {
  px: SystemInfoSize;
  css: SystemInfoSize;
}

/** getSafeAreaInsets() 的返回值（CSS px）。 */
export type SafeAreaInsets = SystemInfoEdges;

/** getStatusBarInfo() 的返回值。 */
export interface StatusBarInfo {
  px: SystemInfoSize;
  css: SystemInfoSize;
  visible: boolean;
}

/** getNavigationBarInfo() 的返回值。 */
export interface NavigationBarInfo {
  px: SystemInfoSize;
  css: SystemInfoSize;
  visible: boolean;
  mode: SystemInfoNavMode;
}

/** getCutoutInfo() 的返回值。 */
export interface CutoutInfo {
  exists: boolean;
  /** 挖孔安全区（仅 exists 时给出），CSS px。 */
  insetsCss?: SystemInfoEdges;
  /** 挖孔包围盒列表，物理像素、屏幕坐标系。 */
  boundingRectsPx?: SystemInfoRect[];
}

/** getSystemInfo() 的返回值。 */
export interface SystemInfoData {
  platform: SystemInfoPlatform;
  /** 系统版本号，如 "10" / "17.5"。 */
  osVersion: string;
  /** Android API Level，如 29；非 Android 平台为 undefined。 */
  sdkInt?: number;
  manufacturer: string;
  brand: string;
  /** 型号，如 M2007J3SC / iPhone13,2。 */
  model: string;
  /** 设备代号（codename），如 apollo / sargo；iOS 上与 model 相同。 */
  deviceCode: string;
  /** 是否运行在模拟器上。 */
  isEmulator: boolean;
  /** 支持的 CPU ABI，如 ["arm64-v8a", "armeabi-v7a"]。 */
  cpuAbis: string[];
  /** 区域设置，如 zh-CN。 */
  locale: string;
  /** 语言，如 zh。 */
  language: string;
  /** 时区，如 Asia/Shanghai。 */
  timezone: string;
  /** 是否 24 小时制。 */
  is24Hour: boolean;
  /** 系统字体缩放倍率（Android fontScale；iOS 为动态字号的近似映射）。 */
  fontScale: number;
  /** 系统深色模式（App 当前配置看到的；注意 MIUI 等 ROM 上它可能与 matchMedia 不一致）。 */
  darkMode: boolean;
}

/** getWebViewInfo() 的返回值。 */
export interface WebViewInfo {
  /** Android：WebView 包名（如 com.google.android.webview）；iOS：WKWebView。 */
  packageName?: string;
  /** Android：WebView 版本号；iOS：UA 里的 Safari 内核版本。 */
  version?: string;
  userAgent: string;
}

/** getMemoryInfo() 的返回值。 */
export interface MemoryInfo {
  /** 物理内存总量（字节）。 */
  totalBytes: number;
  /** 当前可用内存（字节）；iOS 部分场景无法获取时为 -1。 */
  availableBytes: number;
}

/** getStorageInfo() 的返回值。 */
export interface StorageInfo {
  /** 数据分区总量（字节）。 */
  totalBytes: number;
  /** 数据分区剩余（字节）。 */
  freeBytes: number;
}

/** getBatteryInfo() 的返回值。 */
export interface BatteryInfo {
  /** 电量 0~100；未知为 -1（如 iOS 模拟器）。 */
  level: number;
  charging: boolean;
}

/** getNetworkInfo() 的返回值。 */
export interface NetworkInfo {
  type: SystemInfoNetworkType;
  /** 是否有可用网络（已连接且具备互联网能力）。 */
  connected: boolean;
  /** 是否计费网络。 */
  metered: boolean;
}

/** getAppInfo() 的返回值。 */
export interface AppInfo {
  id: string;
  name: string;
  version: string;
  build?: string;
}
