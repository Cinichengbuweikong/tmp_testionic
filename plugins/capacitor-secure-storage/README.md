# capacitor-secure-storage

安全地在本机保存敏感数据（token / 用户名 / 密码 / 私钥），底层用系统提供的加密能力，**不是**把明文写进 localStorage / SharedPreferences。

| 平台 | 后端 | 生物识别门控 |
| --- | --- | --- |
| iOS | Keychain（`kSecClassGenericPassword`） | `SecAccessControl(.biometryCurrentSet)`，读取时系统自动弹 Face ID / Touch ID |
| Android | AndroidKeyStore 里的 **AES-256-GCM** 密钥 + SharedPreferences 密文（`AES/GCM/NoPadding`） | `androidx.biometric` + `CryptoObject`，Keystore 密钥 `setUserAuthenticationRequired(true)` |
| Web | WebCrypto **AES-GCM-256** + **不可导出**的 `CryptoKey`（存 IndexedDB），密文存 localStorage | 不支持（`BIOMETRY_UNSUPPORTED`） |

> ⚠️ Web 端只是「尽力而为」：它防的是"密文被顺手拷走 / 直接翻 localStorage"，**不防 XSS** ——
> 同源脚本（含被你引入的第三方库、浏览器扩展）都能调用同一个 API 解出明文。
> 真正的安全边界来自 iOS Keychain / Android Keystore 的硬件保护。

## 安装

```bash
npm install file:../plugins/capacitor-secure-storage
npx cap sync
```

Android 侧会自动合并 `USE_BIOMETRIC` 权限，并引入 `androidx.biometric:biometric:1.1.0`（Gradle 依赖，无需你配置）。

## 快速上手

```ts
import { SecureStorage } from 'capacitor-secure-storage';

// 普通条目
await SecureStorage.set({ key: 'auth.token', value: accessToken });
const { value } = await SecureStorage.get({ key: 'auth.token' }); // value: string | null
await SecureStorage.remove({ key: 'auth.token' });

// 需要生物识别的条目（每次读取都会弹系统认证）
await SecureStorage.set({
  key: 'bank.password',
  value: 'secret',
  requireBiometric: true,
  reason: '请验证身份以保存密码',
});
const { value: pwd } = await SecureStorage.get({
  key: 'bank.password',
  reason: '请验证身份以读取密码',
});

// 进入敏感页面前先验证一次身份（不读写数据）
const { success } = await SecureStorage.authenticate({ reason: '请验证身份' });
```

## API

| 方法 | 说明 |
| --- | --- |
| `set({ key, value, requireBiometric?, accessibility?, title?, reason?, cancelText? })` | 加密写入（覆盖同名 key）。`value` 必须是字符串 |
| `get({ key, reason?, title?, cancelText? })` | 解密读取。不存在时返回 `{ value: null, notFound: true }`，**不 reject** |
| `remove({ key })` | 删除一个 key（不存在也算成功） |
| `keys()` | 列出本 App 写入过的 key（iOS 读的是 UserDefaults 索引，不碰 Keychain，不会弹认证） |
| `clear()` | 清空本插件所有数据 |
| `isBiometryAvailable()` | 探测生物识别能力（**不弹窗**） |
| `authenticate({ reason?, title?, cancelText? })` | 主动发起一次身份验证，返回 `{ success, reason? }` |
| `getInfo()` | 诊断信息：后端实现、条目数、生物识别状态 |

### `accessibility`（仅 iOS 生效）

| 值 | 含义 |
| --- | --- |
| `whenUnlockedThisDeviceOnly`（默认） | 解锁后可读，不随备份迁移到新设备 |
| `afterFirstUnlockThisDeviceOnly` | **重启后首次解锁即可读**（后台任务用这个） |
| `whenUnlocked` / `afterFirstUnlock` | 同上，但会随 iCloud/iTunes 备份迁移 |
| `whenPasscodeSetThisDeviceOnly` | 要求设备设置了密码；**移除密码时该条目会被系统删除** |

`requireBiometric: true` 的条目忽略 `accessibility`，固定使用
`SecAccessControl(.biometryCurrentSet)` + `kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly`。

## 错误码

`message` 以错误码开头（`CODE: 说明`），可以直接 `startsWith('BIOMETRY_NOT_ENROLLED')` 判断：

| 错误码 | 含义 / 处理建议 |
| --- | --- |
| `BIOMETRY_NOT_ENROLLED` | 设备没有录入指纹/面容。iOS 模拟器需要 `Features > Face ID > Enrolled` |
| `BIOMETRY_UNSUPPORTED` | 系统版本太低（Android 需要 10+）或 Web 端 |
| `BIOMETRY_KEY_INVALIDATED` | 用户改了指纹/面容（或删了指纹），Keystore 密钥被永久失效 → **旧数据解不开，只能重新写入** |
| `VAULT_INVALIDATED` | 密文与当前密钥不匹配（典型场景：系统备份恢复到了新设备） |
| `USER_CANCELED` | 用户在认证框点了取消 |
| `AUTH_FAILED` / `AUTH_LOCKOUT` | 认证失败 / 连续失败被锁 |
| `INVALID_KEY` / `INVALID_VALUE` | 参数问题 |

## 三个平台的行为差异（必读）

1. **写入时会不会弹窗**
   - iOS：`set()` **不弹**，只有 `get()` 会弹（ACL 在读取时校验）；
   - Android：`set()` 与 `get()` **都会弹**（Keystore 的 per-operation 认证要求每次使用密钥都先认证）。
2. **生物识别范围**
   - iOS 是**逐条目**的 ACL；
   - Android 的生物识别是**密钥级**的：本插件用 `capacitor_secure_storage_bio_v1` 这一个密钥承载所有需要认证的条目，
     所以任何一条的读写都会弹一次窗（够安全，但不是"每条独立密钥"）。
3. **卸载重装**
   - iOS Keychain 条目**在卸载后依然保留**（Apple 的设计），但 `keys()` 依赖的索引在 UserDefaults 里会被清空 →
     重装后 `keys()` 是空的、`get()` 仍能取到数据；需要彻底清干净请在卸载前调 `clear()`。
   - Android 卸载会删掉 SharedPreferences 与 Keystore 密钥，数据彻底消失。

## 宿主配置

- **Android**：无需配置（权限与依赖由插件自带）。若你的 App 开了 `android:allowBackup="true"`（默认），
  强烈建议把本插件的 prefs 排除掉，否则备份恢复到新设备后 Keystore 密钥不存在 → 解密永远失败：
  ```xml
  <!-- res/xml/backup_rules.xml（API ≤ 30）/ data_extraction_rules.xml（API 31+） -->
  <exclude domain="sharedpref" path="capacitor_secure_storage.xml"/>
  ```
- **iOS**：无需 entitlement（默认 access group）；如需与 App Extension 共享，需要自行加 `keychain-access-groups`。

## 安全边界（说清楚不做什么）

- ❌ 不防"设备已 root / 越狱"；❌ 不防 WebView 被注入（JS 能调 API 就能解）；
- ❌ 不做传输加密（那是 HTTPS 的事）；
- ✅ 防的是：设备丢失后被人翻文件、备份文件泄露、其他 App 读取（系统级隔离）、
  用户改了生物识别后旧密文被继续滥用；
- ✅ 密钥**永不离开** iOS Keychain / Android Keystore（Android 的密钥可用
  `KeyInfo.isInsideSecureHardware()` 验证是否在 TEE/StrongBox 里，本插件未强制 StrongBox）。

## 实测记录

真机 Redmi K30S（Android 10 / MIUI 12 / API 29）：

- `set/get/keys/remove/clear` 全部通过；`getInfo().backend = "AndroidKeyStore AES-256-GCM + SharedPreferences"`；
- `isBiometryAvailable()` → `{ available: true, type: "biometric", enrolled: true }`（设备已录指纹）；
- autotest 中**不弹认证框**（避免阻塞自动化），只验证"设备无生物识别时必须立刻 reject"这条路径。

模拟器 Pixel_6（API 36）：同上（生物识别未录入时走 `BIOMETRY_NOT_ENROLLED`）。

iOS：CI 模拟器上验证 `set/get/remove/keys` 与 `isBiometryAvailable()`（Face ID 未录入 → `available:false`）。

## 相关文件

- `src/definitions.ts`：完整类型与字段说明
- Android：`SecureStoragePlugin.java` + `BiometricGate.java`
- iOS：`SecureStoragePlugin.swift`
- Web：`src/web.ts`
