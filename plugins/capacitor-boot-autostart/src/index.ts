import { registerPlugin } from '@capacitor/core';

import type { BootAutostartPlugin } from './definitions';

const BootAutostart = registerPlugin<BootAutostartPlugin>('BootAutostart', {
  web: () => import('./web').then((m) => new m.BootAutostartWeb()),
});

export * from './definitions';
export { BootAutostart };
