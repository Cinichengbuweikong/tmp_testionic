/**
 * 热更新插件（web 资源 OTA）。
 *
 * 工作原理：从后端拉一个 **zip 包**（内含 index.html 等构建产物）→ 校验 SHA-256 与
 * ECDSA P-256 签名 → 解压到 App 私有目录 → 把 Capacitor 的 WebView 根路径指向该目录
 * （Android `Bridge.setServerBasePath` / iOS `WebViewAssetHandler`）→ reload。
 *
 * 关键设计（可靠性/安全性）：
 * - **签名必须验**：默认要求 `publicKey`（PEM），没配就拒绝安装（`SIGNATURE_KEY_MISSING`）；
 *   本地调试可显式 `allowUnsigned: true`。
 * - **原子切换**：先把包装解压到临时目录，全部校验通过后才 rename 成正式 bundle 目录。
 * - **启动自愈**：安装后状态标记为 `pending`。JS 必须调用 `notifyAppReady()` 确认；
 *   如果新包启动后 JS 崩了没确认，下次启动累计到 2 次就**自动回滚**到上一个版本。
 * - **原生升级优先**：App 的 versionCode/CFBundleVersion 变化（装了新 APK/IPA）时，
 *   自动丢弃 OTA 包，回到内置资源，避免"新原生 + 旧 Web"的错配。
 *
 * ⚠️ 商店政策：Apple 允许解释型代码更新（不得改变 App 主要目的/绕过审核）；
 * Google Play 禁止下发 DEX/native 代码。热更新**只能**更新 HTML/JS/CSS/图片等 Web 资源。
 * 详见 README。
 */
export interface LiveUpdatePlugin {
  /** 设置后端地址与验签公钥（会持久化）。 */
  configure(options: LiveUpdateConfig): Promise<LiveUpdateConfigState>;

  /** 读取当前配置（公钥会脱敏）。 */
  getConfig(): Promise<LiveUpdateConfigState>;

  /**
   * 向后端查询是否有新版本（只读，不下载）。
   *
   * 默认请求：`GET {serverUrl}/latest.json?appId=..&platform=..&channel=..&version=..`
   */
  checkForUpdate(options?: {
    serverUrl?: string;
    channel?: string;
    /** 忽略 `version` 比较，强制认为有更新（调试用） */
    force?: boolean;
  }): Promise<UpdateCheckResult>;

  /**
   * 下载并安装一个更新包（必须先 `checkForUpdate` 拿到 url，或直接传 url）。
   * 安装完成后 WebView 会自动 reload（`requiresReload: true`）。
   */
  downloadAndInstall(options?: {
    url?: string;
    version?: string;
    sha256?: string;
    signature?: string;
    notes?: string;
  }): Promise<UpdateInstallResult>;

  /** 当前状态：正在用的包、内置版本、pending 标记、本地已有包列表。 */
  getState(): Promise<LiveUpdateState>;

  /**
   * 告诉插件"新包已经正常启动"。
   * 业务代码应在 App 启动后尽快调用（见 README 的接入示例）。
   */
  notifyAppReady(): Promise<{ ok: boolean; currentVersion: string; pendingCleared: boolean }>;

  /** 手动回滚到上一个包（没有上一个时回到内置资源）。 */
  rollback(): Promise<UpdateRollbackResult>;

  /** 清空所有 OTA 包并回到内置资源。 */
  reset(): Promise<{ cleared: number; currentVersion: string }>;

  /** 重新加载 WebView。 */
  reload(): Promise<void>;

  /**
   * **离线自检**：校验任意一个 zip 包（SHA-256 + ECDSA 签名 + 解压），但**不安装**。
   *
   * 适合：发布前后端自测、App 内诊断页、以及不需要起服务器的自动化测试。
   */
  verifyBundle(options: {
    zipBase64: string;
    sha256?: string;
    signature?: string;
    publicKey?: string;
  }): Promise<BundleVerifyResult>;

  /** 下载进度事件（`bytes` / `total` / `percent`）。 */
  addListener(
    eventName: 'downloadProgress',
    listenerFunc: (progress: DownloadProgress) => void,
  ): Promise<import('@capacitor/core').PluginListenerHandle>;
}

export interface LiveUpdateConfig {
  /** 后端基地址，例如 `https://updates.example.com/app`（不带 `/latest.json`） */
  serverUrl: string;
  /** ECDSA P-256 公钥（PEM 或 base64 的 X.509 SubjectPublicKeyInfo）。缺省则拒绝安装 */
  publicKey?: string;
  /** 只允许开发环境：跳过签名校验（默认 false） */
  allowUnsigned?: boolean;
  /** 渠道名，默认 `production` */
  channel?: string;
  /** 应用标识，默认用 bundle id / applicationId */
  appId?: string;
  /** 允许安装比当前更旧的版本（默认 false） */
  allowDowngrade?: boolean;
  /** 网络超时（毫秒，默认 30000） */
  timeoutMs?: number;
}

export interface LiveUpdateConfigState {
  serverUrl: string | null;
  /** 公钥只返回前 16 个字符 + `...` */
  publicKeyPreview: string | null;
  allowUnsigned: boolean;
  channel: string;
  appId: string;
  allowDowngrade: boolean;
  timeoutMs: number;
  platform: 'android' | 'ios' | 'web';
}

export interface UpdateCheckResult {
  available: boolean;
  /** 后端给出的最新版本 */
  version?: string;
  url?: string;
  sha256?: string;
  size?: number;
  signature?: string;
  notes?: string;
  /** 当前正在用的版本（OTA 或内置） */
  currentVersion: string;
  /** 无需更新的原因：`UP_TO_DATE` / `DOWNGRADE_BLOCKED` / `NATIVE_TOO_OLD` */
  reason?: string;
}

export interface UpdateInstallResult {
  installed: boolean;
  version: string;
  bundleId: string;
  requiresReload: boolean;
  /** 解压后的文件数量 */
  files?: number;
  /** 解压后的总字节数 */
  bytes?: number;
}

export interface LiveUpdateState {
  platform: 'android' | 'ios' | 'web';
  /** 当前 WebView 实际加载的版本（OTA 版本号或内置版本号） */
  currentVersion: string;
  /** 当前 bundle 目录名（内置资源时为 `builtin`） */
  currentBundleId: string;
  /** 安装包自带的版本号（CFBundleShortVersionString / versionName） */
  builtinVersion: string;
  /** 是否在用 OTA 包 */
  usingOta: boolean;
  /** 新包还没被 `notifyAppReady()` 确认 */
  pending: boolean;
  /** 启动尝试次数（达到 2 会自动回滚） */
  bootAttempts: number;
  previousVersion: string | null;
  lastCheckAt: number | null;
  updates: LiveUpdateBundleInfo[];
  note?: string;
}

export interface LiveUpdateBundleInfo {
  version: string;
  bundleId: string;
  path: string;
  bytes: number;
  active: boolean;
  previous: boolean;
}

export interface UpdateRollbackResult {
  rolledBack: boolean;
  toVersion: string;
  requiresReload: boolean;
}

export interface DownloadProgress {
  bytes: number;
  total: number;
  percent: number;
}

export interface BundleVerifyResult {
  /** SHA-256 匹配 + 签名通过 + 解压出 index.html */
  valid: boolean;
  /** 实际算出来的 SHA-256（hex） */
  sha256: string;
  /** 传了 sha256 时它是否匹配；没传则 null */
  sha256Matches: boolean | null;
  /** 传了 signature + publicKey 时签名是否有效；没传则 null */
  signatureValid: boolean | null;
  /** 解压出的文件数（解压失败为 0） */
  files: number;
  /** 解压出的总字节数 */
  bytes: number;
  /** 解压出的文件相对路径（最多 100 条） */
  entries: string[];
  note?: string;
}
