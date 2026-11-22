import { registerPlugin } from '@capacitor/core';
import type { BackgroundKeepalivePlugin } from './definitions';

const BackgroundKeepalive = registerPlugin<BackgroundKeepalivePlugin>('BackgroundKeepalive', {
  web: () => import('./web').then((m) => new m.BackgroundKeepaliveWeb()),
});

export * from './definitions';
export { BackgroundKeepalive };
