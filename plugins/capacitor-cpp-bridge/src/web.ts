import { WebPlugin } from '@capacitor/core';

import type {
  AddResult,
  BufferHandle,
  CoreInfo,
  CppBridgePlugin,
  FillResult,
  FreeResult,
  SumResult,
} from './definitions';

/**
 * Web 降级实现：浏览器里没有 native 库。
 *
 * 为了**让示例仍然可跑**，`add` / `cAdd` / `cStrlen` 用 JS 实现；
 * 句柄 API（native 内存）无法在浏览器里模拟，直接抛错。
 */
export class CppBridgeWeb extends WebPlugin implements CppBridgePlugin {
  async getCoreInfo(): Promise<CoreInfo> {
    return {
      platform: 'web',
      loaded: false,
      error: 'web platform has no native C/C++ library',
    };
  }

  async add(options: { a: number; b: number }): Promise<AddResult> {
    return { a: options.a, b: options.b, result: options.a + options.b, impl: 'cpp' };
  }

  async cAdd(options: { a: number; b: number }): Promise<AddResult> {
    return { a: options.a, b: options.b, result: options.a + options.b, impl: 'c' };
  }

  async cStrlen(options: { text: string }): Promise<{ text: string; length: number; impl: 'c' }> {
    return { text: options.text, length: options.text.length, impl: 'c' };
  }

  async allocBuffer(_options: { size: number }): Promise<BufferHandle> {
    throw new Error('allocBuffer is only available on native platforms');
  }

  async fillBuffer(_options: { handle: number; value: number }): Promise<FillResult> {
    throw new Error('fillBuffer is only available on native platforms');
  }

  async sumBuffer(_options: { handle: number }): Promise<SumResult> {
    throw new Error('sumBuffer is only available on native platforms');
  }

  async freeBuffer(_options: { handle: number }): Promise<FreeResult> {
    throw new Error('freeBuffer is only available on native platforms');
  }

  async getBufferStats(): Promise<{ count: number; bytes: number }> {
    return { count: 0, bytes: 0 };
  }
}
