import { registerPlugin } from '@capacitor/core';

import type { KeyboardAvoidingPlugin } from './definitions';

const KeyboardAvoiding = registerPlugin<KeyboardAvoidingPlugin>('KeyboardAvoiding', {
  web: () => import('./web').then((m) => new m.KeyboardAvoidingWeb()),
});

export * from './definitions';
export { KeyboardAvoiding };
