import { registerPlugin } from '@capacitor/core';
import type {
  LiveUpdatePlugin,
  UpdateCheckResult,
  UpdateInstallResult,
} from './definitions';

const LiveUpdate = registerPlugin<LiveUpdatePlugin>('LiveUpdate', {
  web: () => import('./web').then((m) => new m.LiveUpdateWeb()),
});

/**
 * 便捷方法：检查 + 下载安装一步到位。
 *
 * （放在 TS 层而不是原生层：这样各平台只要实现 check/download 两个原子能力，
 * 组合逻辑只写一次，也方便业务层自己控制"先提示用户再安装"。）
 */
export async function syncUpdate(options?: {
  serverUrl?: string;
  channel?: string;
  force?: boolean;
}): Promise<{ check: UpdateCheckResult; install: UpdateInstallResult | null }> {
  const check = await LiveUpdate.checkForUpdate(options);
  if (!check.available) {
    return { check, install: null };
  }
  const install = await LiveUpdate.downloadAndInstall({
    url: check.url,
    version: check.version,
    sha256: check.sha256,
    signature: check.signature,
    notes: check.notes,
  });
  return { check, install };
}

export * from './definitions';
export { LiveUpdate };
