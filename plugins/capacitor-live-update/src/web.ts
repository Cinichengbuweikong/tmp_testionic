import { WebPlugin } from '@capacitor/core';
import type {
  LiveUpdateBundleInfo,
  LiveUpdateConfig,
  LiveUpdateConfigState,
  LiveUpdatePlugin,
  LiveUpdateState,
  UpdateCheckResult,
  UpdateInstallResult,
  UpdateRollbackResult,
} from './definitions';

/**
 * Web 端：**不能**真正切换资源根目录。
 *
 * 浏览器里页面资源由 HTTP 服务器提供，没有 `setServerBasePath` 这种能力；
 * 唯一"热更新"的正道是重新部署服务器资源 + 刷新页面（或 Service Worker）。
 * 所以这里只实现 `checkForUpdate`（方便调试后端协议），安装相关方法直接 reject。
 */
export class LiveUpdateWeb extends WebPlugin implements LiveUpdatePlugin {
  private config: LiveUpdateConfig | null = null;
  private lastCheckAt: number | null = null;

  async configure(options: LiveUpdateConfig): Promise<LiveUpdateConfigState> {
    this.config = { ...options };
    return this.toState();
  }

  async getConfig(): Promise<LiveUpdateConfigState> {
    return this.toState();
  }

  private toState(): LiveUpdateConfigState {
    return {
      serverUrl: this.config?.serverUrl ?? null,
      publicKeyPreview: this.config?.publicKey
        ? `${this.config.publicKey.slice(0, 16)}...`
        : null,
      allowUnsigned: this.config?.allowUnsigned ?? false,
      channel: this.config?.channel ?? 'production',
      appId: this.config?.appId ?? 'web',
      allowDowngrade: this.config?.allowDowngrade ?? false,
      timeoutMs: this.config?.timeoutMs ?? 30000,
      platform: 'web',
    };
  }

  async checkForUpdate(options?: {
    serverUrl?: string;
    channel?: string;
    force?: boolean;
  }): Promise<UpdateCheckResult> {
    const serverUrl = options?.serverUrl ?? this.config?.serverUrl;
    if (!serverUrl) throw new Error('NOT_CONFIGURED: 请先调用 configure({ serverUrl })');
    const url = new URL(`${serverUrl.replace(/\/$/, '')}/latest.json`);
    url.searchParams.set('appId', this.config?.appId ?? 'web');
    url.searchParams.set('platform', 'web');
    url.searchParams.set('channel', options?.channel ?? this.config?.channel ?? 'production');
    url.searchParams.set('version', 'web');

    const response = await fetch(url.toString());
    if (!response.ok) throw new Error(`HTTP_${response.status}`);
    const body = (await response.json()) as Record<string, unknown>;
    this.lastCheckAt = Date.now();
    return {
      available: Boolean(body.version),
      version: body.version as string | undefined,
      url: body.url as string | undefined,
      sha256: body.sha256 as string | undefined,
      size: body.size as number | undefined,
      signature: body.signature as string | undefined,
      notes: body.notes as string | undefined,
      currentVersion: 'web',
      reason: body.version ? undefined : 'UP_TO_DATE',
    };
  }

  async downloadAndInstall(): Promise<UpdateInstallResult> {
    throw this.unavailable('NOT_SUPPORTED_ON_WEB: 浏览器无法切换 WebView 资源根目录');
  }

  async getState(): Promise<LiveUpdateState> {
    return {
      platform: 'web',
      currentVersion: 'web',
      currentBundleId: 'builtin',
      builtinVersion: 'web',
      usingOta: false,
      pending: false,
      bootAttempts: 0,
      previousVersion: null,
      lastCheckAt: this.lastCheckAt,
      updates: [] as LiveUpdateBundleInfo[],
      note: 'Web 端没有 OTA 能力：资源由服务器提供，请直接部署 + 刷新。',
    };
  }

  async notifyAppReady(): Promise<{ ok: boolean; currentVersion: string; pendingCleared: boolean }> {
    return { ok: true, currentVersion: 'web', pendingCleared: false };
  }

  async rollback(): Promise<UpdateRollbackResult> {
    return { rolledBack: false, toVersion: 'web', requiresReload: false };
  }

  async reset(): Promise<{ cleared: number; currentVersion: string }> {
    return { cleared: 0, currentVersion: 'web' };
  }

  async reload(): Promise<void> {
    window.location.reload();
  }

  async verifyBundle(): Promise<{
    valid: boolean;
    sha256: string;
    sha256Matches: boolean | null;
    signatureValid: boolean | null;
    files: number;
    bytes: number;
    entries: string[];
    note?: string;
  }> {
    return {
      valid: false,
      sha256: '',
      sha256Matches: null,
      signatureValid: null,
      files: 0,
      bytes: 0,
      entries: [],
      note: 'NOT_SUPPORTED_ON_WEB: 浏览器端没有 zip 解析与 ECDSA 验签实现（原生端才需要这个自检）',
    };
  }
}
