# capacitor-live-update · 插件概览

**一句话**：从自己的后端下发 web 资源包（HTML/JS/CSS/图片），**验签后**安装并切换 WebView 根路径，
实现不发版的"热更新"；带原子安装、启动自愈回滚、原生升级自动失效。

## 它解决什么问题

改一行前端文案也要走商店审核（Apple 1~3 天）。热更新把 web 资源从 APK/IPA 里"搬"到可写目录，
App 启动时从那个目录加载 —— 后端发新包，用户重启界面就能生效。

**能更新**：HTML / JS / CSS / 图片 / 字体。
**不能更新**：Java/Kotlin/Swift 代码、插件、权限、Manifest / Info.plist 配置。

## 工作流（一次完整更新）

```
后端 latest.json ──► checkForUpdate()
                      │  version / url / sha256 / signature
                      ▼
              downloadAndInstall()  ──► 校验 SHA-256 + ECDSA P-256 签名
                      │                  解压到 staging → rename（原子）
                      ▼
        写 Capacitor 的 serverBasePath ──► reload ──► 新界面生效
                      │
              JS 调 notifyAppReady() 确认；没确认就自动回滚
```

## 安全设计（为什么敢用）

- **ECDSA P-256 签名**（对 zip 原始字节）：没有私钥的人造不出能装进去的包；
- **SHA-256**：先做便宜的完整性检查，区分"下载坏了"和"被伪造了"；
- **默认要求签名**（`allowUnsigned` 默认 false）+ **拒绝降级**（`allowDowngrade` 默认 false）；
- **原子安装**：staging 解压全成功才 rename，不会留半个包；
- **启动自愈**：新包 JS 没 `notifyAppReady()` → 连续 2 次冷启动自动回滚；
- **原生升级优先**：`versionCode`/`CFBundleVersion` 变了就丢弃 OTA，避免"新原生 + 旧 Web"；
- **解压防护**：拒绝 `..`/绝对路径（zip-slip）、限条目数与解压体积、必须有 `index.html`。

## 核心 API

```ts
LiveUpdate.configure({ serverUrl, publicKey, channel })   // 一次即可，持久化
LiveUpdate.notifyAppReady()                               // 启动后确认（必须调用）
LiveUpdate.checkForUpdate()                               // 查更新
LiveUpdate.downloadAndInstall({ url, version, sha256, signature })  // 装更新
LiveUpdate.getState()                                     // 当前版本 / pending / 本地包列表
LiveUpdate.rollback() / reset()                           // 回滚 / 清空回内置资源
LiveUpdate.verifyBundle({ zipBase64, sha256, signature, publicKey })  // 离线自检（不安装）
```

## 必须知道的坑

1. **`notifyAppReady()` 必须调**，否则第二次冷启动会被当成坏包回滚；但也不能调得太早
   （放得越早，自动回滚越"迟钝"）；
2. **`downloadAndInstall()` 在 reload 前就 resolve 了**，真正切换在 ~500ms 后，别立刻断言界面已换；
3. **reload 会重建 JS 上下文**：各插件的 `addListener` 全部失效需重新注册；`data-channel` 的
   端口/OTP 变量也丢了（原生 server 还在）；
4. **只能更新 Web 资源**：新 JS 必须兼容已安装的旧原生二进制，跨版本用 `minNativeVersion` 卡住；
5. **商店政策**：Apple 允许解释型代码更新但不得改变 App 主要目的/绕过审核；Google Play 禁止
   下发 DEX/native 代码；
6. **不要与 Ionic Appflow / Capgo 同时启用**（抢同一个 `serverBasePath`）。

## 后端协议（最小实现）

`GET {serverUrl}/latest.json?appId=..&platform=..&channel=..&version=..` 返回
`{ version, url, sha256, signature, size?, notes?, minNativeVersion? }`；没有更新返回 `{ version: "" }`。
仓库里带两个开发脚本：`dev-ota-server.py`（本地后端）与 `make-live-update-fixture.py`（夹具 + 签名）。

## 实测

Android 10 真机 + Android 16 模拟器：安装 51 文件 / 1.19MB → reload → 冷启动直接进新包（无闪屏）
→ `notifyAppReady` → `rollback`；"安装后不确认，第 2 次冷启动自动回滚"也实测通过。
iOS 模拟器（CI）验证 zip 解析（deflate）+ CryptoKit 验签。
