# capacitor-live-update

从自己的后端下发 **web 资源包**（HTML/JS/CSS/图片），验签后原子安装到 App 私有目录，
把 Capacitor 的 WebView 根路径切过去，实现无需发版的"热更新"。

```
后端 latest.json ──► 应用 checkForUpdate()
                      │  version / url / sha256 / signature
                      ▼
              应用 downloadAndInstall()  ──► 校验 SHA-256 + ECDSA 签名
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
# 1) 插件目录里先构建（dist/ 是 tsc 产物、不入库，必须现场生成）
cd plugins/capacitor-live-update && npm install && npm run build

# 2) 回到项目根，用 file: 方式安装并同步原生工程
npm install ./plugins/capacitor-live-update   # 或 "file:plugins/capacitor-live-update"
npx cap sync
```

> ⚠️ 直接 `npm install file:...` 而不先 `npm run build`，宿主会解析不到入口
> （`package.json` 的 `main` 指向 `dist/esm/index.js`）。

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

| 字段 | 类型 | 必填 | 含义 |
| --- | --- | --- | --- |
| `version` | string | 是 | 新包版本号。空字符串/缺失 = 没有更新。用语义化版本比较（`1.2.0 > 1.2.0-beta`） |
| `url` | string | 是 | zip 的下载地址。建议 https + CDN；`http` 只适合本地联调 |
| `sha256` | string | 是 | zip **整个文件**的 SHA-256，小写 hex。客户端会重新计算并比对 |
| `signature` | string | 是* | ECDSA P-256（SHA-256）对 zip **整个文件字节**的签名，DER 编码后 base64。`allowUnsigned: true` 时可省 |
| `size` | number | 否 | zip 包的字节数（**单位：bytes**，不是 KB/MB）。仅用于展示/估算下载进度，**不参与校验**（校验看 `sha256`） |
| `notes` | string | 否 | 更新说明，会透传给 `checkForUpdate()` 的返回值 |
| `minNativeVersion` | string | 否 | 低于此内置版本时客户端返回 `available:false, reason:'NATIVE_TOO_OLD'` |

- 没有更新时返回 `{ "version": "" }` 或 `200 + {}`；
- **签名必须对 zip 的原始字节**做（不是对 JSON、不是对 zip 的哈希再签名 —— 当然两者等价，
  但本插件约定直接签字节，实现最简单也最不容易出错）；`sha256` 是第二道校验。
- 允许返回 302/307 重定向（客户端跟随），但**签名与摘要始终针对最终下载到的字节**。

### 查询参数（客户端 → 后端）

`checkForUpdate()` 会把下面 4 个参数**逐个 URL 编码**后拼到 `latest.json` 上
（Android 用 `Uri.encode`，iOS 用 `URLComponents`；值里的空格、中文、`/` 都会被编码）：

| 参数 | 类型 | 取值来源 | 示例 | 后端可以拿它做什么 |
| --- | --- | --- | --- | --- |
| `appId` | string | `configure({ appId })`；没配时用包名（Android `applicationId`）/ bundle id（iOS） | `com.myapp.testionic` | 一个后端服务多个 App / 多条产品线时区分是哪一个 |
| `platform` | `'android' \| 'ios'` | 客户端按当前平台固定传 | `android` | 同一个版本号给两端发**不同**的包（两端原生桥/资源可能不同）；也可以用来做两端灰度 |
| `channel` | string | `configure({ channel })` 或 `checkForUpdate({ channel })`；默认 `production` | `production` / `beta` / `gray` | 灰度、内测、A/B：同一 `appId` 按渠道返回不同版本 |
| `version` | string | 当前**实际运行**的版本（正在用 OTA 包就是 OTA 版本，否则是内置版本，即 `getState().currentVersion`） | `9.9.9` | 判断"要不要给更新"、决定返回全量还是提示、记录灰度指标 |

后端怎么用（建议）：

- 这 4 个参数**都是客户端自述**，服务端可以完全忽略（例如永远返回最新包）。
  客户端拿到响应后还会**自己再比一次版本**（见 `checkForUpdate` 的「行为细节」），
  所以"后端多给"不会被误装；但如果后端**少给**（比如总是返回旧版本），客户端就永远收不到更新。
- **`platform` 建议务必参与选择包**：如果两个平台的 web 资源完全一致，也可以忽略；
  一旦两端的原生桥/插件集合不同（例如只有 Android 有某个插件），就必须分开发包。
- **灰度不要靠客户端随机数**（会破坏 CDN/HTTP 缓存，且每次请求结果不稳定）；
  正确做法是后端根据 `appId + channel + version`（或你自己的用户身份，如果有鉴权）决定返回哪个版本。
- **不要把 token / 密码放进这些查询参数**：它们会进服务器访问日志、CDN 日志、浏览器历史。
  当前实现**不发送自定义 header**；如果更新接口必须鉴权，建议：
  1. 在 `configure({ serverUrl })` 里带一次性签名参数（后端校验签名 + 过期时间），或
  2. 改造插件给请求加 header（Android `HttpURLConnection.setRequestProperty` /
     iOS `URLRequest.addValue`）—— 但**不要**把 token 放进 `latest.json` 的响应里。
- `version` 可能为空字符串或格式不规范（用户改过包名/版本号），后端要能容错返回，
  不要因为 version 解析失败就 500。

本地联调可以直接用仓库里的脚本：

```bash
python scripts/make-live-update-fixture.py        # 生成/.复用测试私钥 + 夹具
python scripts/dev-ota-server.py --dist dist --port 8899 --version 9.9.9
adb reverse tcp:8899 tcp:8899                     # 手机通过 USB 访问电脑
# 然后在 App 里 configure({ serverUrl: 'http://127.0.0.1:8899', publicKey: <脚本打印的公钥> })
```

> 两个脚本的**完整带注释源码**见本文末尾的附录 A / B。

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

> ⚠️ `notifyAppReady()` 的语义是「这个包能正常跑起来」。如果你的 App 在启动早期就可能崩溃
> （例如某个页面必崩），请把确认放在**你确信业务已经初始化成功**的位置；放得越早，
> 自动回滚就越"迟钝"（因为它认为包是好的）。反过来，**完全不调用**会导致新包在第二次
> 冷启动时被回滚（这是有意的安全网）。

---

## API 详解

> 类型标注采用 TS 语法：`T` 必填、`T?` 可选、`A | B` 联合类型、`null` 表示 JSON null。
> 所有方法都返回 `Promise`；`reject` 时 `Error.message` 以**大写错误码**开头（可以 `startsWith` 判断）。

### `configure(options): Promise<LiveUpdateConfigState>`

设置并**持久化**后端地址与验签公钥。只需调用一次（之后 App 重启仍生效），
也可以在"设置页"里让用户切换渠道。

| 参数 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- |
| `options.serverUrl` | `string` | — | 后端基地址，**不带** `/latest.json`。例：`https://updates.example.com/testionic` |
| `options.publicKey?` | `string` | 保留上次 | ECDSA P-256 公钥。PEM（`-----BEGIN PUBLIC KEY-----`）或裸 base64 的 X.509 SubjectPublicKeyInfo 都支持 |
| `options.allowUnsigned?` | `boolean` | `false` | **仅开发用**：跳过签名校验。生产必须 `false` |
| `options.channel?` | `string` | `"production"` | 渠道名，会拼进查询参数 |
| `options.appId?` | `string` | 包名 / bundle id | 应用标识，会拼进查询参数 |
| `options.allowDowngrade?` | `boolean` | `false` | 允许安装**更低**版本（默认拒绝，防降级攻击） |
| `options.timeoutMs?` | `number` | `30000` | 网络超时（连接 + 读取），毫秒 |

返回 `LiveUpdateConfigState`（当前生效配置的快照）：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `serverUrl` | `string \| null` | 未配置时为 `null` |
| `publicKeyPreview` | `string \| null` | 公钥前 16 个字符 + `...`（不回传完整公钥，避免日志泄露） |
| `allowUnsigned` | `boolean` | 是否跳过验签 |
| `channel` | `string` | 渠道 |
| `appId` | `string` | 应用标识 |
| `allowDowngrade` | `boolean` | 是否允许降级 |
| `timeoutMs` | `number` | 超时毫秒 |
| `platform` | `'android' \| 'ios' \| 'web'` | 当前平台 |

错误：`INVALID_CONFIG`（serverUrl 为空）。

### `getConfig(): Promise<LiveUpdateConfigState>`

只读当前配置，字段同上。不会联网。

### `checkForUpdate(options?): Promise<UpdateCheckResult>`

向后端查询是否有新版本（**只读，不下载**）。

| 参数 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- |
| `options.serverUrl?` | `string` | 已配置值 | 临时覆盖后端地址（做灰度/多环境时有用） |
| `options.channel?` | `string` | 已配置值 | 临时覆盖渠道 |
| `options.force?` | `boolean` | `false` | 忽略版本比较，强制认为"有更新"（调试用） |
| `options.allowDowngrade?` | `boolean` | 已配置值 | 临时覆盖 `configure({ allowDowngrade })`：`true` 时后端给的版本比当前**旧**也视为可安装（默认拒绝，防降级攻击） |

返回 `UpdateCheckResult`：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `available` | `boolean` | 是否有可安装的更新 |
| `currentVersion` | `string` | 当前实际运行的版本（OTA 版本或内置版本） |
| `version?` | `string` | 后端给出的最新版本 |
| `url?` | `string` | zip 地址（直接传给 `downloadAndInstall`） |
| `sha256?` | `string` | 后端给出的摘要 |
| `signature?` | `string` | 后端给出的签名 |
| `size?` | `number` | zip 包的字节数（**单位：bytes**），即后端 `latest.json` 里的 `size`。可用于显示"约 1.2 MB"或估算下载进度；**不要**用它做完整性校验（那是 `sha256` 的事）。服务器没给时为 `undefined` |
| `notes?` | `string` | 更新说明 |
| `reason?` | `'UP_TO_DATE' \| 'DOWNGRADE_BLOCKED' \| 'NATIVE_TOO_OLD'` | `available:false` 时的原因 |

行为细节：

- 版本比较是**语义化**的，规则与完整例子见下面「版本比较规则与示例」；
- `available` 的判定：
  - 后端版本比当前**新** → 有更新；
  - 后端版本与当前**相等但字符串不同** → 也认为有更新（兼容后端版本号格式调整，例如 `1.2` ↔ `1.2.0`）；
  - 后端版本比当前**旧** → `DOWNGRADE_BLOCKED`，除非 `force` 或 `allowDowngrade: true`；
  - 后端没给 `version`（空字符串）→ `UP_TO_DATE`；
- 后端如果返回 `minNativeVersion` 且高于内置版本 → `NATIVE_TOO_OLD`（不下载，提示用户升级 App）；
- 成功后会记录 `lastCheckAt`（`getState()` 可见）。

#### 版本比较规则与示例

实现（Android `LiveUpdatePlugin.compareVersions` / iOS 同名方法，两端逻辑完全一致）
把版本号按 `.`、`-`、`+` 切成段，从左到右逐段比较：

1. **数字段按数值比**（不是字典序）：`10 > 9`；
2. **字母段按字典序**（区分大小写）：`beta < rc`；
3. **同一位置：数字段比字母段"新"**：`1.0.0.1 > 1.0.0.1-rc`；
4. **段数不同时，缺的段按 `0` 补**：`1.2 == 1.2.0`、`1.0.0 < 1.0.0.1`；
   但如果对侧剩下的是**字母段**，说明它是预发布版，**段数少的一侧更新**：`1.0.0 > 1.0.0-beta`。

| 当前版本 | 后端返回 | 结果 | 说明 |
| --- | --- | --- | --- |
| `1.2.0` | `1.2.1` | ✅ 更新 | 补丁位数字变大 |
| `1.2.0` | `1.2.0` | ⏸ `UP_TO_DATE` | 完全相等 |
| `1.2` | `1.2.0` | ✅ 更新 | 比较结果相等但字符串不同 → 仍会装一次（见上面的说明） |
| `1.2.0-beta` | `1.2.0` | ✅ 更新 | 正式版 > 预发布版 |
| `1.2.0` | `1.2.0-beta` | ⛔ `DOWNGRADE_BLOCKED` | 后端给的是预发布版，比当前旧（除非允许降级） |
| `1.2.0` | `1.2.0-beta.2` | ⛔ `DOWNGRADE_BLOCKED` | 先比到 `1.2.0` vs `1.2.0-beta`，预发布更旧 |
| `1.2.0-beta` | `1.2.0-beta.2` | ✅ 更新 | 预发布号递增（`beta` < `beta.2`） |
| `1.0.0-alpha` | `1.0.0-beta` | ✅ 更新 | 字母段字典序 |
| `1.10.0` | `1.9.0` | ⛔ `DOWNGRADE_BLOCKED` | 按**数值**比：10 > 9，所以 1.10 比 1.9 新（反向即降级） |
| `2.0` | `1.9.9` | ⛔ `DOWNGRADE_BLOCKED` | 主版本号更大的一方更新 |
| `1.0.0` | `1.0.0.1` | ✅ 更新 | 多一个补丁段（缺段补 0 后 0 < 1） |
| `1.0.0+build5` | `1.0.0+build6` | ✅ 更新 | `+` 后面的段继续按字母/数字规则比 |
| `v1.2.0` | `1.2.0` | ⛔ `DOWNGRADE_BLOCKED` | **别加 `v` 前缀**：`v` 是字母段，比数字段"旧" |

⚠️ 已知取舍（用的时候避开）：

- **纯数字后缀会被当成补丁段**：`1.0.0-1` 比 `1.0.0` **新**（缺段补 0 后 1 > 0）。
  所以预发布请用 `alpha` / `beta` / `rc` 这类**字母**标识，不要用纯数字；
- **区分大小写**：`1.0.0-RC` 与 `1.0.0-rc` 不相等，且 `RC < rc`（字典序），建议全小写；
- 后端最好**固定一种格式**（例如始终 `MAJOR.MINOR.PATCH[-prerelease]`），不要混用
  `1.2` / `1.2.0` —— 虽然能比，但会因为"字符串不同"触发一次多余的安装。

错误：`NOT_CONFIGURED` / `CHECK_FAILED`（含 HTTP 状态码）。

### `downloadAndInstall(options?): Promise<UpdateInstallResult>`

下载 → 校验 → 解压 → 安装 → 切换 → reload。**调用前建议先 `checkForUpdate()`**，
这样不传参也能用上一次的检查结果。

| 参数 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- |
| `options.url?` | `string` | 上次 check 的 url | zip 地址 |
| `options.version?` | `string` | 上次 check 的 version | 版本号；用于生成 bundle 目录名与状态记录 |
| `options.sha256?` | `string` | 上次 check 的 sha256 | **必填**（没传会 reject `SHA256_REQUIRED`） |
| `options.signature?` | `string` | 上次 check 的 signature | `allowUnsigned:false` 时必填 |
| `options.notes?` | `string` | 上次 check 的 notes | 仅记录，便于排查 |

返回 `UpdateInstallResult`：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `installed` | `boolean` | 是否安装成功（失败会 reject，不会返回 false） |
| `version` | `string` | 安装的版本 |
| `bundleId` | `string` | 本地目录名，格式 `<sanitized-version>-<sha256 前 8 位>` |
| `requiresReload` | `boolean` | 恒为 `true`：500ms 后会自动 reload 到新包 |
| `files?` | `number` | 解压出的文件数 |
| `bytes?` | `number` | 解压后的总字节数 |

错误码（都是 reject）：

| 错误码 | 含义 |
| --- | --- |
| `NO_UPDATE_INFO` | 既没传 url，也没有上一次 check 结果 |
| `VERSION_REQUIRED` / `SHA256_REQUIRED` | 缺关键字段 |
| `SIGNATURE_KEY_MISSING` | 没配置公钥且没开 `allowUnsigned` |
| `SIGNATURE_REQUIRED` | 后端没给签名且没开 `allowUnsigned` |
| `SHA256_MISMATCH` | 下载到的字节与后端声明不一致（传输损坏/被换包） |
| `SIGNATURE_INVALID` | 验签失败（包被篡改，或公钥不匹配） |
| `MISSING_INDEX_HTML` | 包里没有 `index.html`（或为空） |
| `ZIP_SLIP` / `ABSOLUTE_PATH_IN_ZIP` / `PATH_TRAVERSAL_IN_ZIP` | 包里有危险路径 |
| `TOO_MANY_ENTRIES` / `ARCHIVE_TOO_LARGE` | 超过 5000 个条目 / 解压超过 200MB |
| `INSTALL_FAILED` | 落盘/rename 等 IO 失败 |

### `getState(): Promise<LiveUpdateState>`

当前运行状态与本地包清单（不联网）。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `platform` | `'android' \| 'ios' \| 'web'` | 平台 |
| `currentVersion` | `string` | 当前实际版本（OTA 或内置） |
| `currentBundleId` | `string` | 当前 bundle 目录名；内置资源时为 `'builtin'` |
| `builtinVersion` | `string` | 安装包自带版本（`versionName` / `CFBundleShortVersionString`） |
| `usingOta` | `boolean` | 是否正在用 OTA 包（且目录真实存在） |
| `pending` | `boolean` | 新包还没被 `notifyAppReady()` 确认 |
| `bootAttempts` | `number` | 连续未确认的启动次数（≥2 会触发自动回滚，回滚后归零） |
| `previousVersion` | `string \| null` | 上一个包版本（用于 `rollback()`） |
| `lastCheckAt` | `number \| null` | 上次 `checkForUpdate` 的时间（Unix 毫秒） |
| `updates` | `LiveUpdateBundleInfo[]` | 本地已有的包（最多保留 3 个） |
| `note?` | `string` | 自愈/回滚时写的一句说明（例如"连续 2 次启动未能确认…已自动回滚"） |

`LiveUpdateBundleInfo`：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `version` | `string` | 版本号 |
| `bundleId` | `string` | 目录名 |
| `path` | `string` | 该包在设备上的绝对路径 |
| `bytes` | `number` | 该包在设备上占用的字节数（**单位：bytes**） |
| `active` | `boolean` | 是否是当前生效的包 |
| `previous` | `boolean` | 是否是 `rollback()` 的目标 |

### `notifyAppReady(): Promise<{ ok, currentVersion, pendingCleared }>`

告诉插件"这个包已经正常跑起来了"。

| 返回字段 | 类型 | 说明 |
| --- | --- | --- |
| `ok` | `boolean` | 恒 `true` |
| `currentVersion` | `string` | 确认时的版本 |
| `pendingCleared` | `boolean` | 是否真的清掉了一个 pending 标记（没 pending 时 `false`） |

副作用：`pending = false`、`bootAttempts = 0`。**不会** reload，也不会联网。

### `rollback(): Promise<{ rolledBack, toVersion, requiresReload }>`

回到上一个包（没有上一个就回内置资源），随后自动 reload。

| 返回字段 | 类型 | 说明 |
| --- | --- | --- |
| `rolledBack` | `boolean` | `true` = 回到了上一个包；`false` = 没有上一个包，回到了内置资源 |
| `toVersion` | `string` | 回滚后的版本 |
| `requiresReload` | `boolean` | 恒 `true` |

### `reset(): Promise<{ cleared, currentVersion }>`

删除**所有** OTA 包并回内置资源（清空状态、清掉 Capacitor 的持久化路径），随后 reload。

| 返回字段 | 类型 | 说明 |
| --- | --- | --- |
| `cleared` | `number` | 删掉的 bundle 目录数量 |
| `currentVersion` | `string` | 重置后的版本（= 内置版本） |

### `reload(): Promise<void>`

单纯重载 WebView（不改变版本）。适合"改了 localStorage/语言"后刷新界面。

### `verifyBundle(options): Promise<BundleVerifyResult>`

**离线自检**：对任意一个 zip 做「SHA-256 + 验签 + 解压」，但**不安装、不 reload**。
用于：后端发布前自测、App 内诊断页、无需起服务器的自动化测试。

| 参数 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- |
| `options.zipBase64` | `string` | — | zip 文件的 base64 |
| `options.sha256?` | `string` | — | 期望的摘要；不传则 `sha256Matches: null` |
| `options.signature?` | `string` | — | DER+base64 签名；不传则 `signatureValid: null` |
| `options.publicKey?` | `string` | 已配置公钥 | 验签公钥（PEM 或 base64 DER） |

返回 `BundleVerifyResult`：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `valid` | `boolean` | 摘要匹配 **且** 签名有效 **且** 解压出 `index.html` |
| `sha256` | `string` | 实际算出的 hex 摘要 |
| `sha256Matches` | `boolean \| null` | 传了 sha256 时的比对结果 |
| `signatureValid` | `boolean \| null` | 传了 signature（+公钥）时的验签结果 |
| `files` | `number` | 解压出的文件数（失败为 0） |
| `bytes` | `number` | 解压出的总字节数（**单位：bytes**；是解压后的体积，通常大于 `size` 里 zip 的字节数） |
| `entries` | `string[]` | 解压出的相对路径（最多 100 条，便于肉眼检查） |
| `note?` | `string` | 失败原因（例如 `error in opening zip file`、缺少签名） |

> Web 端没有 zip 解析与 ECDSA 实现，返回 `valid:false` + `note: 'NOT_SUPPORTED_ON_WEB'`。

### `addListener('downloadProgress', cb)`

下载进度事件（Android/iOS 都会发）。

| payload 字段 | 类型 | 说明 |
| --- | --- | --- |
| `bytes` | `number` | 已下载字节数（**单位：bytes**） |
| `total` | `number` | 总字节（服务器没给 Content-Length 时为 `-1`） |
| `percent` | `number` | `0~100`；`total<=0` 时为 `-1` |

```ts
const handle = await LiveUpdate.addListener('downloadProgress', (p) => {
  if (p.percent >= 0) progressBar.value = p.percent;
});
// 组件卸载时：
await handle.remove();
```

### 辅助函数 `syncUpdate(options?)`

`src/index.ts` 里导出的便捷函数（不是原生方法）：`checkForUpdate()` + `downloadAndInstall()`。

```ts
const { check, install } = await syncUpdate({ force: false });
// check: UpdateCheckResult；install: UpdateInstallResult | null（没更新时为 null）
```

## 使用例(前端)

```vue
<section class="card">
  <h2>LiveUpdate（热更新）</h2>
  <div class="actions">
    <button @click="liveUpdateState">读取状态</button>
    <button @click="liveUpdateVerify">离线自检（zip + 签名）</button>
    <button @click="liveUpdateCheck">检查更新</button>
    <button @click="liveUpdateConfigure">配置后端</button>
  </div>
  <div class="actions">
    <button @click="liveUpdateRollback">回滚</button>
    <button @click="liveUpdateReset">清空 OTA 包</button>
    <button @click="liveUpdateReload">重载 WebView</button>
  </div>
  <p class="hint">
    离线自检不需要服务器：用内置的已签名 zip 验证「解压 + SHA-256 + ECDSA P-256 验签」。
    真正安装请先配置 serverUrl（后端需提供 /latest.json 与 zip）。
  </p>
</section>

<script>
import { LiveUpdate } from 'capacitor-live-update';

async liveUpdateState() {
  try {
    const [state, config] = await Promise.all([LiveUpdate.getState(), LiveUpdate.getConfig()]);
    this.print('热更新状态', { state, config });
  } catch (err) {
    this.error('LiveUpdate.getState', err);
  }
},

async liveUpdateVerify() {
  try {
    const [ok, tampered, badSha] = await Promise.all([
      LiveUpdate.verifyBundle({
        zipBase64: LIVE_UPDATE_FIXTURE.zipBase64,
        sha256: LIVE_UPDATE_FIXTURE.sha256,
        signature: LIVE_UPDATE_FIXTURE.signatureBase64,
        publicKey: LIVE_UPDATE_FIXTURE.publicKeyPem,
      }),
      LiveUpdate.verifyBundle({
        zipBase64: LIVE_UPDATE_FIXTURE.zipBase64,
        sha256: LIVE_UPDATE_FIXTURE.sha256,
        signature: LIVE_UPDATE_FIXTURE.tamperedSignatureBase64,
        publicKey: LIVE_UPDATE_FIXTURE.publicKeyPem,
      }),
      LiveUpdate.verifyBundle({ zipBase64: LIVE_UPDATE_FIXTURE.zipBase64, sha256: 'deadbeef' }),
    ]);
    this.print('离线自检', { ok, tampered, badSha });
  } catch (err) {
    this.error('LiveUpdate.verifyBundle', err);
  }
},

async liveUpdateConfigure() {
  try {
    const res = await LiveUpdate.configure({
      serverUrl: 'https://updates.example.com/testionic',
      publicKey: LIVE_UPDATE_FIXTURE.publicKeyPem,
      channel: 'production',
      allowUnsigned: false,
    });
    this.print('配置热更新后端', res);
  } catch (err) {
    this.error('LiveUpdate.configure', err);
  }
},

async liveUpdateCheck() {
  try {
    const res = await LiveUpdate.checkForUpdate();
    this.print('检查更新', res);
  } catch (err) {
    this.error('LiveUpdate.checkForUpdate', err);
  }
},

async liveUpdateRollback() {
  try {
    const res = await LiveUpdate.rollback();
    this.print('回滚', res);
  } catch (err) {
    this.error('LiveUpdate.rollback', err);
  }
},

async liveUpdateReset() {
  try {
    const res = await LiveUpdate.reset();
    this.print('清空 OTA 包', res);
  } catch (err) {
    this.error('LiveUpdate.reset', err);
  }
},

async liveUpdateReload() {
  try {
    await LiveUpdate.reload();
  } catch (err) {
    this.error('LiveUpdate.reload', err);
  }
},
</script>
```

---

## 安全设计：做了哪些措施、为什么、防什么

先明确威胁模型 —— **热更新 = 你在运行"从网络下载的代码"**，所以安全设计的核心只有一个问题：
**"这段代码一定是我们的服务器发出来的吗？"** 其余措施都是围绕"即使包是坏的/恶意的，也不能把 App 搞死或越权"。

### 1. 措施清单（做了什么 → 为什么 → 防什么）

| # | 措施 | 为什么这么做 | 防住了什么 |
| --- | --- | --- | --- |
| 1 | **ECDSA P-256 签名**（对 zip 原始字节） | 只有持有私钥的那台签名服务能产出合法包；客户端只内置公钥 | 攻击者自建更新包（后端被入侵、CDN 投毒、DNS 劫持、内部人偷偷发版） |
| 2 | **SHA-256 摘要** | 比签名便宜的完整性强校验；能在验签前给出明确错误码，也便于后端/客户端对齐 | 传输损坏、下载被截断、CDN 返回了错误的旧文件 |
| 3 | **HTTPS**（生产强制） | 传输层加密 + 服务器身份认证，防止中间人**实时**替换 `latest.json` | 局域网/运营商中间人篡改 manifest（把 url 指到攻击者的包 → 但签名仍然拦得住第 1 条） |
| 4 | **公私钥分离**：公钥编进 App，私钥只在服务端 | 客户端只有验证能力；私钥泄露 = 完全失守，所以它必须是最高等级机密 | 逆向 App 拿到公钥也无法伪造签名 |
| 5 | **默认要求签名**（`allowUnsigned` 默认 false） | 安全默认值；漏配公钥时**拒绝安装**而不是静默降级 | 开发者"忘了配公钥"导致线上裸奔 |
| 6 | **拒绝降级**（`allowDowngrade` 默认 false） | 旧版本可能有已修复的漏洞，降级 = 重新引入 | 降级攻击（把用户打回有漏洞的版本） |
| 7 | **`minNativeVersion` 门槛** | 新 JS 可能用到新的原生能力；旧 App 装了会白屏 | "新 Web + 旧原生"导致的功能错配 |
| 8 | **先 staging 后 rename** | 解压到 `.staging-<bundleId>`，全部成功才 rename 成正式目录 | 解压中断/磁盘满导致"半个包"被当成可用版本启动 → 白屏 |
| 9 | **解压白名单**：拒绝 `..`、绝对路径、盘符、NUL；canonical path 必须落在目标目录内 | zip 里的路径是攻击者可控的 | zip-slip：把文件写到 App 沙盒外（覆盖配置/其他 App 数据） |
| 10 | **条目数 ≤ 5000、解压后 ≤ 200MB** | 解压是"按压缩包声明的大小"分配/写入 | zip bomb：小包解出巨量数据，撑爆存储/内存 |
| 11 | **必须有 `index.html`** | 没有入口的包等于坏包 | 传错文件/空包被当成有效更新 |
| 12 | **CRC32 + 大小校验**（iOS 自研解压器） | 解压器是自己写的，必须有内部一致性校验 | 解压器 bug / 包内单个文件损坏被静默接受 |
| 13 | **pending + 2 次启动自动回滚** | 签名只能证明"是我们发的"，不能证明"这个包能在**这台设备**跑起来" | 坏包（白屏、JS 崩溃、资源缺失）让 App 永久变砖 |
| 14 | **原生版本变化时丢弃 OTA** | `versionCode`/`CFBundleVersion` 变了说明是新的原生二进制 | 新原生 + 旧 Web 的错配（接口不兼容、启动即崩） |
| 15 | **包只写 App 私有目录** | Android `filesDir/live-update/...`、iOS `Library/NoCloud/...`，其他 App 无法读写 | 其他 App 替换你的 Web 资源 |
| 16 | **只保留最近 3 个包** | 磁盘配额有限；也减少"陈旧包被误启用"的面 | 存储被撑爆 / 回滚目标过多导致状态混乱 |
| 17 | **公钥/签名不进日志** | `getConfig()` 只回公钥前 16 字符；错误信息不含完整包内容 | 日志/崩溃上报泄露敏感数据（相对次要，但是好习惯） |
| 18 | **先 resolve 再 reload** | reload 会销毁 JS 上下文 | 不是安全问题，但"安装成功却永远等不到 Promise"是高频事故 |

### 2. 为什么签"文件字节"而不是签"manifest 字段"

两种设计都能做，本插件选前者，理由：

1. **少一层解析**：客户端拿到 zip → 算 SHA-256 → 验签 → 解压。不需要再解析一个"清单文件"、
   再校验清单里每个文件的哈希（那一层很容易被实现成"只校验清单本身"）。
2. **任何一个字节被改都会失败**：包括文件名、压缩方法、目录结构；
   如果签名只覆盖 manifest，攻击者仍可改 zip 里"未被 manifest 覆盖"的部分。
3. **和服务端工具链解耦**：后端只要 `sign(zip_bytes)`，不需要保证"zip 的字节在不同打包环境下稳定"
   （zip 的 mtime/顺序会让同一份内容的字节不同，这正是签名 manifest 的常见坑）。
4. 代价：**包内任何微小改动都要重新签名**（这恰恰是我们想要的属性）。

### 3. 为什么"已经有签名了还要 SHA-256"

- 签名验证是密码学运算，失败信息不直观；SHA-256 先做一次**廉价的完整性检查**，
  能用 `SHA256_MISMATCH` 明确区分"下载坏了"与"有人伪造"（`SIGNATURE_INVALID`）；
- 后端/运维常用 SHA-256 做发布对账（`sha256sum`），日志里出现摘要便于排查；
- 对"签名算法实现有 bug"是一种兜底（两道校验同时被绕过的概率极低）。

### 4. 密钥管理与轮换建议

- **私钥**：只放在签名服务（或 CI 的 secret）里；不要进仓库、不要进构建产物、不要给客户端。
  签名服务应记录"谁在什么时候发了什么版本"（审计）。
- **公钥**：编进 App（`configure({ publicKey })` 或在代码里写死）。公钥泄露无害。
- **轮换**：需要"多把公钥同时有效"时，改插件为"公钥数组，任一验证通过即可"，
  并在客户端版本铺开后再移除旧公钥。**不要在服务端直接换密钥**，否则旧客户端全部拒绝更新。
- 如果私钥疑似泄露：立刻停用、轮换、并考虑把"最低允许版本"抬高（强制用户升级原生 App）。

### 5. 这套设计**不**解决的问题（别指望它）

- **不加密包内容**：JS 是明文分发（本来也要给浏览器执行），不要往包里放密钥。
- **不防"你自己的后端发了坏代码"**：签名只证明来源，不证明质量 —— 靠 staged rollout + 回滚。
- **不防设备已 root / 用户主动改沙盒**：那种环境下任何客户端校验都能被绕过。
- **不防"WebView 被注入"**：JS 能调 `LiveUpdate` API，就能切到别的包（但换不了签名密钥）。
- **不做增量更新**：每次都是完整 zip（简单可靠；体积大时可在后端做 CDN 压缩，而不是插件做 diff）。
- **不做"审核绕过"**：见下面的商店政策，用热更新下发审核时没有的功能会被封号。

### 6. 商店政策（上线前必须确认）

- **Apple**：允许解释型代码更新（JS/HTML/CSS），但不得改变 App 主要目的、不得绕过审核
  （App Store Review Guidelines 2.5.2 / 3.3.1）。
- **Google Play**：**禁止**下发 DEX / native 代码（Device and Network Abuse 政策）；
  下发 web 资源本身允许（属于远程内容，注意隐私政策与内容合规）。

---

## 应用端如何成功接收一次热更新（端到端时序）

这一节回答"**我要做哪些事，才能保证 App 真的能收到并成功切换**"。

### 阶段 0：首次启动 / 每次冷启动（插件 `load()` 自动做）

```
App 冷启动
  │
  ├─ Capacitor 创建 WebView，读它自己的持久化路径
  │    · Android: SharedPreferences("CapWebViewSettings").serverBasePath
  │    · iOS:     KeyValueStore["serverBasePath"] → 只取最后一段 →
  │               Library/NoCloud/ionic_built_snapshots/<最后一段>
  │    → 目录存在就直接用（**第一帧就是新包，没有闪屏**）
  │
  └─ 插件 load()
       ├─ 原生版本变了？ → 删所有 OTA、清持久化、回内置资源（必要时 reload）
       └─ 有 pending 包？ → bootAttempts+1；≥2 → 回滚到上一版并 reload
```

**你要保证的**：

1. `index.html` 用**相对路径**引用资源（`./assets/...`）——因为 origin 仍是 `https://localhost`；
2. 不要把 `capacitor.config.json` 打进包（不会被读取）；
3. **在业务初始化成功后调用 `notifyAppReady()`**（否则第二次冷启动会被当成坏包回滚）。

### 阶段 1：检查更新（`checkForUpdate`）

```
GET {serverUrl}/latest.json?appId=..&platform=android|ios&channel=..&version=<当前版本>
  → {version,url,sha256,signature,size,notes,minNativeVersion}
  → 版本比较 → available:true/false
```

**你要保证的**：

- 后端返回的 `version` 是**单调递增**的（否则会一直提示 `UP_TO_DATE`）；
- 生产用 **HTTPS**（明文 http 仅用于 127.0.0.1 联调）；
- 跨域不需要：这是**原生** HTTP 请求，不走 WebView 的 CORS（Android `HttpURLConnection` / iOS `URLSession`）。

### 阶段 2：下载 + 校验 + 解压（`downloadAndInstall`）

```
下载到 cache 临时 zip
  → SHA-256 比对（不等 → SHA256_MISMATCH，整包丢弃）
  → 验签（失败 → SIGNATURE_INVALID，整包丢弃）
  → 解压到 bundles/.staging-<bundleId>
       · 校验路径安全、条目数、总大小、index.html
  → rename → bundles/<bundleId>（原子）
  → 写状态：current=bundleId, previous=旧包, pending=true, bootAttempts=0
  → 写 Capacitor 的 serverBasePath（下次冷启动直接生效）
  → resolve() ← 先返回
  → 500ms 后 reload → 新界面
```

**你要保证的**：

- zip 里**根目录**有 `index.html`（不是 `dist/index.html`）；
- 包的大小 < 200MB、文件数 < 5000；
- 后端返回的 `sha256`/`signature` 与**实际字节**一致（附录 A 的脚本会自动算）；
- 不要在 `downloadAndInstall()` 之后立刻假设"新界面已经生效" —— 它在 reload 前就 resolve 了，
  真正的切换发生在 ~500ms 后。

### 阶段 3：确认（`notifyAppReady`）

```
新包加载完成 → 你的 App 初始化成功 → notifyAppReady()
  → pending=false, bootAttempts=0
```

如果**没有**调用：下次冷启动 `bootAttempts=1`（还信新包），再下次 `=2` → **自动回滚**。
这是"新包把 JS 跑崩了"时的最后一道保险。

### 阶段 4：出问题（`rollback` / `reset`）

- `rollback()`：回到上一个包（或内置资源），自动 reload；
- `reset()`：删掉所有 OTA 包，回内置资源，自动 reload。

### 失败模式对照表

| 现象 | 最可能的原因 | 怎么确认 |
| --- | --- | --- |
| `NOT_CONFIGURED` | 没调 `configure` 或 serverUrl 为空 | `getConfig().serverUrl` |
| `CHECK_FAILED: HTTP_404` | 后端路径不对（少/多了斜杠） | 浏览器直接访问 `{serverUrl}/latest.json` |
| `available:false` 但明明发了新版 | `version` 没递增 / 后端缓存 | `check.reason`、`check.version` vs `getState().currentVersion` |
| `SHA256_MISMATCH` | CDN 返回了别的文件 / 下载被截断 | 对比 `sha256sum` |
| `SIGNATURE_INVALID` | 公钥与私钥不匹配 / 包被改动 | 用 `verifyBundle()` 在设备上复验 |
| `MISSING_INDEX_HTML` | 打包时多套了一层目录 | 解开 zip 看根目录 |
| 安装成功但界面没变 | 你观察太早（reload 有 500ms 延迟）/ 新包资源路径不对 | 等 1s；`getState().currentVersion` |
| 冷启动又回到旧版 | `notifyAppReady()` 没调（2 次回滚） | `getState().note` / `bootAttempts` |
| 新包白屏但没回滚 | 触发了但还没到第 2 次启动 | 再冷启动一次；或直接 `rollback()` |
| 每次启动都回滚 | 新包确实跑不起来（或 `notifyAppReady` 放太晚/报错） | `getState().note`、看 JS 控制台 |

### 自测顺序（推荐）

```powershell
# 1) 离线验证"包本身没问题"（不需要服务器）
#    用测试页的 "离线自检（zip + 签名）" 按钮，或：
node scripts/webview-eval.mjs --port 9311 --expr "...调用 verifyBundle..."

# 2) 端到端：本地后端 + adb reverse
npm run build
python scripts/dev-ota-server.py --dist dist --port 8899 --version 9.9.9
adb reverse tcp:8899 tcp:8899

# 3) App 内：configure(serverUrl=http://127.0.0.1:8899, publicKey=脚本打印值)
#    → checkForUpdate → downloadAndInstall → 等 reload
#    → getState（currentVersion 应为 9.9.9, pending true）
#    → notifyAppReady（pending false）
#    → 杀掉进程重开（应直接进 9.9.9）
#    → rollback（回内置）
```

---

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
  App network_security_config.xml 配置如下内容），生产必须 https。
  ```xml
  <network-security-config>
    <base-config cleartextTrafficPermitted="false" />
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="false">127.0.0.1</domain>
        <domain includeSubdomains="false">localhost</domain>
    </domain-config>
  </network-security-config>
  ```
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

---

## 附录 A：scripts/dev-ota-server.py（本地热更新后端，带注释）

> 用途：在你自己的电脑上跑一个"最小可用的热更新后端"，配合 `adb reverse` 做端到端联调。
> 完整源码（与仓库里的文件逐字一致）：

```python
#!/usr/bin/env python3
"""本地热更新后端（**仅用于开发/联调**，不要用于生产）。

────────────────────────────────────────────────────────────────────────────
它是干什么的
────────────────────────────────────────────────────────────────────────────
它是 `capacitor-live-update` 插件 README 里那套「最小后端协议」的可运行实现：

    GET /latest.json  →  {
                            "version": "9.9.9",
                            "url": "http://127.0.0.1:8899/bundle.zip",
                            "sha256": "<zip 全文件 SHA-256，hex>",
                            "signature": "<ECDSA P-256 + SHA-256 对 zip 全文件签名，base64(DER)>",
                            "size": 123456,
                            "notes": "本地联调包"
                          }
    GET /bundle.zip   →  把 `--dist` 目录压成 deflate zip 返回（内含 index.html）

App 端流程（对应插件 API）：

    LiveUpdate.configure({ serverUrl: 'http://127.0.0.1:8899', publicKey: <脚本打印的 PEM> })
    LiveUpdate.checkForUpdate()      → 拿到 version/url/sha256/signature
    LiveUpdate.downloadAndInstall()  → 下载 → 验 SHA-256 → 验签 → 解压 → 切换 → reload

────────────────────────────────────────────────────────────────────────────
为什么这么实现（对应插件的安全设计）
────────────────────────────────────────────────────────────────────────────
- **签名对 zip 的原始字节**：客户端校验的也是文件字节，不需要额外解析"清单"，
  少一层解析就少一类漏洞；任何字节被改都会让 SHA-256 与签名同时失败。
- **同时提供 sha256 与 signature**：sha256 防"传输损坏/被换包"，签名防"有人自己造包"。
  两者都过才安装（插件默认要求二者，除非显式 `allowUnsigned: true`）。
- **服务只绑 127.0.0.1**：开发机上不对外暴露；手机通过 USB 用
  `adb reverse tcp:8899 tcp:8899` 访问电脑的回环地址（插件允许明文 http 仅限回环）。
- **每次启动重新打包 + 重新签名**：改完 web 代码重启脚本就能测新一轮，避免"改了没生效"。
- **没有鉴权/限流/TLS**：这是开发工具，别拿它上生产；生产后端至少要有 HTTPS、
  鉴权、灰度、审计日志。

────────────────────────────────────────────────────────────────────────────
用法
────────────────────────────────────────────────────────────────────────────
    # 1) 先构建 web 资源（脚本会把 dist/ 打包）
    npm run build

    # 2) 起本地后端（会打印 App 端要用的 publicKey）
    python scripts/dev-ota-server.py --dist dist --port 8899 --version 9.9.9

    # 3) 让手机能访问电脑的回环地址（USB 连着的设备）
    adb reverse tcp:8899 tcp:8899

    # 4) 在 App 里配置 serverUrl 与 publicKey，然后 checkForUpdate / downloadAndInstall

可选参数：
    --dist <目录>     要打包的 web 资源目录（默认仓库根的 dist/）
    --port <端口>     监听端口（默认 8899，只绑 127.0.0.1）
    --version <版本>  返回给客户端的版本号（默认 9.9.9；决定"有没有更新"）
    --notes <文本>    latest.json 里的说明文字
    --key <PEM>       签名私钥（默认 .perf/fixture-key.pem；由 make-live-update-fixture.py 生成）

依赖：`pip install cryptography`。
"""
import argparse
import base64
import hashlib
import io
import json
import os
import sys
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

# 仓库根目录（本文件在 scripts/ 下）
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def zip_dir(directory: str) -> bytes:
    """把一个目录压成 deflate zip（路径相对于该目录，index.html 在根）。

    注意：打包时**不要**包含 `capacitor.config.json` 之类只对宿主编译期有意义的东西；
    真实项目里通常直接打包 `dist/` 就够了。
    """
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        # 排序后写入：同样的输入产出同样的 zip（便于 diff/复现）
        for base, _, files in os.walk(directory):
            for name in sorted(files):
                full = os.path.join(base, name)
                relative = os.path.relpath(full, directory).replace(os.sep, "/")
                archive.write(full, relative)
    return buffer.getvalue()


def load_key(path: str):
    """读 PKCS#8 PEM 私钥。"""
    with open(path, "rb") as handle:
        return serialization.load_pem_private_key(handle.read(), password=None)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dist", default=os.path.join(ROOT, "dist"))
    parser.add_argument("--port", type=int, default=8899)
    parser.add_argument("--version", default="9.9.9")
    parser.add_argument("--notes", default="local OTA bundle (dev server)")
    parser.add_argument("--key", default=os.path.join(ROOT, ".perf", "fixture-key.pem"))
    args = parser.parse_args()

    if not os.path.isdir(args.dist):
        sys.exit(f"dist 目录不存在：{args.dist}（先 npm run build）")
    if not os.path.exists(args.key):
        sys.exit(
            f"找不到私钥 {args.key}；先跑一次 python scripts/make-live-update-fixture.py 生成"
        )

    # 1) 准备签名密钥与公钥（公钥要配到 App 的 configure 里）
    private_key = load_key(args.key)
    public_pem = private_key.public_key().public_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PublicFormat.SubjectPublicKeyInfo,
    ).decode()

    # 2) 打包 + 摘要 + 签名（启动时算一次，之后所有请求复用同一份字节）
    payload = zip_dir(args.dist)
    sha256 = hashlib.sha256(payload).hexdigest()
    signature = private_key.sign(payload, ec.ECDSA(hashes.SHA256()))
    base_url = f"http://127.0.0.1:{args.port}"

    manifest = {
        "version": args.version,
        "url": f"{base_url}/bundle.zip",
        "sha256": sha256,
        "signature": base64.b64encode(signature).decode(),
        "size": len(payload),
        "notes": args.notes,
    }

    print(f"[info] bundle: {len(payload)} bytes, sha256={sha256}")
    print(f"[info] serving on {base_url}")
    print("[info] publicKey (配置到 LiveUpdate.configure):")
    print(public_pem)

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, fmt, *argv):  # noqa: N802
            # 把每个请求打到 stdout，便于确认"设备真的来拉了"
            print(f"[http] {self.address_string()} {fmt % argv}")

        def do_GET(self):  # noqa: N802
            path = urlparse(self.path).path
            if path == "/latest.json":
                # 忽略 query（appId/platform/channel/version 等），永远返回"有新版本"，
                # 方便反复测试同一次安装流程。
                body = json.dumps(manifest).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
            elif path == "/bundle.zip":
                self.send_response(200)
                self.send_header("Content-Type", "application/zip")
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)
            else:
                self.send_response(404)
                self.end_headers()

    # 只绑回环：手机通过 `adb reverse` 访问，不需要同 Wi-Fi、也不会暴露到局域网
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    server.serve_forever()


if __name__ == "__main__":
    main()

```

## 附录 B：scripts/make-live-update-fixture.py（夹具 + 签名工具，带注释）

> 用途：生成"真实签名过的 deflate zip"给三端做离线自检；也可用 `sign <zip>`
> 给任意 zip 签名（本地做"真安装"测试时用）。

```python
#!/usr/bin/env python3
"""生成 live-update 插件的「离线自检夹具」，并保存测试用签名私钥。

────────────────────────────────────────────────────────────────────────────
它产出什么
────────────────────────────────────────────────────────────────────────────
1. `src/plugin-test-fixtures.js`
   - 一个**真实的** deflate 压缩 zip（内含 index.html + 2 个资源 + 1 个中文文件名）；
   - 它的 SHA-256（hex）；
   - 它的 ECDSA P-256 签名（DER 编码后 base64）；
   - 一个**故意损坏**的签名（用于验证"验签失败必须拒绝"）；
   - 对应的 PEM 公钥。
   App 的 autotest 会用这份数据调用 `LiveUpdate.verifyBundle()`，在 Android / iOS
   两端验证「zip 解析 + SHA-256 + 验签 + 解压」整条链路，**不需要联网**、不需要起服务器。

2. `.perf/ota-bundle.zip`
   同一份 zip 落盘，方便配合 `scripts/dev-ota-server.py` 做端到端联调（可选）。

3. `.perf/fixture-key.pem`
   P-256 私钥（PKCS#8 PEM）。**已存在的会复用**，所以公钥不会每次重跑都变；
   这把钥匙只用于本地测试，`.perf/` 在 `.gitignore` 里，不会进仓库。

────────────────────────────────────────────────────────────────────────────
为什么这么设计
────────────────────────────────────────────────────────────────────────────
- **必须是真 zip（deflate）**：iOS 的 ZipArchive 是自己实现的（Foundation 没有解压 API），
  如果夹具用 store（不压缩）方式，就测不出 raw-DEFLATE 解压那段代码；用 deflate 才有效。
- **必须有中文文件名**：验证 zip 文件名按 UTF-8 解码、路径拼接没问题（两边都容易写错）。
- **签名覆盖整个 zip 字节**：这和后端协议、两端实现（Java `SHA256withECDSA` /
  CryptoKit `P256.Signing`）完全一致；任何字节被改动都会验签失败。
- **同时生成"损坏签名"**：autotest 要能验证"验签失败 → 拒绝安装"，而不是只测成功路径。

────────────────────────────────────────────────────────────────────────────
用法
────────────────────────────────────────────────────────────────────────────
    python scripts/make-live-update-fixture.py              # 重新生成夹具（复用已存在的私钥）
    python scripts/make-live-update-fixture.py sign a.zip   # 用同一把私钥给任意 zip 签名并打印
                                                            # sha256 / signature / publicKey（JSON）
                                                            # —— 本地做"真 OTA 安装"测试时用

依赖：`pip install cryptography`（本仓库开发机已安装）。
"""
import base64
import hashlib
import io
import json
import os
import sys
import zipfile

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

# 仓库根目录（本文件在 scripts/ 下）
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# ── 夹具 zip 的内容 ─────────────────────────────────────────────────────────
# 故意包含：
#   - 根目录的 index.html（插件会校验它存在，缺了就不算有效 bundle）
#   - 子目录 assets/（测嵌套路径）
#   - 中文文件名 assets/中文.txt（测 UTF-8 文件名）
FILES = {
    "index.html": (
        "<!doctype html>\n<html><head><meta charset=\"utf-8\">\n"
        "<title>OTA bundle (test fixture)</title>\n"
        "<link rel=\"stylesheet\" href=\"assets/style.css\"></head>\n"
        "<body><h1 id=\"ota-marker\">OTA TEST BUNDLE</h1>\n"
        "<script src=\"assets/app.js\"></script></body></html>\n"
    ),
    "assets/app.js": (
        "// 这段脚本会出现在解压后的 bundle 里，用于确认 zip 内的文件没被破坏\n"
        "window.__otaFixture = { version: '9.9.9', env: 'fixture' };\n"
        "console.log('OTA fixture bundle loaded');\n"
    ),
    "assets/style.css": "body { font-family: sans-serif; background: #101418; color: #eee; }\n",
    "assets/中文.txt": "中文文件名也要能正确解压。\n",
}


def build_zip() -> bytes:
    """把 FILES 压成一个 deflate zip（每个条目单独用 ZIP_DEFLATED）。"""
    buffer = io.BytesIO()
    # compresslevel=9：让压缩率固定，保证同样的输入产出同样的字节（便于排查）
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for name, content in FILES.items():
            info = zipfile.ZipInfo(name)
            info.compress_type = zipfile.ZIP_DEFLATED
            # 0o644 << 16 是 zip 里保存 unix 文件权限的字段（解压端可以忽略）
            info.external_attr = 0o644 << 16
            archive.writestr(info, content.encode("utf-8"))
    return buffer.getvalue()


def key_path() -> str:
    return os.path.join(ROOT, ".perf", "fixture-key.pem")


def load_or_create_key():
    """复用 `.perf/fixture-key.pem`；没有就生成一把 P-256 私钥并落盘。

    复用的原因：App 里 `configure({ publicKey })` 配置的是公钥，如果每次重跑都换钥匙，
    之前配好的公钥就失效了，联调会很烦。
    """
    path = key_path()
    if os.path.exists(path):
        with open(path, "rb") as handle:
            return serialization.load_pem_private_key(handle.read(), password=None)

    private_key = ec.generate_private_key(ec.SECP256R1())
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as handle:
        handle.write(
            private_key.private_bytes(
                encoding=serialization.Encoding.PEM,
                format=serialization.PrivateFormat.PKCS8,
                encryption_algorithm=serialization.NoEncryption(),
            )
        )
    print(f"[info] 生成新的测试私钥：{path}（.perf/ 已 gitignore）")
    return private_key


def main() -> None:
    # 1) 生成 zip 并算 SHA-256（插件会用同一个算法校验下载到的文件）
    payload = build_zip()
    sha256 = hashlib.sha256(payload).hexdigest()

    # 2) 取私钥 / 公钥
    private_key = load_or_create_key()
    public_key = private_key.public_key()

    # 3) 对 zip **整个文件的字节**签名（ECDSA P-256 + SHA-256，DER 编码）
    der_signature = private_key.sign(payload, ec.ECDSA(hashes.SHA256()))
    signature_b64 = base64.b64encode(der_signature).decode()

    # 4) 公钥导出成 PEM（Java 侧用 X509EncodedKeySpec 解析，CryptoKit 用 pemRepresentation）
    public_pem = public_key.public_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PublicFormat.SubjectPublicKeyInfo,
    ).decode()

    # 5) 造一个"看起来是 base64、但签名内容不对"的签名：只改 DER 的第一个字节。
    #    注意不能改 base64 字符本身（可能变成非法 base64），要改解码后的字节。
    tampered = bytearray(der_signature)
    tampered[0] = (tampered[0] + 1) % 256
    tampered_signature_b64 = base64.b64encode(bytes(tampered)).decode()

    # 6) 输出给 JS 用的夹具模块（被 src/views/PluginTestView.vue 的 autotest 引用）
    js = (
        "// 由 scripts/make-live-update-fixture.py 生成，请勿手改。\n"
        "// 这是一份真实签名过的 deflate zip：用于三端自检 zip 解析 + SHA-256 + ECDSA 验签。\n"
        "export const LIVE_UPDATE_FIXTURE = "
        + json.dumps(
            {
                "zipBase64": base64.b64encode(payload).decode(),
                "sha256": sha256,
                "signatureBase64": signature_b64,
                "tamperedSignatureBase64": tampered_signature_b64,
                "publicKeyPem": public_pem,
                "files": list(FILES.keys()),
                "bytes": len(payload),
            },
            ensure_ascii=False,
            indent=2,
        )
        + ";\n"
    )

    out_path = os.path.join(ROOT, "src", "plugin-test-fixtures.js")
    with open(out_path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(js)

    # 7) 顺手把 zip 落到 .perf/，方便 dev-ota-server.py 之类的手工联调
    perf_dir = os.path.join(ROOT, ".perf")
    os.makedirs(perf_dir, exist_ok=True)
    with open(os.path.join(perf_dir, "ota-bundle.zip"), "wb") as handle:
        handle.write(payload)

    print(f"fixture zip: {len(payload)} bytes, sha256={sha256}")
    print(f"wrote {out_path}")
    print(f"wrote {os.path.join(perf_dir, 'ota-bundle.zip')}")


def sign_file(path: str) -> None:
    """给任意 zip 签名，并打印插件后端协议需要的三个字段（JSON）。

    用途：本地做"真的安装一次热更新"的端到端测试 ——
    你自己打包一个 zip（比如当前 `dist/`），用这个命令签名，再把输出塞进
    `latest.json`（或者改用 scripts/dev-ota-server.py，它内置了签名逻辑）。
    """
    with open(path, "rb") as handle:
        payload = handle.read()
    private_key = load_or_create_key()
    public_key = private_key.public_key()
    signature = private_key.sign(payload, ec.ECDSA(hashes.SHA256()))
    public_pem = public_key.public_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PublicFormat.SubjectPublicKeyInfo,
    ).decode()
    print(
        json.dumps(
            {
                "sha256": hashlib.sha256(payload).hexdigest(),
                "size": len(payload),
                "signature": base64.b64encode(signature).decode(),
                "publicKey": public_pem,
            },
            indent=2,
        )
    )


if __name__ == "__main__":
    # 两种模式：默认生成夹具；`sign <zip>` 只签名并打印字段
    if len(sys.argv) >= 3 and sys.argv[1] == "sign":
        sign_file(sys.argv[2])
    else:
        main()

```

---

## 与其他插件一起使用（重要）

> 热更新的本质是 **重建整个 WebView 页面**：原生状态都还在，**JS 侧的一切都会丢**。
> 这是与其它插件配合时绝大多数坑的来源。

| 一起用的插件 | 会不会冲突 | 注意事项 |
| --- | --- | --- |
| `capacitor-data-channel` | ⚠️ 状态会"半保留" | 原生 HTTP/WS server **继续运行**（端口、OTP 表都在原生内存里），但 JS 侧的端口号/OTP 变量没了。更新后要重新 `getHttpInfo()` / `createHttpOtp()`。未消费的旧 OTP 仍在表里（上限 256），**建议安装前 `revokeAllOtps`**，否则可能"表满" |
| `capacitor-foreground-state` / `capacitor-background-keepalive` / `capacitor-native-rotation` / `capacitor-fullscreen` | ⚠️ 监听失效 | 这些插件的 `addListener` 都在 JS 侧：reload 后监听全部消失，需要重新注册（App 启动代码会重新跑一遍，确保它确实注册了） |
| `capacitor-native-statusbar` / `capacitor-webview-to-edge` / `capacitor-fullscreen` | ✅ 原生状态保留 | 状态栏色块、insets 监听、全屏标志都在原生侧，reload 不影响。但**新包的 CSS**要自己处理（例如 `data-theme`、安全区变量） |
| `capacitor-set-force-dark` | ✅ 会自动重注入 | 它监听 `onPageCommitVisible` / `onPageLoaded` 并在页面加载后注入 `data-theme`，所以 OTA 后主题仍生效；前提是该插件仍被 `cap sync` 注册 |
| `capacitor-native-files` | ⚠️ 不要手动改 OTA 目录 | 包在 App 私有目录（Android `filesDir/live-update/bundles`、iOS `Library/NoCloud/ionic_built_snapshots`），native-files 能列出来；**但不要写入/改名**，否则签名与文件一致性失效（下次安装会覆盖，回滚目标也可能被破坏） |
| `capacitor-secure-storage` | ✅ 数据不受影响 | Keystore/Keychain 与 Web 资源无关。但安装时若有认证弹窗或 pending 的 `get()`，回调会落到已销毁的 JS 上下文 |
| `capacitor-permissions` | ⚠️ 有依赖 | 新包要用**新的原生权限**时，宿主 Manifest 不会因为 OTA 变 → 申请不到。发版策略：先发原生（含权限），再用 `minNativeVersion` 放开 Web 包 |
| Ionic Appflow Live Updates / Capgo（`@capgo/capacitor-updater`） | ⚠️ 不要同时启用 | iOS 的 `Library/NoCloud/ionic_built_snapshots` 是 Ionic 公共约定目录：本插件只清理自己命名的 `<version>-<sha8>` 目录（不会删别家的包），但**两个 OTA 系统会互相覆盖 `serverBasePath`** → 只选一个 |
| 宿主直接用 Capacitor 内置的 `WebView.setServerBasePath` / `persistServerBasePath` | ⚠️ 会互相覆盖 | 本插件持久化用的正是 Capacitor 的 `CapWebViewSettings/serverBasePath`（Android）与 `KeyValueStore["serverBasePath"]`（iOS）。宿主若也调用这些 API，两者会抢同一个 key |

其它注意（跨插件的通用坑）：

- **安装期间不要有 pending 的原生交互**：`native-files.pickFiles`（等用户选文件）、`secure-storage` 的
  生物识别弹窗、`data-channel` 的大文件上传……reload 后这些回调的 JS 端已经没了。
  建议：先停止前台任务 → 再安装更新。
- **插件方法在 Capacitor 里是单线程串行执行的**：本插件已经把网络请求、下载、解压放到专用
  worker（Android 单线程 `Executor` / iOS 全局队列），不会堵住 Toast/状态栏等其它插件调用；
  但 `getState()` 会遍历本地包目录统计大小，包很多时略有耗时。
- **存储占用**：最多保留 3 个包（每个是完整 web 资源）。如果同时装了体积很大的别的资源
  （data-channel 的上传缓存、native-files 临时文件），注意设备空间：解压前要留出 ~2 倍包大小的空间。
