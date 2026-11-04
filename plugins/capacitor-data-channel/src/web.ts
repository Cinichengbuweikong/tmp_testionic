import { WebPlugin } from '@capacitor/core';

import type { ChannelInfo, DataChannelPlugin, StartChannelOptions } from './definitions';

/**
 * Web 降级实现。
 *
 * 浏览器里无法启动本地 HTTP server（这是原生能力），所以：
 * - start()/stop()/getInfo() 返回 rejected promise，提示仅原生平台可用；
 * - 页面在 Web 端开发时可以直接用 fetch 访问开发服务器的接口，无需本插件。
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
      token: '',
      rootPath: '',
      maxBodyBytes: 0,
      startedAt: 0,
    };
  }
}
