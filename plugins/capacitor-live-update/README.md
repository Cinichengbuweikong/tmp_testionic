# capacitor-live-update

从自己的后端下发 **web 资源包**（HTML/JS/CSS/图片），验签后原子安装到 App 私有目录，
把 Capacitor 的 WebView 根路径切过去，实现无需发版的"热更新"。

```
后端 latest.json ──► checkForUpdate()
                      │  version / url / sha256 / signature
                      ▼
              downloadAndInstall()  ──► 校验 SHA-256 + ECDSA 签名
                      │                  解压到 staging → rename（原子）
                      ▼
        写 Capacitor 的 serverBasePath ──► reload ──► 新界面生效
                      │
              JS 调 notifyAppReady() 确认；没确认就自动回滚
```

| 平台 | 能力 | 实现 |
| --- | --- | --- |
| Android | ✅ 完整支持 | `Bridge.setServerBasePath()` + `CapWebViewSettings/serverBasePath` 持久化 + `java.util.zip` |
| iOS | ✅ 完整支持 | `CAPBridgeViewController.setServerBasePath(path:)` + `KeyValueStore["serverBasePath"]` + **自研 ZipArchive**（Compression 框架解 raw deflate） |
| Web | ❌ 只实现 `checkForUpdate` | 浏览器无法切换资源根目录；部署后刷新即可 |

## 安装

```bash
npm install file:../plugins/capacitor-live-update
npx cap sync
```

## 后端协议（最小实现）

`GET {serverUrl}/latest.json?appId=..&platform=..&channel=..&version=..`

```json
{
  "version": "1.2.0",
  "url": "https://cdn.example.com/bundles/1.2.0.zip",
  "size": 1234567,
  "sha256": "a62f63e3...（zip 全文件的 SHA-256，hex）",
  "signature": "MEUCIH...（ECDSA P-256 + SHA-256 对 zip 全文件签名，base64(DER)）",
  "notes": "修复登录页键盘遮挡",
  "minNativeVersion": "1.0.0"
}
```

- 没有更新时返回 `{ "version": "" }` 或 `200 + {}`；
- `minNativeVersion` 高于当前 App 的内置版本时，插件返回 `available:false, reason:'NATIVE_TOO_OLD'`；
- **签名必须对 zip 的原始字节**做（不是对 JSON、不是对 zip 的哈希再签名 —— 当然两者等价，
  但本插件约定直接签字节，实现最简单也最不容易出错）；`sha256` 是第二道校验。

本地联调可以直接用仓库里的脚本：

```bash
python scripts/make-live-update-fixture.py        # 生成/.复用测试私钥 + 夹具
python scripts/dev-ota-server.py --dist dist --port 8899 --version 9.9.9
adb reverse tcp:8899 tcp:8899                     # 手机通过 USB 访问电脑
# 然后在 App 里 configure({ serverUrl: 'http://127.0.0.1:8899', publicKey: <脚本打印的公钥> })
```

## 快速上手

```ts
import { LiveUpdate, syncUpdate } from 'capacitor-live-update';

// 1) 配置（只需一次，会持久化）
await LiveUpdate.configure({
  serverUrl: 'https://updates.example.com/testionic',
  publicKey: `-----BEGIN PUBLIC KEY-----
MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE...
-----END PUBLIC KEY-----`,
  channel: 'production',
});

// 2) App 启动时：确认上一个包可用（**必须**在 UI 可用后调用）
await LiveUpdate.notifyAppReady();

// 3) 检查 + 安装（同步逻辑封装好了）
const { check, install } = await syncUpdate();
if (install) console.log('已安装', install.version, '即将重启界面');
```

也可以拆开做，方便先弹窗问用户：

```ts
const check = await LiveUpdate.checkForUpdate();
if (check.available) {
  const install = await LiveUpdate.downloadAndInstall({
    url: check.url,
    version: check.version,
    sha256: check.sha256,
    signature: check.signature,
  });
}
```

### 建议的接入位置（`main.js`）

```js
import { Capacitor } from '@capacitor/core';
import { LiveUpdate } from 'capacitor-live-update';

if (Capacitor.isNativePlatform()) {
  // 注意：不要 await 阻塞首屏渲染，确认放到 UI 挂载之后
  window.addEventListener('load', () => {
    LiveUpdate.notifyAppReady().catch(() => {});
  });
}
```

## API

| 方法 | 说明 |
| --- | --- |
| `configure(options)` | 设置 `serverUrl` / `publicKey` / `channel` / `appId` / `allowDowngrade` / `timeoutMs` / `allowUnsigned`（持久化） |
| `getConfig()` | 读配置（公钥只返回前 16 字符） |
| `checkForUpdate({ serverUrl?, channel?, force? })` | 查询更新（只读） |
| `downloadAndInstall({ url?, version?, sha256?, signature?, notes? })` | 下载 + 验签 + 解压 + 切换 + reload |
| `getState()` | 当前版本 / bundle / pending / 本地包列表 |
| `notifyAppReady()` | 确认新包可用（清掉 pending 与启动计数） |
| `rollback()` | 回到上一个包（没有则回内置资源） |
| `reset()` | 删除所有 OTA 包，回内置资源 |
| `reload()` | 重载 WebView |
| `verifyBundle({ zipBase64, sha256?, signature?, publicKey? })` | **离线自检**：验摘要 + 验签 + 解压，但不安装 |
| `addListener('downloadProgress', cb)` | 下载进度 `{ bytes, total, percent }` |

### 状态字段（`getState()`）

```json
{
  "currentVersion": "9.9.9",
  "currentBundleId": "9.9.9-a62f63e3",
  "builtinVersion": "1.0",
  "usingOta": true,
  "pending": false,
  "bootAttempts": 0,
  "previousVersion": null,
  "updates": [{ "version": "9.9.9", "bundleId": "9.9.9-a62f63e3", "path": "...", "bytes": 1193827, "active": true, "previous": false }]
}
```

## 可靠性设计（为什么敢用）

1. **原子安装**：解压到 `.staging-<bundleId>`，全部校验通过后才 `rename` 成正式目录；
   解压中途失败不会留下半个包。
2. **双重校验**：SHA-256（完整性）+ ECDSA P-256（来源真实性）。默认**必须**有公钥与签名，
   配 `allowUnsigned: true` 才能跳过（仅限开发）。
3. **启动自愈**：安装后标记 `pending`。JS 调 `notifyAppReady()` 才清掉；
   否则每次冷启动 `bootAttempts + 1`，到 **2** 就自动回滚到上一版并 reload。
   典型场景：新包白屏 / JS 崩溃 / 资源加载失败 → 用户最多看到两次异常，第三次一定是好的。
4. **原生升级优先**：`versionCode` / `CFBundleVersion` 变化（装了新 APK/IPA）时，
   自动丢弃 OTA 包回内置资源，避免"新原生 + 旧 Web"错配。
   （iOS 侧 Capacitor 自己也会在二进制版本变化时清空 `serverBasePath`，插件再兜一层。）
5. **持久化在 Capacitor 自己的 key 上**：所以**冷启动第一帧就是新包**，不会先闪一下旧界面。
6. **存储隔离**：包只装在自己的私有目录（Android `filesDir/live-update/bundles`、
   iOS `Library/NoCloud/ionic_built_snapshots`），保留最近 3 个，自动清理旧的。
7. **解压安全**：拒绝 `..`、绝对路径、盘符、NUL；canonical path 必须落在目标目录内；
   条目数 ≤ 5000、解压后 ≤ 200MB；必须存在 `index.html` 才算有效包。
8. **不阻塞调用线程**：钩子（pending 计数/回滚）在插件 `load()` 里同步做，网络与解压在后台线程。

## 安全边界（务必读）

- **公钥必须内置在你的 App 里**（`configure({ publicKey })`），私钥只放在后端签名服务/CI 里。
  公钥有效期与 App 生命周期一致；泄露私钥 = 任何人都能给你的用户下发 JS。
- **必须用 HTTPS**（插件接受 `http://` 是为了本地 127.0.0.1 联调；生产用 http 等于把包交给中间人）。
- **只能更新 Web 资源**：HTML/JS/CSS/图片/字体。**不能**更新 Java/Kotlin/Swift 代码、插件、权限、
  Manifest / Info.plist 配置。新 JS 必须兼容已安装的旧原生二进制（桥的接口不能变）。
- **商店政策**
  - Apple：允许解释型代码更新，但不得改变 App 主要目的、不得绕过审核
    （App Store Review Guidelines 2.5.2 / 3.3.1）；用热更新下发"审核时没有的功能"会被封号。
  - Google Play：**禁止**下发 DEX / native 代码（Device and Network Abuse 政策）；
    下发 web 资源本身是允许的（就是普通的远程内容，注意隐私政策与内容合规）。
- **签名只保证"包是我们发的"**，不保证"包里的 JS 是安全的" —— 你的后端签名服务就是信任根。

## 平台细节与坑

### Android

- 持久化用 `SharedPreferences("CapWebViewSettings").serverBasePath`（Capacitor `WebView` 插件自己的 key），
  `Bridge` 启动时若该目录存在就直接 `hostFiles()` —— 所以冷启动无闪屏。
- 必须 `INTERNET` 权限（插件 Manifest 已声明）；明文 http 只允许 127.0.0.1（宿主
  `network_security_config.xml` 已配），生产必须 https。
- 安装后 `reload` 是延迟 500ms 触发的：**先 `resolve()` 再 reload**，否则 JS 上下文被销毁，
  Promise 永远收不到结果（这个坑踩过）。
- `versionCode` 用 `PackageInfo.getLongVersionCode()`（API 28+）。

### iOS

- Capacitor iOS 的持久化 key 是 `KeyValueStore.standard["serverBasePath"]`，但**下次启动它只取路径的最后一段**，
  去 `Library/NoCloud/ionic_built_snapshots/<最后一段>` 找资源。所以本插件**必须**把包装在这个固定目录下
  （`<bundleId>` 作为最后一段），否则持久化后启动会找不到资源而白屏。
- iOS **没有**公开的解压 API。插件自带 `ZipArchive.swift`（约 250 行）：解析 EOCD / 中央目录 / 本地文件头，
  store 与 deflate 都支持，用 `Compression` 框架的 `COMPRESSION_ZLIB`（raw DEFLATE）解压，
  并校验 CRC32、防止 zip-slip。不引入第三方 SPM 依赖（避免 CI 上包解析失败）。
- 签名验证用 CryptoKit 的 `P256.Signing.PublicKey`（PEM 或 DER 都支持），签名必须是 DER 编码。
- `setServerBasePath(path:)` 内部会 `webView.load(...)`，所以同样要**先 resolve 再切换**。

### 通用

- 热更新包里的 `index.html` 必须是**相对路径**引用资源（`./assets/...`）。
  用 `file://` 绝对路径或 `capacitor://localhost/xxx` 会失效（origin 仍是 `https://localhost`）。
- 包内不要放 `capacitor.config.json`（不会被读取，容易让人误解）。
- 更新后 `localStorage` / `IndexedDB` 不会丢（origin 不变）。
- 如果新包依赖"新的原生能力"，先发版再加 `minNativeVersion` 门槛，否则旧 App 会拿到跑不动的 JS。

## 实测记录（真机 Redmi K30S / Android 10 / MIUI 12）

用 `scripts/dev-ota-server.py` 起了本地后端（`adb reverse` 到设备），完整跑通了：

| 步骤 | 结果 |
| --- | --- |
| `configure` + `checkForUpdate` | `available:true, version 9.9.9, size 296595` |
| `downloadAndInstall` | `installed:true, files:51, bytes:1193827`，500ms 后自动 reload |
| reload 后 | `location.href = https://localhost/#/`、`currentVersion 9.9.9`、`usingOta:true`、`pending:true` |
| `notifyAppReady` | `{ok:true, pendingCleared:true}` |
| 冷启动（force-stop 后重开） | **直接进 9.9.9**（`usingOta:true`, `pending:false`），无闪屏 |
| `rollback` | `{rolledBack:false, toVersion:'1.0'}` → 回内置资源并 reload |
| **自动回滚**（安装后不确认，连续启动 2 次） | 第 1 次 `bootAttempts:1` 仍在 9.9.9；第 2 次自动回滚到 `1.0`，`note = "新包连续 2 次启动未能确认…已自动回滚"` |
| `verifyBundle`（离线） | 4 个文件的 deflate zip（含中文文件名 `assets/中文.txt`）：`valid:true, sha256Matches:true, signatureValid:true`；篡改签名 → `signatureValid:false`；错 sha → `sha256Matches:false` |

模拟器 Pixel_6（Android 16 / API 36）：`verifyBundle` 同上（`files:4`），autotest 通过。

iOS：CI 模拟器上 `verifyBundle` 用同一份夹具跑通了 zip 解析（deflate）+ CryptoKit 验签。

## 相关文件

- 协议与类型：`src/definitions.ts`
- Android：`LiveUpdatePlugin.java` / `UpdateStore.java` / `UpdateInstaller.java` / `UpdateVerifier.java`
- iOS：`LiveUpdatePlugin.swift` / `ZipArchive.swift`
- 本地联调：`scripts/dev-ota-server.py`、`scripts/make-live-update-fixture.py`
