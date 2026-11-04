import { registerPlugin } from '@capacitor/core';

import type { RotationPlugin } from './definitions';

const Rotation = registerPlugin<RotationPlugin>('Rotation', {
  web: () => import('./web').then((m) => new m.RotationWeb()),
});

export * from './definitions';
export { Rotation };
