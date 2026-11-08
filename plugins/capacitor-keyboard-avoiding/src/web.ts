import { WebPlugin } from '@capacitor/core';

import type { KeyboardAvoidingPlugin, KeyboardState } from './definitions';

/**
 * Web 降级实现：用 `visualViewport` 计算键盘高度并派发同构事件。
 *
 * 注意：浏览器不能修改页面的键盘避让（那是浏览器的职责），所以 `enable()` 只记录开关
 * 状态与事件；真正的避让请在页面里用 CSS/env 或根据事件里的 viewportHeight 自己处理。
 */
export class KeyboardAvoidingWeb extends WebPlugin implements KeyboardAvoidingPlugin {
  private autoAvoid = false;
  private listening = false;
  private visible = false;
  private keyboardHeight = 0;

  constructor() {
    super();
    this.startListening();
  }

  async enable(): Promise<KeyboardState> {
    this.autoAvoid = true;
    return this.currentState();
  }

  async disable(): Promise<KeyboardState> {
    this.autoAvoid = false;
    return this.currentState();
  }

  async setEnabled(options: { enabled: boolean }): Promise<KeyboardState> {
    return options?.enabled ? this.enable() : this.disable();
  }

  async isEnabled(): Promise<KeyboardState> {
    return this.currentState();
  }

  async getState(): Promise<KeyboardState> {
    return this.currentState();
  }

  private currentState(): KeyboardState {
    const dpr = window.devicePixelRatio || 1;
    return {
      visible: this.visible,
      height: this.keyboardHeight,
      heightPx: Math.round(this.keyboardHeight * dpr),
      viewportHeight: Math.round(window.innerHeight * 100) / 100,
      viewportHeightPx: Math.round(window.innerHeight * dpr),
      autoAvoid: this.autoAvoid,
      animationDuration: 0,
    };
  }

  private startListening(): void {
    if (this.listening) {
      return;
    }
    this.listening = true;
    window.addEventListener('resize', this.handleChange);
    window.addEventListener('focusin', this.handleChange);
    window.addEventListener('focusout', this.handleChange);
    const vv = window.visualViewport;
    if (vv) {
      vv.addEventListener('resize', this.handleChange);
      vv.addEventListener('scroll', this.handleChange);
    }
  }

  private handleChange = (): void => {
    const vv = window.visualViewport;
    const height = vv ? Math.max(0, window.innerHeight - (vv.height + (vv.offsetTop || 0))) : 0;
    // 小于 80px 的变化通常是浏览器 UI 折叠，不算键盘
    const visible = height > 80;
    const changed = visible !== this.visible || Math.abs(height - this.keyboardHeight) > 1;
    this.visible = visible;
    this.keyboardHeight = visible ? Math.round(height * 100) / 100 : 0;
    if (changed) {
      this.notifyListeners('keyboardChanged', this.currentState());
    }
  };
}
