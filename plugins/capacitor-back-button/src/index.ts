import { registerPlugin } from '@capacitor/core';

import type { BackButtonPlugin } from './definitions';

const BackButton = registerPlugin<BackButtonPlugin>('BackButton', {
  web: () => import('./web').then((m) => new m.BackButtonWeb()),
});

export * from './definitions';
export { BackButton };
