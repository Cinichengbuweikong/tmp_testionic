import { WebPlugin } from '@capacitor/core';

import type {
  LinuxCapabilities,
  LinuxExecOptions,
  LinuxExecResult,
  LinuxInfo,
  LinuxPlugin,
  LinuxSendKeyOptions,
  LinuxShareDir,
  LinuxStartOptions,
  LinuxStatus,
  LinuxWriteOptions,
} from './definitions';

const WEB_REASON =
  'Web 端未实现：要在浏览器里跑 Linux 需要把整个客户机编译成 WebAssembly ' +
  '（v86 / QEMU-wasm / iSH-wasm 一类），体积和性能代价都很大。' +
  'Android 侧是原生 QEMU，可用。';

/**
 * Web 实现：所有会真正操作 VM 的方法都抛 `UNSUPPORTED_PLATFORM`，
 * `getCapabilities()` / `getInfo()` 返回可用的结构（方便调用方统一处理）。
 */
export class LinuxWeb extends WebPlugin implements LinuxPlugin {
  async getCapabilities(): Promise<LinuxCapabilities> {
    return {
      platform: 'web',
      supported: false,
      engine: 'none',
      architecture: 'unknown',
      nativeExecution: false,
      isolation: 'none',
      apkAvailable: false,
      persistence: 'none',
      minimum: {},
      reasons: [WEB_REASON],
    };
  }

  async getInfo(): Promise<LinuxInfo> {
    return {
      supported: false,
      distribution: 'alpine',
      engine: 'none',
      architecture: 'unknown',
      rootfsMode: 'initramfs',
      nativeExecution: false,
      apkAvailable: false,
      executable: '',
      executablePresent: false,
      runtimeExtracted: false,
      runtimeBundled: false,
      runtimeError: WEB_REASON,
      state: 'stopped',
    };
  }

  async getStatus(): Promise<LinuxStatus> {
    return {
      state: 'stopped',
      running: false,
      memoryMb: 0,
      cpus: 0,
      consoleTty: '',
      stderrTty: '',
      lastError: WEB_REASON,
    };
  }

  async start(_options?: LinuxStartOptions): Promise<LinuxStatus> {
    throw this.unsupported();
  }

  async stop(_options?: { force?: boolean }): Promise<LinuxStatus> {
    throw this.unsupported();
  }

  async restart(_options?: LinuxStartOptions): Promise<LinuxStatus> {
    throw this.unsupported();
  }

  async getShareDir(): Promise<LinuxShareDir> {
    return {
      hostPath: '',
      guestPath: '/mnt/host',
      exists: false,
      available: false,
      note: WEB_REASON,
    };
  }

  async write(_options: LinuxWriteOptions): Promise<void> {
    throw this.unsupported();
  }

  async sendKey(_options: LinuxSendKeyOptions): Promise<void> {
    throw this.unsupported();
  }

  async exec(_options: LinuxExecOptions): Promise<LinuxExecResult> {
    throw this.unsupported();
  }

  private unsupported(): Error {
    const err = new Error(WEB_REASON);
    (err as Error & { code?: string }).code = 'UNSUPPORTED_PLATFORM';
    return err;
  }
}
