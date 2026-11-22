# capacitor-background-keepalive

让 App「在后台继续活着」。Android 用**前台服务 + 常驻通知**（系统唯一承认的方式）；
iOS 没有等价能力，只提供三种明确标注限制的尽力模式。

> 只想"发一条常驻通知"是**没用**的：`NotificationManager.notify()` 只影响通知栏，
> Android 的 AMS 照样会在内存紧张时杀掉进程。真正改变进程优先级的是 `startForeground()`
> （前台服务）。本插件做的就是这件事。

## 能力矩阵

| | Android | iOS |
| --- | --- | --- |
| 常驻通知 | ✅ 前台服务通知（`ongoing`，带"停止"按钮） | ❌ iOS **没有**常驻通知 API（Live Activity 也不是） |
| 进程长期存活 | ✅ `specialUse` 前台服务，无平台时长上限 | ❌ 不存在通用方案 |
| 长时后台执行 | ✅ | ⚠️ 仅 `audio` / `location`（见下） |
| 后台任务续期 | — | ⚠️ `beginBackgroundTask`，约 30 秒 |
| Web | ❌ 页面隐藏后定时器被节流/冻结 | — |

### iOS 的三种模式（都不是"通用保活"）

| 模式 | 效果 | 需要 | 风险 |
| --- | --- | --- | --- |
| `backgroundTask`（默认退化） | 每次 `beginBackgroundTask` 把挂起时间往后推约 30 秒，可续期但**不保证** | 无 | 系统仍会挂起；到期会回调 `expirationHandler` |
| `audio` | 后台循环播放**静音音频**，进程真的会一直活着 | Info.plist `UIBackgroundModes = [audio]` | **违反 App Store 审核指南 2.5.4**（非音频类 App 用后台音频），有拒审风险；来电/Siri 会中断会话 |
| `location` | 持续定位，进程持续运行 | `UIBackgroundModes = [location]` + `NSLocationAlwaysAndWhenInUseUsageDescription` + 用户选"始终允许" | 只适合导航/运动类 App；状态栏会出现定位蓝条；耗电 |

**结论**：一个"普通"的 Capacitor App 在 iOS 上无法做到像 Android 那样的长期保活。
iOS 的正解是：**推送（APNs）+ 后台 URLSession（传文件）+ BGTaskScheduler（机会式刷新）**，
这些都不需要"进程一直在"。如果你要做 IM/长连接，请以"推送唤醒 + 重连"为架构前提。

## 安装

```bash
npm install file:../plugins/capacitor-background-keepalive
npx cap sync
```

## 快速上手

```ts
import { BackgroundKeepalive } from 'capacitor-background-keepalive';

await BackgroundKeepalive.start({
  mode: 'auto', // Android: specialUse；iOS: 有 audio 背景模式就用 audio，否则 backgroundTask
  notification: {
    title: '同步中…',
    text: '点按返回应用',
    subText: '正在后台同步数据',
  },
});

const status = await BackgroundKeepalive.isRunning();
// { running: true, platform: 'android', mode: 'foreground-service', ... }

await BackgroundKeepalive.updateNotification({ title: '已同步 3/10', text: '继续中…' });
await BackgroundKeepalive.stop();
```

## API

| 方法 | 说明 |
| --- | --- |
| `start(options?)` | 开始保活。返回**实际生效**的状态（可能被降级/失败，务必看返回值） |
| `stop()` | 停止并移除通知 |
| `isRunning()` | 读取状态（无副作用） |
| `updateNotification({ title?, text?, subText? })` | 更新 Android 常驻通知（iOS/Web 空操作） |
| `getCapabilities()` | 能力矩阵：每个模式是否支持、需要什么配置、真实效果 |
| `isBatteryOptimizationIgnored()` | Android：是否已豁免电池优化（iOS/Web `applicable:false`） |
| `requestBatteryOptimizationExemption()` | Android：弹系统"忽略电池优化"授权框 |
| `openBatteryOptimizationSettings()` | Android：打开电池优化设置页 |
| `addListener('keepaliveChanged', cb)` | 服务被启动/停止/被系统杀掉时通知 JS |

### `start()` 的参数

| 字段 | 平台 | 说明 |
| --- | --- | --- |
| `mode` | Android | `specialUse`（默认）/ `mediaPlayback` / `dataSync` |
| `mode` | iOS | `auto`（默认）/ `audio` / `location` / `backgroundTask` |
| `notification.title/text/subText` | Android | 通知文案 |
| `notification.smallIcon` | Android | drawable 资源名，默认用 App 图标 |
| `notification.channelId/channelName` | Android | 通知渠道（默认 `capacitor_keepalive` / `后台运行`），渠道重要性 `IMPORTANCE_LOW` |
| `notification.dismissible` | Android | `false`（默认）= `ongoing` 不可划掉 |
| `keepScreenOn` | Android | 前台服务期间保持屏幕常亮（会申请 `SCREEN_BRIGHT_WAKE_LOCK`） |
| `audioVolume` | iOS | `audio` 模式的播放音量（音频本身是静音，默认 1.0） |
| `distanceFilter` | iOS | `location` 模式的位移过滤（米，默认 10） |

## Android 细节

### 前台服务类型（Android 14+ 必须声明，Android 15+ 有时长限制）

| 类型 | 平台时长限制 | 说明 |
| --- | --- | --- |
| `specialUse`（默认） | **无** | 需要在 Manifest 里写 `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` 说明用途（插件已写好）；上架 Google Play 时要在控制台申报 |
| `mediaPlayback` | 无 | 只给真的在放音频/视频的 App 用；**不要**拿来冒充 |
| `dataSync` | **Android 15+：24 小时内累计 6 小时** | 超时会回调 `onTimeout()`，插件会立刻 `stopSelf()` 并派发 `keepaliveChanged` |

插件已经在自己的 `AndroidManifest.xml` 里声明了服务、`FOREGROUND_SERVICE*` 权限、
通知权限、`WAKE_LOCK`、`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`（会合并进宿主）。

> ⚠️ **必须用全限定类名**声明 Service。写成 `.KeepaliveService` 会被解析成宿主包名，
> 运行时报 `Unable to start service ... not found`（这个坑本项目踩过）。

### Android 12+ 的后台启动限制

`startForegroundService()` 从**后台**调用会抛 `ForegroundServiceStartNotAllowedException`。
所以：

- 在 App 处于前台（用户点了按钮、页面可见）时调 `start()`；
- 不要指望在 `boot` / `push` 的回调里直接起前台服务（那种场景需要 `specialUse` 的豁免理由或改用 WorkManager）；
- 插件会等服务真正 `startForeground()` 完成（最多等 1.5s）再返回，失败会带回 `note`。

### 通知权限

Android 13+ 需要 `POST_NOTIFICATIONS` 才会显示前台服务通知（服务本身仍能跑）。
用 [capacitor-permissions](../capacitor-permissions) 申请：

```ts
await Permissions.request({ permissions: ['notifications'] });
```

### 电池优化

国内 ROM（MIUI/EMUI/ColorOS…）会额外杀后台。插件能做的只有：

1. `requestBatteryOptimizationExemption()` 弹系统白名单对话框（Play 对 `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
   有政策限制，建议在"设置"页由用户主动点）；
2. 提示用户去 `openBatteryOptimizationSettings()` 手动把 App 设成"不限制"；
3. MIUI 还额外需要"自启动 + 省电策略无限制"，这些**没有公开 API**，只能引导用户手动设置。

## iOS 细节

- 插件不会自动改你的 Info.plist。用 `audio`/`location` 模式必须自己加 `UIBackgroundModes`；
- 用 `location` 模式时，`allowsBackgroundLocationUpdates = true` **必须**先有 `UIBackgroundModes: location`，
  否则系统会直接 crash（插件已做检查并 reject）；
- `audio` 模式在音频会话被中断（来电/Siri）后会在中断结束时自动恢复播放，并派发 `keepaliveChanged` 说明；
- `stop()` 会停播放、停定位、结束后台任务，并把 `AVAudioSession` 设为 inactive（不打断其他 App 的音乐）。

## Web

浏览器没有后台执行保证：页面隐藏后 `setTimeout` 会被节流/冻结（Chrome 的 intensive throttling、
Safari 的进程挂起），WebSocket 也可能被断开。`start()` 会返回 `running:false` 并给出说明，
不会假装成功。需要"后台任务"请用 Service Worker + Push。

## 实测记录

- **真机 Redmi K30S（Android 10 / MIUI 12 / API 29）**：`start({mode:'auto'})` → 前台服务启动成功
  （`mode: foreground-service`、`notificationVisible: true`、`fgsType: specialUse`），
  通知栏出现常驻通知；`stop()` → 服务停止、通知消失、`running:false`。autotest 通过。
- **模拟器 Pixel_6（Android 16 / API 36）**：同上（autotest 通过）。
- **iOS 模拟器（CI）**：没有 `UIBackgroundModes` 时 `auto` 退化为 `backgroundTask`，
  `start()→running:true`、`stop()→running:false`；`audio`/`location` 模式因缺少 plist 配置会明确 reject
  （`BACKGROUND_MODE_MISSING`）。

## 已知限制

- 用户从"最近任务"里划掉 App 后，`START_STICKY` 的服务通常会被重建，但 MIUI 等 ROM 可能直接冻结 App；
- 部分 ROM 会强制把前台服务降级/隐藏通知（Android 13+ 用户可以手动隐藏前台服务通知）；
- iOS 上**不存在**"像 Android 一样保活"的方案，任何声称能做到的插件最终都落在
  `audio` / `location` / 后台推送 这几条路上。
