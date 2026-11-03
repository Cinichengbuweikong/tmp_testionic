import { WebPlugin } from '@capacitor/core';

import type { FullscreenPlugin, FullscreenState } from './definitions';

type WebkitDocument = Document & {
  webkitFullscreenElement?: Element | null;
  webkitExitFullscreen?: () => Promise<void> | void;
};

type WebkitElement = HTMLElement & {
  webkitRequestFullscreen?: () => Promise<void> | void;
};

/**
 * Web 降级实现：用浏览器 Fullscreen API（隐藏浏览器 UI），
 * 方向变化/退出事件同构派发 fullscreenChanged。
 */
export class FullscreenWeb extends WebPlugin implements FullscreenPlugin {
  private listening = false;

  constructor() {
    super();
    this.attach();
  }

  async enable(): Promise<FullscreenState> {
    if (!this.isNativeFullscreen()) {
      const el = document.documentElement as WebkitElement;
      try {
        if (document.documentElement.requestFullscreen) {
          await document.documentElement.requestFullscreen();
        } else if (el.webkitRequestFullscreen) {
          await el.webkitRequestFullscreen();
        }
      } catch {
        // 无用户手势时浏览器会拒绝，保持原状态
      }
    }
    return this.isFullscreen();
  }

  async disable(): Promise<FullscreenState> {
    if (this.isNativeFullscreen()) {
      const doc = document as WebkitDocument;
      try {
        if (document.exitFullscreen) {
          await document.exitFullscreen();
        } else if (doc.webkitExitFullscreen) {
          await doc.webkitExitFullscreen();
        }
      } catch {
        // 忽略（可能已退出）
      }
    }
    return this.isFullscreen();
  }

  async setFullscreen(options: { enabled: boolean }): Promise<FullscreenState> {
    return options.enabled ? this.enable() : this.disable();
  }

  async toggle(): Promise<FullscreenState> {
    const state = await this.isFullscreen();
    return state.fullscreen ? this.disable() : this.enable();
  }

  async isFullscreen(): Promise<FullscreenState> {
    const active = this.isNativeFullscreen();
    return {
      fullscreen: active,
      statusBarHidden: active,
      navigationBarHidden: active,
    };
  }

  private isNativeFullscreen(): boolean {
    const doc = document as WebkitDocument;
    return !!(document.fullscreenElement || doc.webkitFullscreenElement);
  }

  private attach(): void {
    if (this.listening) {
      return;
    }
    this.listening = true;
    document.addEventListener('fullscreenchange', this.handleChange);
    document.addEventListener('webkitfullscreenchange', this.handleChange);
  }

  private handleChange = (): void => {
    void this.isFullscreen().then((state) => this.notifyListeners('fullscreenChanged', state));
  };
}
