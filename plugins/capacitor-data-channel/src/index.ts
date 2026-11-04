import { registerPlugin } from '@capacitor/core';

import type { DataChannelPlugin } from './definitions';

const DataChannel = registerPlugin<DataChannelPlugin>('DataChannel', {
  web: () => import('./web').then((m) => new m.DataChannelWeb()),
});

export * from './definitions';
export { DataChannel };
