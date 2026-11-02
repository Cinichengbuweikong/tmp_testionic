import { WebPlugin } from '@capacitor/core';

import type {
  EnabledState,
  PluginListenerHandle,
  SafeAreaInsets,
  SetEnabledOptions,
  ToEdgeState,
  WebViewToEdgePlugin,
} from './definitions';

/**
 * 浏览器里的降级实现。
 *
 * 浏览器没有"让内容延伸到状态栏下面"这种可编程开关（PWA 里由 viewport-fit=cover + env() 决定），
 * 所以这里只做两件事：
 *  1. 维护 enabled 状态，保持 API 行为一致；
 *  2. 探测 `env(safe-area-inset-*)`，把结果写进 `--safe-area-inset-*`，让页面用同一套变量。
 */
export class WebViewToEdgeWeb extends WebPlugin implements WebViewToEdgePlugin {
  private enabled = false;
  private currentInsets: SafeAreaInsets = { top: 0, right: 0, bottom: 0, left: 0 };

  constructor() {
    super();
    this.measureInsets();
  }

  async enable(): Promise<ToEdgeState> {
    return this.setEnabled({ enabled: true });
  }

  async disable(): Promise<ToEdgeState> {
    return this.setEnabled({ enabled: false });
  }

  async setEnabled(options: SetEnabledOptions): Promise<ToEdgeState> {
    this.enabled = options.enabled;
    this.measureInsets();
    return { enabled: this.enabled, insets: this.currentInsets };
  }

  async isEnabled(): Promise<EnabledState> {
    return { enabled: this.enabled };
  }

  async getInsets(): Promise<SafeAreaInsets> {
    this.measureInsets();
    return this.currentInsets;
  }

  async addListener(
    eventName: 'insetsChanged',
    listener: (insets: SafeAreaInsets) => void,
  ): Promise<PluginListenerHandle> {
    return super.addListener(eventName, listener);
  }

  /** 用 CSS env() 探测安全区，并同步到 CSS 变量。 */
  private measureInsets(): void {
    const probe = document.createElement('div');
    probe.style.cssText = [
      'position:fixed',
      'top:0',
      'left:0',
      'width:0',
      'height:0',
      'pointer-events:none',
      'padding-top:env(safe-area-inset-top, 0px)',
      'padding-right:env(safe-area-inset-right, 0px)',
      'padding-bottom:env(safe-area-inset-bottom, 0px)',
      'padding-left:env(safe-area-inset-left, 0px)',
    ].join(';');
    document.body.appendChild(probe);
    const style = getComputedStyle(probe);
    const insets: SafeAreaInsets = {
      top: Number.parseFloat(style.paddingTop) || 0,
      right: Number.parseFloat(style.paddingRight) || 0,
      bottom: Number.parseFloat(style.paddingBottom) || 0,
      left: Number.parseFloat(style.paddingLeft) || 0,
    };
    probe.remove();

    const changed =
      insets.top !== this.currentInsets.top ||
      insets.right !== this.currentInsets.right ||
      insets.bottom !== this.currentInsets.bottom ||
      insets.left !== this.currentInsets.left;

    this.currentInsets = insets;
    this.applyCssVariables(insets);
    if (changed) {
      this.notifyListeners('insetsChanged', insets);
    }
  }

  private applyCssVariables(insets: SafeAreaInsets): void {
    const root = document.documentElement;
    root.style.setProperty('--safe-area-inset-top', `${insets.top}px`);
    root.style.setProperty('--safe-area-inset-right', `${insets.right}px`);
    root.style.setProperty('--safe-area-inset-bottom', `${insets.bottom}px`);
    root.style.setProperty('--safe-area-inset-left', `${insets.left}px`);
  }
}
