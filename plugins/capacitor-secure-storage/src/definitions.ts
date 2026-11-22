/**
 * 安全加密存储插件（iOS Keychain / Android Keystore / Web WebCrypto）。
 *
 * 三个平台都**不是**把明文写进 localStorage / SharedPreferences：
 * - iOS：Keychain（`kSecClassGenericPassword`），可选 `SecAccessControl` 生物识别门控；
 * - Android：AndroidKeyStore 里的 AES-256-GCM 密钥加密后写入 SharedPreferences，
 *   可选「每次使用都需要生物识别」的 Keystore 密钥（`setUserAuthenticationRequired`）；
 * - Web：WebCrypto AES-GCM + **不可导出**的 CryptoKey（存在 IndexedDB），密文存在 localStorage。
 *
 * ⚠️ Web 端只能算「尽力而为」：同源脚本（含 XSS）在页面里可以调用同样的 API 解出明文，
 * 浏览器的 localStorage/IndexedDB 也没有系统级隔离。真正的安全边界来自 iOS Keychain /
 * Android Keystore 的硬件保护。详见 README。
 */
export interface SecureStoragePlugin {
  /**
   * 写入（或覆盖）一个键值。值会先加密再落盘。
   *
   * @param options.key 键名（建议用 `a-z0-9._-`，避免各端编码差异）
   * @param options.value 明文值（字符串；二进制请先自己 base64）
   * @param options.requireBiometric 读取/写入该项都必须通过生物识别。
   *   - iOS：`SecAccessControl(.biometryCurrentSet)`；写入时不弹窗，**读取**时系统自动弹窗
   *   - Android：使用「每次使用都需要认证」的 Keystore 密钥，**写入与读取都会弹窗**
   *   - Web：不支持，会 reject `BIOMETRY_UNSUPPORTED`
   * @param options.accessibility iOS 专用：Keychain 可访问性，默认 `whenUnlockedThisDeviceOnly`
   */
  set(options: {
    key: string;
    value: string;
    requireBiometric?: boolean;
    accessibility?: SecureStorageAccessibility;
  }): Promise<{ key: string; requireBiometric: boolean }>;

  /**
   * 读取一个键值。不存在时返回 `value: null`（不 reject，方便 `?? 默认值`）。
   *
   * @param options.key 键名
   * @param options.reason 生物识别弹窗上的说明文字（iOS 用它作为 Face ID 提示语；
   *   Android 12+ 也有 `setSubtitle` 可用）
   */
  get(options: { key: string; reason?: string }): Promise<{
    key: string;
    value: string | null;
    requireBiometric: boolean;
    notFound: boolean;
  }>;

  /** 删除一个键（不存在也算成功）。 */
  remove(options: { key: string }): Promise<{ key: string; deleted: boolean }>;

  /**
   * 列出本插件管理的所有键名。
   *
   * 只返回**当前 App 安装**写入过的键（iOS Keychain 在卸载后仍然保留，重装后列表会是空的，
   * 但旧数据仍在 Keychain 里；需要清干净请用 `clear()` + 重新安装或 `deleteAllKeychainItems`）。
   */
  keys(): Promise<{ keys: string[] }>;

  /** 清空本插件的所有键值。 */
  clear(): Promise<{ cleared: number }>;

  /** 生物识别能力探测（不会弹窗）。 */
  isBiometryAvailable(): Promise<{
    available: boolean;
    /** `face` | `fingerprint` | `iris` | `biometric` | `none` */
    type: BiometryType;
    /** 设备有硬件但用户没录入指纹/面容时为 false */
    enrolled: boolean;
    reason?: string;
  }>;

  /**
   * 主动发起一次生物识别验证（不读写任何数据）。
   * 适合「进入敏感页面前先验证一次」的场景。
   */
  authenticate(options?: { reason?: string; title?: string; cancelText?: string }): Promise<{
    success: boolean;
    reason?: string;
  }>;

  /** 诊断信息：后端实现、条目数量、生物识别状态、iCloud 同步是否可能。 */
  getInfo(): Promise<{
    platform: 'ios' | 'android' | 'web';
    backend: string;
    itemCount: number;
    biometry: {
      available: boolean;
      type: BiometryType;
      enrolled: boolean;
    };
  }>;
}

/** iOS Keychain 可访问性等级（Android / Web 忽略此参数）。 */
export type SecureStorageAccessibility =
  | 'whenUnlocked'
  | 'afterFirstUnlock'
  | 'whenUnlockedThisDeviceOnly'
  | 'afterFirstUnlockThisDeviceOnly'
  | 'whenPasscodeSetThisDeviceOnly';

export type BiometryType = 'face' | 'fingerprint' | 'iris' | 'biometric' | 'none';

/** `set()` 的返回类型（供外部 import）。 */
export type SecureStorageSetResult = { key: string; requireBiometric: boolean };

/** `get()` 的返回类型（供外部 import）。 */
export type SecureStorageGetResult = {
  key: string;
  value: string | null;
  requireBiometric: boolean;
  notFound: boolean;
};
