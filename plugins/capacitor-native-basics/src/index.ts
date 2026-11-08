import { registerPlugin } from '@capacitor/core';

import type { NativeBasicsPlugin } from './definitions';

const NativeBasics = registerPlugin<NativeBasicsPlugin>('NativeBasics', {
  web: () => import('./web').then((m) => new m.NativeBasicsWeb()),
});

export * from './definitions';
export { NativeBasics };
