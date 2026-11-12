import { WebPlugin } from '@capacitor/core';

import type { BootAutostartPlugin, BootAutostartStatus } from './definitions';

/**
 * Web 降级实现：浏览器/桌面没有"开机自启"概念，统一返回 `supported: false`。
 */
export class BootAutostartWeb extends WebPlugin implements BootAutostartPlugin {
  private status(): BootAutostartStatus {
    return {
      supported: false,
      enabled: false,
      reason: 'Web 环境不支持开机自启',
    };
  }

  async getStatus(): Promise<BootAutostartStatus> {
    return this.status();
  }

  async enable(): Promise<BootAutostartStatus> {
    return this.status();
  }

  async disable(): Promise<BootAutostartStatus> {
    return this.status();
  }

  async requestNotificationPermission(): Promise<BootAutostartStatus> {
    return this.status();
  }

  async requestIgnoreBatteryOptimizations(): Promise<BootAutostartStatus> {
    return this.status();
  }

  async openAppSettings(): Promise<void> {
    // 浏览器无对应设置页
  }

  async openAutostartSettings(): Promise<void> {
    // 浏览器无对应设置页
  }
}
