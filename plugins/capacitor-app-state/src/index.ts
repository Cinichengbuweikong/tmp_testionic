import { registerPlugin } from '@capacitor/core';

import type { AppStatePlugin } from './definitions';

const AppState = registerPlugin<AppStatePlugin>('AppState', {
  web: () => import('./web').then((m) => new m.AppStateWeb()),
});

export * from './definitions';
export { AppState };
