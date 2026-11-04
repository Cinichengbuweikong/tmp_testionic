import type { PluginListenerHandle } from '@capacitor/core';

/**
 * Rotation 插件（capacitor-native-rotation）。
 *
 * 两大类能力：
 * 1. **监听**：读取当前方向（`getRotation()`）、方向变化事件（`rotationChanged`）；
 * 2. **控制**：主动锁定横屏/竖屏（`lock()`）与恢复自动旋转（`unlock()`）——
 *    典型用途是视频「全屏播放」：`lock({ orientation: 'landscape' })` + `Fullscreen.enable()`，
 *    退出播放时 `unlock()` + `Fullscreen.disable()`。
 */
export interface RotationPlugin {
  /**
   * 读取当前旋转状态（不会触发事件、不改变锁定）。
   *
   * @since 0.1.0
   */
  getRotation(): Promise<RotationState>;

  /**
   * 锁定屏幕方向。
   *
   * - `landscape`：锁定横屏（支持左右两个横屏方向，适合视频全屏播放）；
   * - `portrait`：锁定竖屏；
   * - `auto`：同 `unlock()`，恢复系统自动旋转。
   *
   * 锁定后屏幕方向不再跟随传感器（系统可能允许从边缘手势临时唤出系统栏，
   * 但方向保持锁定）。
   *
   * @since 0.2.0
   */
  lock(options: { orientation: 'landscape' | 'portrait' | 'auto' }): Promise<OrientationLockState>;

  /**
   * 解除方向锁定，恢复系统自动旋转（等价于 `lock({ orientation: 'auto' })`）。
   *
   * @since 0.2.0
   */
  unlock(): Promise<OrientationLockState>;

  /**
   * 读取当前方向锁定状态（不改变任何状态）。
   *
   * @since 0.2.0
   */
  getLock(): Promise<OrientationLockState>;

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

/** 方向锁定状态。 */
export interface OrientationLockState {
  /** 当前锁定的方向；`auto` 表示未锁定（跟随系统自动旋转）。 */
  orientation: 'landscape' | 'portrait' | 'auto';

  /** 是否处于锁定状态（`orientation !== 'auto'` 时为 true）。 */
  locked: boolean;
}
