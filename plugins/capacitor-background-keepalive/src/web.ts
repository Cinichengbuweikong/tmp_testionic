import { WebPlugin } from '@capacitor/core';
import type {
  BackgroundKeepalivePlugin,
  KeepaliveCapabilities,
  KeepaliveStartOptions,
  KeepaliveStatus,
} from './definitions';

/**
 * Web 端：浏览器**没有**后台执行保证。
 *
 * - 页面切到后台后，`setTimeout/setInterval` 会被节流甚至冻结（Chrome 的
 *   "Intensive throttling"、移动端 Safari 的进程挂起），WebSocket 也可能被断；
 * - 没有 Service Worker 能"让页面继续跑"（Service Worker 是独立进程，不能操作 DOM 或你的 JS 状态）；
 * - `Screen Wake Lock` 只防息屏，切后台照样停。
 *
 * 所以这里只做诚实的能力说明：`start()` 不报错，但返回 `running: false`，
 * 让业务层自己决定要不要提示用户。
 */
export class BackgroundKeepaliveWeb extends WebPlugin implements BackgroundKeepalivePlugin {
  async start(options?: KeepaliveStartOptions): Promise<KeepaliveStatus> {
    return {
      running: false,
      platform: 'web',
      mode: 'none',
      note: `浏览器无法在后台保持执行（请求的模式：${options?.mode ?? 'auto'}）。`
        + '页面隐藏后定时器会被节流/冻结，WebSocket 可能被断开。',
    };
  }

  async stop(): Promise<KeepaliveStatus> {
    return { running: false, platform: 'web', mode: 'none' };
  }

  async isRunning(): Promise<KeepaliveStatus> {
    return { running: false, platform: 'web', mode: 'none' };
  }

  async updateNotification(): Promise<KeepaliveStatus> {
    return { running: false, platform: 'web', mode: 'none' };
  }

  async getCapabilities(): Promise<KeepaliveCapabilities> {
    return {
      platform: 'web',
      continuous: false,
      note: '浏览器没有后台执行保证；需要后台任务请用 Service Worker + Push，或回到原生。',
      modes: [
        {
          mode: 'none',
          supported: false,
          requires: [],
          effect: '页面隐藏后 JS 会被节流/冻结',
          recommended: true,
        },
      ],
    };
  }

  async isBatteryOptimizationIgnored(): Promise<{ applicable: boolean; ignored: boolean }> {
    return { applicable: false, ignored: false };
  }

  async requestBatteryOptimizationExemption(): Promise<{
    applicable: boolean;
    ignored: boolean;
    requested: boolean;
  }> {
    return { applicable: false, ignored: false, requested: false };
  }

  async openBatteryOptimizationSettings(): Promise<{ opened: boolean }> {
    return { opened: false };
  }
}
