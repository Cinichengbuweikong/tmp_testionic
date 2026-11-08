import { WebPlugin } from '@capacitor/core';

import type {
  AppInfo,
  BatteryInfo,
  CutoutInfo,
  MemoryInfo,
  NavigationBarInfo,
  NetworkInfo,
  SafeAreaInsets,
  ScreenInfo,
  StatusBarInfo,
  StorageInfo,
  SystemInfoData,
  SystemInfoPlugin,
  ViewportInfo,
  WebViewInfo,
} from './definitions';

const anyNav = navigator as unknown as Record<string, any>;

function uaPlatform(userAgent: string): 'android' | 'ios' | 'web' {
  if (/iphone|ipad|ipod/i.test(userAgent)) {
    return 'ios';
  }
  if (/android/i.test(userAgent)) {
    return 'android';
  }
  return 'web';
}

function uaMatch(userAgent: string, re: RegExp): string {
  const m = userAgent.match(re);
  return m ? m[1] : '';
}

/**
 * Web 降级实现：全部用浏览器 API 估算。
 * 只是为了让 `npm run dev` / PWA 不报错；真正的原生数据在 Android / iOS 上。
 * 每个方法独立实现，互不影响。
 */
export class SystemInfoWeb extends WebPlugin implements SystemInfoPlugin {
  async getScreenInfo(): Promise<ScreenInfo> {
    const dpr = window.devicePixelRatio || 1;
    const w = window.screen.width;
    const h = window.screen.height;
    return {
      px: { width: w * dpr, height: h * dpr },
      css: { width: w, height: h },
      orientation: w > h ? 'landscape' : 'portrait',
      rotationDegrees: 0,
      density: dpr,
      densityDpi: Math.round(dpr * 160),
      scaledDensity: dpr,
      refreshRate: 60,
      maxRefreshRate: 60,
    };
  }

  async getViewport(): Promise<ViewportInfo> {
    const dpr = window.devicePixelRatio || 1;
    const w = window.innerWidth;
    const h = window.innerHeight;
    return {
      px: { width: w * dpr, height: h * dpr },
      css: { width: w, height: h },
    };
  }

  async getSafeAreaInsets(): Promise<SafeAreaInsets> {
    // 普通浏览器里没有系统安全区（桌面 PWA 用 viewport-fit=cover 时 env() 才有值，
    // 这里不做特判，统一返回 0）
    const zero = { top: 0, right: 0, bottom: 0, left: 0 };
    return { px: zero, css: zero };
  }

  async getStatusBarInfo(): Promise<StatusBarInfo> {
    return { px: { width: 0, height: 0 }, css: { width: 0, height: 0 }, visible: false };
  }

  async getNavigationBarInfo(): Promise<NavigationBarInfo> {
    return {
      px: { width: 0, height: 0 },
      css: { width: 0, height: 0 },
      visible: false,
      mode: 'none',
    };
  }

  async getCutoutInfo(): Promise<CutoutInfo> {
    return { exists: false };
  }

  async getSystemInfo(): Promise<SystemInfoData> {
    const ua = navigator.userAgent;
    const platform = uaPlatform(ua);
    let osVersion = '';
    let model = '';
    let brand = '';
    if (platform === 'android') {
      osVersion = uaMatch(ua, /Android\s+([\d.]+)/);
      model = uaMatch(ua, /; ([^;)]+?) Build\//);
    } else if (platform === 'ios') {
      osVersion = uaMatch(ua, /OS\s+([\d_]+)/).replace(/_/g, '.');
      model = /ipad/i.test(ua) ? 'iPad' : /iphone/i.test(ua) ? 'iPhone' : 'iPod';
      brand = 'Apple';
    }

    let is24Hour = true;
    try {
      const probe = new Intl.DateTimeFormat(undefined, { hour: 'numeric' }).resolvedOptions();
      is24Hour = probe.hour12 === false || probe.hour12 === undefined;
    } catch {
      is24Hour = true;
    }

    const locale = navigator.language || '';
    return {
      platform,
      osVersion,
      manufacturer: brand,
      brand,
      model,
      deviceCode: model,
      isEmulator: false,
      cpuAbis: [],
      locale,
      language: locale.split('-')[0] || '',
      timezone: Intl.DateTimeFormat().resolvedOptions().timeZone || '',
      is24Hour,
      fontScale: 1,
      darkMode: window.matchMedia('(prefers-color-scheme: dark)').matches,
    };
  }

  async getWebViewInfo(): Promise<WebViewInfo> {
    const ua = navigator.userAgent;
    let version = uaMatch(ua, /Chrome\/([\d.]+)/);
    if (!version) {
      version = uaMatch(ua, /Version\/([\d.]+)/);
    }
    return { packageName: '', version, userAgent: ua };
  }

  async getMemoryInfo(): Promise<MemoryInfo> {
    const deviceMemory = anyNav.deviceMemory;
    const totalBytes =
      typeof deviceMemory === 'number' && deviceMemory > 0
        ? Math.round(deviceMemory * 1024 * 1024 * 1024)
        : -1;
    return { totalBytes, availableBytes: -1 };
  }

  async getStorageInfo(): Promise<StorageInfo> {
    let totalBytes = -1;
    let freeBytes = -1;
    if (anyNav.storage && typeof anyNav.storage.estimate === 'function') {
      try {
        const est = await anyNav.storage.estimate();
        totalBytes = typeof est.quota === 'number' ? est.quota : -1;
        freeBytes =
          typeof est.quota === 'number' && typeof est.usage === 'number'
            ? Math.max(0, est.quota - est.usage)
            : -1;
      } catch {
        // 部分浏览器不支持
      }
    }
    return { totalBytes, freeBytes };
  }

  async getBatteryInfo(): Promise<BatteryInfo> {
    if (typeof anyNav.getBattery !== 'function') {
      return { level: -1, charging: false };
    }
    try {
      const battery = await anyNav.getBattery.call(navigator);
      return {
        level: Math.round((Number(battery.level) || 0) * 100),
        charging: !!battery.charging,
      };
    } catch {
      return { level: -1, charging: false };
    }
  }

  async getNetworkInfo(): Promise<NetworkInfo> {
    const connection = anyNav.connection;
    if (!connection) {
      return { type: 'unknown', connected: false, metered: false };
    }
    const eff = String(connection.effectiveType || connection.type || '');
    let type: NetworkInfo['type'] = 'unknown';
    if (/wifi/i.test(eff)) {
      type = 'wifi';
    } else if (/cellular|4g|3g|2g|slow-2g/i.test(eff)) {
      type = 'cellular';
    } else if (eff === 'ethernet') {
      type = 'ethernet';
    }
    return { type, connected: true, metered: false };
  }

  async getAppInfo(): Promise<AppInfo> {
    return { id: '', name: 'web', version: '' };
  }
}
