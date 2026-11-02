import { WebPlugin } from '@capacitor/core';

import type {
  DarkModeInfo,
  DarkModePlugin,
  PluginListenerHandle,
  SetForceDarkOptions,
  SetThemeOptions,
  ThemeInfo,
  ThemeMode,
} from './definitions';

/**
 * 浏览器里的实现：
 * 浏览器本身支持 `prefers-color-scheme`，所以这里只需要维护覆盖状态，
 * 并把 `data-theme` / `color-scheme` 同步到 <html> 上（与原生端行为保持一致）。
 */
export class DarkModeWeb extends WebPlugin implements DarkModePlugin {
  private mode: ThemeMode = 'system';
  private forceDark = false;
  private media = window.matchMedia('(prefers-color-scheme: dark)');
  private mediaListener = () => {
    if (this.mode !== 'system') return;
    this.applyTheme(this.media.matches ? 'dark' : 'light');
    this.emitChange();
  };

  constructor() {
    super();
    this.media.addEventListener('change', this.mediaListener);
    this.applyTheme(this.media.matches ? 'dark' : 'light');
  }

  async setTheme(options: SetThemeOptions): Promise<ThemeInfo> {
    this.mode = options.theme;
    const isDark = this.mode === 'system' ? this.media.matches : this.mode === 'dark';
    this.applyTheme(isDark ? 'dark' : 'light');
    this.emitChange();
    return this.currentTheme();
  }

  async getTheme(): Promise<ThemeInfo> {
    return this.currentTheme();
  }

  async setForceDark(options: SetForceDarkOptions): Promise<void> {
    this.forceDark = options.enabled;
  }

  async getInfo(): Promise<DarkModeInfo> {
    return {
      ...this.currentTheme(),
      forceDarkEnabled: this.forceDark,
      injected: true,
      userAgent: navigator.userAgent,
    };
  }

  async addListener(
    eventName: 'themeChanged',
    listener: (info: ThemeInfo) => void,
  ): Promise<PluginListenerHandle> {
    return super.addListener(eventName, listener);
  }

  private currentTheme(): ThemeInfo {
    const isDark = this.mode === 'system' ? this.media.matches : this.mode === 'dark';
    return {
      mode: this.mode,
      isDark,
      overridden: this.mode !== 'system',
    };
  }

  private applyTheme(theme: 'light' | 'dark'): void {
    const root = document.documentElement;
    root.setAttribute('data-theme', theme);
    root.style.colorScheme = theme;
  }

  private emitChange(): void {
    this.notifyListeners('themeChanged', this.currentTheme());
  }
}
