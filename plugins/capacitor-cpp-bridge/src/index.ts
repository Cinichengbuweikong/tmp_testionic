import { registerPlugin } from '@capacitor/core';

import type { CppBridgePlugin } from './definitions';

const CppBridge = registerPlugin<CppBridgePlugin>('CppBridge', {
  web: () => import('./web').then((m) => new m.CppBridgeWeb()),
});

export * from './definitions';
export { CppBridge };
