export interface NativeBasicsPlugin {
  /**
   * 原样返回传入的字符串，用于验证 JS <-> 原生 通道是否正常。
   *
   * @since 0.1.0
   */
  echo(options: { value: string }): Promise<{ value: string }>;

  /**
   * 显示一个原生 Toast（Android 的 Toast，iOS 需另行实现）。
   *
   * @since 0.1.0
   */
  showToast(options: ShowToastOptions): Promise<void>;

  /**
   * 读取设备基础信息。
   *
   * @since 0.1.0
   */
  getDeviceInfo(): Promise<DeviceInfo>;
}

export interface ShowToastOptions {
  /**
   * Toast 文本。
   */
  text: string;

  /**
   * 显示时长，默认 short。
   */
  duration?: 'short' | 'long';
}

export interface DeviceInfo {
  /**
   * 设备制造商，例如 Xiaomi / Apple。
   */
  manufacturer: string;

  /**
   * 设备型号，例如 M2007J3SC / iPhone。
   */
  model: string;

  /**
   * 系统版本号，例如 10 / 17.2。
   */
  osVersion: string;

  /**
   * 当前运行平台。
   */
  platform: 'android' | 'ios' | 'web';

  /**
   * Android API Level，例如 29；非 Android 平台为 undefined。
   */
  sdkInt?: number;

  /**
   * 是否运行在模拟器上。
   */
  isEmulator: boolean;
}
