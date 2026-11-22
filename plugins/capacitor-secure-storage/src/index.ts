import { registerPlugin } from '@capacitor/core';
import type { SecureStoragePlugin } from './definitions';

const SecureStorage = registerPlugin<SecureStoragePlugin>('SecureStorage', {
  web: () => import('./web').then((m) => new m.SecureStorageWeb()),
});

export * from './definitions';
export { SecureStorage };
