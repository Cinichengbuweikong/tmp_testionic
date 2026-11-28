import { registerPlugin } from '@capacitor/core';

import type { LinuxPlugin } from './definitions';

/**
 * 门面：Android 走原生 QEMU 虚拟机；Web 走 web 实现（明确告知不支持并给出原因）；
 * iOS 由 Swift 侧返回 `UNSUPPORTED_PLATFORM`（见 README「iOS 路线」）。
 */
const Linux = registerPlugin<LinuxPlugin>('Linux', {
  web: () => import('./web').then((m) => new m.LinuxWeb()),
});

export * from './definitions';
export * from './keys';
export { Linux };
