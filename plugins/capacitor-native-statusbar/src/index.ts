import { registerPlugin } from '@capacitor/core';

import type { NativeStatusBarPlugin } from './definitions';

const NativeStatusBar = registerPlugin<NativeStatusBarPlugin>('NativeStatusBar', {
  web: () => import('./web').then((m) => new m.NativeStatusBarWeb()),
});

export * from './definitions';
export { NativeStatusBar };
