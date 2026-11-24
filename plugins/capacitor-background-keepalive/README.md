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
# 1) 插件目录里先构建（dist/ 是 tsc 产物、不入库，必须现场生成）
cd plugins/capacitor-background-keepalive && npm install && npm run build

# 2) 回到项目根，用 file: 方式安装并同步原生工程
npm install ./plugins/capacitor-background-keepalive   # 或 "file:plugins/capacitor-background-keepalive"
npx cap sync
```

> ⚠️ 直接 `npm install file:...` 而不先 `npm run build`，宿主会解析不到入口
> （`package.json` 的 `main` 指向 `dist/esm/index.js`）。

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
| `channelId?` | `string` | `'capacitor_keepalive'` | 通知渠道（channel）id，见下方「`channelId` 是什么」 |
| `channelName?` | `string` | `'后台运行'` | 渠道名（显示在系统设置里；渠道重要性固定 `IMPORTANCE_LOW`，静音、不弹横幅） |
| `dismissible?` | `boolean` | `false` | `false` = `ongoing` 不可划掉 |

#### `channelId` 是什么（Android 通知渠道）

Android 8.0（API 26）起，**每条通知都必须属于一个"通知渠道"（NotificationChannel）**，
渠道是系统设置里给用户看的"分门别类开关"的单位：

- 用户在「设置 → 应用 → 通知」里看到的是**按渠道分组**的列表，可以单独把某个渠道
  关掉、调重要性、关声音/震动/角标；
- 同一个 `channelId` = **同一个渠道**：插件复用已存在的渠道（名称/描述可更新，
  但**重要性创建后 App 改不了**，只有用户能改）；
- 换一个 `channelId` = **新建一个渠道**，旧的会留在系统设置里（用户会看到两个），
  所以**不要**每次启动换 id。

本插件的用法：

| 场景 | 怎么传 |
| --- | --- |
| 只要一个"后台运行"通知（最常见） | 不传，用默认 `capacitor_keepalive` / `后台运行` |
| 想把"保活通知"和 App 的其他通知分开（让用户能单独静音其中一个） | 传自己的 id，例如 `channelId: 'myapp.keepalive', channelName: '后台同步'` |
| 同一 App 有多种后台任务（下载 / 同步 / 播放） | 每种一个 id，用户可分别控制；但**同一个用途要固定同一个 id** |

注意：

- `channelId` 建议加 App 前缀（如 `com.example.keepalive`），避免与其他插件/库的渠道撞名；
- 渠道**重要性固定 `IMPORTANCE_LOW`**（插件写死的）：不响铃、不震动、不弹横幅，
  适合"常驻但别打扰用户"的前台服务通知；想让它更显眼要改插件代码，而且在 Android 上
  App 也不能把已创建渠道的重要性**调高**（只能用户自己改）；
- `channelName` 只在**首次创建**时生效（之后可以更新名称，但用户看惯的名字变化容易困惑），
  所以**不要用它做动态文案** —— 动态内容请用 `notification.title/text`；
- 卸载重装后渠道会被清掉重建；覆盖安装（升级）会保留用户对渠道的自定义设置。

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

## 使用例

```vue
<section class="card">
  <h2>BackgroundKeepalive（后台保活）</h2>
  <div class="actions">
    <button @click="keepaliveStart">启动保活</button>
    <button @click="keepaliveStop">停止保活</button>
    <button @click="keepaliveStatus">读取状态</button>
    <button @click="keepaliveCapabilities">能力矩阵</button>
    <button @click="keepaliveBattery">电池优化豁免</button>
    <button @click="keepaliveWatchExpired">监听到期事件</button>
  </div>
  <p class="hint">
    Android 用前台服务 + 常驻通知（唯一被系统承认的方式）；iOS 默认 backgroundTask
    （安全、无商店风险），audio / location 需显式指定并自担审核风险，详见能力矩阵。
    iOS 后台时间用完会触发 keepaliveExpired（挂起期间产生的会在回到前台时补发）。
  </p>
</section>

<script>
import { BackgroundKeepalive } from 'capacitor-background-keepalive';

async keepaliveStart() {
  try {
    const res = await BackgroundKeepalive.start({
      mode: 'auto',
      notification: {
        title: '后台保活运行中',
        text: '点按返回应用',
        subText: '由 capacitor-background-keepalive 提供',
      },
    });
    this.print('启动保活', res);
  } catch (err) {
    this.error('BackgroundKeepalive.start', err);
  }
},

async keepaliveStop() {
  try {
    const res = await BackgroundKeepalive.stop();
    this.print('停止保活', res);
  } catch (err) {
    this.error('BackgroundKeepalive.stop', err);
  }
},

async keepaliveStatus() {
  try {
    const res = await BackgroundKeepalive.isRunning();
    this.print('保活状态', res);
  } catch (err) {
    this.error('BackgroundKeepalive.isRunning', err);
  }
},

async keepaliveCapabilities() {
  try {
    const res = await BackgroundKeepalive.getCapabilities();
    this.print('保活能力矩阵', res);
  } catch (err) {
    this.error('BackgroundKeepalive.getCapabilities', err);
  }
},

async keepaliveBattery() {
  try {
    const [ignored, requested] = await Promise.all([
      BackgroundKeepalive.isBatteryOptimizationIgnored(),
      BackgroundKeepalive.requestBatteryOptimizationExemption(),
    ]);
    this.print('电池优化豁免', { ignored, requested });
  } catch (err) {
    this.error('batteryOptimization', err);
  }
},

async keepaliveWatchExpired() {
  try {
    // iOS backgroundTask 到期事件；挂起期间产生的会在回到前台时补发（deliveredAfterResume:true）
    const handle = await BackgroundKeepalive.addListener('keepaliveExpired', (event) => {
      this.print('后台任务到期事件', event);
    });
    this.print('已监听 keepaliveExpired', {
      note: 'iOS：让 App 进后台待 30 秒以上（或等系统挂起）再回前台看这里；Android 不会触发该事件。',
    });
    this.keepaliveExpiredHandle = handle;
  } catch (err) {
    this.error('addListener(keepaliveExpired)', err);
  }
},
</script>
```

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

---

## 与其他插件一起使用（注意事项）

| 一起用的插件 | 会不会冲突 | 注意事项 |
| --- | --- | --- |
| `capacitor-permissions` | ⚠️ 有依赖 | Android 13+ 需要 `POST_NOTIFICATIONS` 才能显示前台服务通知：先 `Permissions.request({permissions:['notifications']})`。没授权时服务照跑但通知不显示（`notificationVisible:false`） |
| `capacitor-foreground-state` | ⚠️ 语义容易混淆 | 前台服务让**进程**活着，但 Activity 生命周期照旧。用户从最近任务划掉 App 后进程仍在，而 JS 上下文已销毁 → `foreground-state` 会停在最后一次事件，**不要**把 `running:true` 当成"App 在前台" |
| `capacitor-data-channel` | ✅ 推荐组合，但注意 Doze | 后台传文件是本插件的典型用途。Android：Doze 会掐网络，建议 `requestBatteryOptimizationExemption()`（Play 有政策限制）；iOS：后台 socket 会被挂起，**本插件帮不上忙**（见 data-channel README 的 iOS 说明） |
| `capacitor-live-update` | ⚠️ 监听会失效 | OTA reload 只重建 JS 上下文，原生前台服务继续运行；但之前 `addListener('keepaliveChanged')` 的监听**没了**，新代码要重新注册。安装更新前建议先 `stop()`，避免"旧 JS 已死、新 JS 还没接手"的空窗 |
| `capacitor-fullscreen` / `capacitor-webview-to-edge` / `capacitor-native-statusbar` | ✅ 无冲突 | 各自操作 DecorView/Insets。注意 `keepScreenOn:true` + 全屏会让屏幕**一直亮**，耗电明显 |
| `capacitor-secure-storage` | ✅ 无冲突 | Keystore/Keychain 与前台服务互不影响 |
| 宿主自己建的通知 / 其他通知类插件 | ⚠️ 可能覆盖 | 本插件固定用通知 id **4301** 与默认渠道 **`capacitor_keepalive`**。如果宿主自己或别的插件也用同一个 id，会互相覆盖（表现为通知突然变内容/消失）。**改 `channelId` 不会改通知 id**，需要时改插件常量 |

其它注意：

- Android 上 `start()` 会最多阻塞 ~1.5s（等服务真正 `startForeground`），而 Capacitor 的插件调用是
  **单线程串行**的 → 这期间其它插件的调用会排队。不要在动画/输入关键路径上同步 await 它。
- 多个插件同时申请 `FOREGROUND_SERVICE_*` 类型时，Android 只按**每个服务**审批；本插件与别的
  前台服务插件（如果有）会各自显示通知，用户可能看到两条常驻通知。
- iOS 的 `backgroundTask` 与其它插件的后台任务（例如 `URLSession` 后台传输）**共享** iOS 给的
  后台时间，实际能拖多久会互相影响；`keepaliveExpired` 早到属于正常。

---

## ⚠️ 已知限制（只能文档化，代码无法消除）

1. **Android 前台服务被系统杀掉后"会不会被重建、什么时候重建"完全由系统决定**：
   `START_STICKY` 只是"允许重建"的意愿声明，不是保证。插件能做的是
   **把上次的启动配置持久化，重建时（`onStartCommand(intent == null)`）原样恢复**；
   但以下情况不会重建：用户"强行停止"App、用户在设置里限制后台、部分 ROM 的省电策略、
   服务被 `stopSelf()`（用户点了通知上的"停止"）。
   → 不要把"保活"当作关键业务的唯一保障；关键任务要用 WorkManager/推送/服务端补偿。
2. **iOS 的 `keepaliveExpired` 在进程被系统直接终止时不会补发**：
   `expirationHandler` 只是"即将挂起"的通知，如果之后进程被终止（内存压力），
   缓存的事件再没有机会投递。所以它是"事后记录"，不是可靠的告警通道；
   需要必达的通知请用本地通知（`UNUserNotificationCenter`）或服务端推送。
3. **iOS `backgroundTask` 的实际时长不保证**：Apple 只承诺"有限时间"（经验约 30 秒），
   续期也不保证成功；`backgroundTimeRemaining` 只能当参考值。
