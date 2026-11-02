import { WebPlugin } from '@capacitor/core';

import type { DeviceInfo, NativeBasicsPlugin, ShowToastOptions } from './definitions';

export class NativeBasicsWeb extends WebPlugin implements NativeBasicsPlugin {
  async echo(options: { value: string }): Promise<{ value: string }> {
    return options;
  }

  async showToast(options: ShowToastOptions): Promise<void> {
    // 浏览器里没有原生 Toast，退化成控制台输出，方便 Web 端调试。
    console.log(`[NativeBasics.showToast] ${options.text}`);
  }

  async getDeviceInfo(): Promise<DeviceInfo> {
    return {
      manufacturer: 'web',
      model: navigator.userAgent,
      osVersion: '',
      platform: 'web',
      isEmulator: false,
    };
  }
}
