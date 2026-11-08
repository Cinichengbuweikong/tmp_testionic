import { registerPlugin } from '@capacitor/core';

import type { ForegroundStatePlugin } from './definitions';

const ForegroundState = registerPlugin<ForegroundStatePlugin>('ForegroundState', {
  web: () => import('./web').then((m) => new m.ForegroundStateWeb()),
});

export * from './definitions';
export { ForegroundState };
