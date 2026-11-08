import { registerPlugin } from '@capacitor/core';

import type { NativeFilesPlugin } from './definitions';

const NativeFiles = registerPlugin<NativeFilesPlugin>('NativeFiles', {
  web: () => import('./web').then((m) => new m.NativeFilesWeb()),
});

export * from './definitions';
export { NativeFiles };
