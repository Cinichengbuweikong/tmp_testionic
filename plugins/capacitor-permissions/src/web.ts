import { WebPlugin } from '@capacitor/core';
import type {
  PermissionId,
  PermissionInfo,
  PermissionResults,
  PermissionState,
  PermissionsPlugin,
} from './definitions';

/**
 * Web 端：依赖浏览器 Permissions API（`navigator.permissions.query`）。
 *
 * 现实限制：
 * - Chrome 支持 `geolocation` / `notifications` / `camera` / `microphone` / `clipboard-read` 等；
 *   Safari 只支持很少几种，且 `query()` 可能直接抛错；
 * - `camera`/`microphone` 的 "prompt" 状态在很多浏览器里查不到，只能靠 `getUserMedia` 触发；
 * - 浏览器没有"打开 App 设置页"的概念。
 */
const QUERY_NAME: Partial<Record<PermissionId, PermissionName>> = {
  camera: 'camera' as PermissionName,
  microphone: 'microphone' as PermissionName,
  location: 'geolocation' as PermissionName,
  locationCoarse: 'geolocation' as PermissionName,
  notifications: 'notifications' as PermissionName,
};

function mapState(state: PermissionState | string): PermissionState {
  switch (state) {
    case 'granted':
      return 'granted';
    case 'denied':
      return 'denied';
    case 'prompt':
      return 'prompt';
    default:
      return 'prompt';
  }
}

export class PermissionsWeb extends WebPlugin implements PermissionsPlugin {
  private async queryOne(id: PermissionId): Promise<PermissionState> {
    const name = QUERY_NAME[id];
    if (!name) return 'unsupported';
    if (typeof navigator === 'undefined' || !navigator.permissions?.query) {
      return 'unsupported';
    }
    try {
      const status = await navigator.permissions.query({ name });
      return mapState(status.state);
    } catch {
      // 浏览器不认识这个 name（Safari 常见）
      return 'unsupported';
    }
  }

  async check(options: { permissions: PermissionId[] }): Promise<PermissionResults> {
    const results: Record<string, PermissionState> = {};
    const details: Record<string, string> = {};
    for (const id of options.permissions ?? []) {
      results[id] = await this.queryOne(id);
      details[id] = results[id] === 'unsupported'
        ? '浏览器不支持查询该权限（可能仍可通过具体 API 触发授权）'
        : `浏览器 Permissions API: ${results[id]}`;
    }
    return { results, details };
  }

  async request(options: { permissions: PermissionId[] }): Promise<PermissionResults> {
    const results: Record<string, PermissionState> = {};
    const details: Record<string, string> = {};
    for (const id of options.permissions ?? []) {
      results[id] = await this.requestOne(id, details);
    }
    return { results, details };
  }

  private async requestOne(id: PermissionId, details: Record<string, string>): Promise<PermissionState> {
    const current = await this.queryOne(id);
    if (current === 'granted' || current === 'denied') return current;

    if (id === 'notifications') {
      if (typeof Notification === 'undefined') {
        details[id] = '浏览器不支持 Notification API';
        return 'unsupported';
      }
      const permission = await Notification.requestPermission();
      details[id] = `Notification.requestPermission(): ${permission}`;
      return mapState(permission);
    }

    if (id === 'camera' || id === 'microphone') {
      if (!navigator.mediaDevices?.getUserMedia) {
        details[id] = '浏览器不支持 getUserMedia';
        return 'unsupported';
      }
      try {
        const stream = await navigator.mediaDevices.getUserMedia(
          id === 'camera' ? { video: true } : { audio: true },
        );
        stream.getTracks().forEach((track) => track.stop());
        details[id] = 'getUserMedia 成功（授权后已立即释放设备）';
        return 'granted';
      } catch (error) {
        details[id] = `getUserMedia 失败：${(error as Error).name}`;
        return (error as Error).name === 'NotAllowedError' ? 'denied' : 'prompt';
      }
    }

    if (id === 'location' || id === 'locationCoarse') {
      if (!navigator.geolocation) {
        details[id] = '浏览器不支持 Geolocation';
        return 'unsupported';
      }
      return new Promise<PermissionState>((resolve) => {
        navigator.geolocation.getCurrentPosition(
          () => {
            details[id] = 'getCurrentPosition 成功';
            resolve('granted');
          },
          (error) => {
            details[id] = `getCurrentPosition 失败：${error.message}`;
            resolve(error.code === error.PERMISSION_DENIED ? 'denied' : 'prompt');
          },
          { timeout: 10000, maximumAge: 0 },
        );
      });
    }

    details[id] = '浏览器无法主动请求该权限';
    return 'unsupported';
  }

  async getSupported(): Promise<{
    platform: 'web';
    permissions: PermissionInfo[];
  }> {
    const ids: PermissionId[] = Object.keys(QUERY_NAME) as PermissionId[];
    return {
      platform: 'web',
      permissions: ids.map((id) => ({
        id,
        supported: true,
        requires: [],
        note: '浏览器 Permissions API（支持程度因浏览器而异）',
      })),
    };
  }

  async openAppSettings(): Promise<{ opened: boolean }> {
    return { opened: false };
  }
}
