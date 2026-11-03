import { WebPlugin } from '@capacitor/core';

import type { RotationPlugin, RotationState } from './definitions';

/**
 * Web 降级实现：用 `screen.orientation` / `orientationchange` 事件模拟。
 * 实例化时开始监听（插件首次被调用时实例化），方向变化时派发
 * `rotationChanged` —— 与原生行为保持一致。
 */
export class RotationWeb extends WebPlugin implements RotationPlugin {
  private listening = false;

  constructor() {
    super();
    this.startListening();
  }

  async getRotation(): Promise<RotationState> {
    const angle = this.currentAngle();
    const normalized = ((angle % 360) + 360) % 360;
    const isLandscape = normalized === 90 || normalized === 270;
    const orientation: RotationState['orientation'] =
      isLandscape ? 'landscape' : normalized === 0 || normalized === 180 ? 'portrait' : 'unknown';
    return {
      orientation,
      rotationDegrees: normalized,
      isPortrait: orientation === 'portrait',
      isLandscape,
    };
  }

  private currentAngle(): number {
    if (typeof screen !== 'undefined' && screen.orientation) {
      return Number(screen.orientation.angle) || 0;
    }
    const legacy = (window as unknown as { orientation?: number }).orientation;
    return Number(legacy) || 0;
  }

  private startListening(): void {
    if (this.listening) {
      return;
    }
    this.listening = true;
    window.addEventListener('orientationchange', this.handleChange);
    if (typeof screen !== 'undefined' && screen.orientation && screen.orientation.addEventListener) {
      screen.orientation.addEventListener('change', this.handleChange);
    }
  }

  private handleChange = (): void => {
    void this.getRotation().then((state) => this.notifyListeners('rotationChanged', state));
  };
}
