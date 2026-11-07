# capacitor-data-channel

原生 ↔ WebView **二进制数据通道**：在原生侧启动**两个完全独立**的本地 server（可分别启停）：

| Server | 能力 | 典型用途 |
| --- | --- | --- |
| **HTTP**（`startHttp/stopHttp`） | 大文件上传/下载（流式、Range） | 视频/图片/任意文件读写 |
| **WebSocket**（`startWebSocket/stopWebSocket`） | 实时双向二进制帧 | 音视频帧流、连续 buffer、控制信令 |

两个 server 都只监听 `127.0.0.1`，让 WebView 用标准 `fetch` / `<video>` / `new WebSocket` 直接收发
**原始二进制**，绕开 Capacitor 桥的 JSON/base64 序列化。

> 本插件**只负责高速传输**。存储权限、系统选择器、目录列表/删除/重命名等请用
> [`capacitor-native-files`](../capacitor-native-files/)。

**实测性能**（真机 Redmi K30S / Android 10）：

| 场景 | 结果 |
| --- | --- |
| HTTP 上传 100MB（分块） | 920ms（109 MB/s） |
| HTTP 下载 100MB | 353ms（285 MB/s） |
| WebSocket 10MB 往返 | 181ms（55 MB/s） |
| WebSocket 64KB 帧 ping-pong | 1.7ms/帧（588 fps） |
| server 空闲开销 | RSS +0.5MB、CPU 0.13% 单核 |

## 鉴权：OTP（一次性凭证，不是长期 token）

**为什么不用长期 token**：长期 token 一旦被抓包拿到就等于全权限；而且 Origin 白名单无法区分
"其他 Capacitor App"（它们也是 `capacitor://localhost` / `https://localhost`）。

**OTP 方案**：

- OTP 通过 **Capacitor 桥**下发（`createHttpOtp` / `createWsOtp`）——桥是 `postMessage` **进程内通信**，
  **抓包软件看不到生成过程**；
- 抓包者最多看到"**已经用掉**"的 OTP → **无法重放**；
- **一次性**（默认）：用一次即作废，无时间限制；
- **可复用（reusable）**：仅允许 `scope: 'read'`（媒体持续读取用）；
- **写操作不允许 reusable**（每次写必须新 OTP）；
- **无 TTL**：等价于"server 生命周期内有效"，server 停止即全部失效。

> ## ⚠️⚠️ 必须 revoke
>
> **`reusable: true` 的 OTP 在 server 运行期间永久有效（无 TTL）。**
> **用完（播放结束 / 页面卸载 / 切换媒体）必须调用 `revokeOtp`，否则会留下严重的安全隐患**——
> 一旦该 OTP 泄露（如 URL 被记录、截图、调试日志），攻击者可以在 server 运行期间反复读取该文件。
>
> 建议：
> - 媒体 `onended` / 组件 `beforeUnmount` 时 `revokeOtp({ otp })`；
> - 切后台、退出页面时 `revokeAllOtps()` 兜底；
> - 只给**真的需要持续读取**的场景用 reusable，其他一律一次性。

## 快速开始

```ts
import { DataChannel } from 'capacitor-data-channel';

// ---------- HTTP：大文件 ----------
const http = await DataChannel.startHttp();
// http = { running, port, baseUrl: 'http://127.0.0.1:41523', rootPath, maxBodyBytes, maxConnections, ... }

// 上传：先通过桥拿一次性 OTP（写）
const w = await DataChannel.createHttpOtp({ scope: 'write', path: 'videos/clip.mp4' });
await fetch(`${http.baseUrl}/upload?path=videos/clip.mp4`, {
  method: 'PUT',
  headers: { Authorization: `OTP ${w.otp}` },
  body: fileBlob,
});

// 下载：一次性 OTP（读）
const r = await DataChannel.createHttpOtp({ scope: 'read', path: 'videos/clip.mp4' });
const blob = await (await fetch(`${http.baseUrl}/download?path=videos/clip.mp4&otp=${r.otp}`)).blob();

// 媒体播放：reusable OTP（放 URL），播放结束必须 revoke
const media = await DataChannel.createHttpOtp({
  scope: 'read',
  path: 'videos/clip.mp4',
  reusable: true,
});
video.src = `${http.baseUrl}/download?path=videos/clip.mp4&otp=${media.otp}`;
video.onended = () => DataChannel.revokeOtp({ otp: media.otp });

// ---------- WebSocket：实时双向 ----------
const wsInfo = await DataChannel.startWebSocket();
const wsOtp = await DataChannel.createWsOtp(); // 一次性
const ws = new WebSocket(wsInfo.wsUrl, [`otp.${wsOtp.otp}`]); // 子协议传 OTP（不进 URL）
ws.binaryType = 'arraybuffer';
ws.onmessage = (event) => {
  const bytes = new Uint8Array(event.data);
  /* ... */
};
ws.send(new Uint8Array([1, 2, 3]));

// ---------- 停止 ----------
await DataChannel.stopHttp();
await DataChannel.stopWebSocket();
```

## OTP 规则速查

| 项目 | 规则 |
| --- | --- |
| 默认 scope | `read` |
| `path`（HTTP OTP） | **必填**，OTP 只对**该精确路径**生效（不是前缀） |
| `reusable` 默认 | `false`（一次性） |
| `reusable: true` | **仅允许 `scope: 'read'`**；写操作会 reject |
| 有效期 | **无 TTL**（server 停止才失效） |
| 使用次数 | 一次性 OTP 用 1 次；reusable 不限（直到 revoke） |
| 数量上限 | 256 个（超出创建会报错） |
| WS OTP | **只支持一次性**（双向含写入，不允许 reusable） |
| 校验失败 | 统一返回 **404**（不泄露"需要 OTP"） |

## 数据目录（rootDir）

`startHttp({ rootDir })` 支持三种写法：

| 写法 | 示例 | 说明 |
| --- | --- | --- |
| **不传**（默认） | — | Android：`/sdcard/Android/data/<包名>/files/dataChannel`（**无需权限**）<br>iOS：`Documents/dataChannel`（App 私有沙盒） |
| **相对路径** | `myData` | 相对 App 私有目录 |
| **绝对路径** | `/sdcard/Download/myVideos` | 任意位置（**权限取决于平台，见下**） |

| 平台/版本 | 公共目录（如 `/sdcard/Download`） | 需要什么 |
| --- | --- | --- |
| Android ≤ 9 | ✅ 可读写 | `WRITE_EXTERNAL_STORAGE` |
| **Android 10（API 29）** | ✅ **可读写（实测通过）** | `WRITE_EXTERNAL_STORAGE` + 宿主 `requestLegacyExternalStorage="true"` |
| **Android 11+（API 30+）** | ⚠️ 受限 | **`MANAGE_EXTERNAL_STORAGE`**（所有文件访问权限） |
| iOS | 🚫 不存在"公共目录" | 只能访问 App 沙盒内 |

- **权限的查询/申请由 `capacitor-native-files` 提供**（本插件不再包含权限 API）；
- 授权必须走**系统权限弹窗**（用户手动点"允许"），系统才会把 App 的存储挂载切到可写视图；
  用 `adb shell pm grant` 这类命令行授权**不会触发 remount**，会仍然 `EACCES`；
- 无权限时 `startHttp()` 返回明确错误（提示用 native-files 授权）。

## ⚠️ 安装前必做：宿主应用配置（一次性）

页面（`https://localhost` / `capacitor://localhost`）访问 `http://127.0.0.1` 需要宿主放行明文回环。

### Android

1. 新建 `android/app/src/main/res/xml/network_security_config.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <base-config cleartextTrafficPermitted="false" />
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="false">127.0.0.1</domain>
        <domain includeSubdomains="false">localhost</domain>
    </domain-config>
</network-security-config>
```

2. `AndroidManifest.xml` 的 `<application>` 加：

```xml
android:networkSecurityConfig="@xml/network_security_config"
```

> 如需用**公共目录**作为 rootDir（Android 10），再加 `android:requestLegacyExternalStorage="true"`。

### iOS

`Info.plist`：

```xml
<key>NSAppTransportSecurity</key>
<dict>
    <key>NSAllowsLocalNetworking</key>
    <true/>
</dict>
```

## HTTP 路由（只有三个）

| 方法 | 路径 | 鉴权 | 说明 |
| --- | --- | --- | --- |
| GET | `/health` | **免 OTP** | 连通性自检（无敏感信息） |
| PUT / POST | `/upload?path=<相对路径>&append=0\|1` | write OTP | 原始二进制上传（流式写盘）；`append=1` 追加 → `{path,bytes,size,appended}` |
| GET | `/download?path=<相对路径>` | read OTP | 流式下载；支持 `Range` → **206**（视频拖动必需） |
| OPTIONS | 任意 | **免 OTP** | CORS 预检（浏览器预检不带 Authorization） |

OTP 传递方式：
- **Header**：`Authorization: OTP <otp>`（推荐，普通请求）
- **Query**：`?otp=<otp>`（媒体 `<video src>` / `<img src>` 必须用这个，因为不能加 header）

> `/info`、`/list`、`/file` 等文件管理路由**已移除**——那些属于 `capacitor-native-files`。

## WebSocket（实时双向二进制）

| 项 | 说明 |
| --- | --- |
| 端点 | `ws://127.0.0.1:<port>/ws`（`wsInfo.wsUrl`） |
| 鉴权 | **一次性 OTP**，通过 `Sec-WebSocket-Protocol: otp.<otp>` 传递（推荐，不进 URL）；也支持 `?otp=` |
| 支持 | 文本/二进制帧、分片重组、ping/pong、close；单帧上限 64MB |
| 默认模式 | **echo**（收到什么回什么）；`startWebSocket({ wsEcho: false })` 关闭，留给业务接入 |

```ts
const wsInfo = await DataChannel.startWebSocket();
const wsOtp = await DataChannel.createWsOtp();
const ws = new WebSocket(wsInfo.wsUrl, [`otp.${wsOtp.otp}`]);
ws.binaryType = 'arraybuffer';
ws.onopen = () => ws.send(frame);
ws.onmessage = (event) => { /* ArrayBuffer */ };
```

**推荐用法**（按实测）：

| 场景 | 推荐 | 说明 |
| --- | --- | --- |
| 音视频帧流 | ✅ WebSocket（每次一帧） | 64KB 帧 1.7ms，60fps 轻松 |
| 实时控制/信令 | ✅ WebSocket | 几乎零开销 |
| 中等数据块（< 10MB） | ✅ WebSocket（55 MB/s） | |
| 大文件（> 10MB） | ✅ **HTTP**（`/upload`） | 109 MB/s + 断点续传 |
| 超高频率微消息（< 1KB × 数万次/秒） | ⚠️ 不推荐 | 每帧约 1.7ms 的下限 |

## API

### startHttp(options?) → HttpInfo / stopHttp() → HttpInfo

| 参数 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- |
| `port` | `number` | `0` | 0 = 随机端口 |
| `rootDir` | `string` | 见「数据目录」 | 数据根目录 |
| `maxBodyBytes` | `number` | 512MB | 单请求上限 |
| `maxConnections` | `number` | 16 | 已认证连接并发上限（未认证固定 4） |

### startWebSocket(options?) → WsInfo / stopWebSocket() → WsInfo

| 参数 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- |
| `port` | `number` | `0` | 0 = 随机端口 |
| `wsEcho` | `boolean` | `true` | echo 模式 |
| `maxConnections` | `number` | 16 | 已认证连接并发上限（未认证固定 4） |

### getInfo() → { http: HttpInfo | null, webSocket: WsInfo | null }

### createHttpOtp(options) → HttpOtpInfo

`{ scope?: 'read' | 'write' | 'readwrite'（默认 read）, path: string（必填）, reusable?: boolean（默认 false） }`

返回 `{ otp, scope, path, reusable }`。**写 + reusable 会 reject**；`path` 含 `..` 会 reject。

### createWsOtp() → { otp, reusable: false }

### revokeOtp({ otp }) → { revoked: boolean }

### revokeAllOtps({ server? }) → { revoked: number }

`server` 可选 `'http' | 'ws'`；不传则两者都撤销。

### addListener('stateChanged', cb)

start/stop 时派发，载荷同 `getInfo()`。

### HttpInfo / WsInfo 字段

| HttpInfo | 说明 |
| --- | --- |
| `running` / `port` / `baseUrl` | 状态与地址 |
| `rootPath` | 数据根目录绝对路径 |
| `maxBodyBytes` / `maxConnections` | 上限 |
| `activeConnections` / `pendingConnections` | 已认证/未认证连接数 |
| `otpCount` | 当前有效 OTP 数 |
| `startedAt` | 启动时间戳 |

| WsInfo | 说明 |
| --- | --- |
| `running` / `port` / `wsUrl` | 状态与地址 |
| `wsEcho` / `maxConnections` | 配置 |
| `activeConnections` / `pendingConnections` / `clients` | 连接数 |
| `otpCount` | 当前有效 OTP 数 |
| `startedAt` | 启动时间戳 |

## 大文件传输最佳实践

**方向决定策略**（真机实测，App 进程 RSS 峰值增量）：

| 方式 | 105~110MB 文件 | 速度 | RSS 增量 |
| --- | --- | --- | --- |
| 分块 8MB×13 复用缓冲 + `append=1`（**首选**） | 920ms | 109 MB/s | +42.8MB |
| File 直传（用户选的磁盘文件原样转发） | 806ms | 131 MB/s | **+4.2MB** |
| 一次性 `Uint8Array(100MB)` | 912ms | 110 MB/s | +275.3MB |
| 整体生成后包装 `new File([buf])` | 698ms | 143 MB/s | 上传阶段 +0.6MB |

> **结论**：前端生成的数据用**分块 + 复用 buffer**（每块一个新 OTP）；用户选的文件可直接 `body: file`；
> 一次性传大 `Uint8Array` 内存峰值高（100MB 约 +275MB）。

**读**（用户选文件 → WebView）：用 `Blob.slice` 分块读，**不要** `file.arrayBuffer()` 一次读；
或 `<video>`/`<img>` 直接引用 URL（零 JS 内存）。

**下载媒体**：

```html
<video :src="`${http.baseUrl}/download?path=${path}&otp=${mediaOtp}`" @ended="revoke" />
```

## 安全设计

| 措施 | 说明 |
| --- | --- |
| **OTP（桥下发）** | 抓包看不到生成过程；一次性重放无效；reusable 用完 revoke |
| **只绑 127.0.0.1** | 外部设备无法访问 |
| **随机端口** | 降低被扫描命中的概率 |
| **Origin 白名单** | 只放行 `capacitor://localhost` / `https://localhost` 等；非法 Origin **静默断开** |
| **两级连接限流** | 未认证 4（防扫描/DoS）/ 已认证 16（可配） |
| **路径限制** | 只接受 rootDir 内的相对路径；拒绝 `..`、绝对路径、软链逃逸 |
| **OTP 校验失败 404** | 与未知路由不可区分，不泄露服务存在 |
| **日志** | 不记录 OTP/敏感信息 |

### 威胁模型（重要，请务必了解）

| 攻击者 | 能否攻破 | 说明 |
| --- | --- | --- |
| 普通网页（跨源） | ❌ 不能 | Origin 白名单挡住 |
| 同设备**非 root** App | ❌ 不能 | 抓不到 loopback 流量（VPN 也不覆盖 127.0.0.1），没有 OTP |
| 抓包后**重放** | ❌ 不能 | 一次性 OTP 已作废 |
| **root 设备 + 抓包/内存工具** | ⚠️ **能** | root 可 hook WebView/读内存拿到 OTP，甚至直接读沙盒文件 |
| **WebView 被注入/XSS** | ⚠️ **能** | 攻击者可直接调 `createHttpOtp`（等于 App 内被攻破） |

**本插件不防御 root 设备或 WebView 被攻破的场景**（这是客户端方案的边界：数据文件本身是明文，
root 可直接读）。**真正敏感的数据请放在服务端**，客户端只做传输/展示。

## 平台差异与注意事项

### Android

- 只绑 127.0.0.1；需要宿主 `network_security_config` 放行回环明文；
- 公共目录权限见「数据目录」；
- 后台：App 进后台 server 仍在，但进程可能被回收；持续后台传输需要前台服务（本插件不提供）。

### iOS

- `requiredInterfaceType = .loopback`（模拟器上跳过，改用连接来源校验）；
- 默认目录 `Documents/dataChannel`，只允许沙盒内路径；
- 后台：socket 会被挂起（约 30 秒窗口），**不要**指望后台大文件传输。

### 与 native-files / app-state 配合

```ts
// 选文件 → 权限 → 传输
const { files } = await NativeFiles.pickFiles({ multiple: false });
await NativeFiles.requestStorageAccess();          // 如需要公共目录
const copied = await NativeFiles.copyToAppStorage({ uri: files[0].uri });
const http = await DataChannel.startHttp();
const otp = await DataChannel.createHttpOtp({ scope: 'write', path: copied.relativePath });
await fetch(`${http.baseUrl}/upload?path=${copied.relativePath}`, {
  method: 'PUT',
  headers: { Authorization: `OTP ${otp.otp}` },
  body: fileBlob,
});

// 切后台撤销所有 OTP
const state = await AppState.addListener('stateChanged', (s) => {
  if (!s.isActive) DataChannel.revokeAllOtps();
});
```

## 实测记录（真机 Redmi K30S / Android 10）

| 项目 | 结果 |
| --- | --- |
| `startHttp` | running、端口随机、rootPath 正确、maxConnections 16、otpCount 0 |
| OTP 生成 | 43 字符 base64url（32 字节随机） |
| 上传（write OTP） | 200 ✓ |
| **重放写 OTP** | **404** ✓ |
| 下载（read OTP） | 200 ✓ |
| **重放读 OTP** | **404** ✓ |
| **OTP 路径绑定**（绑 A 请求 B） | **404** ✓ |
| **scope 校验**（read OTP 写） | **404** ✓ |
| **写 + reusable** | **reject** ✓ |
| 路径穿越 OTP | **reject** ✓ |
| 媒体 reusable ×2 | 200 / 200 ✓ |
| **revoke 后** | **404** ✓ |
| 无 OTP / 旧路由 | 404 / 404 / 404 ✓ |
| WebSocket（子协议 OTP） | echo 正常 ✓ |
| **WS 重放同一 OTP** | **连接被拒** ✓ |
| **连接限流**（6 个沉默连接） | `pending` 停在 **4** ✓ |
| `getInfo` 停止后 | `{ http: null, webSocket: null }` ✓ |
| HTTP / WS 独立启停 | 各停各的 ✓ |

## 开发

```bash
cd plugins/capacitor-data-channel
npm install
npm run build   # tsc → dist/esm
```

- Android：`android/src/main/java/com/testionic/datachannel/`
  - `OtpStore.java`（OTP + 两级限流）
  - `DataChannelServer.java`（HTTP）
  - `DataChannelWsServer.java`（WebSocket）
  - `DataChannelPlugin.java`（API）
- iOS：`ios/Sources/DataChannelPlugin/`（`OtpStore.swift` / `DataChannelHttpServer.swift` / `DataChannelWsServer.swift` / `DataChannelPlugin.swift`）
- 两端**协议与 OTP 规则完全一致**，改一端请同步另一端。
