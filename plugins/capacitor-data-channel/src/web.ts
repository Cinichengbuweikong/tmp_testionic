import { WebPlugin } from '@capacitor/core';

import type {
  ChannelInfo,
  DataChannelPlugin,
  StartChannelOptions,
  StorageAccessInfo,
} from './definitions';

/**
 * Web 降级实现。
 *
 * 浏览器里无法启动本地 HTTP/WebSocket server（这是原生能力），所以：
 * - start()/stop() 返回 rejected promise，提示仅原生平台可用；
 * - checkAllFilesAccess()/requestAllFilesAccess() 返回"无此概念"；
 * - 页面在 Web 端开发时可以直接用 fetch/WebSocket 访问开发服务器，无需本插件。
 */
export class DataChannelWeb extends WebPlugin implements DataChannelPlugin {
  async start(_options?: StartChannelOptions): Promise<ChannelInfo> {
    throw new Error('DataChannel.start() is only available on native platforms (Android/iOS)');
  }

  async stop(): Promise<ChannelInfo> {
    throw new Error('DataChannel.stop() is only available on native platforms (Android/iOS)');
  }

  async getInfo(): Promise<ChannelInfo> {
    return {
      running: false,
      port: 0,
      baseUrl: '',
      wsUrl: '',
      token: '',
      rootPath: '',
      maxBodyBytes: 0,
      startedAt: 0,
      activeConnections: 0,
      wsClients: 0,
      webSocketEnabled: false,
    };
  }

  async checkAllFilesAccess(): Promise<StorageAccessInfo> {
    return { granted: false, platform: 'web', mode: 'unavailable' };
  }

  async requestAllFilesAccess(): Promise<StorageAccessInfo> {
    return { granted: false, platform: 'web', mode: 'unavailable', opened: false };
  }
}
