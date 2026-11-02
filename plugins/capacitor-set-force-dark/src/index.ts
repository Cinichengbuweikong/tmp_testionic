import { registerPlugin } from '@capacitor/core';

import type { SetForceDarkPlugin } from './definitions';

const SetForceDark = registerPlugin<SetForceDarkPlugin>('SetForceDark', {
  web: () => import('./web').then((m) => new m.SetForceDarkWeb()),
});

export * from './definitions';
export { SetForceDark };
