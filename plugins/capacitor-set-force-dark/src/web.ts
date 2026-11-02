import { WebPlugin } from '@capacitor/core';

import type {
  ForceDarkInfo,
  PluginListenerHandle,
  RequestedTheme,
  SetForceDarkOptions,
  SetForceDarkPlugin,
  SetThemeOptions,
  ThemeState,
} from './definitions';

/**
 * 浏览器里的降级实现。
 *
 * 浏览器本身对 `prefers-color-scheme` 的支持是正常的，所以这里不需要"修"什么，
 * 只做两件事：
 *  1. 把 `data-theme` / `color-scheme` 同步到 <html>，与原生端行为保持一致；
 *  2. 维护"请求的主题"状态，并在系统主题变化时派发 themeChanged。
 */
export class SetForceDarkWeb extends WebPlugin implements SetForceDarkPlugin {
  private requestedTheme: RequestedTheme = 'system';
  private forceDark = false;
  private media = window.matchMedia('(prefers-color-scheme: dark)');

  private mediaListener = () => {
    if (this.requestedTheme !== 'system') return;
    this.applyTheme(this.isDark() ? 'dark' : 'light');
    this.notifyListeners('themeChanged', this.currentTheme());
  };

  constructor() {
    super();
    this.media.addEventListener('change', this.mediaListener);
    this.applyTheme(this.isDark() ? 'dark' : 'light');
  }

  async setTheme(options: SetThemeOptions): Promise<ThemeState> {
    this.requestedTheme = options.theme;
    this.applyTheme(this.isDark() ? 'dark' : 'light');
    const theme = this.currentTheme();
    this.notifyListeners('themeChanged', theme);
    return theme;
  }

  async getTheme(): Promise<ThemeState> {
    return this.currentTheme();
  }

  async setForceDark(options: SetForceDarkOptions): Promise<void> {
    this.forceDark = options.enabled;
  }

  async getInfo(): Promise<ForceDarkInfo> {
    return {
      ...this.currentTheme(),
      forceDarkEnabled: this.forceDark,
      themeInjected: true,
      webViewUserAgent: navigator.userAgent,
    };
  }

  async addListener(
    eventName: 'themeChanged',
    listener: (theme: ThemeState) => void,
  ): Promise<PluginListenerHandle> {
    return super.addListener(eventName, listener);
  }

  private isDark(): boolean {
    return this.requestedTheme === 'system' ? this.media.matches : this.requestedTheme === 'dark';
  }

  private currentTheme(): ThemeState {
    return {
      requestedTheme: this.requestedTheme,
      isDark: this.isDark(),
      isForced: this.requestedTheme !== 'system',
    };
  }

  private applyTheme(theme: 'light' | 'dark'): void {
    const root = document.documentElement;
    root.setAttribute('data-theme', theme);
    root.style.colorScheme = theme;
  }
}
