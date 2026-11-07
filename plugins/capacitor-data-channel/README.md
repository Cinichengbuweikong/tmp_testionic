# capacitor-data-channel

原生 ↔ WebView **二进制数据通道**：在原生侧启动一个**只监听 127.0.0.1 的 HTTP + WebSocket Server**，
让 WebView 用标准 `fetch` / `<video>` / `<img>` / `new WebSocket` 直接收发**原始二进制**，
绕开 Capacitor 桥的 JSON/base64 序列化，从而**高效传输大体积数据**（视频、音频、图片、任意文件、实时数据流）。

> 为什么需要它：Capacitor 的桥是 JSON 序列化，复杂类型必须 base64（官方文档原话：
> "FormData, Blob, ArrayBuffer ... only directly supported on web ... you should serialize the data
> to base64"）。10MB 视频走桥 = 13.3MB base64 字符串 + 两轮编解码。
> 本插件实测 100MB 上传 **0.92 秒（≈109 MB/s）**、下载 **0.35 秒（≈285 MB/s）**；
> WebSocket 10MB 往返 **0.18 秒（55 MB/s）**、64KB 帧每帧 **1.7ms（588 fps）**。

两部分能力：

| 能力 | 端点 | 适合场景 |
| --- | --- | --- |
| **HTTP**（大文件） | `/upload` `/download`（Range） | 上传/下载 10MB~数 GB 的文件、视频拖动、断点续传 |
| **WebSocket**（实时流） | `/ws`（双向二进制帧） | 音视频帧流、实时 buffer、控制信令 |

## 快速开始

```ts
import { DataChannel } from 'capacitor-data-channel';

const ch = await DataChannel.start();
// ch = { running, port, baseUrl, wsUrl, token, rootPath, maxBodyBytes, startedAt, wsClients, webSocketEnabled }

// ---- HTTP：大文件 ----
// 上传（原始二进制）
await fetch(`${ch.baseUrl}/upload?path=videos/clip.mp4`, {
  method: 'PUT',
  headers: { Authorization: `Bearer ${ch.token}` },
  body: fileOrBlob,
});

// 下载
const res = await fetch(`${ch.baseUrl}/download?path=videos/clip.mp4&token=${ch.token}`);
const blob = await res.blob();

// 或者直接给 <video> 用（query token，支持 Range 拖动进度条）
video.src = `${ch.baseUrl}/download?path=videos/clip.mp4&token=${ch.token}`;

// ---- WebSocket：实时双向二进制 ----
const ws = new WebSocket(ch.wsUrl);
ws.binaryType = 'arraybuffer';
ws.onopen = () => ws.send(new Uint8Array([1, 2, 3, 4]));  // 发二进制帧
ws.onmessage = (event) => {
  const bytes = new Uint8Array(event.data);                // 收二进制帧
  console.log('received', bytes.byteLength, 'bytes');
};

await DataChannel.stop();
```

## 数据目录（rootDir）

`start({ rootDir })` 支持三种写法：

| 写法 | 示例 | 说明 |
| --- | --- | --- |
| **不传**（默认） | — | Android：`/sdcard/Android/data/<包名>/files/dataChannel`（App 专属外部目录，**无需权限**）<br>iOS：`Documents/dataChannel`（App 私有沙盒目录） |
| **相对路径** | `myData` | 相对 App 私有目录 |
| **绝对路径** | `/sdcard/Download/myVideos` | 任意位置（**权限取决于平台，见下**） |

**平台限制（经真机实测修正）**：

| 平台/版本 | 公共目录（如 `/sdcard/Download`） | 需要什么 |
| --- | --- | --- |
| Android ≤ 9 | ✅ 可读写 | `WRITE_EXTERNAL_STORAGE` |
| **Android 10（API 29）** | ✅ **可读写（实测通过）** | `WRITE_EXTERNAL_STORAGE` + **`requestLegacyExternalStorage="true"`**（宿主 manifest） |
| **Android 11+（API 30+）** | ⚠️ 受限 | **`MANAGE_EXTERNAL_STORAGE`**（所有文件访问权限） |
| iOS | 🚫 不存在"公共目录" | 只能访问 App 沙盒内（Documents / Application Support / Caches / tmp） |

- **App 专属目录（默认）在任何版本都无需权限**，是最稳的选择；
- 公共目录的权限**由 `capacitor-native-files` 负责查询/申请**（本插件不再提供权限 API）；
  授权必须走**系统权限弹窗**（用户手动点"允许"），系统在授权时才会把 App 的存储挂载切到可写视图
  （`adb shell pm grant` 这类命令行授权**不会触发 remount**，会导致仍然 `EACCES`）；
- 无权限时 `start()` 会返回明确错误（`cannot create rootDir: ... Hint: ...grant it via capacitor-native-files`）。

## ⚠️ 安装前必做：宿主应用配置（一次性）

页面（`https://localhost` / `capacitor://localhost`）访问 `http://127.0.0.1` 需要宿主放行明文回环，
**不配置会被系统直接拦截**。

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

2. 在 `AndroidManifest.xml` 的 `<application>` 上加：

```xml
android:networkSecurityConfig="@xml/network_security_config"
```

> 只对回环地址放行明文，公网仍是禁止的。一个 App 只能有一份 `networkSecurityConfig`，
> 如果已有请把上面的 `domain-config` 合并进去。

### iOS

在 `Info.plist` 加（允许本地网络明文，不放开公网）：

```xml
<key>NSAppTransportSecurity</key>
<dict>
    <key>NSAllowsLocalNetworking</key>
    <true/>
</dict>
```

## HTTP 路由

**只有三个路由**（本插件只负责传输，不做文件管理）：

| 方法 | 路径 | 鉴权 | 说明 |
| --- | --- | --- | --- |
| GET | `/health` | 否 | 连通性自检，返回 `{"ok":true,"name":"capacitor-data-channel"}` |
| PUT / POST | `/upload?path=<相对路径>&append=0\|1` | 是 | 原始二进制上传（流式写盘）；`append=1` 追加（分块/续传）→ `{path,bytes,size,appended}` |
| GET | `/download?path=<相对路径>[&token=<t>]` | 是 | 流式下载；支持 `Range: bytes=start-end` → **206 + Content-Range**（视频拖动必需） |
| OPTIONS | 任意 | 否 | CORS 预检 → 204 |

> `/info`、`/list`、`/file`（删除）等**文件管理路由已移除**——这些属于
> `capacitor-native-files`（目录列表、删除、重命名等）。本插件只做"把字节高速送进/送出"。

鉴权两种等价写法：请求头 `Authorization: Bearer <token>`，或 query `?token=<token>`
（后者给 `<video src>` / `<img src>` 这类不能自定义头的场景）。

## WebSocket（实时双向二进制）

同一个端口提供 `/ws` 升级端点（RFC 6455 子集）：WebView 用原生 `new WebSocket(wsUrl)` 即可与
native 实时互发**二进制帧**（ArrayBuffer），同样不经过 JSON/base64 桥。

| 项 | 说明 |
| --- | --- |
| 端点 | `ws://127.0.0.1:<port>/ws?token=<token>`（`channel.wsUrl` 已带好 token） |
| 鉴权 | token 校验同 HTTP（query 或握手头） |
| 支持 | 文本/二进制帧、分片重组、ping/pong、close；单帧上限 64MB |
| 默认模式 | **echo**（收到什么回什么），便于自测；`start({ wsEcho: false })` 可关闭，留给后续业务接入 |

```ts
const ch = await DataChannel.start();
const ws = new WebSocket(ch.wsUrl);
ws.binaryType = 'arraybuffer';

ws.onopen = () => {
  // 发送视频帧 / 音频帧 / 任意二进制
  ws.send(frameBuffer);
};

ws.onmessage = (event) => {
  const bytes = new Uint8Array(event.data);
  // 处理收到的二进制
};

// 关闭
ws.close();
```

**性能实测**（真机 Redmi K30S / MIUI 12）：

| 场景 | 结果 |
| --- | --- |
| 10MB 单块往返 | **181ms（55 MB/s）** |
| 64KB 帧 ping-pong ×200 | **每帧 1.7ms、588 fps**（约 37 MB/s） |
| WS 空闲 CPU | **0%**（10 秒 0 jiffies） |
| 内存 | 不做持久缓冲（即收即发），峰值 ≈ 单条消息大小 |

**推荐用法**（按实测数据）：

| 场景 | 推荐 | 说明 |
| --- | --- | --- |
| **音视频帧流** | ✅ WebSocket，每次一帧（音频 20~100KB / 视频 100KB~2MB） | 60fps 轻松（单帧 1.7ms） |
| **实时控制/信令** | ✅ WebSocket，小消息低频 | 几乎零开销 |
| **中等数据块**（< 10MB） | ✅ WebSocket | 55 MB/s |
| **大文件（> 10MB）** | ✅ **HTTP**（`/upload`） | 109 MB/s，且支持断点续传 |
| **超大缓冲一次性发送** | ⚠️ 不推荐 | 内存峰值 = 消息大小，且单帧上限 64MB |
| **超高频率微消息**（< 1KB × 数万次/秒） | ⚠️ 不推荐 | 每帧约 1.7ms 的下限，会产生背压 |

## API

### start(options?) → ChannelInfo

启动本地 HTTP + WebSocket Server（已启动则直接返回现有信息）。

| 参数 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- |
| `port` | `number` | `0` | `0` = 系统随机分配（推荐） |
| `rootDir` | `string` | 见「数据目录」 | 数据根目录：不传 = App 专属外部目录；相对路径 = App 私有目录；绝对路径 = 任意位置（受平台限制） |
| `maxBodyBytes` | `number` | 512MB | HTTP 单请求最大字节数，超过返回 413 |
| `wsEcho` | `boolean` | `true` | WebSocket 是否 echo（收到即回发）；`false` 时丢弃消息，留给业务接入 |

### stop() → ChannelInfo / getInfo() → ChannelInfo

停止 / 读取状态（字段见下）。

### addListener('channelStateChanged', cb)

start/stop 时派发，载荷同 `ChannelInfo`。

### ChannelInfo 字段

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| `running` | `boolean` | server 是否在运行 |
| `port` | `number` | 实际监听端口（随机分配） |
| `baseUrl` | `string` | `http://127.0.0.1:<port>`（停止时为空） |
| `wsUrl` | `string` | `ws://127.0.0.1:<port>/ws?token=...`（停止时为空） |
| `token` | `string` | Bearer token（每次启动重新生成；停止时为空） |
| `rootPath` | `string` | 数据根目录绝对路径 |
| `maxBodyBytes` | `number` | HTTP 单请求上限 |
| `startedAt` | `number` | 启动时间戳（毫秒），未运行为 0 |
| `activeConnections` | `number` | 当前活动 HTTP 连接数 |
| `wsClients` | `number` | 当前 WebSocket 客户端数 |
| `webSocketEnabled` | `boolean` | WebSocket 端点是否可用 |

## 大文件传输最佳实践（重点）

### 先看清两个最常见的真实场景

真实项目里，数据流动主要是这两个方向（**不是**"选个文件后原样传到别处"）：

| 方向 | 场景 | 推荐做法 |
| --- | --- | --- |
| **读：磁盘/原生 → WebView** | 用户选择文件，把内容读进 WebView 处理（预览、解码、编辑、解析） | **分块加载**：逐块读，每块用完即释放，或是逐步把完整文件拼接到 WebView 中 |
| **写：WebView → 磁盘/原生** | WebView 生成的数据写回磁盘（导出、保存、编码结果） | **分块 + 复用 buffer**：内存恒定、性能好 |
| （少见）整体转发 | 用户选的文件**不做处理**，原样存到别处 | File 直传（最省，但场景少） |

真机实测（Redmi K30S / MIUI 12 / Android 10，App 进程 RSS 峰值增量）：

| 方式 | 文件大小 | 耗时 | 速度 | RSS 峰值增量 |
| --- | --- | --- | --- | --- |
| 分块上传（8MB/块，复用缓冲 + `append=1`） | 100MB | 920ms | 109 MB/s | +42.8 MB |
| File 直传（磁盘文件原样转发） | 105.8MB | 806ms | **131 MB/s** | **+4.2 MB** |
| 一次性 `Uint8Array(100MB)` | 100MB | 912ms | 110 MB/s | +275.3 MB |
| 整体生成后包装 `new File([buf])` | 100MB | 698ms | **143 MB/s** | 上传阶段 +0.6 MB（生成峰值仍在，Chromium 会把大 Blob spool 到磁盘临时文件，上传时流式读取，所以不占内存） |

### 场景一（读）：用户通过 input 选择文件(没经过此插件) → 分块加载进 WebView

**不要** `file.arrayBuffer()` 一次性读（100MB 文件 = 100MB 内存）。
用 `Blob.slice` 分块读（slice 是引用，不复制数据）：

```ts
const file = input.files[0];
const CHUNK = 4 * 1024 * 1024;

for (let offset = 0; offset < file.size; offset += CHUNK) {
  const chunk = file.slice(offset, offset + CHUNK);        // 引用，不复制
  const bytes = new Uint8Array(await chunk.arrayBuffer());  // 只有这一块进内存
  await processChunk(bytes, offset);                        // 解码/解析/渲染
  // 处理完 bytes 即可释放（下一轮循环覆盖引用）
}
```

如果文件在 native 侧（data-channel 的 `rootPath` 里），用 **Range 分块下载**：

```ts
const CHUNK = 4 * 1024 * 1024;
const info = await (await fetch(`${ch.baseUrl}/info?path=large.bin&token=${ch.token}`)).json();

for (let offset = 0; offset < info.size; offset += CHUNK) {
  const end = Math.min(offset + CHUNK - 1, info.size - 1);
  const res = await fetch(`${ch.baseUrl}/download?path=large.bin&token=${ch.token}`, {
    headers: { Range: `bytes=${offset}-${end}` },
  });
  const bytes = new Uint8Array(await res.arrayBuffer());
  await processChunk(bytes, offset);
}
```

如果只是**播放/显示**（视频、图片、音频）：直接给 `<video>` / `<img>` 引用 URL，
浏览器自己流式处理，**JS 侧零内存**：

```html
<video :src="`${ch.baseUrl}/download?path=videos/clip.mp4&token=${ch.token}`" controls></video>
```

### 场景二（写，首选）：WebView 生成数据 → 分块写回磁盘

核心两点：**复用同一块缓冲区** + 第 2 块起带 `append=1`。

```ts
const ch = await DataChannel.start();
const CHUNK = 8 * 1024 * 1024;
const buffer = new Uint8Array(CHUNK);          // 复用这一块，全程内存恒定
const REMOTE = 'exports/video.mp4';

let index = 0;
while (hasMoreData()) {
  const size = fillChunk(buffer, index);       // 生成第 index 块，返回实际字节数
  const res = await fetch(
    `${ch.baseUrl}/upload?path=${REMOTE}${index === 0 ? '' : '&append=1'}`,
    {
      method: 'PUT',
      headers: { Authorization: `Bearer ${ch.token}` },
      body: buffer.subarray(0, size),          // 只发这一块
    },
  );
  if (!res.ok) throw new Error(`chunk ${index} failed: HTTP ${res.status}`);
  index += 1;
}
```

> 实测：100MB 用这种方式内存峰值只 **+42.8MB**（一次性则是 +275.3MB），速度 109 MB/s。
> 额外好处：`append=1` 天然支持**断点续传**（失败后从最后一成功块继续）。

如果数据来自 File/Blob 切片（`Blob.slice` 也是引用，同样不复制）：

```ts
for (let offset = 0, index = 0; offset < file.size; offset += CHUNK, index += 1) {
  const chunk = file.slice(offset, offset + CHUNK);
  await fetch(
    `${ch.baseUrl}/upload?path=${REMOTE}${index === 0 ? '' : '&append=1'}`,
    { method: 'PUT', headers: { Authorization: `Bearer ${ch.token}` }, body: chunk },
  );
}
```

### 场景三（少见）：选文件后原样转发

只有"用户选的文件**不做处理**、原样存到别处"时才用：

```ts
const file = document.getElementById('fileInput').files[0];
await fetch(`${ch.baseUrl}/upload?path=${encodeURIComponent(file.name)}`, {
  method: 'PUT',
  headers: { Authorization: `Bearer ${ch.token}` },
  body: file, // 磁盘引用，流式发送，内存几乎零增长
});
```

### 其他情况

**数据必须整体生成**（第三方库只能一次性产出完整 ArrayBuffer）→ 生成后立即包装成 Blob/File 上传，
上传阶段内存极低：

```ts
let buf = await someLib.renderToArrayBuffer();  // 这一步无法避免占用 buf 大小
const blob = new Blob([buf], { type: 'application/octet-stream' });
buf = null;                                     // 尽快释放原始引用

await fetch(`${ch.baseUrl}/upload?path=export.bin`, {
  method: 'PUT',
  headers: { Authorization: `Bearer ${ch.token}` },
  body: blob,                                   // 上传阶段 +0.6MB（实测），速度 143 MB/s
});
```

**一次性上传（body 直接传整个数组）**——代码最简，但**大数据会占用大量内存**：

```ts
import { DataChannel } from 'capacitor-data-channel';

const ch = await DataChannel.start();

// 1) 在 JS 里整块构造数据：这一步本身就占用了 100MB 堆内存
const TOTAL_BYTES = 100 * 1024 * 1024; // 100MB
const bytes = new Uint8Array(TOTAL_BYTES);

// 填充内容（示例用随机数；真实场景可能是编码后的视频帧、导出的文件内容等）
const filler = new Uint8Array(65536);
crypto.getRandomValues(filler);
for (let offset = 0; offset < bytes.length; offset += filler.length) {
  bytes.set(filler.subarray(0, Math.min(filler.length, bytes.length - offset)), offset);
}

// 2) 一次性上传：body 直接传整个 Uint8Array
const startedAt = performance.now();
const res = await fetch(`${ch.baseUrl}/upload?path=bench/100mb-single.bin`, {
  method: 'PUT',
  headers: {
    Authorization: `Bearer ${ch.token}`,
    'Content-Type': 'application/octet-stream',
  },
  body: bytes, // 整个数组作为请求体，不经过 base64
});

if (!res.ok) {
  throw new Error(`upload failed: HTTP ${res.status} ${await res.text()}`);
}

const result = await res.json(); // { path, bytes, size, appended }
const elapsedMs = performance.now() - startedAt;
console.log(
  `uploaded ${result.bytes} bytes in ${Math.round(elapsedMs)}ms ` +
    `(${Math.round((result.bytes / 1024 / 1024 / elapsedMs) * 1000)} MB/s)`,
);
```

真机实测（100MB，App 进程 RSS 峰值增量）：

| 方式 | 耗时 | 速度 | RSS 峰值增量 |
| --- | --- | --- | --- |
| 一次性 `Uint8Array(100MB)` | 912ms | 110 MB/s | **+275.3 MB** |

内存峰值来自两份数据：JS 堆里的 `Uint8Array`（100MB）+ Chromium 网络栈的缓冲
（Android WebView 与 App 是同一个进程）。
**所以：小于几 MB 完全没问题；几十 MB 以上请改用上面的分块方式（场景二）。**

**小文件（< 几 MB）** → 直接传 `Uint8Array`/`ArrayBuffer` 即可，不必分块：

```ts
await fetch(`${ch.baseUrl}/upload?path=icons/a.png`, {
  method: 'PUT',
  headers: { Authorization: `Bearer ${ch.token}` },
  body: bytes,
});
```

### 下载：优先让浏览器直接消费（零 JS 内存）

```html
<!-- 视频/音频/图片：直接引用，支持拖动进度条（Range） -->
<video
  :src="`${ch.baseUrl}/download?path=videos/clip.mp4&token=${ch.token}`"
  controls
></video>

<!-- 图片同理 -->
<img :src="`${ch.baseUrl}/download?path=images/photo.jpg&token=${ch.token}`" />
```

需要拿 `Blob` 时（比如交给第三方库处理）：

```ts
const res = await fetch(`${ch.baseUrl}/download?path=videos/clip.mp4&token=${ch.token}`);
const blob = await res.blob(); // 注意：整份数据会进内存，大文件优先用上面的直连方式
```

**流式读取**（低内存，适合边下边处理）：

```ts
const res = await fetch(`${ch.baseUrl}/download?path=data/large.bin&token=${ch.token}`);
const reader = res.body!.getReader();
let total = 0;
for (;;) {
  const { done, value } = await reader.read();
  if (done) break;
  total += value.byteLength;
  // 在这里处理每一块（写入 IndexedDB、解码、绘制……），不需要攒完整份
}
```

**Range 请求**（断点续传 / 只取文件头部）：

```ts
const res = await fetch(`${ch.baseUrl}/download?path=videos/clip.mp4&token=${ch.token}`, {
  headers: { Range: 'bytes=0-1048575' }, // 前 1MB
});
// res.status === 206
// res.headers.get('content-range') === 'bytes 0-1048575/10485760'
const head = new Uint8Array(await res.arrayBuffer());
```

## 安全设计

| 措施 | 说明 |
| --- | --- |
| **只绑 127.0.0.1** | 外部设备/其他机器无法访问，只有本机 WebView 能连 |
| **随机端口** | 每次 `start()` 由系统分配，降低被扫描命中的概率 |
| **Bearer token** | 每次启动生成 32 字节随机 token，`MessageDigest.isEqual`（Android）/ 手写常量时间比较（iOS）；只在内存，不写日志 |
| **鉴权失败返回 404** | 与"未知路由"不可区分，**不泄露"这里有个需要认证的服务"**（比 401 更少信息泄露） |
| **Origin 白名单** | 只放行 `capacitor://localhost` / `https://localhost` / `http://localhost` / `ionic://localhost`；其它 Origin **直接断开连接**（不回任何响应） |
| **路径限制** | 只接受相对路径；拒绝 `..`、绝对路径、`~`、NUL；canonical/软链解析后必须仍在数据根目录内 |
| **大小与超时** | 请求体上限（默认 512MB）、请求头上限 32KB、socket 读超时 20 秒 |
| **上传原子性** | 先写 `.part` 再 rename，避免读到半截文件 |
| **响应头硬化** | `Cache-Control: no-store`、`X-Content-Type-Options: nosniff`、`Referrer-Policy: no-referrer` |
| **日志** | 只记录方法/路径/状态码/耗时，**绝不记录 token** |

> 为什么不用"伪装状态码 / 返回迷惑数据"这类手段：它拦不住真正的攻击者（脚本直接读 body），
> 却会破坏 `<video>` 的 Range、`fetch` 的 `res.ok`、CORS 预检和调试体验。
> 安全应该靠"默认拒绝 + 最小暴露面"，上面的措施已经是这个思路。

## 平台差异与注意事项

### Android

- 需要 **INTERNET** 权限（Capacitor 应用通常已有）；本地回环明文需要上面的 `networkSecurityConfig`；
- `start()/stop()/getInfo()` 在主线程，HTTP 请求在独立线程池（4 线程）；
- App 进程被杀后 server 自动消失（无残留）。

### iOS

- `NWParameters.requiredInterfaceType = .loopback` + 连接来源校验（双重保证只服务回环）；
- 数据根目录：默认 `Documents/dataChannel`（可在"文件"App 中访问），只允许沙盒内路径；
- 本地回环明文需要上面的 `NSAppTransportSecurity` 配置。

### 数据目录的平台限制（实测）

| 平台 | 默认目录 | 任意绝对路径 |
| --- | --- | --- |
| Android 11+ | `/sdcard/Android/data/<pkg>/files/dataChannel`（无需权限） | 需要 `MANAGE_EXTERNAL_STORAGE`（用 `capacitor-native-files` 的权限 API 申请） |
| **Android 10 及以下** | 同上 | 有 `WRITE_EXTERNAL_STORAGE` + 宿主 `requestLegacyExternalStorage="true"` 时**可读写公共目录**（真机实测通过） |
| iOS | `Documents/dataChannel` | 仅沙盒内（Documents / Application Support / Caches / tmp） |

> **重要实测结论**：Android 10 上 `requestLegacyExternalStorage` **是生效的**（`privateFlags` 可见
> `PRIVATE_FLAG_REQUEST_LEGACY_EXTERNAL_STORAGE`），公共目录可读写——前提是走**系统权限弹窗**授权。
> 用 `adb shell pm grant` 强制授权**不会**触发 mount namespace 的 remount，App 仍会 `EACCES`。

### 后台运行

- **Android**：App 进后台后 server 仍在，但进程可能被系统回收；持续后台传输需要前台服务（本插件不提供）；
- **iOS**：App 进后台后 socket 会被挂起（只有约 30 秒的 `beginBackgroundTask` 窗口），
  **不要**指望后台大文件传输——请在前台完成。
- 建议配合 `capacitor-app-state` 插件监听前后台变化：切后台时暂停传输/提示用户。

### 与 native-files 的分工（职责边界）

| 能力 | 由谁负责 |
| --- | --- |
| 高速传输（上传/下载/WebSocket 二进制帧） | ✅ **本插件** |
| 存储权限查询/申请（`WRITE_EXTERNAL_STORAGE` / `MANAGE_EXTERNAL_STORAGE`） | `capacitor-native-files` |
| 系统文件/文件夹选择器 | `capacitor-native-files` |
| 列目录、删除、重命名、复制 | `capacitor-native-files` |
| 根目录路径（rootDir） | 两者都能拿：native-files 提供 App 目录路径，交给 data-channel 的 `rootDir` |

**配合方式**（不再要求先复制文件）：

```ts
// 1) native-files：申请权限（如需要用公共目录）
await NativeFiles.requestStorageAccess();   // 走系统弹窗，用户点"允许"

// 2) data-channel：直接以任意目录为根（权限已由 native-files 拿到）
const ch = await DataChannel.start({ rootDir: '/sdcard/Download/myVideos' });

// 3) 直接上传/下载（不需要 copyToAppStorage 中转）
await fetch(`${ch.baseUrl}/upload?path=clip.mp4`, { method: 'PUT', ... });
```

> 若用默认目录（App 专属外部目录），**连权限都不需要**，开箱即用。

## 实测记录

| 项目 | 结果 |
| --- | --- |
| 上传 10MB | **179ms（≈56 MB/s）**，`bytes` 正确 |
| 上传 105.8MB（File 直接上传） | **806ms（≈131 MB/s）**，RSS 峰值 **+4.2MB** |
| 上传 100MB（`new File([buf])` 包装后上传） | **698ms（143 MB/s）**，上传阶段 RSS **+0.6MB**（生成阶段峰值仍在） |
| 上传 100MB（分块 8MB×13） | 920ms（109 MB/s），RSS 峰值 **+42.8MB** |
| 上传 100MB（一次性 Uint8Array） | 912ms（110 MB/s），RSS 峰值 **+275.3MB** |
| 下载 100MB | 353ms（≈285 MB/s），头/中/尾校验一致 |
| **WebSocket 1MB 往返** | **45ms**（约 22 MB/s 往返） |
| **WebSocket 10MB 往返** | **181ms（55 MB/s）** |
| **WebSocket 64KB 帧 ping-pong ×200** | 每帧 **1.7ms、588 fps**（约 37 MB/s） |
| **WebSocket 空闲 CPU** | **0%**（10 秒 0 jiffies） |
| Range 请求 | 206 + `Content-Range: bytes 100-199/10485760` + 100 字节 |
| 无 token / 错误 token | 404（与未知路由不可区分） |
| 路径穿越 `../escape.txt` | 400 |
| 未知路由 | 404 |
| **已移除的路由**（`/info`、`/list`、`DELETE /file`） | 404（只保留传输相关路由） |
| CORS 预检 | 204 |
| 默认 rootDir | `/storage/emulated/0/Android/data/com.myapp.testionic/files/dataChannel`（无需权限） |
| 自有目录绝对路径 | `/sdcard/Android/data/<pkg>/files/myData` 可写 ✓ |
| **公共目录（Android 10）** | 授予 `WRITE_EXTERNAL_STORAGE`（**系统弹窗**）后**可读写** ✓；未授权时 `EACCES`（`pm grant` 不触发 remount，仍失败） |
| server 空闲开销 | RSS **+0.5MB**、CPU **0.13% 单核**、1 个监听 fd |
| 传输期间 CPU（App 进程） | 100MB 上传+下载约 **1.47s CPU**（单核约 100%，仅在传输时） |
| 冒烟测试（真机 + 模拟器） | 启动/上传/下载/WS/停止 全 PASS |

## 开发

```bash
cd plugins/capacitor-data-channel
npm install
npm run build   # tsc → dist/esm
```

- Android：`android/src/main/java/com/testionic/datachannel/`（`DataChannelServer.java` 手写 HTTP + WebSocket 实现 + `DataChannelPlugin.java`）
- iOS：`ios/Sources/DataChannelPlugin/`（`DataChannelServer.swift` 用 NWListener 手写 HTTP + WebSocket 实现 + `DataChannelPlugin.swift`）
- 两端**协议完全一致**（路由/状态码/安全策略/WS 帧），改一端时请同步另一端。

## 设计边界（为什么只保留传输路由）

本插件定位是「**原生 ↔ WebView 的高速字节通道**」，所以刻意不包含：
- 权限查询/申请 → 属于 `capacitor-native-files`（它更懂系统的权限模型）；
- 文件管理（列表/删除/重命名）→ 属于 `capacitor-native-files`；
- 目录/文件存在性检查 → 可以用 `/download` 的 404 或 native-files 的 `getInfo`。

好处：职责单一、安全面小（只有 upload/download 两个会写文件的入口），也更容易被其它插件复用。
