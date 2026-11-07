# capacitor-data-channel 插件介绍

> 给 WebView 开一条**真正的高速数据通道**：让 JS 直接收发原始二进制，绕开 Capacitor 桥的
> JSON/base64 序列化。补 `capacitor-native-files` 的能力空白——**读写大数据**。

## 一句话

**Capacitor 的桥适合"传参数"，不适合"传数据"。** 这个插件在原生侧起一个只监听 `127.0.0.1` 的
HTTP + WebSocket 服务，让 WebView 用标准的 `fetch` / `<video>` / `new WebSocket` 直接搬字节。

## 它解决什么问题

用 Capacitor 桥传数据时，原生 ↔ JS 必须走 JSON 序列化，二进制要先转 base64：

| | Capacitor 桥 | capacitor-data-channel |
| --- | --- | --- |
| 1MB 二进制 | base64 后 ≈ 1.37MB 字符串 + JSON 解析 | 原始 1MB 直接搬 |
| 100MB 文件 | 内存翻倍、JS 主线程卡顿、易 OOM | 流式 93MB/s，内存平稳（+46MB 峰值，会回落） |
| 视频播放 | 无法用 `<video src>` | HTTP Range → 206，直接拖动进度条 |
| 实时帧流 | 每帧一次 JSON 序列化 | WebSocket 二进制帧，300KB 往返 15.9ms（批量 OTP 后 10.3ms） |

实测对比（真机 Redmi K30S / Android 10）：

| 场景 | 本插件 | 桥（估算/实测） |
| --- | --- | --- |
| HTTP 上传 100MB | **93 MB/s** | base64 膨胀 ~37% + 主线程解析，慢一个量级 |
| HTTP 下载 100MB | **322~333 MB/s** | 同上 |
| WS 300KB 往返 | **10.3~15.9 ms/条** | 每帧 JSON 序列化 |
| 内存 | 峰值 +12~46MB，回落到基线 | 100MB 数据 → 峰值 +275MB |

## 两个通道，各管一段

| | **HTTP**（`startHttp`） | **WebSocket**（`startWebSocket`） |
| --- | --- | --- |
| 本质 | 短连接、可断点、可 Range | 长连接、双向、低延迟 |
| 适合 | **大文件**（上传/下载/视频/图片） | **实时数据**（音视频帧、连续 buffer、信令） |
| 实测 | 上传 93 MB/s、下载 322 MB/s | 300KB 往返 15.9ms；流水线 49 MB/s |
| 独立启停 | ✅ 不用就不开，省资源、减攻击面 | ✅ 同上 |

## 能力清单

- **HTTP**：`PUT /upload`（流式写盘、`append` 追加、`.part` 原子写）、`GET /download`
  （Range/206、流式）、`GET /health`
- **WebSocket**：二进制/文本帧、分片重组、ping/pong、close、echo 或业务接管
- **任意 rootDir**：app 私有目录（默认，免权限）、相对路径、绝对路径（公共目录）
- **鉴权：OTP**（一次性凭证）——通过 Capacitor 桥下发，抓包看不到生成过程；一次性、可撤销、
  HTTP 可绑定路径、媒体用可复用只读 OTP
- **批量 OTP**：一次桥调用拿 N 个，高频小包场景 -35% 延迟
- **两级连接限流**：未认证 4（防扫描/DoS）/ 已认证 16（可配）

## 30 秒上手

```ts
import {
  DataChannel, createWsOtpPool, encodeWsMessageWithOtp, decodeWsMessage,
} from 'capacitor-data-channel';

// ---------- 大文件：HTTP ----------
const http = await DataChannel.startHttp();          // 返回 baseUrl / rootPath
const otp = await DataChannel.createHttpOtp({ scope: 'write', path: 'videos/a.mp4' });
await fetch(`${http.baseUrl}/upload?path=videos/a.mp4`, {
  method: 'PUT',
  headers: { Authorization: `OTP ${otp.otp}` },
  body: fileBlob,                                     // 用户选的文件直接传，内存最省
});

// 播放（Range 拖动、零 JS 内存）
const media = await DataChannel.createHttpOtp({ scope: 'read', path: 'videos/a.mp4', reusable: true });
video.src = `${http.baseUrl}/download?path=videos/a.mp4&otp=${media.otp}`;
video.onended = () => DataChannel.revokeOtp({ otp: media.otp });   // ⚠️ 必须 revoke

// ---------- 实时：WebSocket ----------
const wsInfo = await DataChannel.startWebSocket();
const handshake = await DataChannel.createWsOtp();
const ws = new WebSocket(wsInfo.wsUrl, [`otp.${handshake.otp}`]);
ws.binaryType = 'arraybuffer';

const pool = await createWsOtpPool(64);               // 批量取 OTP，避免每条一次桥调用
ws.send(encodeWsMessageWithOtp(pool.take(), frame));  // 同步拼包

ws.onmessage = async (event) => {
  const { ok, payload, error } = await decodeWsMessage(event.data);
  if (!ok) return console.warn('消息校验失败，已丢弃:', error);
  handleFrame(new Uint8Array(payload));
};
```

## 安全定位（一句话）

**凭证只给本 App 的 WebView 用，不承诺传输机密性。**

- 桥（`postMessage`）不跨 App，所以别的 App 拿不到 OTP → 连不上、或连上也是 404；
- OTP 一次性 → 抓包抓到的是**已作废**的凭证，无法重放；
- **不防** root 设备（可读内存/沙盒）、**不防**解包改代码、**不提供**传输加密（明文 loopback）。

原因与完整推理见 [README 的「安全讨论与最终结论」](README.md#安全讨论与最终结论)——
简单说：能改 loopback 的人必然有 root，而 root 能直接读内存和文件，加密收益≈0；
真正的边界是"**不要相信客户端**"，敏感数据请放服务端。

## 适合 / 不适合

| ✅ 适合 | ❌ 不适合 |
| --- | --- |
| 视频/音频/图片的本地缓存与播放 | 需要传输机密性的场景（明文） |
| 大文件导入导出（100MB~GB 级） | 对抗 root/重打包攻击者的安全需求 |
| 实时帧流、传感器/音频 buffer 上传 | 想用 WS 传超大文件（请用 HTTP） |
| 离线优先 App 的本地数据管道 | 需要多设备/跨进程通信（这里只绑 127.0.0.1） |

## 依赖与配合

- [`capacitor-native-files`](../capacitor-native-files/)：选文件、存储权限、文件管理（列表/删除/重命名）；
- [`capacitor-app-state`](../capacitor-app-state/)：切后台时 `revokeAllOtps()` 兜底；
- 宿主需放行 loopback 明文（Android `network_security_config`、iOS `NSAllowsLocalNetworking`），
  见 README「安装前必做」。

## 平台与验证状态

| 平台 | 状态 |
| --- | --- |
| Android | ✅ **真机**（Redmi K30S / Android 10）+ **模拟器**（Pixel_6 / Android 16）实测，autotest 全绿 |
| iOS | ✅ **CI**（模拟器编译 + autotest 全绿） |
| Web | ⛔ 不适用（无本地 server 能力，方法直接 reject） |

代码规模：Android 4 个类（HTTP/WS/OTP/Plugin）+ iOS 4 个文件，协议与规则两端一致。

---

**更多细节**：[README.md](README.md)（API、OTP 规则、WebSocket 消息协议、性能实测、安全讨论、平台差异）。
