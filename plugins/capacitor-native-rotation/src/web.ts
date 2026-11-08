import { WebPlugin } from '@capacitor/core';

import type { OrientationLockState, RotationPlugin, RotationState } from './definitions';

/** screen.orientation.lock 的浏览器支持是渐进式的，类型声明里没有统一覆盖。 */
type ScreenOrientationWithLock = ScreenOrientation & {
  lock?: (orientation: 'landscape' | 'portrait' | 'any') => Promise<void>;
  unlock?: () => void;
};

/**
 * Web 降级实现：
 * - 监听：用 `screen.orientation` / `orientationchange` 事件模拟，方向变化时派发
 *   `rotationChanged` —— 与原生行为保持一致；
 * - 锁定：用 `screen.orientation.lock()`（前提：页面处于全屏、且浏览器支持；
 *   不支持时 reject，和原生一样由调用方处理）。
 */
export class RotationWeb extends WebPlugin implements RotationPlugin {
  private listening = false;
  private lockedOrientation: OrientationLockState['orientation'] = 'auto';

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

  async lock(options: { orientation: OrientationLockState['orientation'] }): Promise<OrientationLockState> {
    const requested = options?.orientation ?? 'landscape';
    if (requested === 'auto') {
      return this.unlock();
    }
    const orientationApi = this.orientationApi();
    if (!orientationApi || typeof orientationApi.lock !== 'function') {
      throw new Error('screen.orientation.lock is not supported in this browser');
    }
    try {
      // 浏览器要求页面处于全屏状态才能锁定方向；调用方应先进入全屏
      await orientationApi.lock(requested);
      this.lockedOrientation = requested;
    } catch (err) {
      this.lockedOrientation = 'auto';
      throw new Error(`screen.orientation.lock failed: ${String((err as Error)?.message ?? err)}`);
    }
    return this.lockState();
  }

  async unlock(): Promise<OrientationLockState> {
    const orientationApi = this.orientationApi();
    if (orientationApi && typeof orientationApi.unlock === 'function') {
      orientationApi.unlock();
    }
    this.lockedOrientation = 'auto';
    return this.lockState();
  }

  async getLock(): Promise<OrientationLockState> {
    return this.lockState();
  }

  private lockState(): OrientationLockState {
    return {
      orientation: this.lockedOrientation,
      locked: this.lockedOrientation !== 'auto',
    };
  }

  private orientationApi(): ScreenOrientationWithLock | null {
    if (typeof screen === 'undefined' || !screen.orientation) {
      return null;
    }
    return screen.orientation as ScreenOrientationWithLock;
  }

  private currentAngle(): number {
    const api = this.orientationApi();
    if (api) {
      return Number(api.angle) || 0;
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
    const api = this.orientationApi();
    if (api && api.addEventListener) {
      api.addEventListener('change', this.handleChange);
    }
  }

  private handleChange = (): void => {
    void this.getRotation().then((state) => this.notifyListeners('rotationChanged', state));
  };
}
