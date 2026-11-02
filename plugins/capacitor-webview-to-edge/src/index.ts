import { registerPlugin } from '@capacitor/core';

import type { WebViewToEdgePlugin } from './definitions';

const WebViewToEdge = registerPlugin<WebViewToEdgePlugin>('WebViewToEdge', {
  web: () => import('./web').then((m) => new m.WebViewToEdgeWeb()),
});

export * from './definitions';
export { WebViewToEdge };
