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
>
> 📖 想先了解"这个插件是做什么的、适不适合我"？看 [插件介绍（INTRO.md）](INTRO.md)。
> 🧩 想要可复制的完整代码？看 [使用示例（EXAMPLES.md）](EXAMPLES.md)。

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

// 上传：先通过桥拿一次性 OTP（写）每次写都需要新 OTP
const w = await DataChannel.createHttpOtp({ scope: 'write', path: 'videos/clip.mp4' });
await fetch(`${http.baseUrl}/upload?path=videos/clip.mp4`, {
  method: 'PUT',
  headers: { Authorization: `OTP ${w.otp}` },
  body: fileBlob,
});

// 下载：一次性 OTP（读）默认每次读都需要 OTP  但为了方便 video audio 的场景 提供 reuseable 的长时间 OTP 来供播放使用
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

// ---------- WebSocket：实时双向（每条消息一个一次性 OTP）----------
import { encodeWsMessage, decodeWsMessage } from 'capacitor-data-channel';

const wsInfo = await DataChannel.startWebSocket();
const handshake = await DataChannel.createWsOtp(); // 仅用于握手
const ws = new WebSocket(wsInfo.wsUrl, [`otp.${handshake.otp}`]); // 子协议传 OTP（不进 URL）
ws.binaryType = 'arraybuffer';

// 发送：encodeWsMessage 内部 createWsOtp，拼成 [otp][0x00][payload]
ws.send(await encodeWsMessage(new Uint8Array([1, 2, 3])));

// 接收：decodeWsMessage 内部 validateAndConsumeOTP；ok=false 必须丢弃
ws.onmessage = async (event) => {
  const { ok, payload, error } = await decodeWsMessage(event.data);
  if (!ok) {
    console.warn('消息被篡改/重放，已丢弃:', error);
    return;
  }
  // payload 是原始二进制（ArrayBuffer）
};

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
| **OTP 长度** | **固定 43 字符**（32 字节随机 → base64url 无填充）；协议本身不依赖长度（用 `0x00` 分隔） |
| WS OTP | **只支持一次性**；**握手一个 + 每条消息各一个**（见「WebSocket 消息协议」） |
| 校验失败 | HTTP 统一返回 **404**（不泄露"需要 OTP"）；WS 回一条**错误消息**（`[0x00][错误文本]`） |

## 批量 OTP（高频小包优化）

**背景**：Capacitor 的插件调用在两端都是**串行**的（Android 单 `HandlerThread("CapacitorPlugins")`；
iOS 串行 `DispatchQueue(label: "bridge")`），并且每个调用响应都会回到**主线程**执行一次 JS。
所以"每条消息一次 `createWsOtp`"会把 ~2ms 的往返延迟逐条累加。

**批量 API** 把 N 次调用压成 1 次：

```ts
import { createWsOtpPool, encodeWsMessageWithOtp, decodeWsMessagesBatch } from 'capacitor-data-channel';

const pool = await createWsOtpPool(64);          // 一次桥调用拿 64 个
ws.send(encodeWsMessageWithOtp(pool.take(), frame));  // 同步拼包，无桥往返

// 接收侧批量校验（先验证、后交付）
const results = await decodeWsMessagesBatch(bufferedMessages);
for (const r of results) if (r.ok) handle(r.payload);
```

| API | 说明 |
| --- | --- |
| `createWsOtps({ count })` | 一次生成 N 个 WS OTP（1..256） |
| `createHttpOtps({ count, scope, path, reusable })` | 一次生成 N 个 HTTP OTP |
| `validateAndConsumeOtps({ otps })` → `{ results }` | 批量验证并消费（返回即已全部验证） |
| helper `createWsOtpPool(n)` / `encodeWsMessageWithOtp(otp, payload)` | 发送侧：池 + 同步拼包 |
| helper `decodeWsMessagesBatch(messages)` | 接收侧：批量验证后交付 |

**实测收益**（真机，300KB × 500 顺序往返）：**15.89 ms/条 → 10.33 ms/条**（-35%），吞吐 63 → 97 条/s。

⚠️ 批量 OTP 仍是**一次性**：每个 OTP 在 Server 校验（或被 `validateAndConsumeOtps` 消费）后失效；
池用尽要重新 `createWsOtps`。批量创建时"活跃 OTP + 请求数"不得超过 256。

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

## HTTP 路由

| 方法 | 路径 | 鉴权 | 说明 |
| --- | --- | --- | --- |
| GET | `/health` | **免 OTP** | 连通性自检（无敏感信息） |
| PUT | `/upload?path=X` | write OTP | **单次上传**（写 `.part` → 校验 → 原子 rename） |
| PUT | `/upload?path=X&offset=N&total=T` | write OTP | **分块上传**：校验 `.part` 长度 == N 后追加；返回 `{partSize}` |
| POST | `/upload?path=X&finalize=1&total=T[&sha256=..]` | write OTP | **落地**：校验总长/哈希后 `rename(.part → X)`，失败不落地 |
| GET | `/upload?path=X&status=1` | write OTP | **查询进度**：`{partExists, partSize, exists, size}`（断点续传用） |
| GET | `/download?path=X` | read OTP | 流式下载；支持 `Range` → **206**（视频拖动必需） |
| OPTIONS | 任意 | **免 OTP** | CORS 预检（浏览器预检不带 Authorization） |

OTP 传递方式：
- **Header**：`Authorization: OTP <otp>`（推荐，普通请求）
- **Query**：`?otp=<otp>`（媒体 `<video src>` / `<img src>` / `GET /upload?status=1` 这种不能加 header 的场景）

错误体统一为 `{"error": "...", "code": "E_...", "size"?: N}`；完整错误码表见
[EXAMPLES.md 第 6 节](EXAMPLES.md#6-错误码与处理建议)。

> `/info`、`/list`、`/file` 等文件管理路由**已移除**——那些属于 `capacitor-native-files`。

### 断点续传上传（offset + finalize）

**为什么这样设计**：分块上传必然可能中断（断网、切后台、进程被杀）。如果直接 append 到目标文件，
"块传一半"会静默污染文件。因此：

- 所有分块先写 `X.part`，**目标文件只在 finalize 时原子出现**；
- 每次 append 前校验 `offset == .part` 实际长度 → 错位会被 409 挡住，不会拼错；
- finalize 校验 `total`（可选 `sha256`）后才 rename。

```
PUT  /upload?path=X&offset=0&total=T           # 第 1 块（新建/截断 .part）
PUT  /upload?path=X&offset=<n>&total=T         # 后续块（校验 .part 长度 == n）
GET  /upload?path=X&status=1                   # 断点续传：查询已有 partSize
POST /upload?path=X&finalize=1&total=T&sha256=…# 校验后原子落地
```

| 情况 | 行为 |
| --- | --- |
| 中途断开 | `.part` 保留已写字节；**目标文件不变**；用 `status` 续传 |
| offset 不匹配 | **409** `E_OFFSET_MISMATCH` + `size`（服务端实际字节数） |
| 没有 `.part` 却 offset > 0 | **409** `E_NO_PART`（从 0 重来） |
| finalize 总长不符 | **409** `E_SIZE_MISMATCH`，`.part` 保留 |
| finalize sha256 不符 | **409** `E_HASH_MISMATCH`，`.part` 保留 |
| 旧的 `append=1` | **400** `E_APPEND_DEPRECATED`（已废弃，避免误用） |

完整代码见 [EXAMPLES.md 1.3 断点续传](EXAMPLES.md#13-断点续传完整流程含-sha256-校验)。

## WebSocket（实时双向二进制）

| 项 | 说明 |
| --- | --- |
| 端点 | `ws://127.0.0.1:<port>/ws`（`wsInfo.wsUrl`） |
| 握手鉴权 | **一次性 OTP**，通过 `Sec-WebSocket-Protocol: otp.<otp>` 传递（不进 URL）；也支持 `?otp=` |
| 消息鉴权 | **每条消息一个一次性 OTP**（见下「消息协议」） |
| 支持 | 文本/二进制帧、分片重组、ping/pong、close；单帧上限 64MB |
| 默认模式 | **echo**（校验通过后原样回发）；`startWebSocket({ wsEcho: false })` 关闭，留给业务接入 |

### WebSocket 消息协议（必须遵守）

**WebSocket 消息本身没有"头"**（只有握手是 HTTP），所以 OTP 必须放在消息体里：

```
正常消息（双向）：[OTP 字符串(43 字符 ASCII)][0x00][payload 原始二进制]
错误消息（Server→Client）：[0x00][错误文本 UTF-8]
```

| 规则 | 说明 |
| --- | --- |
| 分隔符 | **第一个 `0x00`**。OTP 是 base64url（`A-Za-z0-9_-`），永不含 `0x00`；payload 可以包含 `0x00`（不会误判） |
| Client → Server | 每条消息前先 `createWsOtp()` 拿新 OTP 拼进消息；Server 校验失败 → **丢弃并回错误消息** |
| Server → Client | Server 每条消息生成新的一次性 nonce 放进消息；JS 必须调 `validateAndConsumeOTP` 验证并消费 |
| 错误消息 | 首字节 `0x00`（OTP 为空），与正常消息天然区分；JS 收到后返回错误，**不得使用 payload** |
| nonce 堆积 | 插件不清理（由 JS 保证"每条都验证"）；表满 256 时回错误消息 `too many pending nonces` |

> **为什么 Server→Client 用"反向验证"**：如果 Server 通过桥"提前通知" OTP，桥事件和 WS 消息是两条
> 通道，**顺序无法保证**（竞态）。改成"Server 先记录 nonce → JS 收到消息后反向验证"就完全避免竞态。

### 使用 helper（推荐）

```ts
import { DataChannel, encodeWsMessage, decodeWsMessage } from 'capacitor-data-channel';

const wsInfo = await DataChannel.startWebSocket();
const handshake = await DataChannel.createWsOtp();
const ws = new WebSocket(wsInfo.wsUrl, [`otp.${handshake.otp}`]);
ws.binaryType = 'arraybuffer';

// 发送（内部 createWsOtp + 拼协议）
ws.send(await encodeWsMessage(frame));

// 接收（内部 validateAndConsumeOTP；ok=false 必须丢弃）
ws.onmessage = async (event) => {
  const { ok, payload, error } = await decodeWsMessage(event.data);
  if (!ok) {
    console.warn('discarded:', error);
    return;
  }
  handleFrame(new Uint8Array(payload));
};
```

**性能**：每条消息一次桥调用（~1ms）——这是安全优先的代价；60fps 下每帧 16ms，占比约 6%。

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

### getHttpInfo() → HttpInfo

### getWsInfo() → WsInfo

（两个 server 独立，状态分开读取；停止时也返回结构体，`running: false`）

### createHttpOtp(options) → HttpOtpInfo

`{ scope?: 'read' | 'write' | 'readwrite'（默认 read）, path: string（必填）, reusable?: boolean（默认 false） }`

返回 `{ otp, scope, path, reusable }`。**写 + reusable 会 reject**；`path` 含 `..` 会 reject。

### createWsOtp() → { otp, reusable: false }

用于 **握手** 和 **每条 Client → Server 消息**（消息前缀 `[otp][0x00][payload]`）。只支持一次性。

### validateAndConsumeOTP({ otp }) → { valid: boolean }

验证并消费一个 OTP（用于 WebView 校验 **Server → Client** 的 WS 消息）：
- `valid: true` → 消息合法（nonce 已消费，不可重放）；
- `valid: false` → 消息被篡改/伪造/重放，**必须丢弃**。

同时检查 HTTP 与 WS 的 OTP 表。

### revokeOtp({ otp }) → { revoked: boolean }

### revokeAllOtps({ server? }) → { revoked: number }

`server` 可选 `'http' | 'ws'`；不传则两者都撤销。

### addListener('stateChanged', cb)

start/stop 时派发，载荷为 `{ http: HttpInfo | null, webSocket: WsInfo | null }`。

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
| 分块 8MB×13 复用缓冲 + `offset`/`finalize`（**首选**） | 920ms | 109 MB/s | +42.8MB |
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
| **OTP（桥下发）** | 抓包看不到生成过程；**HTTP 每请求一个**、**WS 每条消息一个**（握手另算）；一次性重放无效；reusable 用完 revoke |
| **WS 双向校验** | Client→Server 由 Server 校验；Server→Client 由 JS 调 `validateAndConsumeOTP` 反向验证（避免桥/WS 通道竞态） |
| **只绑 127.0.0.1** | 外部设备无法访问 |
| **随机端口** | 降低被扫描命中的概率 |
| **Origin 白名单** | 只放行 `capacitor://localhost` / `https://localhost` 等；非法 Origin **静默断开** |
| **两级连接限流** | 未认证 4（防扫描/DoS）/ 已认证 16（可配） |
| **路径限制** | 只接受 rootDir 内的相对路径；拒绝 `..`、绝对路径、软链逃逸 |
| **校验失败行为** | HTTP 统一 404（与未知路由不可区分）；WS 回错误消息（`[0x00][错误文本]`），payload 丢弃 |
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

## 安全讨论与最终结论

这一节记录**为什么这样设计**，以及**我们明确不做什么**——避免以后重复纠结，也避免误以为它比实际更安全。

### 1. 为什么从"长期 token"改成 OTP

最初的实现是：`start()` 返回一个 32 字节 token，之后所有请求带 `Authorization: Bearer <token>`。
问题出在两处：

- **Origin 白名单区分不了"别的 Capacitor App"**（它们同样是 `capacitor://localhost` / `https://localhost`）；
- **token 一旦泄露就是长期全权限**：它能出现在 URL（`<video src>`、分享、截图、WebView 历史、日志）里，
  抓包也只是一次就够。

OTP 方案把"一次性、桥下发（不经过网络）、可撤销、可绑定路径"引入进来，解决的是**凭证泄露的时间窗**。

### 2. OTP 和 token 到底差多少（诚实版）

**在"阻止其它 App 访问"这件事上，两者是等价的**——都是"猜不到的秘密"，没有凭证就 404。
OTP 真正多出来的只有两点：

| 多出来的能力 | 现实价值 |
| --- | --- |
| 抓到的是**已作废**的凭证，无法重放 | 只对"能抓包但读不到内存"的攻击者有效，而这类人几乎不存在 |
| **URL 里的凭证可撤销、只读、绑定路径** | ✅ 这是最实在的收益（媒体 URL 泄露是高频场景） |

也就是说：**OTP 不是"更安全的密码"，而是"爆炸半径更小的凭证"**。

### 3. 为什么保留"WS 每条消息一个 OTP"（明知道它慢）

实测代价：每条消息多 ~2ms 桥调用（1KB 消息里 86% 的时间在等桥；300KB 约 26%）。
我们仍然保留它，理由和取舍是明确的：

- 用户的选择是"**要么不做（回到 token），做就尽量把门槛拔高**"；
- 它确实封住了"握手 OTP 泄露 → 攻击者长期占用连接"这条路；
- 代价可以用**批量 OTP** 缓解（300KB 往返 15.89 → 10.33ms/条）；
- 但必须承认：**它防不住 root 读内存、防不住解包改代码**。

### 4. 为什么**不做**传输加密（MITM 讨论的结论）

用户曾提出：中间人可以不管 OTP，直接拦截并篡改数据 → 加密传输。我们分析后**决定不做**：

**（1）谁能对 `127.0.0.1` 做 MITM？**

| 攻击者 | 能改/嗅探 loopback？ | 说明 |
| --- | --- | --- |
| 普通 App（无 root） | ❌ | 没有 raw socket 权限 |
| 普通 VPN App（无 root） | ❌ | `tun` 只接管走路由表的流量，**环回不走路由表** |
| 抢端口 | ❌ | `port=0` 由内核分配，无法预知/抢占 |
| **root 工具**（iptables / tcpdump on lo / 抓包 App） | ✅ | 需要 root |

**（2）root 攻击者能读进程内存**：Frida hook WebView、`/proc/<pid>/mem`、dump JS 堆 → 直接拿到密钥/明文。
加密只对"能改包、但不能读内存"的攻击者有意义，而这一类在现实里几乎不存在。

**（3）XOR 不是加密**：`C = P ⊕ K`；43 字符 OTP（≈258 bit 熵）覆盖不了 1MB payload（8M bit）→
密钥流必须扩展/重复 → known-plaintext 直接恢复；而且**没有完整性校验**，bit-flipping 可定向篡改
（恰好没防住"篡改"）。真要做必须用 ChaCha20-Poly1305 之类的 AEAD，成本和性能开销都不小。

**（4）还有更现实的篡改面**：数据**落地之后**。写到公共目录（`/sdcard/Download`）的文件，
任何有存储权限的 App 都能改——这比改传输中的包容易得多，加密也防不了。

**（5）最根本的边界**：本插件是 Capacitor 应用的一部分，**攻击者可以解包、改代码、重新打包**。
任何客户端侧的校验/加密都会连同代码一起被改掉。

> 结论：**传输加密的收益 ≈ 0，成本不低**，所以不做。真正该做的是"敏感数据放服务端 + 服务端鉴权"。

### 5. 我们承诺什么 / 不承诺什么

| 我们**做到**了 | 我们**做不到** |
| --- | --- |
| 只有本 App 的 WebView 能拿到凭证（桥不跨 App） | 防 root 设备（可读内存/沙盒） |
| 一次性凭证不可重放（HTTP 请求 / WS 消息 / 握手） | 防解包改代码后重新分发的版本 |
| 凭证可撤销、可绑定路径、媒体只读 | 防 WebView 被注入/XSS |
| 跨源网页被 Origin 白名单挡掉 | 防"落地后被改"（公共目录） |
| 扫描/DoS 被两级连接限流挡住 | 提供传输机密性（明文） |

### 6. 想真正提高门槛，优先做这些（而不是加密）

1. **敏感数据放服务端**，客户端只做展示/传输；
2. 默认 rootDir 用 **app 私有目录**（`Android/data/<pkg>/...`，Android 11+ 其它 App 无法访问），
   不要把敏感文件放公共目录；
3. 关键文件做 **checksum / 签名校验**（防落地后被改）；
4. 媒体用完 **`revokeOtp`**，切后台 **`revokeAllOtps()`**；
5. 发布侧：Play Integrity / App Attest 抬高重打包成本（但不是安全边界）。

## 平台差异与注意事项

### Android

- 只绑 127.0.0.1；需要宿主 `network_security_config` 放行回环明文；
- 公共目录权限见「数据目录」；
- 后台：App 进后台 server 仍在，但进程可能被回收；持续后台传输需要前台服务（本插件不提供）。

### iOS

- `requiredInterfaceType = .loopback`（模拟器上跳过，改用连接来源校验）；
- 默认目录 `Documents/dataChannel`，只允许沙盒内路径；
- 后台：socket 会被挂起（约 30 秒窗口），**不要**指望后台大文件传输。

### 与 native-files / foreground-state 配合

```ts
// 选文件 →（可选）权限 → 复制进 data-channel 根目录 → 高速传输
const { files } = await NativeFiles.pickFiles({ multiple: false });

// 需要读写公共目录（Android 11+）时先申请"所有文件访问权限"
await NativeFiles.requestAllFilesAccess();

// 复制到 data-channel 的默认根目录（免权限），返回相对路径
const copied = await NativeFiles.copyToAppStorage({ path: files[0].path });

const http = await DataChannel.startHttp();
const otp = await DataChannel.createHttpOtp({ scope: 'write', path: copied.relativePath });
await fetch(`${http.baseUrl}/upload?path=${copied.relativePath}`, {
  method: 'PUT',
  headers: { Authorization: `OTP ${otp.otp}` },
  body: fileBlob,
});

// 切后台撤销所有 OTP
const state = await ForegroundState.addListener('stateChanged', (s) => {
  if (!s.isActive) DataChannel.revokeAllOtps();
});
```

> native-files 从 0.2.0 起全部用**真实绝对路径**（`path`），不再有 `uri` 参数。

## Capacitor 的调用调度模型（为什么批量 OTP 有效）

这是理解本插件性能与"批量 OTP"必要性的前提，结论来自**读源码 + 真机实测**：

| 平台 | 插件调用调度 | 响应回程 |
| --- | --- | --- |
| **Android** | `Bridge` 里 `HandlerThread("CapacitorPlugins")`，`taskHandler.post()` → **所有插件调用串行在单线程** | `webView.post(() -> evaluateJavascript(...))` → **主线程** |
| **iOS** | `CapacitorBridge.dispatchQueue = DispatchQueue(label: "bridge")`（串行队列）→ **串行** | `DispatchQueue.main.async { evaluateJavaScript }` → **主线程** |

**实测**（真机，`createWsOtp` 各 100 次）：

| 调用方式 | 总耗时 | 每次 |
| --- | --- | --- |
| 顺序 `await` | 198ms | **1.98ms**（单次往返延迟） |
| 并发 `Promise.all` | 57ms | **0.57ms**（原生吞吐） |
| 加速比 | **3.44×** | — |

**结论**：

1. JS 侧"并发调用原生"= 原生侧**排队串行执行**；并发只是把**往返延迟流水化**，不会让原生并行；
2. 每个调用的结果都要**回主线程执行一次 JS** → 高频调用会同时压插件线程和主线程（实测 400 次
   OTP 调用期间 rAF 从 79 掉到 57 fps）；
3. 所以**减少调用次数**（批量 OTP / 一次桥调用多件事）比"并发调用"更有效；
4. 也解释了为什么高频小包场景下逐条 OTP 会明显变慢。

## 性能实测（真机 Redmi K30S / Android 10）

CPU 用 `/proc/<pid>/stat` 精确采样（10ms 时间片），先扣 idle 基线；PSS 取 `dumpsys meminfo` 峰值。

| 场景 | 吞吐 / 延迟 | App 进程 CPU | 峰值 PSS |
| --- | --- | --- | --- |
| HTTP 上传 100MB | **93 MB/s** | — | +46MB |
| HTTP 下载 100MB | **322~333 MB/s** | — | — |
| WS 1KB 往返（逐条 OTP） | 4.76 ms/条（210 条/s） | — | — |
| WS 300KB 往返（逐条 OTP） | 15.89 ms/条（63 条/s） | 31.8 CPU·s / 32.2s | +12MB |
| WS 300KB 往返（**批量 OTP 池**） | **10.33 ms/条（97 条/s）** | 6.4 CPU·s / 5.5s | — |
| WS 1.5MB 往返 | 37.4 ms/条（40 MB/s） | 4.96 CPU·s / 4.8s | +21MB |
| WS 300KB 流水线（窗口 16） | 49.3 MB/s（168 条/s） | 10.2 CPU·s / 5.4s | +25MB |
| OTP 桥调用 | create 1.98ms / validate 2.12ms | — | — |
| idle | — | 0.58 CPU·s / 8.25s（7%） | 109.6MB |

**为什么是这样**：

- Capacitor 插件调用**串行**（见上节），单次 await ≈ 2ms 往返；并发/批量时原生吞吐 ≈ **0.57ms/次**；
- 每条消息两次 OTP 调用 → 小包（1KB）有 **86%** 的时间在等桥；300KB 约 26%；1.5MB 约 11%；
- HTTP 大文件只受 1 次 OTP 影响（≈0.2%），基本无感；
- 高频调用会让插件线程 + 主线程持续繁忙（实测 400 次 OTP 调用期间 rAF 从 79 → 57 fps），
  所以**持续高频**场景建议用批量 OTP，避免长时间压满。

**模拟器**（Pixel_6 / Android 16 / WHPX 4 核 + 软件 GPU）：OTP 桥往返 18.2ms、1KB 18.8ms/条、
300KB 43.0ms/条、HTTP 上传 39~41 MB/s —— 整体慢 2.4~4.6×，**不代表真机**。

**电池**：充电状态下 `dumpsys battery` 与 batterystats 的放电数据不可用（MIUI 在 `--reset` 后
没有 "Estimated power use" 段），所以本轮用 **CPU·秒** 作为能耗代理（能耗的物理来源）。
需要真实 mAh 时请拔掉 USB、锁屏、跑 5 分钟循环后读 batterystats 的放电步进。

### 性能建议（怎么用才快）

| 场景 | 建议 |
| --- | --- |
| 大文件（> 10MB） | 走 **HTTP**（上传 93 MB/s、下载 322 MB/s；内存峰值 +46MB 且会回落） |
| 前端生成的数据上传 | **分块 + 复用 buffer**（8MB/块，`offset` + `finalize`），不要一次性 `Uint8Array(100MB)` |
| 用户选的磁盘文件 | 直接 `body: file`（RSS 增量最小，实测 +4.2MB） |
| 高频小消息（WS） | **批量 OTP 池**：`createWsOtpPool(64)` + `encodeWsMessageWithOtp`（-35% 延迟） |
| 接收侧高频 | `decodeWsMessagesBatch`（先验证后交付）或单条 `decodeWsMessage` |
| 视频/音频播放 | HTTP + **可复用只读 OTP** + 播放结束 `revokeOtp`（不要用 WS 传媒体流） |
| 避免 | 每条消息都 `createWsOtp()`；高频 `getHttpInfo/getWsInfo` 轮询；长时间压满插件线程 |

> 注意：App 进后台后进程可能被系统回收（实测压测期间被 LMK 杀过一次），长时间后台传输需要前台服务。

## 实测记录（真机 Redmi K30S / Android 10）

| 项目 | 结果 |
| --- | --- |
| `startHttp` / `getHttpInfo` | running、端口随机、rootPath 正确、maxConnections 16、otpCount 0 |
| `getWsInfo`（未启动） | `running: false`、`wsUrl: ""` |
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
| WebSocket（握手 OTP） | 连接正常 ✓ |
| **WS 握手重放同一 OTP** | **连接被拒** ✓ |
| WS 消息（`[otp][0x00][payload]`） | echo 正常、payload 中的 `0x00` 保留 ✓ |
| **WS 消息无 OTP** | **回错误消息**（`missing OTP separator`）✓ |
| **WS 消息重放（同一 OTP 两次）** | 第 1 次 合法 / 第 2 次 **错误消息** ✓ |
| **`validateAndConsumeOTP`** | 首次 `true` / 再次 `false` ✓ |
| **批量 OTP**（`createWsOtps({count:8})`） | 8 个互不重复 ✓ |
| **批量拼包 + `decodeWsMessagesBatch`** | 全部 `ok` ✓ |
| **批量 nonce 再验证** | 全部 `false`（已消费）✓ |
| **`createHttpOtps({count:3})`** | 3 个，第一个下载 200 ✓ |
| **断点续传 offset** | 第 1/2 块 200；**offset 错位 → 409 `E_OFFSET_MISMATCH` + size** ✓ |
| **续传 status** | `partSize` 与服务端一致（200KB）✓ |
| **finalize 总长不符** | **409 `E_SIZE_MISMATCH`**，目标文件不存在（404）✓ |
| **finalize + sha256 正确** | 200，落地 300KB，内容 8/8/9 三段校验通过 ✓ |
| **finalize sha256 错误** | **409 `E_HASH_MISMATCH`** ✓ |
| **旧的 `append=1`** | **400 `E_APPEND_DEPRECATED`** ✓ |
| **中断上传**（XHR abort） | 目标文件不存在（404），`.part` 可续传 ✓ |
| **手工续传**（20MB：8+8 → status 16MB → 续 4MB → finalize） | 324ms，下载 20MB ✓ |
| **连接限流**（6 个沉默连接） | `pending` 停在 **4** ✓ |
| `stopHttp` / `stopWebSocket` 后 | 各自 `running: false` ✓ |
| HTTP / WS 独立启停 | 各停各的 ✓ |

> 真机 autotest 全绿（`ok: true`，含以上全部断言）。

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
