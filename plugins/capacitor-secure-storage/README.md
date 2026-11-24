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

## API 一览

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

---

## API 详解

> 类型标注：`T` 必填、`T?` 可选、`A | B` 联合类型、`null` 表示 JSON null。
> 所有方法返回 `Promise`；reject 时 `Error.message` 以**大写错误码**开头（见下面的错误码表）。

### `set(options): Promise<{ key, requireBiometric }>`

加密并写入（同名 key 会被**覆盖**）。

| 参数 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- |
| `options.key` | `string` | — | 键名。建议 `a-z0-9._-`；不要用 `/`、控制字符（iOS 的 account 字段虽然能存，但不利于排查） |
| `options.value` | `string` | — | 明文。**必须是字符串**（二进制请自己 base64；数字/对象请 `JSON.stringify`） |
| `options.requireBiometric?` | `boolean` | `false` | 该条目是否需要生物识别门控（详见"平台差异"） |
| `options.accessibility?` | `SecureStorageAccessibility` | `whenUnlockedThisDeviceOnly` | **仅 iOS**，Keychain 可访问性（见上表）；`requireBiometric:true` 时被忽略 |
| `options.title?` | `string` | `'保存加密数据'` | **Android** 认证框标题（iOS 忽略） |
| `options.reason?` | `string` | `'请验证身份以保存该项'` | 认证框说明文字（iOS 用作 `LAContext.localizedReason`） |
| `options.cancelText?` | `string` | `'取消'` | 取消按钮文案（iOS/Android） |

返回：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `key` | `string` | 原样回显 |
| `requireBiometric` | `boolean` | 实际生效的值（Android 上 `requireBiometric:true` 一定有认证；iOS 上写入时**不**弹） |

错误：`INVALID_KEY` / `INVALID_VALUE`（value 为 null）/ `BIOMETRY_UNSUPPORTED` /
`BIOMETRY_NOT_ENROLLED` / `BIOMETRY_KEY_INVALIDATED` / `USER_CANCELED` / `AUTH_FAILED` / `AUTH_LOCKOUT`。

### `get(options): Promise<{ key, value, requireBiometric, notFound }>`

解密读取。**key 不存在不会 reject**（返回 `notFound:true`、`value:null`），方便 `value ?? 默认值`。

| 参数 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- |
| `options.key` | `string` | — | 键名 |
| `options.reason?` | `string` | `'验证身份以读取加密数据'` | 认证框说明（iOS） |
| `options.title?` | `string` | `'读取加密数据'` | 认证框标题（Android） |
| `options.cancelText?` | `string` | `'取消'` | 取消按钮文案 |

返回：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `key` | `string` | 原样回显 |
| `value` | `string \| null` | 明文；`notFound:true` 时为 `null` |
| `requireBiometric` | `boolean` | 该条目是否受生物识别保护 |
| `notFound` | `boolean` | 是否不存在 |

错误：`INVALID_KEY` / `CORRUPTED_DATA`（记录损坏）/ `BIOMETRY_UNSUPPORTED` /
`BIOMETRY_NOT_ENROLLED` / `BIOMETRY_KEY_INVALIDATED` / `VAULT_INVALIDATED` / `USER_CANCELED` / `AUTH_FAILED`。

### `remove(options): Promise<{ key, deleted }>`

| 参数 | 类型 | 说明 |
| --- | --- | --- |
| `options.key` | `string` | 键名 |

返回：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `key` | `string` | 原样回显 |
| `deleted` | `boolean` | 之前是否存在（不存在也返回 `false`，不报错） |

### `keys(): Promise<{ keys: string[] }>`

列出**本插件管理**的键名（不是设备上所有 Keychain 条目）。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `keys` | `string[]` | 键名数组。Android 来自 SharedPreferences 的 key 集合；iOS 来自 UserDefaults 索引（**不会弹认证**） |

> iOS 注意：Keychain 条目在卸载后仍保留，但 UserDefaults 索引会被清空 → 重装后 `keys()` 为空，
> 而 `get()` 仍能读到旧数据。

### `clear(): Promise<{ cleared: number }>`

清空本插件的**所有**条目（iOS 按 service 整体删除，能清掉索引丢失后的残留）。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `cleared` | `number` | 清掉的条目数（iOS 是索引里的数量；Android 是 prefs 条目数） |

> `clear()` **不会**删除 Keystore 密钥（Android）/ 不改 Keychain 的其他 service，所以几毫秒内完成。

### `isBiometryAvailable(): Promise<{ available, type, enrolled, reason? }>`

探测生物识别能力，**绝不弹窗**。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `available` | `boolean` | 现在是否可以发起生物识别（硬件有 + 已录入 + 未被锁定） |
| `type` | `'face' \| 'fingerprint' \| 'iris' \| 'biometric' \| 'none'` | iOS 能区分 Face ID / Touch ID / Optic ID；Android 平台不暴露具体类型，统一 `biometric` |
| `enrolled` | `boolean` | 硬件存在但用户没录入时为 `false`（此时 `available` 也是 `false`） |
| `reason?` | `string` | 不可用原因：`NONE_ENROLLED` / `NO_HARDWARE` / `REQUIRES_ANDROID_10_API_29` / iOS 错误码 |

### `authenticate(options?): Promise<{ success, reason? }>`

主动发起一次身份验证（**不读写任何数据**）。适合"进入敏感页面前先验一次"。

| 参数 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- |
| `options.reason?` | `string` | `'请验证身份'` | 认证框说明 |
| `options.title?` | `string` | `'身份验证'` | 认证框标题（Android） |
| `options.cancelText?` | `string` | `'取消'` | 取消按钮文案 |

返回：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `success` | `boolean` | 是否通过 |
| `reason?` | `string` | 失败原因：`USER_CANCELED` / `BIOMETRY_NOT_ENROLLED` / `AUTH_LOCKOUT` / 其他平台错误码串 |

> 语义差异：iOS 在**没有可用生物识别时会退回设备密码**（`deviceOwnerAuthentication`）；
> Android 只允许 `BIOMETRIC_STRONG`（不退回 PIN/图案）。需要"生物识别或密码"请自己判断
> `isBiometryAvailable()` 后决定是否提示用户。

### `getInfo(): Promise<{ platform, backend, itemCount, biometry }>`

诊断信息（不弹窗、不联网）。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `platform` | `'ios' \| 'android' \| 'web'` | 平台 |
| `backend` | `string` | 后端实现描述，例如 `AndroidKeyStore AES-256-GCM + SharedPreferences`、`Keychain (kSecClassGenericPassword)`、`webcrypto-aes-gcm-256 (non-extractable key in IndexedDB)` |
| `itemCount` | `number` | 当前条目数 |
| `biometry` | `{ available: boolean; type: string; enrolled: boolean }` | 同 `isBiometryAvailable()` 的三个字段 |

## 数据存在哪里（排查用）

| 平台 | 位置 | 说明 |
| --- | --- | --- |
| Android | `SharedPreferences("capacitor_secure_storage")` | 每条一个 JSON：`{v:1, alg:"A256GCM", bio:boolean, iv:base64, ct:base64}`，**没有明文** |
| Android | `AndroidKeyStore` 别名 `capacitor_secure_storage_v1`（普通）、`capacitor_secure_storage_bio_v1`（需认证） | 密钥不可导出；卸载 App 即销毁 |
| iOS | Keychain，`kSecAttrService = com.testionic.securestorage`，`kSecAttrAccount = key` | 值直接是 UTF-8 明文的 `Data`（由系统加密保护） |
| iOS | `UserDefaults["capacitorSecureStorage.index"]` | `{ key: requireBiometric }` 索引，供 `keys()` 使用 |
| Web | `localStorage["capacitor-secure-storage:<key>"]` = `<base64 iv>.<base64 ct>` | 密钥是 IndexedDB 里的不可导出 `CryptoKey` |

**容量/大小**：插件不限制 value 大小，但建议只存 token/短字符串（几 KB）。
Android 的 SharedPreferences 是**整文件重写**，存大对象会拖慢每次写入；iOS Keychain 单条也有上限
（实践中几百 KB 就开始不稳）。要存大文件请用 `capacitor-native-files` + 自己加密。

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

## 使用例

```vue
<section class="card">
  <h2>SecureStorage（Keychain / Keystore）</h2>
  <div class="actions">
    <button @click="secureSet">写入 token</button>
    <button @click="secureGet">读取 token</button>
    <button @click="secureList">列出键名</button>
    <button @click="secureRemove">删除 token</button>
    <button @click="secureClear">清空</button>
  </div>
  <div class="actions">
    <button @click="secureBiometry">生物识别探测</button>
    <button @click="secureSetBiometric">写入（需生物识别）</button>
    <button @click="secureAuthenticate">主动验证身份</button>
  </div>
  <p class="hint">
    Android：AndroidKeyStore AES-256-GCM + SharedPreferences；iOS：Keychain；
    Web：WebCrypto 不可导出密钥。requireBiometric 的条目在读取时会弹系统认证框。
  </p>
</section>

<script>
import { SecureStorage } from 'capacitor-secure-storage';

async secureSet() {
  try {
    const res = await SecureStorage.set({ key: 'demo.token', value: `token-${Date.now()}` });
    this.print('SecureStorage.set 成功', res);
  } catch (err) {
    this.error('SecureStorage.set', err);
  }
},

async secureGet() {
  try {
    const res = await SecureStorage.get({ key: 'demo.token', reason: '读取演示 token' });
    this.print('SecureStorage.get 成功', res);
  } catch (err) {
    this.error('SecureStorage.get', err);
  }
},

async secureList() {
  try {
    const [keys, info] = await Promise.all([SecureStorage.keys(), SecureStorage.getInfo()]);
    this.print('SecureStorage 状态', { keys, info });
  } catch (err) {
    this.error('SecureStorage.keys', err);
  }
},

async secureRemove() {
  try {
    const res = await SecureStorage.remove({ key: 'demo.token' });
    this.print('SecureStorage.remove', res);
  } catch (err) {
    this.error('SecureStorage.remove', err);
  }
},

async secureClear() {
  try {
    const res = await SecureStorage.clear();
    this.print('SecureStorage.clear', res);
  } catch (err) {
    this.error('SecureStorage.clear', err);
  }
},

async secureBiometry() {
  try {
    const res = await SecureStorage.isBiometryAvailable();
    this.print('生物识别能力', res);
  } catch (err) {
    this.error('isBiometryAvailable', err);
  }
},

async secureSetBiometric() {
  try {
    const res = await SecureStorage.set({
      key: 'demo.bio',
      value: 'biometric-secret',
      requireBiometric: true,
      title: '保存生物识别数据',
      reason: '请验证身份以保存演示数据',
    });
    this.print('SecureStorage.set(requireBiometric) 成功', res);
  } catch (err) {
    this.error('SecureStorage.set(requireBiometric)', err);
  }
},

async secureAuthenticate() {
  try {
    const res = await SecureStorage.authenticate({ reason: '请验证身份（演示）', title: '身份验证' });
    this.print('authenticate', res);
  } catch (err) {
    this.error('authenticate', err);
  }
},
</script>
```

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
4. **Web 端**
   - 密钥不可导出，但**同源脚本可以调用 `subtle.decrypt`**，所以只能算"防明文落盘"；
   - 浏览器清站点数据 / 无痕模式会同时丢掉密钥与密文（数据等于没了）；
   - `requireBiometric` 在 Web 上会 reject `BIOMETRY_UNSUPPORTED`（不会静默降级）。

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
- `requireBiometric:true` 的 `set()` 会真的弹出系统指纹框（`BiometricDialogView`）；
  点返回/取消后返回 `用户取消了生物识别`，且**没有写入任何残留**（`keys()` 为空、`get()` notFound）；
- autotest 中**不弹认证框**（避免阻塞自动化），只验证"设备明确没有生物识别时必须立刻 reject"这条路径。

模拟器 Pixel_6（API 36）：同上（生物识别未录入时走 `BIOMETRY_NOT_ENROLLED`）。

iOS：CI 模拟器上验证 `set/get/remove/keys` 与 `isBiometryAvailable()`（Face ID 未录入 → `available:false`）；
Face ID 门控的弹窗路径需要 iOS 真机（模拟器需手动 `Features > Face ID > Enrolled`）。

## 相关文件

- `src/definitions.ts`：完整类型与字段说明
- Android：`SecureStoragePlugin.java` + `BiometricGate.java`
- iOS：`SecureStoragePlugin.swift`
- Web：`src/web.ts`

---

## 与其他插件一起使用（注意事项）

本插件自带 `androidx.biometric:1.1.0` 依赖，并会往宿主合并 `USE_BIOMETRIC` 权限；除此之外不改动全局状态。

| 一起用的插件 | 会不会冲突 | 注意事项 |
| --- | --- | --- |
| `capacitor-back-button` | ⚠️ 可能互相干扰 | Android 的认证框是 DialogFragment，正常情况下会**自己消费返回键**（按返回 = 取消认证）。但部分 ROM（MIUI/EMUI）可能把返回键同时交给 Activity 的 `OnBackPressedDispatcher` → 如果 `BackButton.enable()` 开着，认证期间可能收到 `backButton` 事件。**建议：在等待 `get()/set({requireBiometric:true})/authenticate()` 的 Promise 期间忽略 backButton 事件**（或用 `disable()` 临时关掉），否则用户按返回会同时"取消认证 + 页面后退" |
| `capacitor-background-keepalive` | ✅ 无冲突 | 前台服务与本插件的 Keystore/Keychain 相互独立。注意：App 在后台时 Android 调 `get()`（需认证项）会弹认证框并把 App 拉回前台；iOS 在设备锁屏/未解锁时读 Keychain 会返回 `INTERACTION_NOT_ALLOWED`/`AUTH_FAILED` |
| `capacitor-permissions` | ✅ 无冲突 | 生物识别不需要运行时权限（`USE_BIOMETRIC` 是普通权限，安装即授予）；不要和 `camera`/`microphone` 混淆 |
| `capacitor-live-update` | ✅ 数据安全，但要注意时序 | OTA 只替换 Web 资源，Keystore/Keychain 里的数据**不受影响**。但 reload 会销毁 JS 上下文：如果安装更新时正好有认证弹窗/`get()` 在等回调，回调会落到已销毁的上下文（JS 收不到结果）。建议安装前先结束敏感操作。另外新包若改了存储 key 的名字/schema，旧数据还在但读不到，需要自己迁移 |
| `capacitor-native-files` / `capacitor-data-channel` | ✅ 无冲突 | 本插件只用私有的 SharedPreferences / Keychain service，不会碰文件系统 |
| 宿主自己 / 其他库也用 `androidx.biometric` | ⚠️ 版本会合并 | Gradle 会解析到最高版本；如果差异很大（例如 1.1.0 vs 1.4.x），`BiometricPrompt.PromptInfo` 的行为可能变化。建议全项目统一版本 |

其它注意：

- 本插件的 Android 存储键是 `capacitor_secure_storage`（prefs 文件名）与 Keystore 别名
  `capacitor_secure_storage_v1` / `capacitor_secure_storage_bio_v1`；iOS 的 Keychain service 是
  `com.testionic.securestorage`。**不要**在别的插件里复用这些名字。
- 同一把 bio 密钥承载所有"需认证"条目：一条在读，另一条就不能同时读（prompt 是模态的），
  并发 `get()` 会排队/互相打断 → 业务上串行使用。

---

## ⚠️ 已知限制（只能文档化，代码无法消除）

1. **iOS 的 `set()` 是"先删后加"**：Keychain 条目的 ACL（是否需要生物识别）只能在创建时指定，
   `SecItemUpdate` 改不了，所以覆盖写入必须 `SecItemDelete` + `SecItemAdd`。
   - 风险：两步之间如果进程被杀/磁盘满/Keychain 报错，**旧值会丢**（新值也没写进去）。
   - 建议：不要把唯一副本只存在这里；需要"更新不能丢"的场景，用**应用层双键**方案
     （写 `key.new` → 读回校验 → 删 `key` → 把 `key.new` 改名/复制为 `key`，读取时两个都查）。
   - Android 侧不受影响（AES-GCM 重写 prefs 是单条记录的原子替换）。
2. **同一把 bio 密钥承载所有"需认证"条目**（Android）：认证弹窗是模态的，两个并发的 `get()`
   会排队/互相打断。这是 Keystore "按密钥认证"的固有模型，插件不改成"每条一个密钥"
   （那样密钥数量会随条目线性增长）。业务上请**串行**使用需要认证的条目。
