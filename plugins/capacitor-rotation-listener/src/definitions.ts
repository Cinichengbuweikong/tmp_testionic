import type { PluginListenerHandle } from '@capacitor/core';

/**
 * Rotation 插件（capacitor-rotation）。
 *
 * 用途：监听屏幕旋转。原生侧在方向**真正变化**时派发 `rotationChanged` 事件，
 * 事件载荷与 `getRotation()` 的返回值结构一致。
 */
export interface RotationPlugin {
  /**
   * 读取当前旋转状态（不会触发事件）。
   *
   * @since 0.1.0
   */
  getRotation(): Promise<RotationState>;

  /**
   * 监听屏幕旋转。方向变化时回调，载荷为 RotationState。
   *
   * 解除监听：`const handle = await Rotation.addListener(...); await handle.remove();`
   *
   * @since 0.1.0
   */
  addListener(
    eventName: 'rotationChanged',
    listenerFunc: (state: RotationState) => void,
  ): Promise<PluginListenerHandle>;

  /**
   * 移除本插件的所有监听（等价于逐个 handle.remove()）。
   *
   * @since 0.1.0
   */
  removeAllListeners(): Promise<void>;
}

/** 旋转状态。 */
export interface RotationState {
  /** 当前朝向（与屏幕宽高比一致）。 */
  orientation: 'portrait' | 'landscape' | 'unknown';

  /** 旋转角度：0 / 90 / 180 / 270（与 getScreenInfo().rotationDegrees 同语义）。 */
  rotationDegrees: number;

  /** 便捷布尔：是否竖屏。 */
  isPortrait: boolean;

  /** 便捷布尔：是否横屏。 */
  isLandscape: boolean;
}
