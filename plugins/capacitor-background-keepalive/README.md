# capacitor-background-keepalive

让 App「在后台继续活着」。Android 用**前台服务 + 常驻通知**（系统唯一承认的方式）；
iOS 没有等价能力，**默认**只提供 `backgroundTask`（安全、无商店风险），
另外两种（`audio` / `location`）需要显式开启并自担风险。

> 只想"发一条常驻通知"是**没用**的：`NotificationManager.notify()` 只影响通知栏，
> Android 的 AMS 照样会在内存紧张时杀掉进程。真正改变进程优先级的是 `startForeground()`
> （前台服务）。本插件做的就是这件事。

## 能力矩阵

| | Android | iOS |
| --- | --- | --- |
| 常驻通知 | ✅ 前台服务通知（`ongoing`，带"停止"按钮） | ❌ iOS **没有**常驻通知 API（Live Activity 也不是） |
| 进程长期存活 | ✅ `specialUse` 前台服务，无平台时长上限 | ❌ 不存在通用方案 |
| 长时后台执行 | ✅ | ⚠️ 仅 `audio` / `location`（需显式指定） |
| 后台任务续期 | — | ⚠️ `beginBackgroundTask`，约 30 秒；到期派发 `keepaliveExpired` |
| Web | ❌ 页面隐藏后定时器被节流/冻结 | — |

### iOS 的三种模式（都不是"通用保活"）

| 模式 | 效果 | 需要 | 风险 |
| --- | --- | --- | --- |
| `backgroundTask`（**默认**，`mode:'auto'` 也走它） | 每次 `beginBackgroundTask` 把挂起时间往后推约 30 秒，可续期但**不保证** | 无 | 系统仍会挂起；到期会回调 `expirationHandler` → 派发 `keepaliveExpired` |
| `audio` | 后台循环播放**静音音频**，进程真的会一直活着 | Info.plist `UIBackgroundModes = [audio]` | **违反 App Store 审核指南 2.5.4**（非音频类 App 用后台音频），有拒审风险；来电/Siri 会中断会话 |
| `location` | 持续定位，进程持续运行 | `UIBackgroundModes = [location]` + `NSLocationAlwaysAndWhenInUseUsageDescription` + 用户选"始终允许" | 只适合导航/运动类 App；状态栏会出现定位蓝条；耗电 |

**结论**：一个"普通"的 Capacitor App 在 iOS 上无法做到像 Android 那样的长期保活。
iOS 的正解是：**推送（APNs）+ 后台 URLSession（传文件）+ BGTaskScheduler（机会式刷新）**，
这些都不需要"进程一直在"。如果你要做 IM/长连接，请以"推送唤醒 + 重连"为架构前提。

> 为什么默认改成 `backgroundTask`？因为 `audio` 对非音频 App 有**明确的商店审核风险**，
> 而且会持续占用音频会话（打断用户听歌/播客）。默认值必须是"不会被拒审"的那个；
> 需要长期后台运行的应用应该**显式**选择 `audio`/`location`，并在审核时准备好理由。

## 安装

```bash
npm install file:../plugins/capacitor-background-keepalive
npx cap sync
```

## 快速上手

```ts
import { BackgroundKeepalive } from 'capacitor-background-keepalive';

await BackgroundKeepalive.start({
  mode: 'auto', // Android: specialUse；iOS: backgroundTask（默认、安全）
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

## API 一览

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
| `addListener('keepaliveExpired', cb)` | **iOS `backgroundTask` 专用**：后台时间用完时的事件（含"挂起后补发"语义） |

---

## API 详解

> 类型标注：`T` 必填、`T?` 可选、`A | B` 联合类型、`null` 表示 JSON null。
> 所有方法都返回 `Promise`；reject 时 `Error.message` 以大写错误码开头。

### `start(options?): Promise<KeepaliveStatus>`

开始保活。**返回值就是实际生效的状态**（可能降级、可能失败），不要假设它一定成功。

| 参数 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- |
| `options.mode?` | `KeepaliveMode` | 见下 | 保活模式。Android：`specialUse`（默认）/ `mediaPlayback` / `dataSync`；iOS：`auto`（默认）→ 等价于 `backgroundTask`，也可显式 `backgroundTask` / `audio` / `location` |
| `options.notification?` | `object` | — | Android 常驻通知外观，见下表 |
| `options.keepScreenOn?` | `boolean` | `false` | Android：前台服务期间保持屏幕常亮（申请 `SCREEN_BRIGHT_WAKE_LOCK`） |
| `options.audioVolume?` | `number` | `1.0` | iOS `audio` 模式播放音量（音频本身是静音，音量只影响会话行为；`0` 可能被系统当成"没在播放"） |
| `options.distanceFilter?` | `number` | `10` | iOS `location` 模式的最小位移（米），越小越耗电 |

`options.notification`（**仅 Android**）：

| 字段 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- |
| `title?` | `string` | `'应用正在后台运行'` | 通知标题 |
| `text?` | `string` | `'点击返回应用'` | 通知正文 |
| `subText?` | `string` | — | 副标题（小字） |
| `smallIcon?` | `string` | App 图标 | drawable/mipmap 资源名 |
| `channelId?` | `string` | `'capacitor_keepalive'` | 通知渠道 id |
| `channelName?` | `string` | `'后台运行'` | 渠道名（渠道重要性固定 `IMPORTANCE_LOW`，静音、不弹横幅） |
| `dismissible?` | `boolean` | `false` | `false` = `ongoing` 不可划掉 |

返回 `KeepaliveStatus`：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `running` | `boolean` | 是否在保活 |
| `platform` | `'android' \| 'ios' \| 'web'` | 平台 |
| `mode` | `'foreground-service' \| 'audio' \| 'location' \| 'background-task' \| 'none'` | 实际生效的模式（未运行 = `none`） |
| `foregroundService?` | `boolean` | Android：前台服务是否在跑 |
| `fgsType?` | `string` | Android：当前前台服务类型（`specialUse` / `mediaPlayback` / `dataSync` / `none`） |
| `notificationVisible?` | `boolean` | Android：通知是否真的显示（= 服务在跑 **且** 有 `POST_NOTIFICATIONS`） |
| `notificationPermissionGranted?` | `boolean` | Android：是否持有 `POST_NOTIFICATIONS` |
| `batteryOptimizationIgnored?` | `boolean` | Android：是否已豁免电池优化 |
| `backgroundTimeRemaining?` | `number \| null` | iOS `backgroundTask` 模式的后台剩余秒数（前台时为很大值；其他模式 `null`） |
| `note?` | `string` | 一句话说明（降级原因、被系统打断、失败原因等） |

错误码：`UNSUPPORTED_MODE`（不认识的 mode）/ `FOREGROUND_SERVICE_START_FAILED`（Android 后台启动被拒）/
`BACKGROUND_MODE_MISSING`（iOS 缺 `UIBackgroundModes`）/ `AUDIO_START_FAILED`（iOS 音频会话失败）。

```ts
const status = await BackgroundKeepalive.start({ mode: 'auto' });
if (!status.running) {
  console.warn('保活没生效：', status.note);
}
```

### `stop(): Promise<KeepaliveStatus>`

停止保活并移除通知（Android 停前台服务；iOS 停音频/定位/结束后台任务；Web 空操作）。
返回停止后的 `KeepaliveStatus`（`running:false`, `mode:'none'`）。

### `isRunning(): Promise<KeepaliveStatus>`

只读当前状态，**无副作用**（不会启动/停止任何东西）。返回结构同 `start()`。

### `updateNotification(options): Promise<KeepaliveStatus>`

更新 Android 常驻通知的文案（不重启服务）。iOS/Web 是空操作（仍返回当前状态）。

| 参数 | 类型 | 说明 |
| --- | --- | --- |
| `options.title?` | `string` | 新标题 |
| `options.text?` | `string` | 新正文 |
| `options.subText?` | `string` | 新副标题 |

错误：`NOT_RUNNING`（服务没在跑）/ `UPDATE_FAILED`。

### `getCapabilities(): Promise<KeepaliveCapabilities>`

平台能力矩阵 —— **建议在 UI 上展示**，用来诚实地告诉用户"iOS 上做不到永久保活"。

| 返回字段 | 类型 | 说明 |
| --- | --- | --- |
| `platform` | `'android' \| 'ios' \| 'web'` | 平台 |
| `continuous` | `boolean` | 是否能做到「进程长期存活」（Android `true`；iOS 仅当宿主配了 audio/location 后台模式） |
| `modes` | `KeepaliveModeCapability[]` | 每个模式的能力（见下） |
| `note` | `string` | 平台总体说明 |

`KeepaliveModeCapability`：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `mode` | `string` | 模式名 |
| `supported` | `boolean` | 是否支持 |
| `requires` | `string[]` | 需要的宿主配置（Info.plist / Manifest / 权限） |
| `effect` | `string` | 这个模式的**真实效果**（含限制） |
| `recommended` | `boolean` | 是否是本平台的推荐模式（iOS 现在是 `backgroundTask`） |

### `isBatteryOptimizationIgnored(): Promise<{ applicable, ignored }>`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `applicable` | `boolean` | 该平台是否有"电池优化白名单"概念（Android `true`，iOS/Web `false`） |
| `ignored` | `boolean` | Android：是否已豁免（iOS/Web 恒 `true`，表示"不存在这个限制"） |

### `requestBatteryOptimizationExemption(): Promise<{ applicable, ignored, requested }>`

Android：弹系统的"忽略电池优化"授权对话框（`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`）。
如果系统不允许（部分 ROM / Play 政策限制），会退化为打开设置列表页。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `applicable` | `boolean` | 是否有此概念（iOS/Web `false`） |
| `ignored` | `boolean` | **调用之后**是否已豁免（用户可能还没点，所以通常仍为 `false`） |
| `requested` | `boolean` | 是否成功把对话框/设置页弹出去了 |

> ⚠️ Google Play 对 `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 有政策限制：
> 建议只在设置页由用户**主动点击**时调用，并在隐私政策/上架说明里解释用途。

### `openBatteryOptimizationSettings(): Promise<{ opened }>`

Android：直接打开"电池优化"设置列表页（用户拒绝上面的对话框时的兜底）。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `opened` | `boolean` | 是否成功打开（iOS/Web 恒 `false`） |

### `addListener('keepaliveChanged', cb)`

保活状态变化时触发。payload = `KeepaliveStatus`（同 `start()` 的返回）。

触发时机：`start()` / `stop()` 成功后、Android 前台服务被系统重建或 `dataSync` 超时被停、
iOS 音频中断恢复/定位失败等（插件内部会 `notifyListeners`）。

```ts
const handle = await BackgroundKeepalive.addListener('keepaliveChanged', (status) => {
  console.log('keepalive', status.running, status.mode, status.note);
});
await handle.remove();
```

### `addListener('keepaliveExpired', cb)`（iOS `backgroundTask` 专用）

后台时间用完（`expirationHandler` 触发）时派发。**这条事件的语义比较特殊，务必读完**：

1. handler 触发时 App **即将被挂起**，插件会「尽力」立刻 `notifyListeners`，
   但 JS **通常不会马上执行**（WebView 的 JS 队列会被冻结）；
2. 插件会把事件**缓存**起来，在 App **回到前台**（`didBecomeActive`）时**补发一次**；
3. 所以它本质上是"**事后记录/提示**"，不要在事件里做"冻结期间必须完成"的动作。

payload = `KeepaliveExpiredEvent`：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `expiredAt` | `number` | 事件产生时间（Unix 毫秒）—— 后台时间用完那一刻 |
| `deliveredAt` | `number \| null` | 真正送到 JS 的时间；挂起补发时远大于 `expiredAt`；立即投递时为 `null` |
| `deliveredAfterResume` | `boolean` | `true` = 这是回到前台后**补发**的 |
| `mode` | `'background-task'` | 触发时的模式 |
| `backgroundTimeRemaining` | `number` | 到期时系统报告的剩余时间（通常 `0`） |
| `reason` | `'background-task-expired'` | 触发原因 |
| `note` | `string` | 给人看的说明（含"为什么会延迟"） |

```ts
const expired = await BackgroundKeepalive.addListener('keepaliveExpired', (e) => {
  console.log('后台时间用完：', e.expiredAt, '补发：', e.deliveredAfterResume);
  // 典型用途：记一条本地日志、回前台时提示"刚才后台被挂起了"
});
```

> Android 不会派发 `keepaliveExpired`：前台服务没有"到期"概念。`dataSync` 的 6 小时超时
> 由系统直接停服务，插件会派发 `keepaliveChanged`（`running:false` + `note`）。

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
- 默认 `backgroundTask` **不需要**任何 Info.plist 配置；
- 用 `location` 模式时，`allowsBackgroundLocationUpdates = true` **必须**先有 `UIBackgroundModes: location`，
  否则系统会直接 crash（插件已做检查并 reject）；
- `audio` 模式在音频会话被中断（来电/Siri）后会在中断结束时自动恢复播放，并派发 `keepaliveChanged` 说明；
- `backgroundTask` 模式会在**进入后台时**重新申请一次后台任务（`beginBackgroundTask` 的计时从后台开始），
  到期时派发 `keepaliveExpired`（见上）；
- `stop()` 会停播放、停定位、结束后台任务，并把 `AVAudioSession` 设为 inactive（不打断其他 App 的音乐）。

### 关于 `expirationHandler` 到底能不能触发 JS 事件

**能调用，但通常不能"立即执行"**：`expirationHandler` 触发时系统正在冻结 App，插件里的
`notifyListeners` 只是把一段 JS 排进 WebView 的队列；等 App 回到前台，JS 才有机会执行。
如果 App 在后台被系统**直接终止**（内存压力），这段 JS 就永远不会执行。

所以本插件的做法是「**立即投递 + 缓存 + 回前台补发**」：

- 立即投递：如果 WebView 恰好还能执行（例如只冻结了一小会儿），JS 会收到一次
  （`deliveredAfterResume:false`）；
- 回前台补发：`didBecomeActive` 时把缓存的事件再发一次（`deliveredAfterResume:true`，
  `deliveredAt` 是真正送到的时间），保证"用户回到 App 后一定知道刚才后台超时了"；
- 事件只补发一次，`stop()` 或重新 `start()` 会清空缓存。

## Web

浏览器没有后台执行保证：页面隐藏后 `setTimeout` 会被节流/冻结（Chrome 的 intensive throttling、
Safari 的进程挂起），WebSocket 也可能被断开。`start()` 会返回 `running:false` 并给出说明，
不会假装成功。需要"后台任务"请用 Service Worker + Push。

## 实测记录

- **真机 Redmi K30S（Android 10 / MIUI 12 / API 29）**：`start({mode:'auto'})` → 前台服务启动成功
  （`mode: foreground-service`、`notificationVisible: true`、`fgsType: specialUse`），
  通知栏出现常驻通知；`stop()` → 服务停止、通知消失、`running:false`。autotest 通过。
  另外单独验证了 `dataSync` 与 `mediaPlayback` 两种类型同样能启动/停止。
- **模拟器 Pixel_6（Android 16 / API 36）**：同上（autotest 通过）。
- **iOS 模拟器（CI）**：默认 `mode:'auto'` 走 `backgroundTask`，
  `start()→running:true`（`mode:'background-task'`）、`stop()→running:false`；
  `audio`/`location` 模式因缺少 plist 配置会明确 reject（`BACKGROUND_MODE_MISSING`）。
  `keepaliveExpired` 的补发逻辑需要真机/长时间后台才能触发，模拟器上只验证了监听注册。

## 已知限制

- 用户从"最近任务"里划掉 App 后，`START_STICKY` 的服务通常会被重建，但 MIUI 等 ROM 可能直接冻结 App；
- 部分 ROM 会强制把前台服务降级/隐藏通知（Android 13+ 用户可以手动隐藏前台服务通知）；
- iOS 上**不存在**"像 Android 一样保活"的方案，任何声称能做到的插件最终都落在
  `audio` / `location` / 后台推送 这几条路上；
- iOS 被系统**直接终止**（不是挂起）时，`keepaliveExpired` 的补发也不会发生（进程已经没了）。
