import { WebPlugin } from '@capacitor/core';

import type {
  NativeStatusBarPlugin,
  SetBackgroundColorOptions,
  SetStyleOptions,
  StatusBarBackgroundResult,
  StatusBarInfo,
  StatusBarStyle,
} from './definitions';

/**
 * 浏览器里的降级实现：
 * 用一条 fixed 定位的色带模拟状态栏（高度取 env(safe-area-inset-top)，普通浏览器里一般是 0）。
 * 主要作用是让 `npm run dev` 时不报错。
 */
export class NativeStatusBarWeb extends WebPlugin implements NativeStatusBarPlugin {
  private color: string | null = null;
  private style: StatusBarStyle = 'default';
  private overlay: HTMLDivElement | null = null;

  async setBackgroundColor(options: SetBackgroundColorOptions): Promise<StatusBarBackgroundResult> {
    this.color = options.color;

    if (!this.overlay) {
      const el = document.createElement('div');
      el.id = 'native-status-bar-overlay';
      el.style.cssText = [
        'position:fixed',
        'top:0',
        'left:0',
        'right:0',
        'height:env(safe-area-inset-top, 0px)',
        'pointer-events:none',
        'z-index:2147483647',
      ].join(';');
      document.body.appendChild(el);
      this.overlay = el;
    }

    const transparent = options.color.toLowerCase() === 'transparent';
    this.overlay.style.background = transparent ? 'transparent' : options.color;
    if (transparent) {
      this.overlay.remove();
      this.overlay = null;
    }

    return { color: options.color, height: this.currentHeight() };
  }

  async setStyle(options: SetStyleOptions): Promise<void> {
    this.style = options.style;
    // 浏览器里没有系统状态栏，只能提示一下
    console.log(`[NativeStatusBar.setStyle] ${options.style}`);
  }

  async getInfo(): Promise<StatusBarInfo> {
    return {
      color: this.color,
      style: this.style,
      height: this.currentHeight(),
      usingOverlay: this.overlay !== null,
    };
  }

  private currentHeight(): number {
    if (!this.overlay) return 0;
    return Number.parseFloat(getComputedStyle(this.overlay).height) || 0;
  }
}
