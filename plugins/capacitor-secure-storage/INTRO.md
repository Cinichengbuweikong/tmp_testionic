# capacitor-secure-storage · 插件概览

**一句话**：把 token / 密码这类敏感数据交给系统加密能力保管，并可选"每次读取都要指纹/面容"。

## 它解决什么问题

Web 前端习惯把 token 放在 `localStorage` —— 在 Capacitor App 里那是**明文**：
设备被拿走、备份被导出、或者别的 App 拿到 root，都能直接读。

这个插件换掉存储后端：

| 平台 | 数据存在哪 | 密钥在哪 |
| --- | --- | --- |
| iOS | Keychain（`kSecClassGenericPassword`） | Secure Enclave / 系统 Keychain |
| Android | SharedPreferences 里的**密文** | AndroidKeyStore（不可导出，可选 TEE/StrongBox） |
| Web | localStorage 里的密文 + IndexedDB 里的不可导出 `CryptoKey` | 浏览器（只能算"防明文落盘"） |

## 三个平台的能力差异（一句话版）

- **iOS**：生物识别是**逐条目**的 ACL，`set()` 不弹窗、`get()` 才弹 Face ID / Touch ID；
- **Android**：生物识别是**密钥级**的，`set()` 和 `get()` 都会弹（Keystore 每次使用密钥都要认证）；
- **Web**：没有系统级安全边界，`requireBiometric` 直接拒绝（不静默降级）。

## 核心 API

```ts
SecureStorage.set({ key, value, requireBiometric? })  // 加密写入
SecureStorage.get({ key })                            // 解密读取（不存在返回 value:null，不抛错）
SecureStorage.remove({ key }) / keys() / clear()
SecureStorage.isBiometryAvailable()                   // 探测（不弹窗）
SecureStorage.authenticate({ reason })                // 只做一次身份验证，不读写
```

## 典型场景

- 登录 token / refresh token 落盘；
- "记住密码"（配 `requireBiometric: true`，读取时强制验证）；
- 进入支付/隐私页面前 `authenticate()` 再放行。

## 必须知道的坑

1. **Android 换指纹/面容后旧数据永久解不开**（Keystore 密钥被系统失效）→ 报
   `BIOMETRY_KEY_INVALIDATED`，只能重新登录/重新写入；
2. **系统备份恢复到新设备**后密文与密钥不匹配 → `VAULT_INVALIDATED`，同样只能重写；
   建议在 `allowBackup` 里排除本插件的 prefs；
3. **iOS 卸载 App 不会清 Keychain**（Apple 的设计），但 `keys()` 的索引会被清 → 重装后
   `keys()` 为空、`get()` 仍能读到旧数据；
4. **iOS `set()` 是"先删后加"**（改 ACL 必须重建条目），极小概率丢旧值 → 不要存唯一副本。

## 定位

- 适合：短字符串（token、密码、证书指纹）、需要系统级加密、需要生物识别门控；
- 不适合：大文件（用 `capacitor-native-files` 自己加密）、跨设备同步（它是设备本地存储）。

## 实测

Android 10（真机，AES-256-GCM + 指纹）/ Android 16（模拟器）/ iOS 模拟器（CI）全部通过；
生物识别弹窗与取消路径在真机验证（取消后无任何残留写入）。
