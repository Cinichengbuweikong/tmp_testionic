import { registerPlugin } from '@capacitor/core';

import type { DataChannelPlugin } from './definitions';

/**
 * 插件实例（单独模块，避免 helpers ↔ index 循环依赖）。
 */
export const DataChannel = registerPlugin<DataChannelPlugin>('DataChannel', {
  web: () => import('./web').then((m) => new m.DataChannelWeb()),
});
