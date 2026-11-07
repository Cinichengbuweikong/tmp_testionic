# capacitor-data-channel 使用示例

> 配套文档：[README](README.md)（API/协议/安全讨论）、[INTRO](INTRO.md)（插件介绍）。
> 所有示例均可在真机 + 模拟器 + iOS 上运行（Web 端不支持，会 reject）。

## 0. 准备（一次性）

**宿主放行 loopback 明文**（否则 WebView 拒绝访问 `http://127.0.0.1`）：

- Android：`res/xml/network_security_config.xml` 放行 `127.0.0.1`/`localhost` + manifest 引用；
- iOS：`Info.plist` → `NSAppTransportSecurity` → `NSAllowsLocalNetworking = true`。

**import**：

```ts
import {
  DataChannel,
  createWsOtpPool,
  encodeWsMessage,
  encodeWsMessageWithOtp,
  decodeWsMessage,
  decodeWsMessagesBatch,
} from 'capacitor-data-channel';
```

---

## 1. HTTP：上传

### 1.1 单次上传（用户选的文件 / Blob，最省内存）

```ts
const http = await DataChannel.startHttp();     // { baseUrl, rootPath, maxBodyBytes, ... }

async function uploadFile(file: File | Blob, path: string) {
  const otp = await DataChannel.createHttpOtp({ scope: 'write', path });
  const res = await fetch(`${http.baseUrl}/upload?path=${encodeURIComponent(path)}`, {
    method: 'PUT',
    headers: { Authorization: `OTP ${otp.otp}` },
    body: file,                                  // 直接传 File/Blob：RSS 增量最小
  });
  const json = await res.json();
  if (!res.ok) throw new Error(`${json.code}: ${json.error}`);
  return json;                                   // { path, bytes, size, finalized: true }
}
```

### 1.2 前端生成的数据：分块 + 复用缓冲区（内存恒定）

```ts
async function uploadGenerated(path: string, totalBytes: number, genChunk: (offset: number, len: number) => Uint8Array) {
  const CHUNK = 8 * 1024 * 1024;
  let offset = 0;
  while (offset < totalBytes) {
    const len = Math.min(CHUNK, totalBytes - offset);
    const otp = await DataChannel.createHttpOtp({ scope: 'write', path });
    const res = await fetch(`${http.baseUrl}/upload?path=${path}&offset=${offset}&total=${totalBytes}`, {
      method: 'PUT',
      headers: { Authorization: `OTP ${otp.otp}` },
      body: genChunk(offset, len),              // 每块写进同一个复用 buffer 也行
    });
    const json = await res.json();
    if (!res.ok) throw new Error(`chunk@${offset}: ${json.code} ${json.error}`);
    offset = json.partSize;                     // 以服务端实际进度为准（更可靠）
  }
  // finalize
  const fin = await DataChannel.createHttpOtp({ scope: 'write', path });
  const res = await fetch(`${http.baseUrl}/upload?path=${path}&finalize=1&total=${totalBytes}`, {
    method: 'POST',
    headers: { Authorization: `OTP ${fin.otp}` },
  });
  const json = await res.json();
  if (!res.ok) throw new Error(`finalize: ${json.code} ${json.error}`);
  return json;                                   // { path, size, finalized: true }
}
```

### 1.3 断点续传（完整流程，含 sha256 校验）

```ts
const sha256Hex = async (buf: Uint8Array) => {
  const digest = await crypto.subtle.digest('SHA-256', buf);
  return Array.from(new Uint8Array(digest)).map((b) => b.toString(16).padStart(2, '0')).join('');
};

async function uploadResumable(path: string, data: Uint8Array) {
  const total = data.length;
  const CHUNK = 8 * 1024 * 1024;

  // 1) 查询已有进度（上次中断留下的 .part）
  const statusOtp = await DataChannel.createHttpOtp({ scope: 'write', path });
  const status = await (
    await fetch(`${http.baseUrl}/upload?path=${path}&status=1&otp=${statusOtp.otp}`)
  ).json();
  // { partExists, partSize, exists, size }
  let offset = status.partExists && status.partSize > 0 && status.partSize < total ? status.partSize : 0;

  // 2) 从 offset 继续传
  while (offset < total) {
    const len = Math.min(CHUNK, total - offset);
    const otp = await DataChannel.createHttpOtp({ scope: 'write', path });
    const res = await fetch(`${http.baseUrl}/upload?path=${path}&offset=${offset}&total=${total}`, {
      method: 'PUT',
      headers: { Authorization: `OTP ${otp.otp}` },
      body: data.subarray(offset, offset + len),
    });
    const json = await res.json();
    if (res.status === 409 && json.code === 'E_OFFSET_MISMATCH') {
      // 客户端与服务端进度不一致：以服务端 size 为准重来
      offset = json.size ?? 0;
      continue;
    }
    if (!res.ok) throw new Error(`chunk@${offset}: ${json.code} ${json.error}`);
    offset = json.partSize;
  }

  // 3) finalize（校验总长 + sha256，失败不会落地）
  const sha = await sha256Hex(data);
  const fin = await DataChannel.createHttpOtp({ scope: 'write', path });
  const res = await fetch(
    `${http.baseUrl}/upload?path=${path}&finalize=1&total=${total}&sha256=${sha}`,
    { method: 'POST', headers: { Authorization: `OTP ${fin.otp}` } },
  );
  const json = await res.json();
  if (!res.ok) throw new Error(`finalize: ${json.code} ${json.error}`);
  return json;                                   // { path, size, finalized: true }
}
```

### 1.4 带进度的分块上传

```ts
async function uploadWithProgress(path: string, data: Uint8Array, onProgress: (pct: number) => void) {
  const total = data.length;
  const CHUNK = 4 * 1024 * 1024;
  let offset = 0;
  while (offset < total) {
    const len = Math.min(CHUNK, total - offset);
    const otp = await DataChannel.createHttpOtp({ scope: 'write', path });
    const res = await fetch(`${http.baseUrl}/upload?path=${path}&offset=${offset}&total=${total}`, {
      method: 'PUT',
      headers: { Authorization: `OTP ${otp.otp}` },
      body: data.subarray(offset, offset + len),
    });
    const json = await res.json();
    if (!res.ok) throw new Error(`${json.code}: ${json.error}`);
    offset = json.partSize;
    onProgress(Math.round((offset / total) * 100));
  }
  const fin = await DataChannel.createHttpOtp({ scope: 'write', path });
  const finRes = await fetch(`${http.baseUrl}/upload?path=${path}&finalize=1&total=${total}`, {
    method: 'POST',
    headers: { Authorization: `OTP ${fin.otp}` },
  });
  if (!finRes.ok) throw new Error((await finRes.json()).error);
}
```

---

## 2. HTTP：下载

### 2.1 一次性下载（小文件）

```ts
async function downloadBlob(path: string) {
  const otp = await DataChannel.createHttpOtp({ scope: 'read', path });
  const res = await fetch(`${http.baseUrl}/download?path=${path}&otp=${otp.otp}`);
  if (!res.ok) {
    const err = await res.json().catch(() => ({}));
    throw new Error(`download ${res.status} ${err.error ?? ''}`);
  }
  return await res.blob();
}
```

### 2.2 流式下载（大文件不占内存）

```ts
async function downloadToSink(path: string, onChunk: (chunk: Uint8Array) => void) {
  const otp = await DataChannel.createHttpOtp({ scope: 'read', path });
  const res = await fetch(`${http.baseUrl}/download?path=${path}&otp=${otp.otp}`);
  if (!res.ok) throw new Error(`download ${res.status}`);
  const reader = res.body!.getReader();
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    onChunk(value);                               // 100MB 也只有几十 KB 常驻
  }
}
```

### 2.3 Range 分片读取（例如只取文件头 / 断点下载）

```ts
async function readRange(path: string, start: number, end: number) {
  const otp = await DataChannel.createHttpOtp({ scope: 'read', path });
  const res = await fetch(`${http.baseUrl}/download?path=${path}&otp=${otp.otp}`, {
    headers: { Range: `bytes=${start}-${end}` },
  });
  // 206 Partial Content
  return new Uint8Array(await res.arrayBuffer());
}
```

---

## 3. 媒体播放（video / audio）

```ts
const http = await DataChannel.startHttp();

async function playMedia(video: HTMLVideoElement, path: string) {
  // 可复用、只读、绑定该文件的 OTP（放 URL，因为 <video> 不能加 header）
  const otp = await DataChannel.createHttpOtp({ scope: 'read', path, reusable: true });
  video.src = `${http.baseUrl}/download?path=${path}&otp=${otp.otp}`;

  // ⚠️ 必须 revoke：reusable OTP 在 server 运行期间一直有效
  const cleanup = () => DataChannel.revokeOtp({ otp: otp.otp });
  video.addEventListener('ended', cleanup, { once: true });
  video.addEventListener('error', cleanup, { once: true });
  return { otp: otp.otp, cleanup };
}
```

切换媒体 / 页面卸载时：

```ts
// 单个
await DataChannel.revokeOtp({ otp });
// 切后台、退出页、切媒体时兜底
await DataChannel.revokeAllOtps({ server: 'http' });
```

> 视频拖动进度条会发多个 `Range` 请求，共用一个 reusable OTP 即可（不要每条 Range 都申请 OTP）。

---

## 4. WebSocket

### 4.1 基本用法：握手 OTP + 每条消息 OTP

```ts
const wsInfo = await DataChannel.startWebSocket();       // { wsUrl, wsEcho, ... }
const handshake = await DataChannel.createWsOtp();       // 仅用于握手

const ws = new WebSocket(wsInfo.wsUrl, [`otp.${handshake.otp}`]);
ws.binaryType = 'arraybuffer';

ws.onopen = async () => {
  // 每条消息：内部 createWsOtp + 拼 [otp][0x00][payload]
  ws.send(await encodeWsMessage(new Uint8Array([1, 2, 3])));
};

ws.onmessage = async (event) => {
  const { ok, payload, error } = await decodeWsMessage(event.data);
  if (!ok) return console.warn('消息校验失败，已丢弃:', error);
  console.log(new Uint8Array(payload));
};
```

### 4.2 高频小包（推荐）：批量 OTP 池 + 同步拼包

```ts
const pool = await createWsOtpPool(64);                  // 一次桥调用拿 64 个
let refill: Promise<void> | null = null;

ws.onopen = () => {
  for (const frame of frames) {
    if (pool.remaining === 0) {
      // 池用尽再补一批（异步，注意排队；不要在这里 await 阻塞整个循环）
      refill = refill ?? createWsOtpPool(64).then((p) => { poolRef = p; refill = null; });
    }
    ws.send(encodeWsMessageWithOtp(pool.take(), frame));  // 同步，无桥往返
  }
};
```

> 简单版：先按消息数量一次取够（`createWsOtpPool(n)`，n ≤ 256 - 活跃数），用完再取下一批。

### 4.3 接收侧批量校验（先验证、后交付）

```ts
const buffer: ArrayBuffer[] = [];

ws.onmessage = (event) => {
  buffer.push(event.data);
  if (buffer.length >= 16) {
    const batch = buffer.splice(0, buffer.length);
    void (async () => {
      const results = await decodeWsMessagesBatch(batch);   // 返回时已全部验证
      for (const r of results) {
        if (!r.ok) continue;                                // 未通过：丢弃
        handleFrame(new Uint8Array(r.payload!));
      }
    })();
  }
};
```

### 4.4 错误处理与重连

```ts
function connect() {
  return new Promise<WebSocket>(async (resolve, reject) => {
    const info = await DataChannel.getWsInfo();
    if (!info.running) await DataChannel.startWebSocket();

    const handshake = await DataChannel.createWsOtp();
    const ws = new WebSocket((await DataChannel.getWsInfo()).wsUrl, [`otp.${handshake.otp}`]);
    ws.binaryType = 'arraybuffer';
    ws.onopen = () => resolve(ws);
    ws.onerror = () => reject(new Error('ws connect failed'));
    ws.onclose = () => {
      // server 停止 / 网络异常：延迟重连（握手要重新取 OTP）
      setTimeout(() => connect().then(handleWs).catch(() => {}), 1000);
    };
  });
}
```

> 服务端对**无 OTP / 无效 OTP / 重放**的消息会回 `[0x00][错误文本]`，`decodeWsMessage` 会返回
> `{ ok: false, error }`——**必须丢弃**。

---

## 5. 与其它插件配合

### 5.1 用户选文件 → 上传（native-files）

```ts
const { files } = await NativeFiles.pickFiles({ multiple: false });
if (!files.length) return;

// 需要写到公共目录时先授权（Android；iOS 沙盒内不需要）
await NativeFiles.requestStorageAccess?.();

const http = await DataChannel.startHttp({ rootDir: '/sdcard/Download/myApp' });
const otp = await DataChannel.createHttpOtp({ scope: 'write', path: 'imported.bin' });
const res = await fetch(`${http.baseUrl}/upload?path=imported.bin`, {
  method: 'PUT',
  headers: { Authorization: `OTP ${otp.otp}` },
  body: files[0].blob,               // native-files 返回的 Blob/File
});
```

### 5.2 切后台自动 revoke（foreground-state）

```ts
const listener = await ForegroundState.addListener('stateChanged', async (s) => {
  if (!s.isActive) {
    await DataChannel.revokeAllOtps();        // 媒体 OTP / 未用 OTP 一次清空
  }
});
// 退出页面：await listener.remove();
```

### 5.3 完整生命周期

```ts
// 进入页面
const http = await DataChannel.startHttp();
const wsInfo = await DataChannel.startWebSocket();

// 离开页面
await DataChannel.stopWebSocket();   // 断开所有客户端 + 撤销 WS OTP
await DataChannel.stopHttp();        // 中断连接 + 撤销 HTTP OTP
```

---

## 6. 错误码与处理建议

所有错误体统一为 `{ "error": "...", "code": "E_...", "size"?: N }`（鉴权失败仍是裸 404 `{"error":"not found"}`，
不泄露端点存在）。

| code | HTTP | 含义 | 客户端建议 |
| --- | --- | --- | --- |
| `E_INVALID_PATH` | 400 | path 非法/穿越 | 检查路径，不要用 `..` |
| `E_BAD_REQUEST` | 400 | 参数非法（如 offset/total 冲突） | 修参数；`offset + len > total` |
| `E_LENGTH_REQUIRED` | 411 | 缺 `Content-Length` | 用 fetch/XHR（会自动带） |
| `E_PAYLOAD_TOO_LARGE` | 413 | 单请求超过 `maxBodyBytes` | 减小 chunk 或调大 `maxBodyBytes` |
| `E_OFFSET_MISMATCH` | 409 | offset 与 `.part` 实际大小不符 | **以 `size` 为准**重传（续传/重来） |
| `E_NO_PART` | 409 | offset>0 但没有 `.part` | 从 `offset=0` 重新开始 |
| `E_SIZE_MISMATCH` | 409 | finalize 时总长不符 | 补齐缺失块后重试 finalize |
| `E_HASH_MISMATCH` | 409 | sha256 不符 | 数据损坏/拼错：丢弃 `.part` 重传 |
| `E_FINALIZE` | 500 | rename/覆盖失败 | 检查目标是否被占用/权限 |
| `E_APPEND_DEPRECATED` | 400 | 使用了已废弃的 `append=1` | 改用 `offset` + `finalize` |
| `E_IO` | 500 | 服务端读写失败（磁盘满等） | 检查剩余空间；可重试 |
| —（裸 404） | 404 | OTP 无效/已用/过期 | **重新申请 OTP**，不要重试旧 OTP |

> **错误处理原则**：409 系列都带 `size`（服务端实际字节数），照它修正本地进度即可；不要盲目重试。

---

## 7. 性能与调优速查

| 需求 | 做法 |
| --- | --- |
| 100MB 级文件 | HTTP 分块（8MB/块）+ 复用 buffer；不要一次 `Uint8Array(100MB)` |
| 用户选的磁盘文件 | 直接 `body: file`（RSS +4MB 级别） |
| 视频播放 | `<video src>` + reusable OTP + `revoke`（零 JS 内存） |
| 60fps 帧流 | WS + **批量 OTP 池**；每帧只做 1 次同步拼包 |
| 避免卡顿 | 不要每条消息 `createWsOtp()`；不要高频轮询 `getHttpInfo/getWsInfo` |
| 内存控制 | 下载用流式 reader；上传用分块复用 buffer |

实测（真机 Redmi K30S / Android 10）：HTTP 上传 93MB/s、下载 322MB/s；WS 300KB 往返 15.9ms
（批量 OTP 后 10.3ms）；内存峰值 +12~46MB 且回落。

---

## 8. 常见陷阱

| 陷阱 | 说明 |
| --- | --- |
| **忘记 revoke reusable OTP** | 它在 server 运行期间永久有效（无 TTL）；用完必须 `revokeOtp` / `revokeAllOtps` |
| OTP 数量上限 | 每个 server 最多 256 个活跃 OTP；批量创建时"活跃 + 请求"不能超 |
| 用旧 OTP 重试 | 一次性 OTP 用过即废，失败要**重新申请** |
| 拿 `append=1` 做续传 | 已废弃（会 400）：块传一半会污染文件；用 offset + finalize |
| 后端目录用公共目录 | Android 11+ 其它 App 可能摸不到；敏感数据用 app 私有目录或服务端 |
| 期望后台持续传输 | App 进后台进程可能被回收；长传输需要前台服务 |
| Web 端调试 | Web 不支持（无本地 server），方法会 reject |
| WS 用 `<video>` | 媒体请走 HTTP Range；WS 是消息流不是文件流 |
