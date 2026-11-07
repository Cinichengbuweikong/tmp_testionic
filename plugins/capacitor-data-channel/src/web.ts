import { WebPlugin } from '@capacitor/core';

import type {
  DataChannelPlugin,
  HttpInfo,
  HttpOtpInfo,
  CreateHttpOtpOptions,
  StartHttpOptions,
  StartWsOptions,
  WsInfo,
  WsOtpInfo,
} from './definitions';

const NATIVE_ONLY = 'DataChannel is only available on native platforms (Android/iOS)';

/**
 * Web 降级实现。
 *
 * 浏览器里无法启动本地 HTTP/WebSocket server（这是原生能力），所有方法直接抛错；
 * 页面在 Web 端开发时可以直接用 fetch/WebSocket 访问开发服务器，无需本插件。
 */
export class DataChannelWeb extends WebPlugin implements DataChannelPlugin {
  async startHttp(_options?: StartHttpOptions): Promise<HttpInfo> {
    throw new Error(`DataChannel.startHttp(): ${NATIVE_ONLY}`);
  }

  async stopHttp(): Promise<HttpInfo> {
    throw new Error(`DataChannel.stopHttp(): ${NATIVE_ONLY}`);
  }

  async startWebSocket(_options?: StartWsOptions): Promise<WsInfo> {
    throw new Error(`DataChannel.startWebSocket(): ${NATIVE_ONLY}`);
  }

  async stopWebSocket(): Promise<WsInfo> {
    throw new Error(`DataChannel.stopWebSocket(): ${NATIVE_ONLY}`);
  }

  async getHttpInfo(): Promise<HttpInfo> {
    throw new Error(`DataChannel.getHttpInfo(): ${NATIVE_ONLY}`);
  }

  async getWsInfo(): Promise<WsInfo> {
    throw new Error(`DataChannel.getWsInfo(): ${NATIVE_ONLY}`);
  }

  async createHttpOtp(_options: CreateHttpOtpOptions): Promise<HttpOtpInfo> {
    throw new Error(`DataChannel.createHttpOtp(): ${NATIVE_ONLY}`);
  }

  async createWsOtp(): Promise<WsOtpInfo> {
    throw new Error(`DataChannel.createWsOtp(): ${NATIVE_ONLY}`);
  }

  async validateAndConsumeOTP(): Promise<{ valid: boolean }> {
    return { valid: false };
  }

  async revokeOtp(): Promise<{ revoked: boolean }> {
    return { revoked: false };
  }

  async revokeAllOtps(): Promise<{ revoked: number }> {
    return { revoked: 0 };
  }
}
