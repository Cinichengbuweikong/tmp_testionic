import { WebPlugin } from '@capacitor/core';

import type { ForegroundStateInfo, ForegroundStatePlugin } from './definitions';

/**
 * Web 降级实现：用 Page Visibility API 模拟。
 *
 * 浏览器里 document.hidden 代表"页面不可见"（切标签/最小化），
 * 与原生前后台语义接近但不等价（浏览器没有真正的 background 状态）。
 */
export class ForegroundStateWeb extends WebPlugin implements ForegroundStatePlugin {
  private state: ForegroundStateInfo = {
    isActive: true,
    state: 'active',
    changedAt: Date.now(),
    lastBackgroundAt: 0,
    backgroundDuration: 0,
  };
  private listening = false;

  async getState(): Promise<ForegroundStateInfo> {
    return this.currentState();
  }

  private currentState(): ForegroundStateInfo {
    return { ...this.state };
  }

  private startListening(): void {
    if (this.listening) {
      return;
    }
    this.listening = true;
    document.addEventListener('visibilitychange', () => {
      const now = Date.now();
      if (document.hidden) {
        this.state = {
          isActive: false,
          state: 'background',
          changedAt: now,
          lastBackgroundAt: now,
          backgroundDuration: 0,
        };
      } else {
        this.state = {
          isActive: true,
          state: 'active',
          changedAt: now,
          lastBackgroundAt: this.state.lastBackgroundAt,
          backgroundDuration: this.state.lastBackgroundAt ? now - this.state.lastBackgroundAt : 0,
        };
      }
      this.notifyListeners('stateChanged', this.currentState());
    });
  }

  constructor() {
    super();
    this.startListening();
  }
}
