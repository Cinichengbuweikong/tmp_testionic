import { WebPlugin } from '@capacitor/core';

import type { BackButtonPlugin } from './definitions';

/**
 * Web 降级实现：浏览器没有硬件返回键，这里只提供可用的等价能力：
 * - `canGoBack` / `goBack` 基于 `history`；
 * - `enable` / `disable` 只记录状态（浏览器无法拦截系统返回键）；
 * - `exitApp` 尝试 `window.close()`（通常只有脚本打开的窗口才允许）；
 * - `minimizeApp` no-op。
 */
export class BackButtonWeb extends WebPlugin implements BackButtonPlugin {
  private enabled = false;

  async enable(): Promise<{ enabled: boolean }> {
    this.enabled = true;
    return { enabled: true };
  }

  async disable(): Promise<{ enabled: boolean }> {
    this.enabled = false;
    return { enabled: false };
  }

  async isEnabled(): Promise<{ enabled: boolean }> {
    return { enabled: this.enabled };
  }

  async canGoBack(): Promise<{ canGoBack: boolean }> {
    return { canGoBack: window.history.length > 1 };
  }

  async goBack(): Promise<{ wentBack: boolean }> {
    if (window.history.length > 1) {
      window.history.back();
      return { wentBack: true };
    }
    return { wentBack: false };
  }

  async exitApp(): Promise<void> {
    window.close();
  }

  async setSwipeBackEnabled(): Promise<{ enabled: boolean }> {
    // 浏览器没有 WKWebView 的侧滑手势
    return { enabled: false };
  }

  async getSwipeBackEnabled(): Promise<{ enabled: boolean }> {
    return { enabled: false };
  }

  async minimizeApp(): Promise<void> {
    // 浏览器没有"最小化"概念
  }
}
