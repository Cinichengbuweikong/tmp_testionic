# capacitor-background-keepalive · 插件概览

**一句话**：Android 用前台服务 + 常驻通知让 App 在后台活着；iOS 提供三种**明确标注限制**的尽力模式。

## 先纠正一个常见误解

"发一条常驻通知，App 就会一直在后台" —— **不成立**。
Android 的 `NotificationManager.notify()` 只影响通知栏，进程照样会被杀；真正改变进程优先级的是
`startForeground()`（前台服务）。这个插件做的就是这件事。

## 平台能力（这就是全部真相）

| | Android | iOS |
| --- | --- | --- |
| 常驻通知 | ✅ 前台服务通知（带"停止"按钮） | ❌ iOS 没有常驻通知 API |
| 长期存活 | ✅ `specialUse` 类型无时长上限 | ❌ 不存在通用方案 |
| 后台任务续期 | — | ⚠️ `beginBackgroundTask`，约 30 秒 |
| 持续后台运行 | ✅ | ⚠️ 只有 `audio`（静音循环）或 `location`（持续定位），都要额外后台模式，且有商店审核风险 |

**iOS 的正确架构**是：推送唤醒 + 后台 URLSession 传文件 + BGTaskScheduler，而不是"让进程一直活着"。

## 核心 API

```ts
BackgroundKeepalive.start({ mode: 'auto' })   // Android: specialUse；iOS: backgroundTask（默认、安全）
BackgroundKeepalive.stop() / isRunning()
BackgroundKeepalive.updateNotification({ title, text })
BackgroundKeepalive.getCapabilities()          // 能力矩阵（建议在 UI 里展示）
BackgroundKeepalive.requestBatteryOptimizationExemption()  // Android 白名单
BackgroundKeepalive.addListener('keepaliveChanged' | 'keepaliveExpired', cb)
```

## 典型场景

- 后台传大文件 / 持续同步（配合 `capacitor-data-channel`）；
- 保持 WebSocket 长连接（Android 可行；iOS 只能靠推送 + 重连）；
- 需要"关屏后任务继续"（Android）。

## 必须知道的坑

1. **iOS 上做不到"像 Android 一样保活"**。`audio` 模式（循环静音音频）技术上能长期运行，
   但非音频类 App 违反 App Store 审核指南 2.5.4，有拒审风险 —— 所以默认**不**用它；
2. **Android 12+ 不允许从后台启动前台服务**：`start()` 必须在 App 处于前台时调用；
3. **Android 13+ 要有通知权限**（`POST_NOTIFICATIONS`）才看得见通知，否则服务照跑但通知隐藏；
4. **国内 ROM 会额外杀后台**：电池优化白名单、MIUI"自启动/省电策略"都没有公开 API，只能引导用户；
5. **系统被杀后是否重建由系统决定**：插件保证"重建时能恢复"，不保证"一定重建"；
6. 前台服务通知 id 固定 `4301`、默认渠道 `capacitor_keepalive`，与 App 其它通知可能互相覆盖。

## 实测

Android 10（真机，MIUI 12）/ Android 16（模拟器）起停、通知、电池优化豁免均验证通过；
`specialUse` / `mediaPlayback` / `dataSync` 三种类型都能启动。iOS 模拟器（CI）验证默认
`backgroundTask` 模式与 `keepaliveExpired` 事件注册。
